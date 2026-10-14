package com.sobigrice.smsrelay.ui

import android.app.Activity
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import com.sobigrice.smsrelay.R

/** Short, state-driven transitions. Compose observes the system animator duration scale. */
internal object FerryMotion {
    const val Content = 220
    const val Container = 280
    const val Exit = 160
    val Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

internal fun Activity.configureSettingsReturnTransition() {
    if (Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE,
            R.anim.ferry_return_enter, R.anim.ferry_return_exit)
    }
}

@Suppress("DEPRECATION")
internal fun Activity.finishSettingsWithTransition() {
    finish()
    if (Build.VERSION.SDK_INT < 34) {
        overridePendingTransition(R.anim.ferry_return_enter, R.anim.ferry_return_exit)
    }
}
