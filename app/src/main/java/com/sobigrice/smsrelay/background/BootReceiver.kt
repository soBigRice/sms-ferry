package com.sobigrice.smsrelay.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sobigrice.smsrelay.relay

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (!context.relay.config.enabled) return
        QueueScheduler.ensureRecovery(context)
        QueueScheduler.scan(context)
        RelayService.start(context)
    }
}
