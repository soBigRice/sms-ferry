package com.sobigrice.smsrelay.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sobigrice.smsrelay.mail.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class QueueDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "smsrelay.test.${UUID.randomUUID()}"
    private val name = "test-${UUID.randomUUID()}.db"
    private lateinit var crypto: CryptoStore
    private lateinit var queue: QueueDatabase
    @Before fun prepare() { crypto = CryptoStore(alias); queue = QueueDatabase(context, crypto, name) }
    @After fun cleanup() {
        queue.close(); context.deleteDatabase(name)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
    }
    private fun job(sourceId: Long = 1) = MailJob(sourceId = sourceId, sourceSender = "106123",
        body = "private-test-message-content", receivedAt = 123, accountAddress = "10001@qq.com", recipientAddress = "10002@qq.com")

    @Test fun sameSourceIdCannotBeEnqueuedTwiceButSameTextCan() {
        assertTrue(queue.enqueue(job(1)))
        assertFalse(queue.enqueue(job(1)))
        assertTrue(queue.enqueue(job(2)))
        assertEquals(2, queue.counts()[JobState.PENDING])
        assertEquals(2L, queue.scanCursor())
    }
    @Test fun twoConcurrentWorkersCannotClaimTheSameRecord() {
        queue.enqueue(job())
        val ready = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { pool.submit<MailJob?> { ready.await(); queue.claimNext(true) } }
            ready.countDown()
            assertEquals(1, futures.map { it.get(5, TimeUnit.SECONDS) }.count { it != null })
            assertEquals(1, queue.counts()[JobState.SENDING])
        } finally { pool.shutdownNow() }
    }
    @Test fun interruptedSendBecomesUncertainInsteadOfBeingSentAgain() {
        queue.enqueue(job()); queue.claimNext(true); queue.recoverInterrupted()
        assertNull(queue.claimNext(true))
        assertEquals(JobState.UNCERTAIN, queue.latest().single().state)
        assertEquals(MailFailure.INTERRUPTED, queue.latest().single().failure)
    }
    @Test fun ciphertextDoesNotExposeSmsAndUsesRandomNonces() {
        val source = "private-test-message-content"
        val first = crypto.encrypt(source); val second = crypto.encrypt(source)
        assertNotEquals(first, second); assertEquals(source, crypto.decrypt(first))
        queue.enqueue(job())
        val bytes = context.getDatabasePath(name).readBytes().toString(Charsets.UTF_8)
        assertFalse(bytes.contains(source)); assertFalse(bytes.contains("10002@qq.com"))
    }
    @Test fun authenticatedEncryptionRejectsTampering() {
        val parts = crypto.encrypt("secret").split('.')
        val bytes = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val broken = parts[0] + "." + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        try { crypto.decrypt(broken) } catch (_: Exception) { return }
        fail("tampered data must not decrypt")
    }
    @Test fun cursorCannotMoveBackwardsWhenImportWorkersOverlap() {
        queue.enqueue(job(2)); queue.enqueue(job(1))
        assertEquals(2L, queue.scanCursor())
    }
    @Test fun explicitRetryPreservesOldDestination() {
        val original = job(); queue.enqueue(original); queue.claimNext(true)
        queue.finish(original.id, JobState.UNCERTAIN, MailFailure.UNKNOWN)
        queue.retry(original.id)
        assertEquals(original.recipientAddress, queue.claimNext(true)?.recipientAddress)
    }

    @Test fun genericServerBindingSurvivesDatabaseReopen() {
        val account = MailAccount.create("alice@example.org", "queue-test-password", "reader@example.net",
            "smtp.example.org", 2525, MailSecurity.STARTTLS, "smtp-user")
        val original = job().copy(accountAddress = account.sender, recipientAddress = account.recipient, smtpIdentity = account.identity)
        queue.enqueue(original); queue.close(); queue = QueueDatabase(context, crypto, name)
        assertEquals(original, queue.job(original.id))
        val payload = queue.readableDatabase.rawQuery("SELECT payload FROM jobs WHERE id=?", arrayOf(original.id)).use {
            assertTrue(it.moveToFirst()); crypto.decrypt(it.getString(0))
        }
        assertFalse(payload.contains(account.authorizationCode))
    }

    @Test fun originalQueuePayloadWithoutServerBindingStaysOnQq() {
        val original = job()
        val payload = crypto.encrypt(JSONObject().put("id", original.id).put("sourceId", original.sourceId)
            .put("sender", original.sourceSender).put("body", original.body).put("receivedAt", original.receivedAt)
            .put("account", original.accountAddress).put("recipient", original.recipientAddress).put("kind", original.kind.name).toString())
        queue.writableDatabase.execSQL("INSERT INTO jobs (id,source_id,kind,payload,state,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
            arrayOf<Any?>(original.id, original.sourceId, original.kind.name, payload, "PENDING", 123L, 123L))
        val restored = queue.job(original.id)!!
        val legacy = MailAccount.legacyQq(original.accountAddress, "abcdefghijklmnop", original.recipientAddress)
        assertTrue(restored.belongsTo(legacy))
        assertFalse(restored.belongsTo(legacy.copy(smtpHost = "smtp.example.org")))
        assertEquals(original.recipientAddress, restored.recipientAddress)
        assertEquals(MailTemplate.DEFAULT, restored.template)
    }

    @Test fun templateSnapshotSurvivesReopenAndExplicitRetry() {
        val template = MailTemplate("saved-template {{sender}}", "{{time}}\n{{body}}")
        val original = job().copy(template = template)
        queue.enqueue(original)
        queue.close(); queue = QueueDatabase(context, crypto, name)
        assertEquals(template, queue.job(original.id)!!.template)
        assertEquals(template, queue.claimNext(true)!!.template)
        queue.finish(original.id, JobState.UNCERTAIN, MailFailure.UNKNOWN)
        queue.retry(original.id)
        // 旧记录的缺省身份在写入时规范化；其余快照必须完整保留。 / Legacy identity is normalized; all other snapshot fields must survive.
        assertEquals(original.copy(smtpIdentity = original.deliveryIdentity()), queue.claimNext(true))
        val bytes = context.getDatabasePath(name).readBytes().toString(Charsets.UTF_8)
        assertFalse(bytes.contains("saved-template"))
    }
}
