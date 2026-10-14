package com.sobigrice.smsrelay.background

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.sobigrice.smsrelay.hasSmsPermission
import com.sobigrice.smsrelay.mail.MailFailure
import com.sobigrice.smsrelay.relay

class InboxWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val graph = applicationContext.relay
        var imported = 0
        try {
            val account = graph.config.account() ?: return Result.success()
            val generation = graph.config.activationGeneration
            if (graph.config.enabled) {
                if (!applicationContext.hasSmsPermission()) {
                    graph.config.backgroundIssue = MailFailure.PERMISSION
                    graph.queue.publish()
                    return Result.success()
                }
                imported = graph.inbox.scan(account) {
                    graph.config.enabled && graph.config.activationGeneration == generation && !isStopped
                }
                if (graph.config.backgroundIssue == MailFailure.PERMISSION) graph.config.backgroundIssue = null
            }
            if (graph.queue.hasPending(graph.config.enabled)) QueueScheduler.send(applicationContext)
            graph.queue.pruneAccepted()
        } catch (_: SecurityException) {
            graph.config.backgroundIssue = MailFailure.PERMISSION; graph.queue.publish(); return Result.success()
        } catch (_: Exception) {
            graph.config.backgroundIssue = MailFailure.ENCRYPTION; graph.queue.publish(); return Result.success()
        }
        // The default SMS application may insert its provider row after SMS_RECEIVED.
        // Retry this specific event twice on its own chain; provider changes need not wait.
        return if (inputData.getBoolean("sms_event", false) && imported == 0 && runAttemptCount < 2 && graph.config.enabled)
            Result.retry() else Result.success()
    }
}
