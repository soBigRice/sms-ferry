package com.sobigrice.smsrelay.background

import android.content.Context
import android.os.Build
import androidx.work.*
import java.util.concurrent.TimeUnit

object QueueScheduler {
    private const val SCAN = "sms-inbox-scan"
    private const val SMS_EVENT_SCAN = "sms-broadcast-scan"
    private const val SEND = "mail-delivery"
    private const val RECOVERY = "sms-recovery"
    fun scan(context: Context, smsEvent: Boolean = false): Operation {
        val request = OneTimeWorkRequestBuilder<InboxWorker>()
            .setInputData(workDataOf("sms_event" to smsEvent))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        if (Build.VERSION.SDK_INT >= 31) request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        // An early broadcast can retry before the SMS provider commits its row. Keep that
        // backoff out of the observer chain so the commit notification can import immediately.
        // Concurrent scans share the database's unique source ID and transactional cursor.
        val chain = if (smsEvent) SMS_EVENT_SCAN else SCAN
        return WorkManager.getInstance(context).enqueueUniqueWork(chain, ExistingWorkPolicy.APPEND_OR_REPLACE, request.build())
    }
    fun send(context: Context) {
        val request = OneTimeWorkRequestBuilder<SendWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        if (Build.VERSION.SDK_INT >= 31) request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        WorkManager.getInstance(context).enqueueUniqueWork(SEND, ExistingWorkPolicy.APPEND_OR_REPLACE, request.build())
    }
    fun ensureRecovery(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(RECOVERY, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<InboxWorker>(15, TimeUnit.MINUTES).build())
    }
    fun pause(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(SCAN); cancelUniqueWork(SMS_EVENT_SCAN); cancelUniqueWork(RECOVERY)
        }
        // Don't interrupt SMTP after DATA. A current delivery may complete; subsequent SMS claims stop.
    }
}
