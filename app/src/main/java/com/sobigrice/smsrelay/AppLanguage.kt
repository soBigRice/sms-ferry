package com.sobigrice.smsrelay

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import com.sobigrice.smsrelay.mail.MailFailure
import com.sobigrice.smsrelay.mail.MailValidation

/** Android 13 owns app locales; earlier releases persist only a non-sensitive language tag. */
object AppLanguage {
    private const val PREFERENCES = "app_language"
    private const val TAG = "tag"

    fun selected(context: Context): String = if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java).applicationLocales
            .let { if (it.isEmpty) "" else it[0].language }
    } else context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(TAG, "").orEmpty()

    fun select(context: Context, tag: String) {
        require(tag in setOf("", "zh", "en"))
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(TAG, tag).apply()
    }

    fun migrateToSystem(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (!preferences.contains(TAG)) return
        val manager = context.getSystemService(LocaleManager::class.java)
        if (manager.applicationLocales.isEmpty) select(context, preferences.getString(TAG, "").orEmpty())
        preferences.edit().remove(TAG).apply()
    }

    // Resolve afresh for notifications so a running service never holds a stale Activity locale.
    fun wrap(context: Context): Context {
        val tag = selected(context)
        val locales = if (tag.isNotEmpty()) LocaleList.forLanguageTags(tag)
            else if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).systemLocales
            else Resources.getSystem().configuration.locales
        val config = Configuration(context.resources.configuration).apply { setLocales(locales) }
        return context.createConfigurationContext(config)
    }
}

fun Context.describe(failure: MailFailure): String = getString(when (failure) {
    MailFailure.NETWORK -> R.string.failure_network
    MailFailure.TEMPORARY -> R.string.failure_temporary
    MailFailure.AUTH -> R.string.failure_auth
    MailFailure.TLS -> R.string.failure_tls
    MailFailure.REJECTED -> R.string.failure_rejected
    MailFailure.UNKNOWN -> R.string.failure_unknown
    MailFailure.INTERRUPTED -> R.string.failure_interrupted
    MailFailure.ACCOUNT_CHANGED -> R.string.failure_account_changed
    MailFailure.ENCRYPTION -> R.string.failure_encryption
    MailFailure.PERMISSION -> R.string.failure_permission
    MailFailure.SERVICE -> R.string.failure_service
    MailFailure.CONFIG -> R.string.failure_config
})

fun Context.describe(validation: MailValidation): String = getString(when (validation) {
    MailValidation.SENDER -> R.string.validation_sender
    MailValidation.RECIPIENT -> R.string.validation_recipient
    MailValidation.CREDENTIAL -> R.string.credential_required
    MailValidation.USERNAME -> R.string.validation_username
    MailValidation.HOST -> R.string.validation_host
    MailValidation.PORT -> R.string.validation_port
    MailValidation.IDENTITY_CHANGED -> R.string.credential_changed
    MailValidation.TEMPLATE_SUBJECT -> R.string.validation_template_subject
    MailValidation.TEMPLATE_BODY -> R.string.validation_template_body
    MailValidation.TEMPLATE_VARIABLE -> R.string.validation_template_variable
    MailValidation.TEMPLATE_MISSING_BODY -> R.string.validation_template_missing_body
})
