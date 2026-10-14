package com.sobigrice.smsrelay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sobigrice.smsrelay.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated emulator only. Presentation fixtures do not touch accounts, the queue, or SMTP. */
@RunWith(AndroidJUnit4::class)
class FerryMotionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun interruptedTransitionsSettleOnLatestStatusAndRealCount() {
        var state by mutableStateOf(HomeState(title = "已暂停", accepted = 9))
        val noOp = {}
        val actions = HomeActions(noOp, noOp, noOp, noOp, noOp, noOp, noOp, noOp, noOp, {})
        compose.setContent { SmsFerryTheme { HomeScreen(state, actions) } }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = state.copy(title = "正在转发", enabled = true, accepted = 10) }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { state = state.copy(title = "已暂停", enabled = false, accepted = 11) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("已暂停").assertIsDisplayed()
        compose.onNodeWithText("11").assertIsDisplayed()
        compose.onNodeWithText("正在转发").assertDoesNotExist()
        compose.onNodeWithText("10").assertDoesNotExist()
        compose.onNodeWithText("9").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }
}
