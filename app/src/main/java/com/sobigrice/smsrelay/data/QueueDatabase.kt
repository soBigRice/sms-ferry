package com.sobigrice.smsrelay.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.sobigrice.smsrelay.mail.*
import org.json.JSONObject

class QueueDatabase(private val context: Context, private val crypto: CryptoStore, name: String = "relay_queue.db") :
    SQLiteOpenHelper(context, name, null, 1), MailQueue {
    companion object { const val ACTION_CHANGED = "com.sobigrice.smsrelay.STATE_CHANGED" }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE jobs (
            id TEXT PRIMARY KEY, source_id INTEGER UNIQUE, kind TEXT NOT NULL,
            payload TEXT NOT NULL, state TEXT NOT NULL, created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, failure TEXT)""")
        db.execSQL("CREATE INDEX jobs_state ON jobs(state, created_at)")
        db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        db.execSQL("INSERT INTO metadata VALUES ('scan_cursor', 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported database upgrade; preserve existing data")
    }
    fun scanCursor(): Long = readableDatabase.rawQuery(
        "SELECT value FROM metadata WHERE name='scan_cursor'", null).use { it.moveToFirst(); it.getLong(0) }
    fun setScanBaseline(id: Long) {
        writableDatabase.execSQL("UPDATE metadata SET value=? WHERE name='scan_cursor'", arrayOf(id))
    }
    fun enqueue(job: MailJob): Boolean {
        val value = ContentValues().apply {
            put("id", job.id); put("source_id", job.sourceId); put("kind", job.kind.name)
            put("payload", encode(job)); put("state", JobState.PENDING.name)
            put("created_at", job.receivedAt); put("updated_at", System.currentTimeMillis())
        }
        val db = writableDatabase
        var inserted = false
        db.beginTransaction()
        try {
            inserted = db.insertWithOnConflict("jobs", null, value, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            job.sourceId?.let {
                // Persist row and monotonic scan cursor together; a crash cannot skip an unqueued SMS.
                db.execSQL("UPDATE metadata SET value=MAX(value, ?) WHERE name='scan_cursor'", arrayOf(it))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (inserted) publish()
        return inserted
    }
    override fun claimNext(allowSms: Boolean): MailJob? {
        val db = writableDatabase
        var id: String? = null
        var payload: String? = null
        db.beginTransaction()
        try {
            val kindFilter = if (allowSms) "" else " AND kind='TEST'"
            db.rawQuery("SELECT id,payload FROM jobs WHERE state='PENDING'$kindFilter ORDER BY created_at,id LIMIT 1", null).use {
                if (it.moveToFirst()) { id = it.getString(0); payload = it.getString(1) }
            }
            id?.let {
                db.execSQL("UPDATE jobs SET state='SENDING',attempts=attempts+1,updated_at=? WHERE id=? AND state='PENDING'",
                    arrayOf<Any>(System.currentTimeMillis(), it))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (id == null) return null
        publish()
        return try { decode(requireNotNull(payload)) }
        catch (e: Exception) {
            finish(requireNotNull(id), JobState.BLOCKED, MailFailure.ENCRYPTION)
            throw QueueDataException(e)
        }
    }
    override fun finish(id: String, state: JobState, failure: MailFailure?) {
        val value = ContentValues().apply {
            put("state", state.name); put("failure", failure?.name); put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("jobs", value, "id=?", arrayOf(id)); publish()
    }
    override fun hasPending(allowSms: Boolean): Boolean {
        val kindFilter = if (allowSms) "" else " AND kind='TEST'"
        return readableDatabase.rawQuery("SELECT 1 FROM jobs WHERE state='PENDING'$kindFilter LIMIT 1", null)
            .use { it.moveToFirst() }
    }
    fun recoverInterrupted() {
        val values = ContentValues().apply {
            put("state", JobState.UNCERTAIN.name); put("failure", MailFailure.INTERRUPTED.name)
            put("updated_at", System.currentTimeMillis())
        }
        if (writableDatabase.update("jobs", values, "state='SENDING'", null) > 0) publish()
    }
    fun retry(id: String) {
        val values = ContentValues().apply { put("state", JobState.PENDING.name); putNull("failure") }
        writableDatabase.update("jobs", values, "id=? AND state IN ('BLOCKED','UNCERTAIN')", arrayOf(id)); publish()
    }
    fun job(id: String): MailJob? = readableDatabase.rawQuery("SELECT payload FROM jobs WHERE id=?", arrayOf(id))
        .use { if (it.moveToFirst()) decode(it.getString(0)) else null }
    fun latest(limit: Int = 20): List<JobSummary> {
        require(limit in 1..100)
        return readableDatabase.rawQuery(
            "SELECT id,kind,state,created_at,attempts,failure FROM jobs ORDER BY created_at DESC,id DESC LIMIT $limit", null
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(JobSummary(cursor.getString(0), JobKind.valueOf(cursor.getString(1)),
                JobState.valueOf(cursor.getString(2)), cursor.getLong(3), cursor.getInt(4),
                cursor.getString(5)?.let { MailFailure.valueOf(it) }))
        } }
    }
    fun problems(): List<JobSummary> = readableDatabase.rawQuery(
        "SELECT id,kind,state,created_at,attempts,failure FROM jobs WHERE state IN ('BLOCKED','UNCERTAIN') ORDER BY created_at LIMIT 20", null
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(JobSummary(cursor.getString(0), JobKind.valueOf(cursor.getString(1)),
            JobState.valueOf(cursor.getString(2)), cursor.getLong(3), cursor.getInt(4),
            cursor.getString(5)?.let { MailFailure.valueOf(it) }))
    } }
    fun counts(): Map<JobState, Int> = readableDatabase.rawQuery("SELECT state,COUNT(*) FROM jobs GROUP BY state", null)
        .use { cursor -> buildMap { while (cursor.moveToNext()) put(JobState.valueOf(cursor.getString(0)), cursor.getInt(1)) } }
    fun pruneAccepted() {
        val before = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        writableDatabase.delete("jobs", "state='ACCEPTED' AND updated_at<?", arrayOf(before.toString()))
    }
    fun publish() { context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName)) }
    private fun encode(job: MailJob): String = crypto.encrypt(JSONObject().apply {
        put("id", job.id); put("sourceId", job.sourceId ?: JSONObject.NULL)
        put("sender", job.sourceSender); put("body", job.body); put("receivedAt", job.receivedAt)
        put("account", job.accountAddress); put("recipient", job.recipientAddress); put("kind", job.kind.name)
        put("template", JSONObject().put("subject", job.template.subject).put("body", job.template.body))
        put("smtp", job.deliveryIdentity().let { JSONObject().put("host", it.host).put("port", it.port)
            .put("security", it.security.name).put("username", it.username) })
    }.toString())
    private fun decode(payload: String): MailJob {
        val j = JSONObject(crypto.decrypt(payload))
        return MailJob(j.getString("id"), if (j.isNull("sourceId")) null else j.getLong("sourceId"),
            j.getString("sender"), j.getString("body"), j.getLong("receivedAt"),
            j.getString("account"), j.getString("recipient"), JobKind.valueOf(j.getString("kind")),
            j.optJSONObject("smtp")?.let { SmtpIdentity(it.getString("host"), it.getInt("port"),
                MailSecurity.valueOf(it.getString("security")), it.getString("username")) },
            j.optJSONObject("template")?.let { MailTemplate(it.getString("subject"), it.getString("body")) } ?: MailTemplate.DEFAULT)
    }
}

class QueueDataException(cause: Throwable) : Exception("Local encrypted queue cannot be read", cause)
