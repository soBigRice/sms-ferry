package com.sobigrice.smsrelay

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.sobigrice.smsrelay.background.RelayService
import com.sobigrice.smsrelay.mail.MailAccount
import com.sobigrice.smsrelay.mail.MailFailure
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Isolated emulator only; AUTH and cancelled jobs prevent real QQ mail delivery. */
@RunWith(AndroidJUnit4::class)
class MailSettingsUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph = context.relay
    private val saved = MailAccount.create("10001@qq.com", "abcdefghijklmnop", "10002@qq.com")
    private var activity: Activity? = null

    @Before fun prepare() {
        graph.config.enabled = false
        context.stopService(Intent(context, RelayService::class.java))
        WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        graph.config.save(saved)
        graph.config.mailFault = MailFailure.AUTH
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync { activity?.finish() }
        instrumentation.waitForIdleSync()
        graph.config.enabled = false
        graph.config.mailFault = MailFailure.AUTH
        context.stopService(Intent(context, RelayService::class.java))
        WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
    }

    @Test fun opensReadOnlyAndCancelDiscardsDraft() {
        val screen = launch(MailSettingsActivity::class.java)
        instrumentation.runOnMainSync {
            assertEquals(View.GONE, screen.findViewById<View>(R.id.editForm).visibility)
            assertEquals(View.VISIBLE, screen.findViewById<View>(R.id.readOnlyCard).visibility)
            screen.findViewById<Button>(R.id.editButton).performClick()
            screen.findViewById<EditText>(R.id.recipientInput).setText("10003@qq.com")
            screen.findViewById<EditText>(R.id.codeInput).setText("ponmlkjihgfedcba")
            screen.findViewById<Button>(R.id.cancelButton).performClick()
            assertEquals(View.GONE, screen.findViewById<View>(R.id.editForm).visibility)
            assertTrue(screen.findViewById<EditText>(R.id.codeInput).text.isEmpty())
        }
        assertEquals(saved, graph.config.account())
        assertEquals(MailFailure.AUTH, graph.config.mailFault)
    }
    @Test fun firstSetupOpensEditorWithoutAnExtraEditClick() {
        context.getSharedPreferences("relay_settings", 0).edit().remove("account").commit()
        val screen = launch(MailSettingsActivity::class.java)
        instrumentation.runOnMainSync {
            assertEquals(View.VISIBLE, screen.findViewById<View>(R.id.editForm).visibility)
            assertEquals(View.GONE, screen.findViewById<View>(R.id.readOnlyCard).visibility)
            assertFalse(screen.findViewById<Button>(R.id.testButton).isEnabled)
        }
        assertNull(graph.config.account())
    }

    @Test fun draftCannotSendOrSaveUntilExplicitSave() {
        val screen = launch(MailSettingsActivity::class.java)
        val before = graph.queue.counts()
        instrumentation.runOnMainSync {
            screen.findViewById<Button>(R.id.editButton).performClick()
            screen.findViewById<EditText>(R.id.recipientInput).setText("10003@qq.com")
            val test = screen.findViewById<Button>(R.id.testButton)
            assertFalse(test.isEnabled)
            // Also exercise the handler guard rather than relying only on a disabled widget.
            test.performClick()
        }
        assertEquals(saved, graph.config.account())
        assertEquals(before, graph.queue.counts())
        instrumentation.runOnMainSync {
            screen.findViewById<Button>(R.id.saveButton).performClick()
            assertEquals(View.GONE, screen.findViewById<View>(R.id.editForm).visibility)
        }
        assertEquals(saved.copy(recipient = "10003@qq.com"), graph.config.account())
        assertFalse(graph.config.enabled)
    }

    @Test fun returningDiscardsDraftAndKeepsForwardingPreference() {
        graph.config.enabled = true
        val screen = launch(MailSettingsActivity::class.java)
        instrumentation.runOnMainSync {
            screen.findViewById<Button>(R.id.editButton).performClick()
            screen.findViewById<EditText>(R.id.senderInput).setText("10004@qq.com")
            screen.findViewById<Button>(R.id.backButton).performClick()
            assertTrue(screen.isFinishing)
        }
        assertEquals(saved, graph.config.account())
        assertTrue(graph.config.enabled)
    }

    @Test fun startingForwardingUsesSavedAccountWithoutResavingOrClearingFault() {
        val preferences = context.getSharedPreferences("relay_settings", 0)
        val encryptedBefore = preferences.getString("account", null)
        val screen = launch(MainActivity::class.java)
        instrumentation.runOnMainSync {
            assertNull(screen.findViewById<View>(R.id.senderInput))
            assertNull(screen.findViewById<View>(R.id.recipientInput))
            screen.findViewById<Button>(R.id.toggleButton).performClick()
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!graph.config.enabled && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue(graph.config.enabled)
        // enabled is written by the IO task before its main-thread service start callback.
        // Wait for the actual foreground promotion before teardown stops the service.
        val serviceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!RelayService.running && System.nanoTime() < serviceDeadline) Thread.sleep(50)
        assertTrue("Foreground service must finish starting before cleanup", RelayService.running)
        instrumentation.waitForIdleSync()
        assertTrue("Starting must not rewrite saved credentials", encryptedBefore == preferences.getString("account", null))
        assertEquals(saved, graph.config.account())
        assertEquals(MailFailure.AUTH, graph.config.mailFault)
    }

    private fun launch(type: Class<out Activity>): Activity = instrumentation.startActivitySync(
        Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    ).also { activity = it; instrumentation.waitForIdleSync() }
}
