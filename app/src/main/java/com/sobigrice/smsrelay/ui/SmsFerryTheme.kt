package com.sobigrice.smsrelay.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun SmsFerryTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(LocalContext.current)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(LocalContext.current)
        dark -> darkColorScheme(primary = Color(0xFF91D5B4), secondary = Color(0xFFB3CCBD))
        else -> lightColorScheme(primary = Color(0xFF176B4C), secondary = Color(0xFF506457))
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
