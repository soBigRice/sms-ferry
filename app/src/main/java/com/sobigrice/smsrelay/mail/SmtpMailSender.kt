package com.sobigrice.smsrelay.mail

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import java.util.Date
import java.util.Properties
import javax.net.ssl.SSLException

internal data class MailEndpoint(val host: String, val port: Int)

class SmtpMailSender internal constructor(
    private val endpointOverride: MailEndpoint? = null,
    // Test-only injected TLS factory; the application always uses the system trust store.
    private val sessionSetup: (Properties) -> Unit = {},
) : MailSender {
    internal fun properties(account: MailAccount) = Properties().apply {
        val endpoint = endpointOverride ?: MailEndpoint(account.smtpHost, account.smtpPort)
        setProperty("mail.smtp.host", endpoint.host)
        setProperty("mail.smtp.port", endpoint.port.toString())
        setProperty("mail.smtp.auth", "true")
        setProperty("mail.smtp.ssl.enable", (account.security == MailSecurity.TLS).toString())
        setProperty("mail.smtp.starttls.enable", (account.security == MailSecurity.STARTTLS).toString())
        setProperty("mail.smtp.starttls.required", (account.security == MailSecurity.STARTTLS).toString())
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
        val content = job.template.render(job)
        // Preserve Message-ID across explicit retries, although SMTP recipients need not deduplicate it.
        return object : MimeMessage(session) {
            override fun updateMessageID() { setHeader("Message-ID", "<sms-${job.id}@smsrelay.local>") }
        }.apply {
            setFrom(InternetAddress(job.accountAddress, "信渡", "UTF-8"))
            setRecipient(Message.RecipientType.TO, InternetAddress(job.recipientAddress, true))
            setSubject(content.subject, "UTF-8")
            setText(content.body, "UTF-8")
            sentDate = Date(job.receivedAt)
            saveChanges()
        }
    }

    override fun send(job: MailJob, account: MailAccount): SendResult {
        val endpoint = endpointOverride ?: MailEndpoint(account.smtpHost, account.smtpPort)
        val session = Session.getInstance(properties(account))
        val mail = try { message(session, job) }
        catch (_: MessagingException) { return SendResult.Rejected(MailFailure.CONFIG) }
        val transport = session.getTransport("smtp")
        try {
            try { transport.connect(endpoint.host, endpoint.port, account.username, account.authorizationCode) }
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
            chain.any { it is SSLException || it is java.security.cert.CertificateException ||
                (it is MessagingException && it.message?.contains("STARTTLS is required") == true) } -> SendResult.Rejected(MailFailure.TLS)
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
