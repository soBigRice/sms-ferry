package com.sobigrice.smsrelay

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sobigrice.smsrelay.mail.*
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class AndroidMailTest {
    @Test fun angusProviderAndUnicodeMimeWorkOnAndroidWithoutNetwork() {
        val account = MailAccount.legacyQq("10001@qq.com", "abcdefghijklmnop", "10002@qq.com")
        val job = MailJob(sourceSender = "106123", body = "安卓本地邮件格式测试🍚\n" + "完整中文正文。".repeat(100),
            receivedAt = 123, accountAddress = account.sender, recipientAddress = account.recipient)
        val sender = SmtpMailSender()
        val session = Session.getInstance(sender.properties(account))
        val transport = session.getTransport("smtp")
        assertFalse(transport.isConnected)
        transport.close()
        val output = ByteArrayOutputStream()
        sender.message(session, job).writeTo(output)
        val decoded = MimeMessage(session, ByteArrayInputStream(output.toByteArray()))
        assertTrue((decoded.content as String).replace("\r\n", "\n").contains(job.body))
        assertEquals(account.recipient, decoded.allRecipients.single().toString())
    }
}
