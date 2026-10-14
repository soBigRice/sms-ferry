package com.sobigrice.smsrelay

import android.Manifest
import android.app.ActivityOptions
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.sobigrice.smsrelay.ui.*
import com.sobigrice.smsrelay.background.QueueScheduler
import com.sobigrice.smsrelay.background.RelayService
import com.sobigrice.smsrelay.data.QueueDatabase
import com.sobigrice.smsrelay.mail.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private var screenState by mutableStateOf(HomeState())
    private var dialogState by mutableStateOf<RelayDialogState?>(null)
    private val executor = Executors.newSingleThreadExecutor()
    private var startAfterPermission = false
    private var receiverRegistered = false
    private var busy = false
    private var permissionErrorVisible = false
    private val permissionPrompts by lazy { getSharedPreferences("permission_prompts", MODE_PRIVATE) }
    private val updates = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { render() }
    }
    private val relayPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val start = startAfterPermission; startAfterPermission = false
        if (hasSmsPermission()) {
            if (start) enableForwarding()
            else if (relay.config.enabled) {
                // Permission recovery retains the original scan cursor.
                RelayService.start(this); QueueScheduler.scan(this)
            }
        } else showError(getString(R.string.sms_permission_denied), permissionRelated = true)
        render()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(if (android.os.Build.VERSION.SDK_INT < 33) AppLanguage.wrap(newBase) else newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startAfterPermission = savedInstanceState?.getBoolean("start_after_permission") ?: false
        enableEdgeToEdge()
        setContent {
            SmsFerryTheme {
                HomeScreen(screenState, HomeActions(::openMailSettings, ::toggleForwarding,
                    { requestRelayPermissions(startRequested = false) }, ::fixNotifications,
                    { if (readiness().backgroundRestricted) openAppSettings()
                      else openSystemSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                    ::showBackgroundHelp, { QueueScheduler.scan(this); toast(getString(R.string.scan_scheduled)) },
                    ::showProblems, ::showHistory, ::showRecord, language = ::showLanguagePicker,
                    update = { startActivity(Intent(this, UpdateActivity::class.java)) }))
                RelayDialog(dialogState) { dialogState = null }
            }
        }
        render()
    }
    private fun toggleForwarding() {
        if (busy) return
        if (relay.config.enabled) {
            relay.config.enabled = false; QueueScheduler.pause(this)
            stopService(Intent(this, RelayService::class.java)); render()
            toast(getString(R.string.paused_toast))
        } else withSavedAccount { requestRelayPermissions(startRequested = true) }
    }
    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(this, updates, IntentFilter(QueueDatabase.ACTION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        if (relay.config.enabled) {
            if (!RelayService.running || relay.config.backgroundIssue in setOf(MailFailure.PERMISSION, MailFailure.SERVICE))
                RelayService.start(this)
            QueueScheduler.ensureRecovery(this)
            QueueScheduler.scan(this)
        }
        render()
    }
    override fun onPause() {
        if (receiverRegistered) { unregisterReceiver(updates); receiverRegistered = false }
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("start_after_permission", startAfterPermission)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
    private fun withSavedAccount(action: (MailAccount) -> Unit) {
        try {
            val account = relay.config.account()
            if (account == null) { openMailSettings(); return }
            screenState = screenState.copy(error = null)
            action(account)
        } catch (_: Exception) { showError(describe(MailFailure.ENCRYPTION)) }
    }
    private fun permanentlyDenied(permission: String): Boolean =
        checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED &&
            permissionPrompts.getBoolean(permission, false) && !shouldShowRequestPermissionRationale(permission)

    private fun requestRelayPermissions(startRequested: Boolean) {
        val sms = listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
        if (sms.any { permanentlyDenied(it) }) {
            toast(getString(R.string.allow_sms_toast))
            openAppSettings()
            return
        }
        val requested = sms.toMutableList()
        if (startRequested && Build.VERSION.SDK_INT >= 33 && !permanentlyDenied(Manifest.permission.POST_NOTIFICATIONS))
            requested += Manifest.permission.POST_NOTIFICATIONS
        val missing = requested.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            if (startRequested) enableForwarding()
            return
        }
        startAfterPermission = startRequested
        markRequested(missing)
        relayPermissions.launch(missing.toTypedArray())
    }
    private fun markRequested(permissions: List<String>) {
        val edit = permissionPrompts.edit()
        permissions.forEach { edit.putBoolean(it, true) }
        edit.apply()
    }
    private fun fixNotifications() {
        val access = readiness().notifications
        if (access == NotificationAccess.PERMISSION_REQUIRED && Build.VERSION.SDK_INT >= 33 &&
            !permanentlyDenied(Manifest.permission.POST_NOTIFICATIONS)) {
            markRequested(listOf(Manifest.permission.POST_NOTIFICATIONS))
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (access == NotificationAccess.CHANNEL_DISABLED) {
            openSystemSettings(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, RelayService.CHANNEL))
        } else openSystemSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
    private fun enableForwarding() = io {
        val baseline = relay.inbox.latestId()
        relay.queue.setScanBaseline(baseline)
        relay.config.enabled = true
        runOnUiThread {
            if (!isDestroyed) { RelayService.start(this); QueueScheduler.ensureRecovery(this); QueueScheduler.scan(this); render() }
        }
    }
    private fun io(action: () -> Unit) {
        if (busy) return
        busy = true; render()
        executor.execute {
            try { action() }
            catch (_: SecurityException) { runOnUiThread { if (!isDestroyed) showError(describe(MailFailure.PERMISSION), permissionRelated = true) } }
            catch (_: Exception) { runOnUiThread { if (!isDestroyed) showError(getString(R.string.operation_failed)) } }
            finally { runOnUiThread { if (!isDestroyed) { busy = false; render() } } }
        }
    }
    private fun render() {
        if (isDestroyed) return
        val checks = readiness()
        var error = screenState.error
        if (checks.smsReady && permissionErrorVisible) { error = null; permissionErrorVisible = false }
        val counts = relay.queue.counts()
        val pending = (counts[JobState.PENDING] ?: 0) + (counts[JobState.SENDING] ?: 0)
        val problems = (counts[JobState.BLOCKED] ?: 0) + (counts[JobState.UNCERTAIN] ?: 0)
        val account = try { relay.config.account() } catch (_: Exception) { null }
        val enabled = relay.config.enabled
        val title = when {
            enabled && !checks.smsReady -> getString(R.string.needs_sms_permission)
            relay.config.mailFault != null -> getString(R.string.mail_attention)
            enabled && RelayService.running -> getString(R.string.forwarding_on)
            enabled -> getString(R.string.background_waiting)
            account != null -> getString(R.string.forwarding_paused)
            else -> getString(R.string.welcome)
        }
        val description = when {
            enabled && !checks.smsReady -> getString(R.string.permission_recovery)
            relay.config.mailFault != null -> relay.config.mailFault?.let(::describe).orEmpty()
            relay.config.backgroundIssue != null -> relay.config.backgroundIssue?.let(::describe).orEmpty()
            account == null -> getString(R.string.setup_description)
            enabled -> getString(R.string.online_description)
            else -> getString(R.string.paused_description)
        }
        val smsDetail = when {
            checks.smsReady -> getString(R.string.sms_ready)
            !checks.smsRead && !checks.smsReceive -> getString(R.string.sms_both_needed)
            !checks.smsRead -> getString(R.string.sms_read_needed)
            else -> getString(R.string.sms_receive_needed)
        }
        val notificationDetail = when (checks.notifications) {
            NotificationAccess.ALLOWED -> getString(R.string.notifications_ready)
            NotificationAccess.CHANNEL_DISABLED -> getString(R.string.notifications_channel_off)
            else -> getString(R.string.notifications_suggested)
        }
        val batteryDetail = when {
            checks.backgroundRestricted -> getString(R.string.background_restricted)
            checks.batteryExempt -> getString(R.string.battery_exempt)
            else -> getString(R.string.battery_suggested)
        }
        val history = relay.queue.latest(5).map { record ->
            val time = SimpleDateFormat("MM-dd HH:mm", resources.configuration.locales[0]).format(Date(record.createdAt))
            val label = if (record.kind == JobKind.TEST) getString(R.string.test_mail) else getString(R.string.sms)
            HistoryRow(record, "$label · $time · ${stateLabel(record.state)}" +
                (record.failure?.let(::describe)?.let { "\n$it" } ?: ""), record.state in setOf(JobState.BLOCKED, JobState.UNCERTAIN))
        }
        screenState = HomeState(title, description,
            account?.let { getString(R.string.mail_summary, maskedAddress(it.recipient)) } ?: getString(R.string.mail_summary_empty),
            enabled, relay.config.mailFault != null || (enabled && !checks.smsReady), busy,
            when { enabled -> getString(R.string.pause_forwarding); account == null -> getString(R.string.setup_email); !checks.smsReady -> getString(R.string.authorize_start); else -> getString(R.string.start_forwarding) },
            counts[JobState.ACCEPTED] ?: 0, pending, problems,
            listOf(CheckRow(getString(R.string.sms_permissions), smsDetail, checks.smsReady,
                if (listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS).any { permanentlyDenied(it) }) getString(R.string.go_settings) else getString(R.string.authorize)),
                CheckRow(getString(R.string.notifications), notificationDetail, checks.notifications == NotificationAccess.ALLOWED, getString(R.string.enable)),
                CheckRow(getString(R.string.battery), batteryDetail, checks.backgroundReady, getString(R.string.settings))),
            if (checks.suggestions == 0) getString(R.string.ready) else resources.getQuantityString(R.plurals.suggestions, checks.suggestions, checks.suggestions), history, error)
    }
    private fun showHistory() {
        dialogState = RelayDialogState(getString(R.string.recent_history), choices = relay.queue.latest().map { record ->
            val time = SimpleDateFormat("MM-dd HH:mm", resources.configuration.locales[0]).format(Date(record.createdAt))
            DialogChoice("$time · ${stateLabel(record.state)}") { showRecord(record) }
        })
    }
    private fun showRecord(record: JobSummary) {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", resources.configuration.locales[0]).format(Date(record.createdAt))
        dialogState = RelayDialogState(stateLabel(record.state),
            getString(R.string.record_details, time, record.attempts) + (record.failure?.let(::describe)?.let { "\n$it" } ?: ""))
    }
    private fun stateLabel(state: JobState) = when (state) {
        JobState.PENDING -> getString(R.string.pending)
        JobState.SENDING -> getString(R.string.sending)
        JobState.ACCEPTED -> getString(R.string.accepted)
        JobState.BLOCKED -> getString(R.string.needs_attention)
        JobState.UNCERTAIN -> getString(R.string.check_inbox)
    }
    private fun showProblems() {
        val problems = relay.queue.problems()
        if (problems.isEmpty()) return
        dialogState = RelayDialogState(getString(R.string.choose_problem), choices = problems.map { record ->
            val time = SimpleDateFormat("MM-dd HH:mm", resources.configuration.locales[0]).format(Date(record.createdAt))
            DialogChoice("$time · ${record.failure?.let(::describe) ?: stateLabel(record.state)}") {
                val uncertain = record.state == JobState.UNCERTAIN
                dialogState = RelayDialogState(stateLabel(record.state),
                    if (uncertain) getString(R.string.uncertain_help)
                    else record.failure?.let(::describe) ?: getString(R.string.retry_help),
                    confirmLabel = if (uncertain) getString(R.string.retry_uncertain) else getString(R.string.retry), confirm = { retryProblem(record) },
                    dismissLabel = getString(R.string.cancel), extraLabel = if (uncertain) getString(R.string.found_inbox) else null,
                    extra = { relay.queue.finish(record.id, JobState.ACCEPTED); render() })
            }
        })
    }
    private fun retryProblem(record: JobSummary) {
        try {
            val account = relay.config.account() ?: return showError(getString(R.string.save_first))
            val job = relay.queue.job(record.id) ?: return
            if (!job.belongsTo(account)) return showError(describe(MailFailure.ACCOUNT_CHANGED))
            relay.config.mailFault = null; relay.queue.retry(record.id); QueueScheduler.send(this); render()
            if (!relay.config.enabled && record.kind == JobKind.SMS) toast(getString(R.string.resume_to_send))
        } catch (_: Exception) { showError(describe(MailFailure.ENCRYPTION)) }
    }
    private fun openAppSettings() { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    private fun openSystemSettings(intent: Intent) {
        try { startActivity(intent) } catch (_: ActivityNotFoundException) { openAppSettings() }
    }
    private fun openMailSettings() {
        startActivity(Intent(this, MailSettingsActivity::class.java),
            ActivityOptions.makeCustomAnimation(this, R.anim.ferry_page_enter, R.anim.ferry_page_exit).toBundle())
    }
    private fun maskedAddress(value: String): String {
        val local = value.substringBefore('@')
        val masked = if (local.length > 4) local.take(2) + "***" + local.takeLast(2) else "***"
        return masked + "@" + value.substringAfter('@')
    }
    private fun showLanguagePicker() {
        val selected = AppLanguage.selected(this)
        dialogState = RelayDialogState(getString(R.string.language), choices = listOf(
            "" to getString(R.string.language_system), "zh" to "中文", "en" to "English"
        ).map { (tag, label) ->
            DialogChoice(if (tag == selected) "$label ✓" else label) {
                if (tag != selected) {
                    AppLanguage.select(this, tag)
                    // Notify the running service without changing forwarding or its scan baseline.
                    relay.queue.publish()
                    if (Build.VERSION.SDK_INT < 33) recreate()
                }
            }
        })
    }
    private fun showBackgroundHelp() {
        dialogState = RelayDialogState(getString(R.string.background_title),
            getString(R.string.background_explanation),
            confirmLabel = getString(R.string.got_it), extraLabel = if (Build.MANUFACTURER.equals("samsung", true)) getString(R.string.samsung_settings) else null,
            extra = {
                val intent = Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")
                    .setPackage("com.samsung.android.lool").putExtra("activity_type", 2)
                try { startActivity(intent) } catch (_: ActivityNotFoundException) {
                    toast(getString(R.string.samsung_path))
                    openSystemSettings(Intent(Settings.ACTION_SETTINGS))
                }
            })
    }
    private fun showError(message: String, permissionRelated: Boolean = false) {
        permissionErrorVisible = permissionRelated
        screenState = screenState.copy(error = message)
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
