package com.sobigrice.smsrelay.mail

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.net.ssl.SSLException

internal data class MailEndpoint(val host: String = "smtp.qq.com", val port: Int = 465)

class QqMailSender internal constructor(
    private val endpoint: MailEndpoint = MailEndpoint(),
    // Test-only injected TLS factory; the application always uses the system trust store.
    private val sessionSetup: (Properties) -> Unit = {},
) : MailSender {
    internal fun properties() = Properties().apply {
        setProperty("mail.smtp.host", endpoint.host)
        setProperty("mail.smtp.port", endpoint.port.toString())
        setProperty("mail.smtp.auth", "true")
        setProperty("mail.smtp.ssl.enable", "true")
        setProperty("mail.smtp.ssl.checkserveridentity", "true")
        setProperty("mail.smtp.ssl.protocols", "TLSv1.3 TLSv1.2")
        setProperty("mail.smtp.connectiontimeout", "15000")
        setProperty("mail.smtp.timeout", "20000")
        setProperty("mail.smtp.writetimeout", "20000")
        // A timeout waiting for QUIT after DATA was accepted must not turn success into retry.
        setProperty("mail.smtp.quitwait", "false")
        setProperty("mail.debug", "false")
        sessionSetup(this)
    }

    internal fun message(session: Session, job: MailJob): MimeMessage {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.CHINA).format(Date(job.receivedAt))
        val source = job.sourceSender.replace(Regex("[\\r\\n]"), " ").take(80)
        val subject = if (job.kind == JobKind.TEST) "[信渡] 邮箱连接测试"
            else "[信渡] $source · $date"
        val content = if (job.kind == JobKind.TEST) job.body else
            "来源号码：${job.sourceSender}\n接收时间：$date\n设备：安卓短信接收端\n\n${job.body}"
        // Preserve Message-ID across explicit retries, although SMTP recipients need not deduplicate it.
        return object : MimeMessage(session) {
            override fun updateMessageID() { setHeader("Message-ID", "<sms-${job.id}@smsrelay.local>") }
        }.apply {
            setFrom(InternetAddress(job.accountAddress, "信渡", "UTF-8"))
            setRecipient(Message.RecipientType.TO, InternetAddress(job.recipientAddress, true))
            setSubject(subject, "UTF-8")
            setText(content, "UTF-8")
            sentDate = Date(job.receivedAt)
            saveChanges()
        }
    }

    override fun send(job: MailJob, account: MailAccount): SendResult {
        val session = Session.getInstance(properties())
        val mail = try { message(session, job) }
        catch (_: MessagingException) { return SendResult.Rejected(MailFailure.CONFIG) }
        val transport = session.getTransport("smtp")
        try {
            try { transport.connect(endpoint.host, endpoint.port, account.sender, account.authorizationCode) }
            catch (e: Exception) { return classifyConnectionFailure(e) }
            return try {
                transport.sendMessage(mail, mail.allRecipients)
                SendResult.Accepted
            } catch (e: SMTPSendFailedException) {
                classifyRejection(e.returnCode)
            } catch (e: SMTPAddressFailedException) {
                classifyRejection(e.returnCode)
            } catch (e: jakarta.mail.SendFailedException) {
                val rejection = causes(e).filterIsInstance<SMTPAddressFailedException>().firstOrNull()
                if (rejection != null) classifyRejection(rejection.returnCode) else SendResult.Uncertain()
            } catch (_: Exception) {
                // The connection may have failed after the server accepted DATA.
                SendResult.Uncertain()
            }
        } finally {
            // Cleanup errors after acceptance do not invalidate the acceptance.
            try { transport.close() } catch (_: MessagingException) { /* Socket is closed by provider. */ }
        }
    }

    private fun classifyConnectionFailure(e: Exception): SendResult {
        val chain = causes(e)
        return when {
            chain.any { it is AuthenticationFailedException } -> SendResult.Rejected(MailFailure.AUTH)
            chain.any { it is SSLException || it is java.security.cert.CertificateException } -> SendResult.Rejected(MailFailure.TLS)
            else -> SendResult.Retry(MailFailure.NETWORK)
        }
    }

    private fun classifyRejection(code: Int): SendResult = when (code) {
        in 400..499 -> SendResult.Retry(MailFailure.TEMPORARY)
        in 500..599 -> SendResult.Rejected(MailFailure.REJECTED)
        else -> SendResult.Uncertain()
    }

    private fun causes(e: Throwable): List<Throwable> {
        val result = mutableListOf<Throwable>()
        var current: Throwable? = e
        while (current != null && result.size < 16 && result.none { it === current }) {
            result += current
            current = current.cause ?: (current as? MessagingException)?.nextException
        }
        return result
    }
}
