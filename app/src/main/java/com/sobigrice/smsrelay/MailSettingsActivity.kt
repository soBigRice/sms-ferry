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

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(if (android.os.Build.VERSION.SDK_INT < 33) AppLanguage.wrap(newBase) else newBase)
    }

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
        showError(describe(MailFailure.ENCRYPTION))
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
            val port = draft.port.toIntOrNull() ?: throw MailValidationException(MailValidation.PORT)
            val candidate = MailAccount.create(draft.sender, "validation-only", draft.recipient,
                draft.host, port, draft.security, draft.username)
            // Never automatically send a stored secret to a newly selected endpoint or username.
            val credential = draft.password.ifEmpty {
                old?.takeIf { it.identity == candidate.identity && it.sender.equals(candidate.sender, true) }?.authorizationCode
                    ?: throw MailValidationException(if (old != null) MailValidation.IDENTITY_CHANGED else MailValidation.CREDENTIAL)
            }
            val account = MailAccount.create(draft.sender, credential, draft.recipient, draft.host, port, draft.security, draft.username)
            relay.config.save(account)
            for (problem in relay.queue.problems()) {
                if (problem.failure in setOf(MailFailure.AUTH, MailFailure.TLS, MailFailure.CONFIG) &&
                    relay.queue.job(problem.id)?.belongsTo(account) == true) relay.queue.retry(problem.id)
            }
            relay.queue.publish()
            endEditing()
            toast(getString(R.string.settings_saved))
        } catch (e: MailValidationException) { showError(describe(e.reason)) }
        catch (_: IllegalArgumentException) { showError(getString(R.string.check_mail_settings)) }
        catch (_: Exception) { showError(getString(R.string.settings_save_failed)) }
    }
    private fun sendSavedAccountTest() {
        if (screenState.editing || screenState.busy || !configurationReadable) return
        val account = savedAccount()
        if (account == null) { showError(getString(R.string.edit_save_first)); render(); return }
        relay.config.mailFault?.let { showError(getString(R.string.test_fault, describe(it))); return }
        screenState = screenState.copy(busy = true, error = null)
        executor.execute {
            try {
                relay.queue.enqueue(MailJob(sourceSender = getString(R.string.test_source),
                    body = getString(R.string.test_body),
                    receivedAt = System.currentTimeMillis(), accountAddress = account.sender,
                    recipientAddress = account.recipient, kind = JobKind.TEST, smtpIdentity = account.identity))
                QueueScheduler.send(this)
                runOnUiThread { if (!isDestroyed) toast(getString(R.string.test_queued)) }
            } catch (_: Exception) {
                runOnUiThread { if (!isDestroyed) showError(getString(R.string.test_queue_failed)) }
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
