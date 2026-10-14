package com.sobigrice.smsrelay

import org.junit.Assert.*
import org.junit.Test

class ReadinessTest {
    @Test fun bothSmsPermissionsAreRequired() {
        assertFalse(state(smsRead = false).smsReady)
        assertFalse(state(smsReceive = false).smsReady)
        assertTrue(state().smsReady)
    }
    @Test fun missingNotificationsDoNotDisableSmsReception() {
        val readiness = state(notifications = NotificationAccess.PERMISSION_REQUIRED)
        assertTrue(readiness.smsReady)
        assertEquals(1, readiness.suggestions)
    }
    @Test fun notificationChannelBlockingIsDetectedAfterAppPermission() {
        assertEquals(NotificationAccess.CHANNEL_DISABLED, Readiness.notificationAccess(true, true, false))
        assertEquals(NotificationAccess.APP_DISABLED, Readiness.notificationAccess(true, false, true))
        assertEquals(NotificationAccess.PERMISSION_REQUIRED, Readiness.notificationAccess(false, false, false))
        assertEquals(NotificationAccess.ALLOWED, Readiness.notificationAccess(true, true, true))
    }
    @Test fun batteryWhitelistDoesNotOverrideBackgroundRestriction() {
        assertFalse(state(backgroundRestricted = true).backgroundReady)
        assertFalse(state(batteryExempt = false).backgroundReady)
        assertTrue(state().backgroundReady)
    }
    @Test fun recommendationsIncludeEachMissingCategoryOnce() {
        assertEquals(3, state(smsRead = false, smsReceive = false,
            notifications = NotificationAccess.APP_DISABLED, batteryExempt = false, backgroundRestricted = true).suggestions)
        assertEquals(0, state().suggestions)
    }
    private fun state(smsRead: Boolean = true, smsReceive: Boolean = true,
        notifications: NotificationAccess = NotificationAccess.ALLOWED,
        batteryExempt: Boolean = true, backgroundRestricted: Boolean = false) =
        Readiness(smsRead, smsReceive, notifications, batteryExempt, backgroundRestricted)
}
