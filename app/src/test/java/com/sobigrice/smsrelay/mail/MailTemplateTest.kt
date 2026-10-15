package com.sobigrice.smsrelay.mail

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.TimeZone

class MailTemplateTest {
    private val job = MailJob(sourceSender = "106123", body = "完整短信🍚\n.\n{{sender}} $1 \\ text",
        receivedAt = 0, accountAddress = "sender@example.org", recipientAddress = "reader@example.net")

    @Test fun defaultPreservesOriginalSmsAndConnectionTestFormat() {
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+08:00"))
            val content = MailTemplate.DEFAULT.render(job)
            assertEquals("[信渡] 106123 · 1970-01-01 08:00:00 +08:00", content.subject)
            assertEquals("来源号码：106123\n接收时间：1970-01-01 08:00:00 +08:00\n设备：安卓短信接收端\n\n${job.body}", content.body)
            assertEquals(MailContent("[信渡] 邮箱连接测试", job.body), MailTemplate.DEFAULT.render(job.copy(kind = JobKind.TEST)))
        } finally { TimeZone.setDefault(originalZone) }
    }

    @Test fun substitutionIsSinglePassAndKeepsFullUnicodeBody() {
        val template = MailTemplate("短信 {{sender}} / {{sender}}", "{{body}}\n来源 {{sender}}\n再次：{{body}}")
        val longJob = job.copy(body = job.body + "中文长短信".repeat(3000))
        val content = template.render(longJob)
        assertEquals("短信 106123 / 106123", content.subject)
        assertEquals("${longJob.body}\n来源 106123\n再次：${longJob.body}", content.body)
        assertEquals(content, template.render(longJob.copy(kind = JobKind.TEST)))
    }

    @Test fun invalidTemplatesHaveSpecificValidationReasons() {
        fun invalid(reason: MailValidation, subject: String = "短信", body: String = "{{body}}") {
            val error = assertThrows(MailValidationException::class.java) { MailTemplate(subject, body) }
            assertEquals(reason, error.reason)
        }
        invalid(MailValidation.TEMPLATE_SUBJECT, subject = " ")
        invalid(MailValidation.TEMPLATE_SUBJECT, subject = "a".repeat(301))
        invalid(MailValidation.TEMPLATE_SUBJECT, subject = "hi\r\nBcc: attacker@example.org")
        invalid(MailValidation.TEMPLATE_BODY, body = "{{body}}" + "a".repeat(10_000))
        invalid(MailValidation.TEMPLATE_BODY, body = "{{body}}\u0000")
        invalid(MailValidation.TEMPLATE_VARIABLE, subject = "{{unknown}}")
        invalid(MailValidation.TEMPLATE_VARIABLE, body = "{{body}} {{time}")
        invalid(MailValidation.TEMPLATE_VARIABLE, body = "{{body}} {{")
        invalid(MailValidation.TEMPLATE_MISSING_BODY, body = "来源 {{sender}}")
        assertEquals("{plain braces}\n${job.body}", MailTemplate("ok", "{plain braces}\n{{body}}").render(job).body)
    }

    @Test fun variablesCannotInjectHeadersAndMimeRetainsCompleteBodyAndIdentity() {
        val template = MailTemplate("{{body}} {{sender}}", "{{body}}\n{{sender}}")
        val source = job.copy(sourceSender = "106\r\nCc: attacker@example.org", body = "验证码🍚\r\nBcc: attacker@example.org\u0000\u2028",
            template = template)
        val account = MailAccount.legacyQq("10001@qq.com", "abcdefghijklmnop", "10002@qq.com")
        val sender = SmtpMailSender()
        val session = Session.getInstance(sender.properties(account))
        val message = sender.message(session, source)
        val output = ByteArrayOutputStream().also(message::writeTo)
        val parsed = MimeMessage(session, ByteArrayInputStream(output.toByteArray()))
        assertNull(parsed.getHeader("Bcc"))
        assertNull(parsed.getHeader("Cc"))
        assertEquals(source.recipientAddress, parsed.allRecipients.single().toString())
        assertTrue(parsed.subject.startsWith("验证码🍚  Bcc:"))
        assertEquals("${source.body}\n${source.sourceSender}".replace("\r\n", "\n"), (parsed.content as String).replace("\r\n", "\n"))
        message.saveChanges()
        assertEquals(parsed.messageID, message.messageID)
    }
}
