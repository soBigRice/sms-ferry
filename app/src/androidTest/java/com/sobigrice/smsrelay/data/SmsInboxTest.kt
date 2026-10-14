package com.sobigrice.smsrelay.data

import android.provider.Telephony
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

/** Run only on an isolated emulator seeded with two identical SMS messages via `adb emu sms send`. */
@RunWith(AndroidJUnit4::class)
class SmsInboxTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "smsrelay.test.${UUID.randomUUID()}"
    private val name = "test-${UUID.randomUUID()}.db"
    private lateinit var queue: QueueDatabase
    private lateinit var inbox: SmsInbox
    private val account = MailAccount.create("10001@qq.com", "abcdefghijklmnop", "10002@qq.com")
    @Before fun prepare() {
        queue = QueueDatabase(context, CryptoStore(alias), name)
        inbox = SmsInbox(context, queue)
    }
    @After fun cleanup() {
        queue.close(); context.deleteDatabase(name)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
    }
    @Test fun providerImportKeepsTwoIdenticalMessagesAndDoesNotResendOnRescan() {
        assertTrue("seed isolated emulator with two SMS messages", inbox.scan(account) { true } >= 2)
        val jobs = queue.latest().map { requireNotNull(queue.job(it.id)) }
        assertTrue(jobs.count { it.body == "SMS relay local test 123456" } >= 2)
        assertEquals(jobs.size, jobs.map { it.sourceId }.toSet().size)
        assertEquals(0, inbox.scan(account) { true })
    }
    @Test fun initialBaselineExcludesHistoryAndUsesTheNewestProviderId() {
        val max = context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms._ID), null, null, "${Telephony.Sms._ID} DESC")!!.use {
            assertTrue(it.moveToFirst()); it.getLong(0)
        }
        assertEquals(max, inbox.latestId())
        queue.setScanBaseline(max)
        assertEquals(0, inbox.scan(account) { true })
        assertTrue(queue.latest().isEmpty())
    }
    @Test fun pausedImportDoesNotCaptureExistingMessages() {
        assertEquals(0, inbox.scan(account) { false })
        assertTrue(queue.latest().isEmpty())
    }
    @Test fun concurrentScansKeepEachProviderIdOnce() {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = List(2) { executor.submit<Int> {
                ready.countDown()
                assertTrue(start.await(5, TimeUnit.SECONDS))
                inbox.scan(account) { true }
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val imported = results.sumOf { it.get(10, TimeUnit.SECONDS) }
            val jobs = queue.latest().map { requireNotNull(queue.job(it.id)) }
            assertTrue("seed isolated emulator with two SMS messages", imported >= 2)
            assertEquals(imported, jobs.size)
            assertEquals(jobs.size, jobs.map { it.sourceId }.toSet().size)
            assertEquals(inbox.latestId(), queue.scanCursor())
            assertEquals(0, inbox.scan(account) { true })
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
