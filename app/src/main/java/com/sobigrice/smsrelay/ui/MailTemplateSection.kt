package com.sobigrice.smsrelay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sobigrice.smsrelay.R
import com.sobigrice.smsrelay.describe
import com.sobigrice.smsrelay.mail.MailJob
import com.sobigrice.smsrelay.mail.MailTemplate
import com.sobigrice.smsrelay.mail.MailValidationException

@Composable
internal fun MailTemplateSection(subject: String, body: String, editing: Boolean, changed: (String, String) -> Unit) {
    var previewOpen by remember(editing) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().testTag("templateCard")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.template_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.template_scope), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (editing) {
                Text(stringResource(R.string.template_variables), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(subject, { changed(it, body) }, label = { Text(stringResource(R.string.template_subject)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("templateSubjectInput"))
                OutlinedTextField(body, { changed(subject, it) }, label = { Text(stringResource(R.string.template_body)) },
                    minLines = 5, maxLines = 10, modifier = Modifier.fillMaxWidth().testTag("templateBodyInput"),
                    supportingText = { Text(stringResource(R.string.template_body_help)) })
            } else {
                Text(stringResource(if (subject == MailTemplate.DEFAULT_SUBJECT && body == MailTemplate.DEFAULT_BODY)
                    R.string.template_default else R.string.template_custom), Modifier.testTag("templateStatus"))
            }
            OutlinedButton(onClick = { previewOpen = true }, modifier = Modifier.fillMaxWidth().testTag("templatePreviewButton")) {
                Text(stringResource(R.string.template_preview))
            }
            if (editing) TextButton(onClick = { changed(MailTemplate.DEFAULT_SUBJECT, MailTemplate.DEFAULT_BODY) },
                modifier = Modifier.fillMaxWidth().testTag("templateResetButton")) { Text(stringResource(R.string.template_reset)) }
        }
    }
    if (previewOpen) {
        val context = LocalContext.current
        val sampleBody = stringResource(R.string.template_sample_body)
        // 固定示例，不读取真实短信；预览与发送共用同一渲染器。 / Fixed sample; preview and delivery share the renderer without reading SMS.
        val preview = remember(subject, body, sampleBody) {
            runCatching { MailTemplate(subject, body).render(MailJob(sourceSender = "10612345678", body = sampleBody,
                receivedAt = 1_792_051_200_000L, accountAddress = "sender@example.com", recipientAddress = "reader@example.com")) }
        }
        AlertDialog(onDismissRequest = { previewOpen = false }, title = { Text(stringResource(R.string.template_preview)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.template_sample_notice), style = MaterialTheme.typography.bodySmall)
                    preview.fold(onSuccess = { content ->
                        Text(content.subject, Modifier.testTag("templatePreviewSubject"), style = MaterialTheme.typography.titleSmall)
                        HorizontalDivider()
                        Text(content.body, Modifier.testTag("templatePreviewBody"))
                    }, onFailure = { error ->
                        Text(context.describe((error as MailValidationException).reason), color = MaterialTheme.colorScheme.error)
                    })
                }
            }, confirmButton = {
                TextButton(onClick = { previewOpen = false }, modifier = Modifier.testTag("templatePreviewClose")) { Text(stringResource(R.string.back)) }
            })
    }
}
