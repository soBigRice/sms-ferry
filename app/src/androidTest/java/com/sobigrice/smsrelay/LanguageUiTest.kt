package com.sobigrice.smsrelay

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.sobigrice.smsrelay.background.RelayService
import com.sobigrice.smsrelay.mail.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Isolated emulator only. Fake credentials and AUTH blocking prevent SMTP delivery. */
@RunWith(AndroidJUnit4::class)
class LanguageUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph = context.relay
    private val saved = MailAccount.legacyQq("10001@qq.com", "abcdefghijklmnop", "10002@qq.com")
    private var originalLanguage = ""
    private var scenario: ActivityScenario<*>? = null

    @Before fun prepare() {
        originalLanguage = AppLanguage.selected(context)
        graph.config.enabled = false
        context.stopService(Intent(context, RelayService::class.java))
        WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        graph.config.save(saved)
        graph.config.mailFault = MailFailure.AUTH
        language("en")
    }

    @After fun cleanup() {
        scenario?.close()
        language(originalLanguage)
        graph.config.enabled = false
        graph.config.mailFault = MailFailure.AUTH
        context.stopService(Intent(context, RelayService::class.java))
        WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
    }

    @Test fun pickerRecreatesScreensAndKeepsConfigurationQueueAndCursor() {
        val counts = graph.queue.counts()
        val cursor = graph.queue.scanCursor()
        val encryptedAccount = context.getSharedPreferences("relay_settings", Context.MODE_PRIVATE).getString("account", null)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("Email needs attention").assertExists()
        compose.onNodeWithTag("homeList").performScrollToNode(hasTestTag("languageButton"))
        compose.onNodeWithTag("languageButton").performClick()
        compose.onNodeWithText("中文", substring = false).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("邮箱需要处理").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("zh", AppLanguage.selected(context))
        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("邮箱需要处理").assertExists()
        compose.onNodeWithTag("homeList").performScrollToNode(hasTestTag("languageButton"))
        compose.onNodeWithTag("languageButton").performClick()
        compose.onNodeWithText("English", substring = false).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Email needs attention").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("en", AppLanguage.selected(context))
        assertEquals(saved, graph.config.account())
        assertEquals(encryptedAccount, context.getSharedPreferences("relay_settings", Context.MODE_PRIVATE).getString("account", null))
        assertEquals(MailFailure.AUTH, graph.config.mailFault)
        assertEquals(counts, graph.queue.counts())
        assertEquals(cursor, graph.queue.scanCursor())
        assertFalse(graph.config.enabled)
        compose.onNodeWithTag("homeList").performScrollToNode(hasTestTag("languageButton"))
        compose.onNodeWithTag("languageButton").performClick()
        compose.onNodeWithText("System default", substring = false).performClick()
        compose.waitUntil(10_000) { AppLanguage.selected(context).isEmpty() }
        assertEquals("", AppLanguage.selected(context))
    }

    @Test fun englishEditorShowsLocalizedValidationAndCancelKeepsSavedAccount() {
        scenario = ActivityScenario.launch(MailSettingsActivity::class.java)
        compose.onNodeWithText("Saved configuration · Read only").assertExists()
        compose.onNodeWithTag("editButton").performClick()
        compose.onNodeWithTag("hostInput").performTextReplacement("https://invalid.example/path")
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("saveButton"))
        compose.onNodeWithTag("saveButton").performScrollTo().performClick()
        compose.onNodeWithTag("settingsList").performScrollToNode(hasText("Enter an SMTP hostname or IP without a protocol, port, or path"))
        compose.onNodeWithText("Enter an SMTP hostname or IP without a protocol, port, or path").assertExists()
        assertEquals(saved, graph.config.account())
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("cancelButton"))
        compose.onNodeWithTag("cancelButton").performScrollTo().performClick()
        compose.onNodeWithTag("readOnlyCard").assertExists()
        assertEquals(MailFailure.AUTH, graph.config.mailFault)
    }

    @Test fun everyMailErrorAndNotificationHasBothLanguages() {
        val english = localized("en")
        val chinese = localized("zh")
        assertEquals("SMS Ferry", english.getString(R.string.app_name))
        assertEquals("信渡", chinese.getString(R.string.app_name))
        for (failure in MailFailure.entries) {
            assertFalse(english.describe(failure).any { it in '\u4e00'..'\u9fff' })
            assertTrue(chinese.describe(failure).any { it in '\u4e00'..'\u9fff' })
        }
        for (validation in MailValidation.entries) {
            assertFalse(english.describe(validation).any { it in '\u4e00'..'\u9fff' })
            assertTrue(chinese.describe(validation).any { it in '\u4e00'..'\u9fff' })
        }
        assertEquals("Pending: 2 · Needs attention: 1", english.getString(R.string.notification_counts, 2, 1))
        assertEquals("待发送 2 条 · 需处理 1 条", chinese.getString(R.string.notification_counts, 2, 1))
        assertEquals("Resolve 1 problem record", english.resources.getQuantityString(R.plurals.problem_records, 1, 1))
        assertEquals("Resolve 2 problem records", english.resources.getQuantityString(R.plurals.problem_records, 2, 2))
    }

    private fun language(tag: String) {
        instrumentation.runOnMainSync { AppLanguage.select(context, tag) }
        instrumentation.waitForIdleSync()
    }

    private fun localized(tag: String): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) })
}
