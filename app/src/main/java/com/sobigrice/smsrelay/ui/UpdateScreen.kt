package com.sobigrice.smsrelay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sobigrice.smsrelay.update.AppRelease
import com.sobigrice.smsrelay.R
import java.io.File
import java.util.Locale

data class UpdateState(
    val currentVersion: String,
    val checking: Boolean = true,
    val release: AppRelease? = null,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val file: File? = null,
    val error: String? = null,
    val errorResource: Int? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(state: UpdateState, back: () -> Unit, check: () -> Unit,
                 download: () -> Unit, cancel: () -> Unit, install: () -> Unit, releases: () -> Unit) {
    val error = state.errorResource?.let { stringResource(it) } ?: state.error
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.ota_title)) }, navigationIcon = { TextButton(onClick = back) { Text(stringResource(R.string.back)) } })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.ota_current_version, state.currentVersion), style = MaterialTheme.typography.bodyMedium)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val title = when {
                            state.checking -> stringResource(R.string.ota_checking)
                            state.downloading -> stringResource(R.string.ota_downloading, state.progress)
                            state.file != null -> stringResource(R.string.ota_ready)
                            state.release != null -> stringResource(R.string.ota_available, state.release.version)
                            error != null -> stringResource(R.string.ota_check_failed)
                            else -> stringResource(R.string.ota_latest)
                        }
                        Text(title, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleLarge)
                        state.release?.let { release ->
                            Text("${release.version}${if (release.prerelease) " · ${stringResource(R.string.ota_preview)}" else ""} · ${String.format(Locale.ROOT, "%.1f", release.size / 1048576.0)} MB",
                                style = MaterialTheme.typography.bodyMedium)
                            if (release.notes.isNotBlank()) Text(release.notes, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (state.downloading) {
                            LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                            OutlinedButton(onClick = cancel, modifier = Modifier.fillMaxWidth().testTag("cancelDownload")) { Text(stringResource(R.string.ota_cancel)) }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        if (!state.checking && !state.downloading) {
                            when {
                                state.file != null -> Button(onClick = install, modifier = Modifier.fillMaxWidth().testTag("installUpdate")) { Text(stringResource(R.string.ota_install)) }
                                state.release != null -> Button(onClick = download, modifier = Modifier.fillMaxWidth().testTag("downloadUpdate")) { Text(stringResource(R.string.ota_download)) }
                            }
                            OutlinedButton(onClick = check, modifier = Modifier.fillMaxWidth().testTag("checkUpdate")) { Text(stringResource(R.string.ota_recheck)) }
                        }
                    }
                }
                Text(stringResource(R.string.ota_description),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = releases, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ota_releases)) }
            }
        }
    }
}
