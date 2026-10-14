package com.sobigrice.smsrelay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.WindowCompat
import com.sobigrice.smsrelay.background.QueueScheduler
import com.sobigrice.smsrelay.databinding.ActivityMailSettingsBinding
import com.sobigrice.smsrelay.mail.*
import java.util.concurrent.Executors

class MailSettingsActivity : Activity() {
    private lateinit var ui: ActivityMailSettingsBinding
    private val executor = Executors.newSingleThreadExecutor()
    private var editing = false
    private var busy = false
    private var configurationReadable = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMailSettingsBinding.inflate(layoutInflater)
        setContentView(ui.root)
        if (Build.VERSION.SDK_INT >= 30) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            ui.root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
                insets
            }
        }
        ui.backButton.setOnClickListener { finish() }
        ui.editButton.setOnClickListener { beginEditing() }
        ui.cancelButton.setOnClickListener { endEditing() }
        ui.saveButton.setOnClickListener { saveSettings() }
        ui.testButton.setOnClickListener { sendSavedAccountTest() }
        ui.qqHelpButton.setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://help.mail.qq.com/detail/106/985"))) }
            catch (_: ActivityNotFoundException) { toast("请在浏览器打开 QQ 邮箱 → 设置 → 账号与安全 → 安全设置") }
        }
        render()
        // First setup has no saved configuration to protect; skip the empty readonly step.
        if (savedAccount() == null && configurationReadable) beginEditing()
    }

    private fun savedAccount(): MailAccount? = try { relay.config.account() }
    catch (_: Exception) {
        configurationReadable = false
        showError(MailFailure.ENCRYPTION.description)
        null
    }

    private fun beginEditing() {
        if (busy || !configurationReadable) return
        val account = savedAccount()
        if (!configurationReadable) { render(); return }
        ui.senderInput.setText(account?.sender.orEmpty())
        ui.recipientInput.setText(account?.recipient.orEmpty())
        // A stored authorization code is never copied into an editable field or view state.
        ui.codeInput.text.clear()
        ui.codeInput.hint = if (account != null) "授权码已保存，留空沿用" else "请输入 16 位授权码"
        ui.errorText.visibility = View.GONE
        editing = true
        render()
    }

    private fun endEditing() {
        editing = false
        ui.senderInput.text.clear(); ui.recipientInput.text.clear(); ui.codeInput.text.clear()
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        ui.errorText.visibility = View.GONE
        render()
    }

    private fun saveSettings() {
        if (!editing || busy || !configurationReadable) return
        try {
            val old = savedAccount()
            if (!configurationReadable) { render(); return }
            val sender = ui.senderInput.text.toString().trim()
            val code = ui.codeInput.text.toString().ifBlank {
                old?.takeIf { it.sender.equals(sender, true) }?.authorizationCode.orEmpty()
            }
            val account = MailAccount.create(sender, code, ui.recipientInput.text.toString())
            relay.config.save(account)
            // Only explicitly saving credentials can unblock known pre-acceptance failures.
            for (problem in relay.queue.problems()) {
                if (problem.failure in setOf(MailFailure.AUTH, MailFailure.TLS, MailFailure.CONFIG) &&
                    relay.queue.job(problem.id)?.accountAddress.equals(account.sender, true)) relay.queue.retry(problem.id)
            }
            relay.queue.publish()
            endEditing()
            toast("设置已保存")
        } catch (e: IllegalArgumentException) { showError(e.message ?: "请检查邮箱设置") }
        catch (_: Exception) { showError("本地配置保存失败，请勿卸载或清除应用数据") }
    }

    private fun sendSavedAccountTest() {
        if (editing || busy || !configurationReadable) return
        val account = savedAccount()
        if (account == null) { showError("请先编辑并保存邮箱配置"); render(); return }
        relay.config.mailFault?.let {
            showError("${it.description}。请编辑配置并保存后再测试。")
            return
        }
        busy = true
        ui.errorText.visibility = View.GONE
        render()
        executor.execute {
            try {
                relay.queue.enqueue(MailJob(sourceSender = "邮箱连接测试",
                    body = "这是一封来自信渡的短信转发测试邮件。\n\n看到这封邮件说明邮箱连接已完成。接下来请在三星端开启转发，再发送一条真实短信验证。",
                    receivedAt = System.currentTimeMillis(), accountAddress = account.sender,
                    recipientAddress = account.recipient, kind = JobKind.TEST))
                QueueScheduler.send(this)
                runOnUiThread { if (!isDestroyed) toast("测试邮件已排队，请查看接收邮箱") }
            } catch (_: Exception) {
                runOnUiThread { if (!isDestroyed) showError("测试邮件未能排队，请检查本地存储") }
            } finally {
                runOnUiThread { if (!isDestroyed) { busy = false; render() } }
            }
        }
    }

    private fun render() {
        val account = savedAccount()
        ui.savedSender.text = account?.sender ?: "尚未设置"
        ui.savedRecipient.text = account?.recipient ?: "尚未设置"
        ui.savedCode.text = if (account != null) "已加密保存" else "尚未设置"
        ui.readOnlyCard.visibility = if (editing) View.GONE else View.VISIBLE
        ui.editForm.visibility = if (editing) View.VISIBLE else View.GONE
        ui.editButton.visibility = if (editing) View.GONE else View.VISIBLE
        ui.editButton.text = if (account != null) "编辑配置" else "设置邮箱"
        ui.editButton.isEnabled = !busy && configurationReadable
        ui.testButton.isEnabled = !editing && !busy && account != null && configurationReadable
        ui.saveButton.isEnabled = editing && !busy && configurationReadable
        ui.cancelButton.isEnabled = !busy
    }

    override fun onDestroy() {
        ui.codeInput.text.clear()
        executor.shutdownNow()
        super.onDestroy()
    }
    private fun showError(message: String) { ui.errorText.text = message; ui.errorText.visibility = View.VISIBLE }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
