package com.sobigrice.smsrelay.mail

import java.util.UUID
import java.net.IDN
import java.net.URI
import java.util.Locale

enum class MailSecurity(val label: String, val defaultPort: Int) {
    TLS("SSL/TLS", 465), STARTTLS("STARTTLS", 587)
}

enum class MailValidation { SENDER, RECIPIENT, CREDENTIAL, USERNAME, HOST, PORT, IDENTITY_CHANGED }
class MailValidationException(val reason: MailValidation) : IllegalArgumentException(reason.name)

data class SmtpIdentity(val host: String, val port: Int, val security: MailSecurity, val username: String)

data class MailAccount(
    val sender: String, val authorizationCode: String, val recipient: String,
    val smtpHost: String, val smtpPort: Int, val security: MailSecurity, val username: String,
) {
    val identity: SmtpIdentity get() = SmtpIdentity(smtpHost, smtpPort, security, username)
    override fun toString() = "MailAccount(sender=$sender, authorizationCode=[redacted], recipient=$recipient, smtpHost=$smtpHost, smtpPort=$smtpPort, security=$security, username=$username)"
    companion object {
        private val address = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
        fun create(sender: String, code: String, recipient: String, smtpHost: String,
                   smtpPort: Int, security: MailSecurity, username: String = sender): MailAccount {
            val from = sender.trim()
            val to = recipient.trim()
            require(from.length <= 254 && address.matches(from)) { throw MailValidationException(MailValidation.SENDER) }
            require(to.length <= 254 && address.matches(to)) { throw MailValidationException(MailValidation.RECIPIENT) }
            // Generic SMTP passwords may contain spaces; never apply QQ's old whitespace rule.
            require(code.isNotBlank() && code.length <= 4096 && code.none { it in "\r\n\u0000" }) {
                throw MailValidationException(MailValidation.CREDENTIAL)
            }
            val login = username.trim()
            require(login.isNotEmpty() && login.length <= 254 && login.none { it in "\r\n\u0000" }) { throw MailValidationException(MailValidation.USERNAME) }
            val rawHost = smtpHost.trim().removeSurrounding("[", "]")
            val host = try {
                (if (':' in rawHost) rawHost else IDN.toASCII(rawHost)).lowercase(Locale.ROOT)
            } catch (_: IllegalArgumentException) { throw MailValidationException(MailValidation.HOST) }
            require(host.isNotEmpty() && host.length <= 253 && host.none { it.isWhitespace() || it in "/\\@?#" } &&
                runCatching { URI(null, null, host, -1, null, null, null).host != null }.getOrDefault(false)) {
                throw MailValidationException(MailValidation.HOST)
            }
            require(smtpPort in 1..65535) { throw MailValidationException(MailValidation.PORT) }
            return MailAccount(from, code, to, host, smtpPort, security, login)
        }

        /** Only pre-0.2.0 persisted data uses the original QQ endpoint. */
        fun legacyQq(sender: String, code: String, recipient: String) = create(sender,
            code.filterNot(Char::isWhitespace), recipient, "smtp.qq.com", 465, MailSecurity.TLS, sender)
    }
}

enum class JobKind { SMS, TEST }
enum class JobState { PENDING, SENDING, ACCEPTED, BLOCKED, UNCERTAIN }
enum class MailFailure {
    NETWORK, TEMPORARY, AUTH, TLS, REJECTED, UNKNOWN, INTERRUPTED, ACCOUNT_CHANGED,
    ENCRYPTION, PERMISSION, SERVICE, CONFIG,
}

data class MailJob(
    val id: String = UUID.randomUUID().toString(),
    val sourceId: Long? = null,
    val sourceSender: String,
    val body: String,
    val receivedAt: Long,
    val accountAddress: String,
    val recipientAddress: String,
    val kind: JobKind = JobKind.SMS,
    val smtpIdentity: SmtpIdentity? = null,
) {
    // Missing identity is a legacy record, never a wildcard for the current server.
    fun deliveryIdentity() = smtpIdentity ?: SmtpIdentity("smtp.qq.com", 465, MailSecurity.TLS, accountAddress)
    fun belongsTo(account: MailAccount) = accountAddress.equals(account.sender, true) && deliveryIdentity() == account.identity
}

data class JobSummary(
    val id: String, val kind: JobKind, val state: JobState,
    val createdAt: Long, val attempts: Int, val failure: MailFailure?,
)

sealed interface SendResult {
    data object Accepted : SendResult
    data class Retry(val failure: MailFailure) : SendResult
    data class Rejected(val failure: MailFailure) : SendResult
    data class Uncertain(val failure: MailFailure = MailFailure.UNKNOWN) : SendResult
}

fun interface MailSender { fun send(job: MailJob, account: MailAccount): SendResult }

interface MailQueue {
    fun claimNext(allowSms: Boolean): MailJob?
    fun finish(id: String, state: JobState, failure: MailFailure? = null)
    fun hasPending(allowSms: Boolean): Boolean
}
