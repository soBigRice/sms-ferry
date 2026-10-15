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
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("cancelButton"))
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
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("saveButton"))
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
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("saveButton"))
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
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("saveButton"))
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
    @Test fun templatePreviewDoesNotSendAndSavePersistsWithoutChangingQueuedMail() {
        val original = MailJob(sourceSender = "106123", body = "queued sample", receivedAt = 123,
            accountAddress = saved.sender, recipientAddress = saved.recipient, smtpIdentity = saved.identity)
        graph.queue.enqueue(original)
        launch(MailSettingsActivity::class.java)
        val before = graph.queue.counts()
        compose.onNodeWithTag("editButton").performClick()
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("templateCard"))
        compose.onNodeWithTag("templateSubjectInput").performScrollTo().performTextReplacement("SMS {{sender}}")
        compose.onNodeWithTag("templateBodyInput").performScrollTo().performTextReplacement("{{body}}\nFrom {{sender}}")
        compose.onNodeWithTag("templatePreviewButton").performScrollTo().performClick()
        compose.onNodeWithTag("templatePreviewSubject").assertTextEquals("SMS 10612345678")
        compose.onNodeWithTag("templatePreviewBody").assertTextContains("From 10612345678", substring = true)
        compose.onNodeWithTag("templatePreviewClose").performClick()
        assertEquals(saved, graph.config.account())
        assertEquals(before, graph.queue.counts())
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("saveButton"))
        compose.onNodeWithTag("saveButton").performScrollTo().performClick()
        assertEquals(MailTemplate("SMS {{sender}}", "{{body}}\nFrom {{sender}}"), graph.config.account()!!.template)
        assertEquals(original, graph.queue.job(original.id))
        assertFalse(graph.config.enabled)
        compose.onNodeWithTag("editButton").performScrollTo().performClick()
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("templateCard"))
        compose.onNodeWithTag("templateSubjectInput").performScrollTo().assertTextContains("SMS {{sender}}", substring = false)
    }

    @Test fun invalidTemplateCannotSaveAndResetOnlyAppliesAfterSave() {
        val custom = saved.copy(template = MailTemplate("Saved {{sender}}", "{{body}}\nSaved"))
        graph.config.save(custom)
        graph.config.mailFault = MailFailure.AUTH
        launch(MailSettingsActivity::class.java)
        compose.onNodeWithTag("editButton").performClick()
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("templateCard"))
        compose.onNodeWithTag("templateBodyInput").performScrollTo().performTextReplacement("missing full text")
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("saveButton"))
        compose.onNodeWithTag("saveButton").performScrollTo().performClick()
        compose.onNodeWithText(AppLanguage.wrap(context).getString(R.string.validation_template_missing_body)).assertIsDisplayed()
        assertEquals(custom, graph.config.account())
        assertEquals(MailFailure.AUTH, graph.config.mailFault)
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("templateCard"))
        compose.onNodeWithTag("templateResetButton").performScrollTo().performClick()
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("cancelButton"))
        compose.onNodeWithTag("cancelButton").performScrollTo().performClick()
        assertEquals(custom, graph.config.account())
        compose.onNodeWithTag("editButton").performScrollTo().performClick()
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("templateCard"))
        compose.onNodeWithTag("templateResetButton").performScrollTo().performClick()
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("saveButton"))
        compose.onNodeWithTag("saveButton").performScrollTo().performClick()
        assertEquals(MailTemplate.DEFAULT, graph.config.account()!!.template)
    }

    @Test fun testEmailQueuesTheSavedTemplate() {
        // 仅允许回环连接，测试不向外部邮件服务器发信。 / Loopback only; this test never contacts an external mail server.
        val local = MailAccount.create("sender@example.org", "fake-password", "reader@example.net", "127.0.0.1", 465,
            MailSecurity.TLS, template = MailTemplate("Test {{sender}}", "{{body}}\n{{time}}"))
        graph.config.save(local)
        val previousIds = graph.queue.latest().map { it.id }.toSet()
        launch(MailSettingsActivity::class.java)
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("testButton"))
        compose.onNodeWithTag("testButton").performScrollTo().performClick()
        compose.waitUntil(10_000) { graph.queue.latest().any { it.id !in previousIds && it.kind == JobKind.TEST } }
        val queued = graph.queue.latest().first { it.id !in previousIds && it.kind == JobKind.TEST }.let { graph.queue.job(it.id)!! }
        assertEquals(local.template, queued.template)
        assertEquals(local.identity, queued.smtpIdentity)
        assertEquals(local.recipient, queued.recipientAddress)
    }

    private fun launch(type: Class<out Activity>) {
        activity = instrumentation.startActivitySync(Intent(context, type)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        compose.waitForIdle()
    }
}
