package com.sobigrice.smsrelay.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sobigrice.smsrelay.relay
import com.sobigrice.smsrelay.mail.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import android.content.Intent
import androidx.work.WorkManager
import com.sobigrice.smsrelay.background.RelayService
import java.util.concurrent.TimeUnit

/** Isolated emulator only. Tests encrypted configuration without any network access. */
@RunWith(AndroidJUnit4::class)
class ConfigMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = context.relay
    private val prefs = context.getSharedPreferences("relay_settings", 0)
    @Before fun prepare() {
        graph.config.enabled = false
        context.stopService(Intent(context, RelayService::class.java))
        WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        graph.config.mailFault = MailFailure.AUTH
    }
    @After fun cleanup() { graph.config.enabled = false; graph.config.mailFault = MailFailure.AUTH }

    @Test fun originalEncryptedQqConfigIsReadWithoutRewritingIt() {
        val original = graph.crypto.encrypt(JSONObject().put("sender", "10001@qq.com")
            .put("code", "abcd efgh ijkl mnop").put("recipient", "10002@qq.com").toString())
        prefs.edit().putString("account", original).commit()
        assertEquals(MailAccount.legacyQq("10001@qq.com", "abcdefghijklmnop", "10002@qq.com"), graph.config.account())
        assertEquals(original, prefs.getString("account", null))
    }
    @Test fun genericSmtpConfigRoundTripsWithExactPassword() {
        val account = MailAccount.create("alice@example.org", " pass word! ", "reader@example.net",
            "smtp.example.org", 2525, MailSecurity.STARTTLS, "independent-login")
        graph.config.save(account)
        assertEquals(account, graph.config.account())
        val ciphertext = prefs.getString("account", null)!!
        assertFalse(ciphertext.contains("pass word!"))
    }
    @Test fun futureConfigurationVersionIsPreservedRatherThanSilentlyReset() {
        val original = graph.crypto.encrypt(JSONObject().put("version", 99).toString())
        prefs.edit().putString("account", original).commit()
        assertThrows(IllegalArgumentException::class.java) { graph.config.account() }
        assertEquals(original, prefs.getString("account", null))
    }
}
