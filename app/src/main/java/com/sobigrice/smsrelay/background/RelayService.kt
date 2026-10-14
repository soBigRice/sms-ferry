package com.sobigrice.smsrelay.background

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.sobigrice.smsrelay.AppLanguage
import com.sobigrice.smsrelay.MainActivity
import com.sobigrice.smsrelay.R
import com.sobigrice.smsrelay.data.QueueDatabase
import com.sobigrice.smsrelay.hasSmsPermission
import com.sobigrice.smsrelay.mail.JobState
import com.sobigrice.smsrelay.mail.MailFailure
import com.sobigrice.smsrelay.relay

class RelayService : Service() {
    companion object {
        internal const val CHANNEL = "sms-forwarding"
        private const val NOTIFICATION = 1
        private const val PAUSE = "com.sobigrice.smsrelay.PAUSE"
        @Volatile var running = false
            private set
        fun start(context: Context) {
            try { context.startForegroundService(Intent(context, RelayService::class.java)) }
            catch (_: RuntimeException) {
                context.relay.config.backgroundIssue = MailFailure.SERVICE
                context.relay.queue.publish()
            }
        }
    }
    private var observing = false
    private var listening = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (relay.config.enabled) QueueScheduler.scan(this@RelayService)
        }
    }
    private val updates = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (running) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) {
            relay.config.enabled = false
            QueueScheduler.pause(this); relay.queue.publish(); stopSelf()
            return START_NOT_STICKY
        }
        if (!relay.config.enabled) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, AppLanguage.wrap(this).getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(NOTIFICATION, notification())
        running = true
        if (hasSmsPermission()) {
            if (!observing) {
                contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
                observing = true
            }
            if (relay.config.backgroundIssue in setOf(MailFailure.PERMISSION, MailFailure.SERVICE))
                relay.config.backgroundIssue = null
        } else relay.config.backgroundIssue = MailFailure.PERMISSION
        if (!listening) {
            ContextCompat.registerReceiver(this, updates, IntentFilter(QueueDatabase.ACTION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            listening = true
        }
        QueueScheduler.ensureRecovery(this)
        QueueScheduler.scan(this)
        relay.queue.publish()
        return START_STICKY
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (running) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }
    private fun notification(): Notification {
        val localized = AppLanguage.wrap(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, localized.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val counts = relay.queue.counts()
        val pending = (counts[JobState.PENDING] ?: 0) + (counts[JobState.SENDING] ?: 0)
        val problems = (counts[JobState.BLOCKED] ?: 0) + (counts[JobState.UNCERTAIN] ?: 0)
        val content = if (relay.config.mailFault != null) localized.getString(R.string.notification_fault)
            else localized.getString(R.string.notification_counts, pending, problems)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, RelayService::class.java).setAction(PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_relay)
            .setContentTitle(localized.getString(R.string.notification_title)).setContentText(content).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, localized.getString(R.string.pause_forwarding), pause).build()).build()
    }
    override fun onDestroy() {
        running = false
        if (observing) contentResolver.unregisterContentObserver(observer)
        if (listening) unregisterReceiver(updates)
        stopForeground(STOP_FOREGROUND_REMOVE)
        relay.queue.publish()
        super.onDestroy()
    }
}
