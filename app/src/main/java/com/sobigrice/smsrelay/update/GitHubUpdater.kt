package com.sobigrice.smsrelay.update

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GitHubUpdater(context: Context) {
    private val context = context.applicationContext
    private val installed = packageInfo(this.context.packageName)
    val currentVersion: String = installed.versionName ?: ""
    val currentVersionCode: Long = versionCode(installed)
    private val debug = this.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val directory get() = File(context.cacheDir, "updates")

    suspend fun check(): AppRelease? = ioRequest(timeoutSeconds = 60) { request ->
        if (SemanticVersion.parse(currentVersion) == null) {
            throw UpdateException(UpdateFailure.CURRENT_VERSION)
        }
        val connection = open(URI(UpdatePolicy.API_URL), request, asset = false)
        try {
            val declaredSize = connection.contentLengthLong
            if (declaredSize > UpdatePolicy.MAX_JSON_BYTES) throw UpdateException(UpdateFailure.MAX_SIZE)
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    request.check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size().toLong() + count > UpdatePolicy.MAX_JSON_BYTES) {
                        throw UpdateException(UpdateFailure.MAX_SIZE)
                    }
                    bytes.write(buffer, 0, count)
                }
            }
            ReleaseParser.select(bytes.toString(Charsets.UTF_8.name()), currentVersion, debug)
        } finally {
            request.release(connection)
        }
    }

    suspend fun download(release: AppRelease, onProgress: (Int) -> Unit): File = downloadLock.withLock {
        UpdatePolicy.validateRelease(release, currentVersion, debug)
        ioRequest(timeoutSeconds = 600) { request ->
            if (!directory.exists() && !directory.mkdirs()) throw UpdateException(UpdateFailure.STORAGE)
            // Keep a single owned download. A new download supersedes the previous installer handoff.
            directory.listFiles()?.filter { it.name.startsWith("sms-ferry-") &&
                (it.name.endsWith(".apk") || it.name.endsWith(".partial")) }?.forEach {
                if (!it.delete()) throw UpdateException(UpdateFailure.STORAGE)
            }
            val partial = File(directory, "${release.assetName}.partial")
            val ready = File(directory, release.assetName)
            var connection: HttpsURLConnection? = null
            try {
                connection = open(UpdatePolicy.validateDownloadUrl(release.downloadUrl, release.tag, release.assetName), request, asset = true)
                val declaredSize = connection.contentLengthLong
                if (declaredSize >= 0 && declaredSize != release.size) throw UpdateException(UpdateFailure.INCOMPLETE)
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                var progress = -1
                onProgress(0)
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            request.check()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > release.size || received > UpdatePolicy.MAX_APK_BYTES) {
                                throw UpdateException(UpdateFailure.MAX_SIZE)
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            // 100% is only reported once the complete APK has passed every check.
                            val next = ((received * 100) / release.size).toInt().coerceAtMost(99)
                            if (next != progress) { progress = next; onProgress(next) }
                        }
                        output.fd.sync()
                    }
                }
                request.check()
                UpdatePolicy.verifyPayload(received, release.size, digest.digest(), release.sha256)
                verifyApk(partial, release)
                request.check()
                if (!partial.renameTo(ready)) throw UpdateException(UpdateFailure.STORAGE)
                onProgress(100)
                ready
            } finally {
                connection?.let(request::release)
                partial.delete()
            }
        }
    }

    private fun verifyApk(file: File, release: AppRelease) {
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, signingFlags())
            ?: throw UpdateException(UpdateFailure.INVALID_APK)
        UpdatePolicy.verifyArchive(
            context.packageName, archive.packageName, currentVersionCode, versionCode(archive),
            release.version, archive.versionName, signerDigests(installed), signerDigests(archive),
        )
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(name: String): PackageInfo = context.packageManager.getPackageInfo(name, signingFlags())

    private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures?.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }?.toSet() ?: emptySet()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else info.versionCode.toLong()

    private fun open(initial: URI, request: RequestControl, asset: Boolean): HttpsURLConnection {
        var url = initial
        repeat(4) { attempt ->
            request.check()
            val connection = url.toURL().openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", if (asset) "application/octet-stream" else "application/vnd.github+json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "SmsFerry-Android")
            if (!asset) connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            request.attach(connection)
            val status = connection.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                request.release(connection)
                if (!asset || attempt == 3 || location == null) throw UpdateException(UpdateFailure.REDIRECT)
                url = UpdatePolicy.validateAssetRedirect(url.resolve(location).toString())
            } else {
                if (status != 200) {
                    request.release(connection)
                    throw when (status) {
                        403, 429 -> UpdateException(UpdateFailure.RATE_LIMIT)
                        404 -> UpdateException(UpdateFailure.NOT_FOUND)
                        else -> UpdateException(UpdateFailure.SERVICE)
                    }
                }
                return connection
            }
        }
        throw UpdateException(UpdateFailure.REDIRECT)
    }

    /** Cancel closes the active connection immediately; partial files are removed by the caller's finally. */
    private suspend fun <T> ioRequest(timeoutSeconds: Int, block: (RequestControl) -> T): T = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val request = RequestControl(timeoutSeconds) { continuation.isActive }
            continuation.invokeOnCancellation { request.cancel() }
            try {
                val result = block(request)
                request.check()
                continuation.resume(result)
            } catch (error: Exception) {
                val failure = when {
                    !continuation.isActive || error is CancellationException -> CancellationException("更新已取消")
                    error is UpdateException -> error
                    error is SocketTimeoutException || error is UpdateRequestFailure -> UpdateException(UpdateFailure.TIMEOUT)
                    else -> UpdateException(UpdateFailure.NETWORK)
                }
                continuation.resumeWithException(failure)
            } finally {
                request.cancel()
            }
        }
    }

    private class UpdateRequestFailure(message: String) : IOException(message)

    private class RequestControl(timeoutSeconds: Int, private val active: () -> Boolean) {
        private val connection = AtomicReference<HttpsURLConnection?>(null)
        private val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        fun check() {
            if (!active()) throw CancellationException("更新已取消")
            if (System.nanoTime() >= deadline) throw UpdateRequestFailure("GitHub 更新请求超时，请稍后重试")
        }
        fun attach(value: HttpsURLConnection) {
            connection.set(value)
            check()
        }
        fun release(value: HttpsURLConnection) {
            connection.compareAndSet(value, null)
            value.disconnect()
        }
        fun cancel() { connection.getAndSet(null)?.disconnect() }
    }

    companion object {
        private val downloadLock = Mutex()
    }
}

internal object ReleaseParser {
    fun select(json: String, currentVersion: String, debug: Boolean): AppRelease? = try {
        val current = SemanticVersion.parse(currentVersion)
            ?: throw UpdateException(UpdateFailure.CURRENT_VERSION)
        val releases = JSONArray(json)
        if (releases.length() > 100) throw UpdateException(UpdateFailure.INVALID_METADATA)
        var newest: AppRelease? = null
        for (i in 0 until releases.length()) {
            val item = releases.getJSONObject(i)
            if (item.getBoolean("draft")) continue
            val tag = item.getString("tag_name")
            val version = UpdatePolicy.versionFromTag(tag) ?: throw UpdateException(UpdateFailure.INVALID_VERSION)
            val parsed = SemanticVersion.parse(version)!!
            if (parsed <= current) continue
            val assets = item.getJSONArray("assets")
            if (assets.length() > 128) throw UpdateException(UpdateFailure.INVALID_METADATA)
            val expectedName = UpdatePolicy.assetName(version, debug)
            val matching = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.getString("name") == expectedName }
            if (matching.isEmpty()) continue
            if (matching.size > 1) throw UpdateException(UpdateFailure.INVALID_METADATA)
            val asset = matching.single()
            if (asset.getString("state") != "uploaded") throw UpdateException(UpdateFailure.INVALID_METADATA)
            val release = AppRelease(version, tag, item.optString("body", "").take(16_384),
                item.getBoolean("prerelease"), expectedName, asset.getString("browser_download_url"),
                positiveSize(asset), UpdatePolicy.checksumFromDigest(asset.optString("digest", "")))
            UpdatePolicy.validateRelease(release, currentVersion, debug)
            if (newest == null || parsed > SemanticVersion.parse(newest.version)!!) newest = release
        }
        newest
    } catch (_: JSONException) { throw UpdateException(UpdateFailure.INVALID_METADATA) }

    private fun positiveSize(asset: JSONObject): Long {
        // JSON floating point/truncated numbers must not silently authorize an incorrect payload size.
        val raw = asset.get("size")
        if (raw !is Int && raw !is Long) throw UpdateException(UpdateFailure.INVALID_METADATA)
        return (raw as Number).toLong()
    }
}
