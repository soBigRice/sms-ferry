package com.sobigrice.smsrelay.mail

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MailContent(val subject: String, val body: String)

data class MailTemplate(val subject: String = DEFAULT_SUBJECT, val body: String = DEFAULT_BODY) {
    init {
        require(subject.isNotBlank() && subject.length <= 300 && subject.none(::unsafeHeaderCharacter)) {
            throw MailValidationException(MailValidation.TEMPLATE_SUBJECT)
        }
        require(body.isNotBlank() && body.length <= 10_000 && '\u0000' !in body) {
            throw MailValidationException(MailValidation.TEMPLATE_BODY)
        }
        for (text in listOf(subject, body)) {
            val remainder = token.replace(text, "")
            require("{{" !in remainder && "}}" !in remainder) {
                throw MailValidationException(MailValidation.TEMPLATE_VARIABLE)
            }
        }
        require("{{body}}" in body) { throw MailValidationException(MailValidation.TEMPLATE_MISSING_BODY) }
    }

    fun render(job: MailJob): MailContent {
        // 保留旧版连接测试格式；自定义模板的测试邮件则验证实际模板。 / Keep legacy test output; custom tests exercise the saved template.
        if (job.kind == JobKind.TEST && this == DEFAULT) return MailContent("[信渡] 邮箱连接测试", job.body)
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.CHINA).format(Date(job.receivedAt))
        fun substitute(text: String, header: Boolean) = token.replace(text) { match ->
            when (match.groupValues[1]) {
                "sender" -> if (header) job.sourceSender.map { if (unsafeHeaderCharacter(it)) ' ' else it }.joinToString("").take(80) else job.sourceSender
                "time" -> date
                else -> job.body
            }
        }
        // 只替换一次，短信中的模板字样保持原样；标题变量不可注入邮件头。 / Substitute once; preserve SMS tokens and prevent header injection.
        return MailContent(substitute(subject, true).map { if (unsafeHeaderCharacter(it)) ' ' else it }.joinToString(""),
            substitute(body, false))
    }

    companion object {
        const val DEFAULT_SUBJECT = "[信渡] {{sender}} · {{time}}"
        const val DEFAULT_BODY = "来源号码：{{sender}}\n接收时间：{{time}}\n设备：安卓短信接收端\n\n{{body}}"
        // Android ICU 比 JVM 更严格，左右花括号都必须显式转义。 / Android ICU requires explicit escaping of both brace pairs.
        private val token = Regex("\\{\\{(sender|time|body)\\}\\}")
        private fun unsafeHeaderCharacter(value: Char) = value.isISOControl() || value == '\u2028' || value == '\u2029'
        val DEFAULT = MailTemplate()
    }
}
