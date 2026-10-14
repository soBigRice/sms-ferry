package com.sobigrice.smsrelay.mail

import org.junit.Assert.*
import org.junit.Test
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MailValidationTest {
    @Test fun recipientHeaderInjectionIsRejected() {
        try { MailAccount.legacyQq("10001@qq.com", "abcdefghijklmnop", "10002@qq.com\r\nBcc: attacker@example.com") }
        catch (_: IllegalArgumentException) { return }
        fail("header injection must be rejected")
    }
    @Test fun arbitrarySenderAndCustomLoginAndPasswordAreAccepted() {
        val account = MailAccount.create("alice@example.org", " pass word! ", "reader@example.net",
            "SMTP.Example.org", 2525, MailSecurity.STARTTLS, "smtp-user")
        assertEquals("smtp.example.org", account.smtpHost)
        assertEquals("smtp-user", account.username)
        assertEquals(" pass word! ", account.authorizationCode)
        assertFalse(account.toString().contains(" pass word! "))
    }
    @Test fun invalidEndpointOrEmptyCredentialIsRejected() {
        for (host in listOf("", "https://smtp.example.org", "smtp.example.org:465", "smtp.example.org/path", "bad\nserver")) {
            assertThrows(IllegalArgumentException::class.java) {
                MailAccount.create("alice@example.org", "password", "reader@example.net", host, 465, MailSecurity.TLS)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            MailAccount.create("alice@example.org", "", "reader@example.net", "smtp.example.org", 465, MailSecurity.TLS)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MailAccount.create("alice@example.org", "password", "reader@example.net", "smtp.example.org", 65536, MailSecurity.TLS)
        }
    }
    @Test fun ipv6EndpointIsAcceptedWithoutDnsLookup() {
        assertEquals("::1", MailAccount.create("a@example.org", "password", "b@example.org", "[::1]", 465, MailSecurity.TLS).smtpHost)
    }
    @Test fun pastedAuthorizationCodeWhitespaceIsNormalized() {
        val account = MailAccount.legacyQq("10001@qq.com", "abcd efgh ijkl mnop\n", "10002@qq.com")
        assertEquals("abcdefghijklmnop", account.authorizationCode)
    }
    @Test fun unicodeLongBodyAndDotLinesSurviveMimeRoundTrip() {
        val account = MailAccount.legacyQq("10001@qq.com", "abcdefghijklmnop", "10002@qq.com")
        val body = "完整长短信：🍚\n.\n第一段\n" + "第二段中文。".repeat(300)
        val job = MailJob(sourceSender = "106123\r\nInjected: no", body = body, receivedAt = 1,
            accountAddress = account.sender, recipientAddress = account.recipient)
        val sender = SmtpMailSender(); val session = Session.getInstance(sender.properties(account))
        val mail = sender.message(session, job); val out = ByteArrayOutputStream(); mail.writeTo(out)
        val parsed = MimeMessage(session, ByteArrayInputStream(out.toByteArray()))
        assertTrue((parsed.content as String).replace("\r\n", "\n").contains(body))
        assertNull(parsed.getHeader("Injected"))
        assertEquals(account.recipient, parsed.allRecipients.single().toString())
        mail.saveChanges()
        assertEquals(parsed.messageID, mail.messageID)
    }
}
