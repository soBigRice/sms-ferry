package com.sobigrice.smsrelay

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import com.sobigrice.smsrelay.background.RelayService

enum class NotificationAccess { ALLOWED, PERMISSION_REQUIRED, APP_DISABLED, CHANNEL_DISABLED }

data class Readiness(
    val smsRead: Boolean,
    val smsReceive: Boolean,
    val notifications: NotificationAccess,
    val batteryExempt: Boolean,
    val backgroundRestricted: Boolean,
) {
    val smsReady: Boolean get() = smsRead && smsReceive
    val backgroundReady: Boolean get() = batteryExempt && !backgroundRestricted
    val suggestions: Int get() = listOf(smsReady, notifications == NotificationAccess.ALLOWED, backgroundReady).count { !it }

    companion object {
        fun notificationAccess(permissionGranted: Boolean, appEnabled: Boolean, channelEnabled: Boolean) = when {
            !permissionGranted -> NotificationAccess.PERMISSION_REQUIRED
            !appEnabled -> NotificationAccess.APP_DISABLED
            !channelEnabled -> NotificationAccess.CHANNEL_DISABLED
            else -> NotificationAccess.ALLOWED
        }
    }
}

/** Public system APIs only. Samsung's Never sleeping list is a separate manual check. */
fun Context.readiness(): Readiness {
    val manager = getSystemService(NotificationManager::class.java)
    val channel = manager.getNotificationChannel(RelayService.CHANNEL)
    val notificationPermission = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return Readiness(
        smsRead = checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED,
        smsReceive = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED,
        notifications = Readiness.notificationAccess(notificationPermission, manager.areNotificationsEnabled(),
            channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE),
        batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
        backgroundRestricted = Build.VERSION.SDK_INT >= 28 && getSystemService(ActivityManager::class.java).isBackgroundRestricted,
    )
}
