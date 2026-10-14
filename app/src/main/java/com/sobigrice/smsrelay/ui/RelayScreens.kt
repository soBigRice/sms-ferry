package com.sobigrice.smsrelay.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sobigrice.smsrelay.mail.*
import com.sobigrice.smsrelay.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalConfiguration

data class CheckRow(val title: String, val detail: String, val ready: Boolean, val action: String)
data class HistoryRow(val record: JobSummary, val label: String, val problem: Boolean)
data class HomeState(
    val title: String = "", val description: String = "",
    val mailSummary: String = "", val enabled: Boolean = false,
    val warning: Boolean = false, val busy: Boolean = false, val toggleLabel: String = "",
    val accepted: Int = 0, val pending: Int = 0, val problems: Int = 0,
    val checks: List<CheckRow> = emptyList(), val checkSummary: String = "", val history: List<HistoryRow> = emptyList(),
    val error: String? = null,
)
data class HomeActions(
    val settings: () -> Unit, val toggle: () -> Unit, val smsPermission: () -> Unit,
    val notifications: () -> Unit, val battery: () -> Unit, val backgroundHelp: () -> Unit,
    val refresh: () -> Unit, val problems: () -> Unit, val history: () -> Unit, val record: (JobSummary) -> Unit, val language: () -> Unit = {},
    val update: () -> Unit = {},
)
data class DialogChoice(val label: String, val action: () -> Unit)
data class RelayDialogState(
    val title: String, val message: String = "", val choices: List<DialogChoice> = emptyList(),
    val confirmLabel: String? = null, val confirm: (() -> Unit)? = null,
    val dismissLabel: String? = null, val extraLabel: String? = null, val extra: (() -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    val statusColor by animateColorAsState(
        if (state.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        tween(FerryMotion.Content, easing = FerryMotion.Easing), label = "statusColor")
    val cardColor by animateColorAsState(
        if (state.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        tween(FerryMotion.Content, easing = FerryMotion.Easing), label = "statusCardColor")
    Scaffold(topBar = {
        TopAppBar(title = { Column { Text(stringResource(R.string.app_name)); Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.labelSmall) } },
            actions = {
                TextButton(onClick = actions.update, modifier = Modifier.testTag("updateButton")) { Text(stringResource(R.string.ota_check)) }
                TextButton(onClick = actions.settings, enabled = !state.busy, modifier = Modifier.testTag("settingsButton")) { Text(stringResource(R.string.mail_settings)) }
            })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 600.dp).fillMaxWidth().testTag("homeList"), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "status") {
                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), modifier = Modifier.animateItem()) {
                        Column(Modifier.animateContentSize(tween(FerryMotion.Container, easing = FerryMotion.Easing))
                            .padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AnimatedContent(state.title, transitionSpec = {
                                (fadeIn(tween(FerryMotion.Content)) + slideInVertically { it / 4 }) togetherWith
                                    (fadeOut(tween(FerryMotion.Exit)) + slideOutVertically { -it / 4 })
                            }, label = "relayStatus") { title ->
                                Text(title, style = MaterialTheme.typography.headlineSmall, color = statusColor)
                            }
                            Text(state.description, Modifier.animateContentSize(), style = MaterialTheme.typography.bodyMedium)
                            Text(state.mailSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth()) {
                                CountCell(stringResource(R.string.accepted), state.accepted, Modifier.weight(1f))
                                CountCell(stringResource(R.string.pending), state.pending, Modifier.weight(1f))
                                CountCell(stringResource(R.string.needs_attention), state.problems, Modifier.weight(1f))
                            }
                            if (state.enabled) OutlinedButton(onClick = actions.toggle, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().testTag("toggleButton")) { Text(state.toggleLabel) }
                            else Button(onClick = actions.toggle, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().testTag("toggleButton")) { Text(state.toggleLabel) }
                            AnimatedVisibility(state.busy, enter = fadeIn(), exit = fadeOut()) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            AnimatedVisibility(state.problems > 0) {
                                TextButton(onClick = actions.problems, modifier = Modifier.fillMaxWidth()) { Text(pluralStringResource(R.plurals.problem_records, state.problems, state.problems)) }
                            }
                        }
                    }
                }
                state.error?.let { error -> item(key = "error") { ErrorMessage(error, Modifier.animateItem()) } }
                item(key = "checks") {
                    OutlinedCard(modifier = Modifier.animateItem().animateContentSize()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.running_checks), style = MaterialTheme.typography.titleMedium)
                                Text(state.checkSummary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val callbacks = listOf(actions.smsPermission, actions.notifications, actions.battery)
                            state.checks.forEachIndexed { index, check ->
                                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(check.title, style = MaterialTheme.typography.labelLarge)
                                        Text(check.detail, style = MaterialTheme.typography.bodySmall,
                                            color = if (check.ready) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                                    }
                                    if (!check.ready) TextButton(onClick = callbacks[index], enabled = !state.busy) { Text(check.action) }
                                }
                            }
                            TextButton(onClick = actions.backgroundHelp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.background_help)) }
                        }
                    }
                }
                item(key = "history") {
                    OutlinedCard(modifier = Modifier.animateItem().animateContentSize()) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.history), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = actions.refresh) { Text(stringResource(R.string.refresh)) }
                                if (state.history.isNotEmpty()) TextButton(onClick = actions.history) { Text(stringResource(R.string.more)) }
                            }
                            if (state.history.isEmpty()) Text(stringResource(R.string.history_empty),
                                Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            state.history.forEachIndexed { index, row ->
                                key(row.record.id) {
                                    HistoryEntry(row, showDivider = index > 0) { actions.record(row.record) }
                                }
                            }
                        }
                    }
                }
                item(key = "notes") {
                    Column(Modifier.animateItem()) {
                        TextButton(onClick = actions.language, enabled = !state.busy, modifier = Modifier.testTag("languageButton")) {
                            Text(stringResource(R.string.language))
                        }
                        Text(stringResource(R.string.delivery_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CountCell(label: String, value: Int, modifier: Modifier) {
    Column(modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Transition between real snapshots; never display interpolated, fictional record counts.
        AnimatedContent(value, transitionSpec = {
            val direction = if (targetState >= initialState) 1 else -1
            ((slideInVertically(tween(FerryMotion.Content, easing = FerryMotion.Easing)) { it * direction / 2 } + fadeIn()) togetherWith
                (slideOutVertically(tween(FerryMotion.Exit)) { -it * direction / 2 } + fadeOut()))
                .using(SizeTransform(clip = true) { _, _ -> tween(FerryMotion.Content) })
        }, label = "recordCount") { count ->
            Text(java.text.NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(count), style = MaterialTheme.typography.headlineMedium)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HistoryEntry(row: HistoryRow, showDivider: Boolean, open: () -> Unit) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visible, enter = fadeIn(tween(FerryMotion.Content)) + expandVertically(tween(FerryMotion.Container))) {
        Column {
            if (showDivider) HorizontalDivider()
            Text(row.label, Modifier.fillMaxWidth().clickable(onClick = open).padding(vertical = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

data class SettingsDraft(
    val sender: String = "", val recipient: String = "", val host: String = "", val port: String = "465",
    val username: String = "", val password: String = "", val security: MailSecurity = MailSecurity.TLS,
) {
    companion object {
        fun from(account: MailAccount?) = account?.let { SettingsDraft(it.sender, it.recipient, it.smtpHost,
            it.smtpPort.toString(), it.username, security = it.security) } ?: SettingsDraft()
    }
}
data class SavedMailSummary(val sender: String, val recipient: String, val host: String, val port: Int, val username: String, val security: MailSecurity)
data class SettingsState(
    val saved: SavedMailSummary? = null, val draft: SettingsDraft = SettingsDraft(), val editing: Boolean = false,
    val busy: Boolean = false, val readable: Boolean = true, val error: String? = null,
)
enum class MailPreset(val label: Int, val host: String, val security: MailSecurity) {
    QQ(R.string.qq_mail, "smtp.qq.com", MailSecurity.TLS), NETEASE(R.string.netease_mail, "smtp.163.com", MailSecurity.TLS)
}
data class SettingsActions(val back: () -> Unit, val edit: () -> Unit, val cancel: () -> Unit, val save: () -> Unit,
    val test: () -> Unit, val draft: (SettingsDraft) -> Unit, val preset: (MailPreset) -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailSettingsScreen(state: SettingsState, actions: SettingsActions) {
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(state.editing) {
        if (!state.editing) {
            focus.clearFocus()
            keyboard?.hide()
        }
        listState.animateScrollToItem(0)
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.mail_settings)) }, navigationIcon = {
        TextButton(onClick = actions.back, modifier = Modifier.testTag("backButton")) { Text(stringResource(R.string.back)) }
    }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 600.dp).fillMaxWidth().testTag("settingsList"), state = listState, contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "profile") {
                    // Animate only the mode, so typing never recreates or cross-fades a password field.
                    AnimatedContent(state.editing, transitionSpec = {
                        ((fadeIn(tween(FerryMotion.Content)) + slideInVertically { it / 16 }) togetherWith
                            fadeOut(tween(FerryMotion.Exit)))
                            .using(SizeTransform(clip = true) { _, _ -> tween(FerryMotion.Container, easing = FerryMotion.Easing) })
                    }, contentAlignment = Alignment.TopStart, label = "mailEditor") { editing ->
                        if (!editing) {
                            OutlinedCard(Modifier.fillMaxWidth().testTag("readOnlyCard")) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text(stringResource(R.string.saved_read_only), style = MaterialTheme.typography.titleMedium)
                                    val saved = state.saved
                                    if (saved == null) Text(stringResource(R.string.mail_not_configured))
                                    else {
                                        SummaryField(stringResource(R.string.sender), saved.sender)
                                        SummaryField(stringResource(R.string.recipient), saved.recipient)
                                        SummaryField(stringResource(R.string.smtp_server), "${saved.host}:${saved.port} · ${saved.security.label}")
                                        SummaryField(stringResource(R.string.username), saved.username)
                                        SummaryField(stringResource(R.string.credential), stringResource(R.string.credential_saved))
                                    }
                                    OutlinedButton(onClick = actions.edit, enabled = !state.busy && editing == state.editing && state.readable,
                                        modifier = Modifier.fillMaxWidth().testTag("editButton")) { Text(if (saved == null) stringResource(R.string.setup_email) else stringResource(R.string.edit_config)) }
                                }
                            }
                        } else {
                            Column(Modifier.testTag("editForm"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text(stringResource(R.string.smtp_setup), style = MaterialTheme.typography.titleLarge)
                                Text(stringResource(R.string.smtp_setup_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                var presetsOpen by remember { mutableStateOf(false) }
                                Box {
                                    OutlinedButton(onClick = { presetsOpen = true }) { Text(stringResource(R.string.smtp_presets)) }
                                    DropdownMenu(expanded = presetsOpen, onDismissRequest = { presetsOpen = false }) {
                                        MailPreset.entries.forEach { preset -> DropdownMenuItem(text = { Text(stringResource(preset.label)) },
                                            onClick = { presetsOpen = false; actions.preset(preset) }) }
                                    }
                                }
                                val draft = state.draft
                                MailField("hostInput", stringResource(R.string.smtp_server), draft.host, KeyboardType.Uri) { actions.draft(draft.copy(host = it)) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MailSecurity.entries.forEach { security ->
                                        Row(Modifier.weight(1f).selectable(selected = draft.security == security, role = Role.RadioButton,
                                            onClick = { actions.draft(draft.copy(security = security,
                                                port = if (draft.port == draft.security.defaultPort.toString()) security.defaultPort.toString() else draft.port)) })
                                            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = draft.security == security, onClick = null)
                                            Text(security.label, style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                }
                                MailField("portInput", stringResource(R.string.smtp_port), draft.port, KeyboardType.Number) { actions.draft(draft.copy(port = it)) }
                                MailField("senderInput", stringResource(R.string.sender), draft.sender, KeyboardType.Email) { actions.draft(draft.copy(sender = it)) }
                                MailField("usernameInput", stringResource(R.string.smtp_username), draft.username, KeyboardType.Text) { actions.draft(draft.copy(username = it)) }
                                MailField("codeInput", stringResource(R.string.credential), draft.password, KeyboardType.Password, secret = true,
                                    help = if (state.saved != null) stringResource(R.string.credential_keep_help) else stringResource(R.string.credential_new_help)) { actions.draft(draft.copy(password = it)) }
                                MailField("recipientInput", stringResource(R.string.recipient), draft.recipient, KeyboardType.Email) { actions.draft(draft.copy(recipient = it)) }
                                Text(stringResource(R.string.draft_help),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(onClick = actions.cancel, enabled = !state.busy && editing == state.editing,
                                        modifier = Modifier.weight(1f).testTag("cancelButton")) { Text(stringResource(R.string.cancel_changes)) }
                                    Button(onClick = actions.save, enabled = !state.busy && editing == state.editing && state.readable,
                                        modifier = Modifier.weight(1f).testTag("saveButton")) { Text(stringResource(R.string.save_settings)) }
                                }
                            }
                        }
                    }
                }
                state.error?.let { error -> item { ErrorMessage(error) } }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = actions.test, enabled = !state.editing && !state.busy && state.saved != null && state.readable,
                            modifier = Modifier.fillMaxWidth().testTag("testButton")) { Text(if (state.busy) stringResource(R.string.queuing) else stringResource(R.string.send_test)) }
                        Text(stringResource(R.string.local_encryption_help),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.smtp_only_help),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun MailField(tag: String, label: String, value: String, type: KeyboardType, secret: Boolean = false,
                      help: String? = null, changed: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = changed, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag), visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Next),
        supportingText = help?.let { { Text(it) } })
}

@Composable
private fun SummaryField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ErrorMessage(message: String, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun RelayDialog(state: RelayDialogState?, dismiss: () -> Unit) {
    if (state == null) return
    AlertDialog(onDismissRequest = dismiss, title = { Text(state.title) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (state.message.isNotEmpty()) Text(state.message)
            state.choices.forEach { choice -> TextButton(onClick = { dismiss(); choice.action() }, modifier = Modifier.fillMaxWidth()) { Text(choice.label, Modifier.fillMaxWidth()) } }
        }
    }, confirmButton = { TextButton(onClick = { dismiss(); state.confirm?.invoke() }) { Text(state.confirmLabel ?: stringResource(R.string.close)) } },
        dismissButton = if (state.dismissLabel != null || state.extraLabel != null) { {
            Row {
                state.extraLabel?.let { label -> TextButton(onClick = { dismiss(); state.extra?.invoke() }) { Text(label) } }
                state.dismissLabel?.let { label -> TextButton(onClick = dismiss) { Text(label) } }
            }
        } } else null)
}
