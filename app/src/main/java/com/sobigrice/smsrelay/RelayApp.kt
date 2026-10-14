package com.sobigrice.smsrelay

import android.app.Application
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import com.sobigrice.smsrelay.data.*

class RelayApp : Application() {
    lateinit var graph: RelayGraph
        private set
    override fun onCreate() {
        super.onCreate()
        AppLanguage.migrateToSystem(this)
        graph = RelayGraph(this)
        // Runs once for each new process, before any Worker can claim a message.
        graph.queue.recoverInterrupted()
    }
}

class RelayGraph(context: Context) {
    val crypto = CryptoStore()
    val config = ConfigStore(context, crypto)
    val queue = QueueDatabase(context, crypto)
    val inbox = SmsInbox(context, queue)
}

val Context.relay: RelayGraph get() = (applicationContext as RelayApp).graph
fun Context.hasSmsPermission() = listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
    .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
