package com.sobigrice.smsrelay.background

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.sobigrice.smsrelay.mail.MailAccount
import com.sobigrice.smsrelay.mail.MailFailure
import com.sobigrice.smsrelay.relay
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Isolated emulator only: fake account and AUTH block prevent real SMTP access. */
@RunWith(AndroidJUnit4::class)
class QueueSchedulerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = context.relay
    private val work = WorkManager.getInstance(context)

    @Before fun prepare() {
        work.cancelAllWork().result.get(5, TimeUnit.SECONDS)
        graph.config.save(MailAccount.create("10001@qq.com", "abcdefghijklmnop", "10002@qq.com"))
        graph.config.mailFault = MailFailure.AUTH
        graph.queue.setScanBaseline(graph.inbox.latestId())
        graph.config.enabled = true
    }

    @After fun cleanup() {
        graph.config.enabled = false
        work.cancelAllWork().result.get(5, TimeUnit.SECONDS)
    }

    @Test fun providerChangeRunsWhileEarlyBroadcastIsWaitingForRetry() {
        val earlyBroadcast = enqueueScan(smsEvent = true)
        awaitInfo(earlyBroadcast) { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 1 }

        // Models the provider commit/observer event arriving after an empty broadcast scan.
        val providerChange = enqueueScan(smsEvent = false)
        awaitInfo(providerChange) { it.state == WorkInfo.State.SUCCEEDED }
        val waiting = work.getWorkInfoById(earlyBroadcast).get(5, TimeUnit.SECONDS)!!
        assertEquals(WorkInfo.State.ENQUEUED, waiting.state)
        assertEquals(1, waiting.runAttemptCount)
        assertEquals(MailFailure.AUTH, graph.config.mailFault)
    }

    @Test fun pauseCancelsTheBroadcastRetry() {
        val earlyBroadcast = enqueueScan(smsEvent = true)
        awaitInfo(earlyBroadcast) { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 1 }
        graph.config.enabled = false
        QueueScheduler.pause(context)
        awaitInfo(earlyBroadcast) { it.state == WorkInfo.State.CANCELLED }
    }

    private fun enqueueScan(smsEvent: Boolean): UUID {
        val tag = InboxWorker::class.java.name
        val before = work.getWorkInfosByTag(tag).get(5, TimeUnit.SECONDS).map { it.id }.toSet()
        QueueScheduler.scan(context, smsEvent).result.get(5, TimeUnit.SECONDS)
        return work.getWorkInfosByTag(tag).get(5, TimeUnit.SECONDS).single { it.id !in before }.id
    }

    private fun awaitInfo(id: UUID, condition: (WorkInfo) -> Boolean): WorkInfo {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        var info: WorkInfo?
        do {
            info = work.getWorkInfoById(id).get(5, TimeUnit.SECONDS)
            if (info != null && condition(info)) return info
            Thread.sleep(50)
        } while (System.nanoTime() < deadline)
        fail("Expected work state was not reached: $info")
        error("unreachable")
    }
}
