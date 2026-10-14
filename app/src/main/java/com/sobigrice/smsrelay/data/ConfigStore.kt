package com.sobigrice.smsrelay.data

import android.content.Context
import com.sobigrice.smsrelay.mail.MailAccount
import com.sobigrice.smsrelay.mail.MailFailure
import org.json.JSONObject

class ConfigStore(context: Context, private val crypto: CryptoStore) {
    private val preferences = context.getSharedPreferences("relay_settings", Context.MODE_PRIVATE)
    fun account(): MailAccount? = preferences.getString("account", null)?.let {
        val value = JSONObject(crypto.decrypt(it))
        MailAccount.create(value.getString("sender"), value.getString("code"), value.getString("recipient"))
    }
    fun save(account: MailAccount) {
        val encrypted = crypto.encrypt(JSONObject().put("sender", account.sender)
            .put("code", account.authorizationCode).put("recipient", account.recipient).toString())
        check(preferences.edit().putString("account", encrypted).remove("fault").commit())
    }
    var enabled: Boolean
        get() = preferences.getBoolean("enabled", false)
        @Synchronized set(value) {
            if (enabled != value) check(preferences.edit().putBoolean("enabled", value)
                .putLong("activation_generation", activationGeneration + 1).commit())
        }
    val activationGeneration: Long get() = preferences.getLong("activation_generation", 0)
    var mailFault: MailFailure?
        get() = preferences.getString("fault", null)?.let { MailFailure.valueOf(it) }
        set(value) { check(preferences.edit().putString("fault", value?.name).commit()) }
    var backgroundIssue: MailFailure?
        get() = preferences.getString("background_issue", null)?.let { MailFailure.valueOf(it) }
        set(value) { check(preferences.edit().putString("background_issue", value?.name).commit()) }
}
