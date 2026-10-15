package com.sobigrice.smsrelay.data

import android.content.Context
import android.os.Bundle
import android.provider.Telephony
import android.content.ContentResolver
import com.sobigrice.smsrelay.mail.MailAccount
import com.sobigrice.smsrelay.mail.MailJob

class SmsInbox(private val context: Context, private val queue: QueueDatabase) {
    fun latestId(): Long = context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
        arrayOf(Telephony.Sms._ID), queryArgs(0, descending = true), null)?.use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    } ?: throw IllegalStateException("SMS provider unavailable")

    fun scan(account: MailAccount, active: () -> Boolean): Int {
        var inserted = 0
        repeat(10) {
            if (!active()) return inserted
            val records = readPage(queue.scanCursor(), account)
            if (records.isEmpty()) return inserted
            for (record in records) {
                if (!active()) return inserted
                if (queue.enqueue(record)) inserted++
            }
            if (records.size < 200) return inserted
        }
        return inserted
    }

    private fun readPage(after: Long, account: MailAccount): List<MailJob> = context.contentResolver.query(
        Telephony.Sms.Inbox.CONTENT_URI,
        arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
        queryArgs(after), null
    )?.use { cursor -> buildList {
        while (size < 200 && cursor.moveToNext()) add(MailJob(sourceId = cursor.getLong(0),
            sourceSender = cursor.getString(1) ?: "未知号码", body = cursor.getString(2) ?: "",
            receivedAt = cursor.getLong(3), accountAddress = account.sender, recipientAddress = account.recipient,
            smtpIdentity = account.identity, template = account.template))
    } } ?: throw IllegalStateException("SMS provider unavailable")

    private fun queryArgs(after: Long, descending: Boolean = false) = Bundle().apply {
        putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(Telephony.Sms._ID))
        putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, if (descending)
            ContentResolver.QUERY_SORT_DIRECTION_DESCENDING else ContentResolver.QUERY_SORT_DIRECTION_ASCENDING)
        putInt(ContentResolver.QUERY_ARG_LIMIT, if (descending) 1 else 200)
        if (!descending) {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${Telephony.Sms._ID}>?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(after.toString()))
        }
    }
}
