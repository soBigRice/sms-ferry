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
import java.util.Locale
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
        } else showError("短信权限未允许。请在应用设置中开启；若没有短信权限开关，请确认安装器允许受限短信权限。", permissionRelated = true)
        render()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }
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
                    ::showBackgroundHelp, { QueueScheduler.scan(this); toast("已安排检查，有网络时会补发") },
                    ::showProblems, ::showHistory, ::showRecord))
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
            toast("已暂停，当前已开始的发信仍可能完成")
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
        } catch (_: Exception) { showError(MailFailure.ENCRYPTION.description) }
    }
    private fun permanentlyDenied(permission: String): Boolean =
        checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED &&
            permissionPrompts.getBoolean(permission, false) && !shouldShowRequestPermissionRationale(permission)

    private fun requestRelayPermissions(startRequested: Boolean) {
        val sms = listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
        if (sms.any { permanentlyDenied(it) }) {
            toast("请在应用权限中允许短信，然后返回")
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
            catch (_: SecurityException) { runOnUiThread { if (!isDestroyed) showError(MailFailure.PERMISSION.description, permissionRelated = true) } }
            catch (_: Exception) { runOnUiThread { if (!isDestroyed) showError("操作失败，请检查短信权限与本地存储，现有队列已保留") } }
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
            enabled && !checks.smsReady -> "需要短信权限"
            relay.config.mailFault != null -> "邮箱需要处理"
            enabled && RelayService.running -> "转发已开启"
            enabled -> "等待后台恢复"
            account != null -> "转发已暂停"
            else -> "欢迎使用"
        }
        val description = when {
            enabled && !checks.smsReady -> "授权后会自动恢复短信接收。"
            relay.config.mailFault != null -> relay.config.mailFault?.description.orEmpty()
            relay.config.backgroundIssue != null -> relay.config.backgroundIssue?.description.orEmpty()
            account == null -> "完成邮箱设置，即可自动转发新短信。"
            enabled -> "有网络时自动发送，断网时保留待发。"
            else -> "点击开始，接收之后的新短信。"
        }
        val smsDetail = when {
            checks.smsReady -> "读取与接收已允许"
            !checks.smsRead && !checks.smsReceive -> "需要读取与接收权限"
            !checks.smsRead -> "还需允许读取短信"
            else -> "还需允许接收短信"
        }
        val notificationDetail = when (checks.notifications) {
            NotificationAccess.ALLOWED -> "运行状态提醒已开启"
            NotificationAccess.CHANNEL_DISABLED -> "转发状态通知被关闭"
            else -> "建议开启运行状态提醒"
        }
        val batteryDetail = when {
            checks.backgroundRestricted -> "后台活动被系统限制"
            checks.batteryExempt -> "系统电池优化已豁免"
            else -> "建议设为不受限制"
        }
        val history = relay.queue.latest(5).map { record ->
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(record.createdAt))
            val label = if (record.kind == JobKind.TEST) "测试邮件" else "短信"
            HistoryRow(record, "$label · $time · ${stateLabel(record.state)}" +
                (record.failure?.description?.let { "\n$it" } ?: ""), record.state in setOf(JobState.BLOCKED, JobState.UNCERTAIN))
        }
        screenState = HomeState(title, description,
            account?.let { "接收邮箱  ${maskedAddress(it.recipient)}" } ?: "先设置接收邮箱，再开启转发",
            enabled, relay.config.mailFault != null || (enabled && !checks.smsReady), busy,
            when { enabled -> "暂停转发"; account == null -> "设置邮箱"; !checks.smsReady -> "授权并开始转发"; else -> "开始转发" },
            counts[JobState.ACCEPTED] ?: 0, pending, problems,
            listOf(CheckRow("短信权限", smsDetail, checks.smsReady,
                if (listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS).any { permanentlyDenied(it) }) "去设置" else "授权"),
                CheckRow("运行通知", notificationDetail, checks.notifications == NotificationAccess.ALLOWED, "开启"),
                CheckRow("后台省电", batteryDetail, checks.backgroundReady, "设置")),
            if (checks.suggestions == 0) "已就绪" else "${checks.suggestions} 项待完善", history, error)
    }
    private fun showHistory() {
        dialogState = RelayDialogState("近期转发记录", choices = relay.queue.latest().map { record ->
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(record.createdAt))
            DialogChoice("$time · ${stateLabel(record.state)}") { showRecord(record) }
        })
    }
    private fun showRecord(record: JobSummary) {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(record.createdAt))
        dialogState = RelayDialogState(stateLabel(record.state),
            "接收时间：$time\n尝试发送：${record.attempts} 次" + (record.failure?.description?.let { "\n$it" } ?: ""))
    }
    private fun stateLabel(state: JobState) = when (state) {
        JobState.PENDING -> "等待发送"
        JobState.SENDING -> "正在发送"
        JobState.ACCEPTED -> "服务器已接收"
        JobState.BLOCKED -> "需要处理"
        JobState.UNCERTAIN -> "请核对邮箱"
    }
    private fun showProblems() {
        val problems = relay.queue.problems()
        if (problems.isEmpty()) return
        dialogState = RelayDialogState("选择需要处理的记录", choices = problems.map { record ->
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(record.createdAt))
            DialogChoice("$time · ${record.failure?.description ?: stateLabel(record.state)}") {
                val uncertain = record.state == JobState.UNCERTAIN
                dialogState = RelayDialogState(stateLabel(record.state),
                    if (uncertain) "请先到接收邮箱核对对应时间的邮件。重试可能产生重复邮件。"
                    else record.failure?.description ?: "处理问题后可以重试。",
                    confirmLabel = if (uncertain) "核对后仍需重试" else "重新发送", confirm = { retryProblem(record) },
                    dismissLabel = "取消", extraLabel = if (uncertain) "已在邮箱找到" else null,
                    extra = { relay.queue.finish(record.id, JobState.ACCEPTED); render() })
            }
        })
    }
    private fun retryProblem(record: JobSummary) {
        try {
            val account = relay.config.account() ?: return showError("请先保存邮箱配置")
            val job = relay.queue.job(record.id) ?: return
            if (!job.belongsTo(account)) return showError(MailFailure.ACCOUNT_CHANGED.description)
            relay.config.mailFault = null; relay.queue.retry(record.id); QueueScheduler.send(this); render()
            if (!relay.config.enabled && record.kind == JobKind.SMS) toast("记录已保留，恢复转发后发送")
        } catch (_: Exception) { showError(MailFailure.ENCRYPTION.description) }
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
    private fun showBackgroundHelp() {
        dialogState = RelayDialogState("保持后台转发",
            "系统电池优化与后台限制已自动检测。三星的“从不休眠”名单需在系统里手动确认。\n\n建议把信渡加入从不休眠名单；强行停止后需重新打开，重启后首次解锁恢复。接收端的邮件通知请在邮箱 App 中开启。",
            confirmLabel = "知道了", extraLabel = if (Build.MANUFACTURER.equals("samsung", true)) "三星后台设置" else null,
            extra = {
                val intent = Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")
                    .setPackage("com.samsung.android.lool").putExtra("activity_type", 2)
                try { startActivity(intent) } catch (_: ActivityNotFoundException) {
                    toast("设置 → 电池 → 后台使用限制 → 从不休眠的应用")
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
