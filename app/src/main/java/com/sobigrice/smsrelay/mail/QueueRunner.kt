package com.sobigrice.smsrelay.mail

enum class RunOutcome { IDLE, MORE, RETRY, BLOCKED }

class QueueRunner(private val queue: MailQueue, private val sender: MailSender) {
    var blockingFailure: MailFailure? = null
        private set
    fun run(
        account: MailAccount,
        forwardingEnabled: () -> Boolean,
        stopped: () -> Boolean = { false },
        maxMessages: Int = 5,
    ): RunOutcome {
        require(maxMessages > 0)
        blockingFailure = null
        repeat(maxMessages) {
            if (stopped()) return RunOutcome.IDLE
            val job = queue.claimNext(forwardingEnabled()) ?: return RunOutcome.IDLE
            if (stopped() || (job.kind == JobKind.SMS && !forwardingEnabled())) {
                queue.finish(job.id, JobState.PENDING)
                return RunOutcome.IDLE
            }
            if (!job.accountAddress.equals(account.sender, ignoreCase = true)) {
                blockingFailure = MailFailure.ACCOUNT_CHANGED
                queue.finish(job.id, JobState.BLOCKED, MailFailure.ACCOUNT_CHANGED)
                return RunOutcome.BLOCKED
            }
            // The queue has durably marked SENDING before SMTP begins. A process crash
            // leaves an ambiguous delivery, which recovery must never blindly resend.
            val result = try { sender.send(job, account) }
            catch (_: Exception) { SendResult.Uncertain() }
            when (result) {
                SendResult.Accepted -> queue.finish(job.id, JobState.ACCEPTED)
                is SendResult.Retry -> {
                    queue.finish(job.id, JobState.PENDING, result.failure)
                    return RunOutcome.RETRY
                }
                is SendResult.Rejected -> {
                    blockingFailure = result.failure
                    queue.finish(job.id, JobState.BLOCKED, result.failure)
                    return RunOutcome.BLOCKED
                }
                is SendResult.Uncertain -> queue.finish(job.id, JobState.UNCERTAIN, result.failure)
            }
        }
        return if (queue.hasPending(forwardingEnabled())) RunOutcome.MORE else RunOutcome.IDLE
    }
}
