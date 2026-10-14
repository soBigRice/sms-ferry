package com.sobigrice.smsrelay

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sobigrice.smsrelay.ui.*
import com.sobigrice.smsrelay.update.AppRelease
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Presentation only; never requests installation or touches real mail/forwarding state. */
@RunWith(AndroidJUnit4::class)
class UpdateUiTest {
    @get:Rule val compose = createComposeRule()
    private val release = AppRelease("0.3.0", "v0.3.0", "修复更新检查", true,
        "sms-ferry-0.3.0-debug.apk", "https://github.com/soBigRice/sms-ferry/releases/download/v0.3.0/sms-ferry-0.3.0-debug.apk",
        12000000, "a".repeat(64))

    @Test fun availableReleaseRequiresDownloadBeforeInstallation() {
        var downloads = 0
        compose.setContent {
            SmsFerryTheme { UpdateScreen(UpdateState("0.2.2", checking = false, release = release),
                {}, {}, { downloads++ }, {}, {}, {}) }
        }
        compose.onNodeWithTag("installUpdate").assertDoesNotExist()
        compose.onNodeWithTag("downloadUpdate").performClick()
        compose.runOnIdle { assertEquals(1, downloads) }
    }

    @Test fun downloadingBlocksRepeatedDownloadAndCheckButAllowsCancel() {
        var cancellations = 0
        compose.setContent {
            SmsFerryTheme { UpdateScreen(UpdateState("0.2.2", checking = false, release = release,
                downloading = true, progress = 42), {}, {}, {}, { cancellations++ }, {}, {}) }
        }
        compose.onNodeWithTag("downloadUpdate").assertDoesNotExist()
        compose.onNodeWithTag("installUpdate").assertDoesNotExist()
        compose.onNodeWithTag("checkUpdate").assertDoesNotExist()
        compose.onNodeWithTag("cancelDownload").performClick()
        compose.runOnIdle { assertEquals(1, cancellations) }
    }

    @Test fun failedCheckNeverClaimsAlreadyLatestAndCanRetry() {
        var checks = 0
        compose.setContent {
            SmsFerryTheme { UpdateScreen(UpdateState("0.2.2", checking = false, error = "网络暂不可用"),
                {}, { checks++ }, {}, {}, {}, {}) }
        }
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.ota_latest)).assertDoesNotExist()
        compose.onNodeWithText("网络暂不可用").assertIsDisplayed()
        compose.onNodeWithTag("checkUpdate").performClick()
        compose.runOnIdle { assertEquals(1, checks) }
    }
}
