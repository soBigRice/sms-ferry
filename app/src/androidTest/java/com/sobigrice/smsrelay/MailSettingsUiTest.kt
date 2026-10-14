package com.sobigrice.smsrelay

import android.app.Activity
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.sobigrice.smsrelay.background.RelayService
import com.sobigrice.smsrelay.mail.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Isolated emulator only; AUTH prevents real SMTP delivery. */
@RunWith(AndroidJUnit4::class)
class MailSettingsUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph = context.relay
    private val saved = MailAccount.legacyQq("10001@qq.com", "abcdefghijklmnop", "10002@qq.com")
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
        launch(MailSettingsActivity::class.java)
        compose.onNodeWithTag("readOnlyCard").assertExists()
        compose.onNodeWithTag("editForm").assertDoesNotExist()
        compose.onNodeWithTag("editButton").performScrollTo().performClick()
        compose.onNodeWithTag("recipientInput").performScrollTo().performTextReplacement("10003@qq.com")
        compose.onNodeWithTag("codeInput").performScrollTo().performTextInput("ponmlkjihgfedcba")
        compose.onNodeWithTag("cancelButton").performScrollTo().performClick()
        compose.onNodeWithTag("editForm").assertDoesNotExist()
        assertEquals(saved, graph.config.account())
        assertEquals(MailFailure.AUTH, graph.config.mailFault)
    }
    @Test fun firstSetupOpensEditorWithoutAnExtraEditClick() {
        context.getSharedPreferences("relay_settings", 0).edit().remove("account").commit()
        launch(MailSettingsActivity::class.java)
        compose.onNodeWithTag("editForm").assertExists()
        compose.onNodeWithTag("readOnlyCard").assertDoesNotExist()
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("testButton"))
        compose.onNodeWithTag("testButton").performScrollTo().assertIsNotEnabled()
        assertNull(graph.config.account())
    }
    @Test fun draftCannotSendOrSaveUntilExplicitSave() {
        launch(MailSettingsActivity::class.java)
        val before = graph.queue.counts()
        compose.onNodeWithTag("editButton").performScrollTo().performClick()
        compose.onNodeWithTag("recipientInput").performScrollTo().performTextReplacement("10003@qq.com")
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("testButton"))
        compose.onNodeWithTag("testButton").performScrollTo().assertIsNotEnabled()
        assertEquals(saved, graph.config.account())
        assertEquals(before, graph.queue.counts())
        compose.onNodeWithTag("saveButton").performScrollTo().performClick()
        compose.onNodeWithTag("editForm").assertDoesNotExist()
        assertEquals(saved.copy(recipient = "10003@qq.com"), graph.config.account())
        assertFalse(graph.config.enabled)
    }
    @Test fun returningDiscardsDraftAndKeepsForwardingPreference() {
        graph.config.enabled = true
        launch(MailSettingsActivity::class.java)
        compose.onNodeWithTag("editButton").performScrollTo().performClick()
        compose.onNodeWithTag("senderInput").performScrollTo().performTextReplacement("10004@qq.com")
        compose.onNodeWithTag("backButton").performClick()
        assertEquals(saved, graph.config.account())
        assertTrue(graph.config.enabled)
    }
    @Test fun changingServerCannotReuseStoredPassword() {
        launch(MailSettingsActivity::class.java)
        compose.onNodeWithTag("editButton").performScrollTo().performClick()
        compose.onNodeWithTag("hostInput").performScrollTo().performTextReplacement("smtp.example.org")
        compose.onNodeWithTag("saveButton").performScrollTo().performClick()
        assertEquals(saved, graph.config.account())
        compose.onNodeWithText(AppLanguage.wrap(context).getString(R.string.credential_changed)).assertExists()
    }
    @Test fun customSmtpProfileIsSavedWithIndependentLogin() {
        launch(MailSettingsActivity::class.java)
        compose.onNodeWithTag("editButton").performScrollTo().performClick()
        compose.onNodeWithTag("hostInput").performScrollTo().performTextReplacement("smtp.example.org")
        compose.onNodeWithText("STARTTLS").performScrollTo().performClick()
        compose.onNodeWithTag("senderInput").performScrollTo().performTextReplacement("alice@example.org")
        compose.onNodeWithTag("usernameInput").performScrollTo().performTextReplacement("smtp-user")
        compose.onNodeWithTag("codeInput").performScrollTo().performTextInput("pass word!")
        compose.onNodeWithTag("recipientInput").performScrollTo().performTextReplacement("reader@example.net")
        compose.onNodeWithTag("saveButton").performScrollTo().performClick()
        assertEquals(MailAccount.create("alice@example.org", "pass word!", "reader@example.net", "smtp.example.org", 587,
            MailSecurity.STARTTLS, "smtp-user"), graph.config.account())
        compose.onNodeWithTag("editForm").assertDoesNotExist()
    }
    @Test fun startingForwardingUsesSavedAccountWithoutResavingOrClearingFault() {
        val preferences = context.getSharedPreferences("relay_settings", 0)
        val encryptedBefore = preferences.getString("account", null)
        launch(MainActivity::class.java)
        compose.onNodeWithTag("senderInput").assertDoesNotExist()
        compose.onNodeWithTag("recipientInput").assertDoesNotExist()
        compose.onNodeWithTag("toggleButton").performClick()
        compose.waitUntil(10000) { graph.config.enabled && RelayService.running }
        assertEquals(encryptedBefore, preferences.getString("account", null))
        assertEquals(saved, graph.config.account())
        assertEquals(MailFailure.AUTH, graph.config.mailFault)
    }
    private fun launch(type: Class<out Activity>) {
        activity = instrumentation.startActivitySync(Intent(context, type)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        compose.waitForIdle()
    }
}
