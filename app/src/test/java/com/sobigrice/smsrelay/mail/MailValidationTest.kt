package com.sobigrice.smsrelay.mail

import org.junit.Assert.*
import org.junit.Test
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MailValidationTest {
    @Test fun recipientHeaderInjectionIsRejected() {
        try { MailAccount.create("10001@qq.com", "abcdefghijklmnop", "10002@qq.com\r\nBcc: attacker@example.com") }
        catch (_: IllegalArgumentException) { return }
        fail("header injection must be rejected")
    }
    @Test fun qqPasswordCannotReplaceAuthorizationCode() {
        try { MailAccount.create("10001@qq.com", "shortpassword", "10002@qq.com") }
        catch (_: IllegalArgumentException) { return }
        fail("must request the SMTP authorization code")
    }
    @Test fun pastedAuthorizationCodeWhitespaceIsNormalized() {
        val account = MailAccount.create("10001@qq.com", "abcd efgh ijkl mnop\n", "10002@qq.com")
        assertEquals("abcdefghijklmnop", account.authorizationCode)
    }
    @Test fun unicodeLongBodyAndDotLinesSurviveMimeRoundTrip() {
        val account = MailAccount.create("10001@qq.com", "abcdefghijklmnop", "10002@qq.com")
        val body = "完整长短信：🍚\n.\n第一段\n" + "第二段中文。".repeat(300)
        val job = MailJob(sourceSender = "106123\r\nInjected: no", body = body, receivedAt = 1,
            accountAddress = account.sender, recipientAddress = account.recipient)
        val sender = QqMailSender(); val session = Session.getInstance(sender.properties())
        val mail = sender.message(session, job); val out = ByteArrayOutputStream(); mail.writeTo(out)
        val parsed = MimeMessage(session, ByteArrayInputStream(out.toByteArray()))
        assertTrue((parsed.content as String).replace("\r\n", "\n").contains(body))
        assertNull(parsed.getHeader("Injected"))
        assertEquals(account.recipient, parsed.allRecipients.single().toString())
        mail.saveChanges()
        assertEquals(parsed.messageID, mail.messageID)
    }
}
