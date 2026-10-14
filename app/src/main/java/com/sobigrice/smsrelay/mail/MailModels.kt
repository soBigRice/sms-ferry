package com.sobigrice.smsrelay.mail

import java.util.UUID
import java.net.IDN
import java.net.URI
import java.util.Locale

enum class MailSecurity(val label: String, val defaultPort: Int) {
    TLS("SSL/TLS", 465), STARTTLS("STARTTLS", 587)
}

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
            require(from.length <= 254 && address.matches(from)) { "请填写完整的发送邮箱地址" }
            require(to.length <= 254 && address.matches(to)) { "请填写一个有效的接收邮箱地址" }
            // Generic SMTP passwords may contain spaces; never apply QQ's old whitespace rule.
            require(code.isNotBlank() && code.length <= 4096 && code.none { it in "\r\n\u0000" }) {
                "请填写 SMTP 密码、授权码或应用专用密码"
            }
            val login = username.trim()
            require(login.isNotEmpty() && login.length <= 254 && login.none { it in "\r\n\u0000" }) { "请填写 SMTP 登录用户名" }
            val rawHost = smtpHost.trim().removeSurrounding("[", "]")
            val host = try {
                (if (':' in rawHost) rawHost else IDN.toASCII(rawHost)).lowercase(Locale.ROOT)
            } catch (_: IllegalArgumentException) { throw IllegalArgumentException("请填写 SMTP 主机名或 IP，不包含协议、端口或路径") }
            require(host.isNotEmpty() && host.length <= 253 && host.none { it.isWhitespace() || it in "/\\@?#" } &&
                runCatching { URI(null, null, host, -1, null, null, null).host != null }.getOrDefault(false)) {
                "请填写 SMTP 主机名或 IP，不包含协议、端口或路径"
            }
            require(smtpPort in 1..65535) { "SMTP 端口必须在 1–65535 之间" }
            return MailAccount(from, code, to, host, smtpPort, security, login)
        }

        /** Only pre-0.2.0 persisted data uses the original QQ endpoint. */
        fun legacyQq(sender: String, code: String, recipient: String) = create(sender,
            code.filterNot(Char::isWhitespace), recipient, "smtp.qq.com", 465, MailSecurity.TLS, sender)
    }
}

enum class JobKind { SMS, TEST }
enum class JobState { PENDING, SENDING, ACCEPTED, BLOCKED, UNCERTAIN }
enum class MailFailure(val description: String) {
    NETWORK("无法连接邮件服务器，网络恢复后自动重试"),
    TEMPORARY("邮件服务器暂时拒绝，稍后自动重试"),
    AUTH("邮箱认证失败，请检查 SMTP 用户名、密码或授权码"),
    TLS("加密连接校验失败，请检查手机时间和网络"),
    REJECTED("邮件被服务器明确拒绝，请检查地址或邮箱限制"),
    UNKNOWN("发送结果不明，请先核对邮箱，避免重复发送"),
    INTERRUPTED("发信过程中应用中断，请先核对邮箱"),
    ACCOUNT_CHANGED("此记录绑定之前的发件账号或 SMTP 服务器，请恢复原配置后重试"),
    ENCRYPTION("本地加密数据无法读取，请勿卸载或清除应用数据"),
    PERMISSION("短信读取权限不可用，请检查应用权限"),
    SERVICE("后台服务未能启动，请打开应用恢复"),
    CONFIG("邮箱配置不可用，请重新检查设置"),
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
