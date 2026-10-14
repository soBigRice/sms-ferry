package com.sobigrice.smsrelay.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.sobigrice.smsrelay.mail.MailFailure
import com.sobigrice.smsrelay.relay
import java.util.concurrent.TimeUnit

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION || !context.relay.config.enabled) return
        val pending = goAsync()
        Thread({
            try { QueueScheduler.scan(context, smsEvent = true).result.get(5, TimeUnit.SECONDS) }
            catch (_: Exception) {
                context.relay.config.backgroundIssue = MailFailure.SERVICE
                context.relay.queue.publish()
            } finally { pending.finish() }
        }, "sms-relay-enqueue").start()
    }
}
