package com.sobigrice.smsrelay.mail

import org.junit.Assert.*
import org.junit.Test

class QueueRunnerTest {
    private val account = MailAccount.legacyQq("10001@qq.com", "abcdefghijklmnop", "10002@qq.com")
    private fun job(sourceId: Long = 1, recipient: String = account.recipient, kind: JobKind = JobKind.SMS) =
        MailJob(sourceId = sourceId, sourceSender = "106123", body = "验证码 123456", receivedAt = 123,
            accountAddress = account.sender, recipientAddress = recipient, kind = kind)

    private class FakeQueue(jobs: List<MailJob>) : MailQueue {
        val jobs = jobs.associateBy { it.id }
        val states = jobs.associate { it.id to JobState.PENDING }.toMutableMap()
        val failures = mutableMapOf<String, MailFailure?>()
        override fun claimNext(allowSms: Boolean): MailJob? = jobs.values.firstOrNull {
            states[it.id] == JobState.PENDING && (allowSms || it.kind == JobKind.TEST)
        }?.also { states[it.id] = JobState.SENDING }
        override fun finish(id: String, state: JobState, failure: MailFailure?) {
            states[id] = state; failures[id] = failure
        }
        override fun hasPending(allowSms: Boolean) = jobs.values.any {
            states[it.id] == JobState.PENDING && (allowSms || it.kind == JobKind.TEST)
        }
    }

    @Test fun sameTextWithDifferentSourceIdsIsSentTwice() {
        val queue = FakeQueue(listOf(job(1), job(2)))
        val delivered = mutableListOf<Long?>()
        QueueRunner(queue) { j, _ -> delivered += j.sourceId; SendResult.Accepted }.run(account, { true })
        assertEquals(listOf(1L, 2L), delivered)
        assertTrue(queue.states.values.all { it == JobState.ACCEPTED })
    }

    @Test fun networkFailureRemainsPendingAndRequestsBackoff() {
        val j = job(); val queue = FakeQueue(listOf(j))
        assertEquals(RunOutcome.RETRY, QueueRunner(queue) { _, _ -> SendResult.Retry(MailFailure.NETWORK) }
            .run(account, { true }))
        assertEquals(JobState.PENDING, queue.states[j.id])
    }

    @Test fun ambiguousDeliveryIsNeverAutomaticallyRetried() {
        val j = job(); val queue = FakeQueue(listOf(j)); var sends = 0
        val runner = QueueRunner(queue) { _, _ -> sends++; SendResult.Uncertain() }
        runner.run(account, { true }); runner.run(account, { true })
        assertEquals(1, sends); assertEquals(JobState.UNCERTAIN, queue.states[j.id])
    }

    @Test fun authenticationFailureStopsTheBatchWithoutDiscardingOtherMessages() {
        val jobs = listOf(job(1), job(2)); val queue = FakeQueue(jobs); var sends = 0
        val outcome = QueueRunner(queue) { _, _ -> sends++; SendResult.Rejected(MailFailure.AUTH) }
            .run(account, { true })
        assertEquals(RunOutcome.BLOCKED, outcome); assertEquals(1, sends)
        assertEquals(JobState.BLOCKED, queue.states[jobs[0].id])
        assertEquals(JobState.PENDING, queue.states[jobs[1].id])
    }

    @Test fun destinationIsTheAddressCapturedAtEnqueueTime() {
        val j = job(recipient = "old@example.com"); val queue = FakeQueue(listOf(j)); var to = ""
        QueueRunner(queue) { record, _ -> to = record.recipientAddress; SendResult.Accepted }
            .run(account, { true })
        assertEquals("old@example.com", to)
    }

    @Test fun changedSendingAccountCannotSendOldQueuedMail() {
        val j = job(); val queue = FakeQueue(listOf(j))
        val other = account.copy(sender = "another@qq.com")
        QueueRunner(queue) { _, _ -> fail("must not send"); SendResult.Accepted }.run(other, { true })
        assertEquals(JobState.BLOCKED, queue.states[j.id])
        assertEquals(MailFailure.ACCOUNT_CHANGED, queue.failures[j.id])
    }

    @Test fun changedServerOrLoginCannotUseExistingQueuedMessages() {
        for (other in listOf(account.copy(smtpHost = "smtp.example.org"), account.copy(smtpPort = 587),
            account.copy(security = MailSecurity.STARTTLS), account.copy(username = "different-login"))) {
            val j = job(); val queue = FakeQueue(listOf(j))
            QueueRunner(queue) { _, _ -> fail("must not send via another SMTP identity"); SendResult.Accepted }
                .run(other, { true })
            assertEquals(JobState.BLOCKED, queue.states[j.id])
            assertEquals(MailFailure.ACCOUNT_CHANGED, queue.failures[j.id])
        }
    }

    @Test fun genericProfileKeepsItsOriginalRecipientWhenSettingsChange() {
        val generic = MailAccount.create("a@example.org", "password", "b@example.org", "smtp.example.org", 587, MailSecurity.STARTTLS)
        val j = job(recipient = generic.recipient).copy(accountAddress = generic.sender, smtpIdentity = generic.identity)
        val queue = FakeQueue(listOf(j)); var deliveredTo = ""
        QueueRunner(queue) { record, _ -> deliveredTo = record.recipientAddress; SendResult.Accepted }
            .run(generic.copy(recipient = "new@example.org"), { true })
        assertEquals("b@example.org", deliveredTo)
        assertEquals(JobState.ACCEPTED, queue.states[j.id])
    }

    @Test fun pauseKeepsSmsButAllowsExplicitTestEmail() {
        val sms = job(); val test = job(kind = JobKind.TEST); val queue = FakeQueue(listOf(sms, test))
        QueueRunner(queue) { _, _ -> SendResult.Accepted }.run(account, { false })
        assertEquals(JobState.PENDING, queue.states[sms.id])
        assertEquals(JobState.ACCEPTED, queue.states[test.id])
    }

    @Test fun cancellationBeforeSendingReturnsClaimToQueue() {
        val j = job(); val queue = FakeQueue(listOf(j)); var calls = 0
        QueueRunner(queue) { _, _ -> fail("must not send"); SendResult.Accepted }
            .run(account, { true }, { ++calls >= 2 })
        assertEquals(JobState.PENDING, queue.states[j.id])
    }

    @Test fun messagesBeyondBatchLimitAreLeftForNextRun() {
        val queue = FakeQueue((1L..6L).map { job(it) })
        assertEquals(RunOutcome.MORE, QueueRunner(queue) { _, _ -> SendResult.Accepted }.run(account, { true }))
        assertEquals(1, queue.states.values.count { it == JobState.PENDING })
    }
}
