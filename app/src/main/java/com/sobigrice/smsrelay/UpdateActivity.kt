package com.sobigrice.smsrelay

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sobigrice.smsrelay.ui.SmsFerryTheme
import com.sobigrice.smsrelay.ui.UpdateScreen
import com.sobigrice.smsrelay.ui.UpdateState
import com.sobigrice.smsrelay.update.GitHubUpdater
import com.sobigrice.smsrelay.update.UpdateException
import com.sobigrice.smsrelay.update.UpdateFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

class UpdateActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[UpdateModel::class.java] }
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (model.awaitingInstallPermission) {
            model.awaitingInstallPermission = false
            if (packageManager.canRequestPackageInstalls()) install()
            else model.showError(R.string.ota_permission_denied)
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(if (android.os.Build.VERSION.SDK_INT < 33) AppLanguage.wrap(newBase) else newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmsFerryTheme {
                UpdateScreen(model.state, ::finish, model::check, model::download, model::cancel,
                    ::install, ::openReleases)
            }
        }
    }

    private fun install() {
        val file = model.state.file?.takeIf { it.isFile } ?: run {
            model.invalidateDownloadedFile()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            model.awaitingInstallPermission = true
            try {
                installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
                model.awaitingInstallPermission = false
                model.showError(R.string.ota_settings_missing)
            }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            model.showError(R.string.ota_installer_missing)
        } catch (_: SecurityException) {
            model.showError(R.string.ota_install_restricted)
        }
    }

    private fun openReleases() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/soBigRice/sms-ferry/releases"))) }
        catch (_: ActivityNotFoundException) { model.showError(R.string.ota_browser_missing) }
    }
}

/** Separate from RelayGraph: checking and cancelling updates never writes forwarding state. */
class UpdateModel(application: Application) : AndroidViewModel(application) {
    private val updater = GitHubUpdater(application)
    var state by mutableStateOf(UpdateState(updater.currentVersion))
        private set
    var awaitingInstallPermission = false
    private var operation: Job? = null

    init { check() }

    fun check() {
        if (operation?.isActive == true) return
        state = UpdateState(updater.currentVersion)
        operation = viewModelScope.launch {
            try { state = state.copy(checking = false, release = updater.check()) }
            catch (e: CancellationException) { throw e }
            catch (e: UpdateException) { state = state.copy(checking = false, errorResource = errorResource(e.reason)) }
            catch (_: IOException) { state = state.copy(checking = false, errorResource = R.string.ota_check_error) }
            catch (_: Exception) { state = state.copy(checking = false, errorResource = R.string.ota_check_error) }
        }
    }

    fun download() {
        if (operation?.isActive == true) return
        val release = state.release ?: return
        state = state.copy(downloading = true, progress = 0, file = null, error = null, errorResource = null)
        operation = viewModelScope.launch {
            try {
                val file = updater.download(release) { progress ->
                    viewModelScope.launch { if (state.downloading) state = state.copy(progress = progress) }
                }
                state = state.copy(downloading = false, progress = 100, file = file)
            } catch (e: CancellationException) {
                state = state.copy(downloading = false, progress = 0, errorResource = R.string.ota_cancelled)
                throw e
            } catch (e: UpdateException) {
                state = state.copy(downloading = false, errorResource = errorResource(e.reason))
            } catch (_: IOException) {
                state = state.copy(downloading = false, errorResource = R.string.ota_download_error)
            } catch (_: Exception) {
                state = state.copy(downloading = false, errorResource = R.string.ota_download_error)
            }
        }
    }

    fun cancel() { if (state.downloading) operation?.cancel() }
    fun invalidateDownloadedFile() { state = state.copy(file = null, errorResource = R.string.ota_file_missing) }
    fun showError(resource: Int) { state = state.copy(errorResource = resource) }
    private fun errorResource(reason: UpdateFailure): Int = when (reason) {
        UpdateFailure.CURRENT_VERSION -> R.string.ota_error_current_version
        UpdateFailure.INVALID_VERSION -> R.string.ota_error_invalid_version
        UpdateFailure.INVALID_METADATA -> R.string.ota_error_invalid_metadata
        UpdateFailure.MISSING_DIGEST -> R.string.ota_error_missing_digest
        UpdateFailure.NOT_NEWER -> R.string.ota_error_not_newer
        UpdateFailure.CHANNEL -> R.string.ota_error_channel
        UpdateFailure.MAX_SIZE -> R.string.ota_error_max_size
        UpdateFailure.INVALID_URL -> R.string.ota_error_invalid_url
        UpdateFailure.REDIRECT -> R.string.ota_error_redirect
        UpdateFailure.INCOMPLETE -> R.string.ota_error_incomplete
        UpdateFailure.CHECKSUM -> R.string.ota_error_checksum
        UpdateFailure.PACKAGE -> R.string.ota_error_package
        UpdateFailure.VERSION_CODE -> R.string.ota_error_version_code
        UpdateFailure.VERSION_NAME -> R.string.ota_error_version_name
        UpdateFailure.SIGNATURE -> R.string.ota_error_signature
        UpdateFailure.STORAGE -> R.string.ota_error_storage
        UpdateFailure.INVALID_APK -> R.string.ota_error_invalid_apk
        UpdateFailure.RATE_LIMIT -> R.string.ota_error_rate_limit
        UpdateFailure.NOT_FOUND -> R.string.ota_error_not_found
        UpdateFailure.SERVICE -> R.string.ota_error_service
        UpdateFailure.TIMEOUT -> R.string.ota_error_timeout
        UpdateFailure.NETWORK -> R.string.ota_error_network
    }
}
