package com.sobigrice.smsrelay.mail

import java.util.UUID

data class MailAccount(val sender: String, val authorizationCode: String, val recipient: String) {
    override fun toString() = "MailAccount(sender=$sender, authorizationCode=[redacted], recipient=$recipient)"
    companion object {
        private val address = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
        fun create(sender: String, code: String, recipient: String): MailAccount {
            val from = sender.trim()
            val to = recipient.trim()
            val secret = code.filterNot(Char::isWhitespace)
            require(from.length <= 254 && address.matches(from)) { "请填写完整的发送邮箱地址" }
            require(from.substringAfter('@').lowercase() in setOf("qq.com", "foxmail.com", "vip.qq.com")) {
                "发送邮箱需要是 QQ 邮箱或其 Foxmail 别名"
            }
            require(to.length <= 254 && address.matches(to)) { "请填写一个有效的接收邮箱地址" }
            require(Regex("[A-Za-z0-9]{16}").matches(secret)) { "请填写 QQ 邮箱的 16 位授权码，不是 QQ 密码" }
            return MailAccount(from, secret, to)
        }
    }
}

enum class JobKind { SMS, TEST }
enum class JobState { PENDING, SENDING, ACCEPTED, BLOCKED, UNCERTAIN }
enum class MailFailure(val description: String) {
    NETWORK("无法连接邮件服务器，网络恢复后自动重试"),
    TEMPORARY("邮件服务器暂时拒绝，稍后自动重试"),
    AUTH("邮箱认证失败，请检查 SMTP 服务与授权码"),
    TLS("加密连接校验失败，请检查手机时间和网络"),
    REJECTED("邮件被服务器明确拒绝，请检查地址或邮箱限制"),
    UNKNOWN("发送结果不明，请先核对邮箱，避免重复发送"),
    INTERRUPTED("发信过程中应用中断，请先核对邮箱"),
    ACCOUNT_CHANGED("此记录使用之前的发送账号，请恢复原账号后重试"),
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
)

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
