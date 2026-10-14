package com.sobigrice.smsrelay.background

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.sobigrice.smsrelay.data.QueueDataException
import com.sobigrice.smsrelay.mail.*
import com.sobigrice.smsrelay.relay

class SendWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val graph = applicationContext.relay
        try {
            val account = graph.config.account() ?: return Result.success()
            if (graph.config.mailFault != null) return Result.success()
            val runner = QueueRunner(graph.queue, QqMailSender())
            val outcome = runner.run(account,
                forwardingEnabled = { graph.config.enabled },
                stopped = {
                    isStopped || graph.config.account() != account
                })
            return when (outcome) {
                RunOutcome.RETRY -> Result.retry()
                RunOutcome.MORE -> { QueueScheduler.send(applicationContext); Result.success() }
                RunOutcome.BLOCKED -> {
                    val failure = runner.blockingFailure
                    if (failure in setOf(MailFailure.AUTH, MailFailure.TLS, MailFailure.CONFIG)) graph.config.mailFault = failure
                    graph.queue.publish(); Result.success()
                }
                RunOutcome.IDLE -> Result.success()
            }
        } catch (_: QueueDataException) {
            graph.config.mailFault = MailFailure.ENCRYPTION
        } catch (_: Exception) {
            graph.config.mailFault = MailFailure.CONFIG
        }
        graph.queue.publish()
        return Result.success()
    }
}
