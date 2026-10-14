package com.sobigrice.smsrelay

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.sobigrice.smsrelay.background.QueueScheduler
import com.sobigrice.smsrelay.mail.*
import com.sobigrice.smsrelay.ui.*
import java.util.concurrent.Executors

class MailSettingsActivity : ComponentActivity() {
    private var screenState by mutableStateOf(SettingsState())
    private val executor = Executors.newSingleThreadExecutor()
    private var configurationReadable = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureSettingsReturnTransition()
        setContent {
            SmsFerryTheme {
                BackHandler { leaveSettings() }
                MailSettingsScreen(screenState, SettingsActions(::leaveSettings, ::beginEditing, ::endEditing,
                    ::saveSettings, ::sendSavedAccountTest, ::updateDraft, ::applyPreset))
            }
        }
        render()
        if (savedAccount() == null && configurationReadable) beginEditing()
    }

    private fun savedAccount(): MailAccount? = try { relay.config.account() }
    catch (_: Exception) {
        configurationReadable = false
        showError(MailFailure.ENCRYPTION.description)
        null
    }

    private fun render() {
        val account = savedAccount()
        screenState = screenState.copy(saved = account?.let { SavedMailSummary(it.sender, it.recipient,
            it.smtpHost, it.smtpPort, it.username, it.security) }, readable = configurationReadable)
    }

    private fun beginEditing() {
        if (screenState.busy || !configurationReadable) return
        val account = savedAccount()
        if (!configurationReadable) { render(); return }
        // Draft state is not saveable; stored secrets never enter the editor or Android view state.
        screenState = screenState.copy(editing = true, draft = SettingsDraft.from(account), error = null)
    }
    private fun endEditing() {
        screenState = screenState.copy(editing = false, draft = SettingsDraft(), error = null)
        render()
    }
    private fun leaveSettings() {
        screenState = screenState.copy(draft = SettingsDraft())
        finishSettingsWithTransition()
    }
    private fun updateDraft(next: SettingsDraft) {
        if (!screenState.editing || screenState.busy) return
        val old = screenState.draft
        val updated = if (next.sender != old.sender && next.username == old.username &&
            (old.username.isEmpty() || old.username == old.sender)) next.copy(username = next.sender) else next
        screenState = screenState.copy(draft = updated, error = null)
    }
    private fun applyPreset(preset: MailPreset) {
        updateDraft(screenState.draft.copy(host = preset.host, port = preset.security.defaultPort.toString(), security = preset.security))
    }
    private fun saveSettings() {
        if (!screenState.editing || screenState.busy || !configurationReadable) return
        try {
            val old = savedAccount()
            if (!configurationReadable) { render(); return }
            val draft = screenState.draft
            val port = draft.port.toIntOrNull() ?: throw IllegalArgumentException("SMTP 端口必须在 1–65535 之间")
            val candidate = MailAccount.create(draft.sender, "validation-only", draft.recipient,
                draft.host, port, draft.security, draft.username)
            // Never automatically send a stored secret to a newly selected endpoint or username.
            val credential = draft.password.ifEmpty {
                old?.takeIf { it.identity == candidate.identity && it.sender.equals(candidate.sender, true) }?.authorizationCode
                    ?: throw IllegalArgumentException(if (old != null) "服务器或登录身份已变化，请重新输入 SMTP 密码或授权码" else "请填写 SMTP 密码、授权码或应用专用密码")
            }
            val account = MailAccount.create(draft.sender, credential, draft.recipient, draft.host, port, draft.security, draft.username)
            relay.config.save(account)
            for (problem in relay.queue.problems()) {
                if (problem.failure in setOf(MailFailure.AUTH, MailFailure.TLS, MailFailure.CONFIG) &&
                    relay.queue.job(problem.id)?.belongsTo(account) == true) relay.queue.retry(problem.id)
            }
            relay.queue.publish()
            endEditing()
            toast("设置已保存")
        } catch (e: IllegalArgumentException) { showError(e.message ?: "请检查邮箱设置") }
        catch (_: Exception) { showError("本地配置保存失败，请勿卸载或清除应用数据") }
    }
    private fun sendSavedAccountTest() {
        if (screenState.editing || screenState.busy || !configurationReadable) return
        val account = savedAccount()
        if (account == null) { showError("请先编辑并保存邮箱配置"); render(); return }
        relay.config.mailFault?.let { showError("${it.description}。请编辑配置并保存后再测试。"); return }
        screenState = screenState.copy(busy = true, error = null)
        executor.execute {
            try {
                relay.queue.enqueue(MailJob(sourceSender = "邮箱连接测试",
                    body = "这是一封来自信渡的短信转发测试邮件。\n\n看到这封邮件说明邮箱连接已完成。接下来请开启转发，再发送一条真实短信验证。",
                    receivedAt = System.currentTimeMillis(), accountAddress = account.sender,
                    recipientAddress = account.recipient, kind = JobKind.TEST, smtpIdentity = account.identity))
                QueueScheduler.send(this)
                runOnUiThread { if (!isDestroyed) toast("测试邮件已排队，请查看接收邮箱") }
            } catch (_: Exception) {
                runOnUiThread { if (!isDestroyed) showError("测试邮件未能排队，请检查本地存储") }
            } finally {
                runOnUiThread { if (!isDestroyed) { screenState = screenState.copy(busy = false); render() } }
            }
        }
    }
    override fun onDestroy() {
        screenState = screenState.copy(draft = SettingsDraft())
        executor.shutdownNow()
        super.onDestroy()
    }
    private fun showError(message: String) { screenState = screenState.copy(error = message) }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
