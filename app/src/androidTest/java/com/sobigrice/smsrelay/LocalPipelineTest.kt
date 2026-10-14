package com.sobigrice.smsrelay

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sobigrice.smsrelay.background.QueueScheduler
import com.sobigrice.smsrelay.background.RelayService
import com.sobigrice.smsrelay.mail.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Leaves a dummy configured receiver for host-driven reboot validation on the temporary emulator.
 *  AUTH fault intentionally prevents all SMTP access; these are not QQ delivery tests. */
@RunWith(AndroidJUnit4::class)
class LocalPipelineTest {
    @Test fun realInboxIsImportedByBackgroundWorkerWithDurablePendingMail() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = context.relay
        graph.config.save(MailAccount.create("10001@qq.com", "abcdefghijklmnop", "10002@qq.com"))
        graph.config.mailFault = MailFailure.AUTH
        graph.queue.setScanBaseline(0)
        graph.config.enabled = true
        val command = instrumentation.uiAutomation.executeShellCommand("am start -n com.sobigrice.smsrelay/.MainActivity")
        command.close()
        // Starting the actual foreground service is authorized by the foreground activity.
        QueueScheduler.ensureRecovery(context)
        QueueScheduler.scan(context)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
        while ((graph.queue.counts()[JobState.PENDING] ?: 0) < 2 && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue((graph.queue.counts()[JobState.PENDING] ?: 0) >= 2)
        assertTrue(graph.queue.latest().all { it.state == JobState.PENDING })
        assertEquals(MailFailure.AUTH, graph.config.mailFault)
        assertTrue(graph.queue.latest().mapNotNull { graph.queue.job(it.id) }.any { it.body == "SMS relay local test 123456" })
        // Host verification additionally checks automatic service restoration after reboot.
    }
}
