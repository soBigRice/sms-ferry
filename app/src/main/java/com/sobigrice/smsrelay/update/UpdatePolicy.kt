package com.sobigrice.smsrelay.update

import java.io.IOException
import java.net.URI
import java.security.MessageDigest

enum class UpdateFailure(val friendlyMessage: String) {
    CURRENT_VERSION("当前应用版本格式不受支持，无法安全检查更新"),
    INVALID_VERSION("GitHub 发布版本格式无效"),
    INVALID_METADATA("GitHub 更新信息格式无效，请稍后重试"),
    MISSING_DIGEST("更新文件缺少有效的 GitHub SHA-256 校验值，请重新上传发布文件"),
    NOT_NEWER("更新版本必须高于当前版本"),
    CHANNEL("更新文件与当前安装渠道不一致"),
    MAX_SIZE("更新文件大小无效或超过 100 MB"),
    INVALID_URL("GitHub 更新下载地址无效"),
    REDIRECT("GitHub 更新下载重定向地址无效"),
    INCOMPLETE("更新文件不完整，请重新下载"),
    CHECKSUM("更新文件校验失败，请重新下载"),
    PACKAGE("更新文件不是当前应用，已拒绝安装"),
    VERSION_CODE("更新文件的版本号未递增，已拒绝安装"),
    VERSION_NAME("更新文件版本与发布版本不一致，已拒绝安装"),
    SIGNATURE("更新文件签名与当前应用不一致，已拒绝安装"),
    STORAGE("无法保存更新文件，请重新打开应用"),
    INVALID_APK("更新文件不是有效的 Android 安装包，已拒绝安装"),
    RATE_LIMIT("GitHub 请求受限，请稍后再试"),
    NOT_FOUND("GitHub 发布或更新文件暂不可用，请稍后再试"),
    SERVICE("GitHub 更新服务暂不可用，请稍后再试"),
    TIMEOUT("GitHub 更新请求超时，请稍后重试"),
    NETWORK("无法连接 GitHub 更新服务，请检查网络后重试"),
}

class UpdateException(val reason: UpdateFailure) : IOException(reason.friendlyMessage)

data class AppRelease(
    val version: String,
    val tag: String,
    val notes: String,
    val prerelease: Boolean,
    val assetName: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String,
)

/** SemVer precedence deliberately ignores build metadata; a repack is not an upgrade. */
internal data class SemanticVersion(
    private val major: Long,
    private val minor: Long,
    private val patch: Long,
    private val prerelease: List<String>,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        for ((left, right) in listOf(major to other.major, minor to other.minor, patch to other.patch)) {
            left.compareTo(right).takeIf { it != 0 }?.let { return it }
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when {
                prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
                prerelease.isEmpty() -> 1
                else -> -1
            }
        }
        for (i in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val left = prerelease[i]
            val right = other.prerelease[i]
            val leftNumeric = left.all(Char::isDigit)
            val rightNumeric = right.all(Char::isDigit)
            val compared = when {
                // SemVer numeric identifiers have no upper bound; compare lengths before digits.
                leftNumeric && rightNumeric -> left.length.compareTo(right.length).takeIf { it != 0 }
                    ?: left.compareTo(right)
                leftNumeric -> -1
                rightNumeric -> 1
                else -> left.compareTo(right)
            }
            if (compared != 0) return compared
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val pattern = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$")

        fun parse(value: String): SemanticVersion? {
            if (value.length > 100) return null
            val match = pattern.matchEntire(value) ?: return null
            val numbers = (1..3).map { match.groupValues[it].toLongOrNull() ?: return null }
            val pre = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.') ?: emptyList()
            if (pre.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
            return SemanticVersion(numbers[0], numbers[1], numbers[2], pre)
        }
    }
}

internal object UpdatePolicy {
    const val REPOSITORY = "soBigRice/sms-ferry"
    const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases?per_page=100"
    const val MAX_JSON_BYTES = 2 * 1024 * 1024L
    const val MAX_APK_BYTES = 100 * 1024 * 1024L
    private val checksum = Regex("[0-9a-fA-F]{64}")

    fun versionFromTag(tag: String): String? = tag.removePrefix("v").takeIf { SemanticVersion.parse(it) != null }

    fun assetName(version: String, debug: Boolean): String = "sms-ferry-$version${if (debug) "-debug" else ""}.apk"

    fun checksumFromDigest(digest: String): String {
        if (!digest.startsWith("sha256:") || !checksum.matches(digest.removePrefix("sha256:"))) {
            throw UpdateException(UpdateFailure.MISSING_DIGEST)
        }
        return digest.removePrefix("sha256:").lowercase()
    }

    fun validateRelease(release: AppRelease, currentVersion: String, debug: Boolean) {
        val installed = SemanticVersion.parse(currentVersion)
            ?: throw UpdateException(UpdateFailure.CURRENT_VERSION)
        val candidate = SemanticVersion.parse(release.version)
            ?: throw UpdateException(UpdateFailure.INVALID_VERSION)
        if (candidate <= installed) throw UpdateException(UpdateFailure.NOT_NEWER)
        if (versionFromTag(release.tag) != release.version) throw UpdateException(UpdateFailure.INVALID_METADATA)
        if (release.assetName != assetName(release.version, debug)) throw UpdateException(UpdateFailure.CHANNEL)
        if (release.size !in 1..MAX_APK_BYTES) throw UpdateException(UpdateFailure.MAX_SIZE)
        if (!checksum.matches(release.sha256)) throw UpdateException(UpdateFailure.MISSING_DIGEST)
        validateDownloadUrl(release.downloadUrl, release.tag, release.assetName)
    }

    fun validateDownloadUrl(value: String, tag: String, asset: String): URI {
        val uri = parseUri(value)
        val expectedPath = "/$REPOSITORY/releases/download/$tag/$asset"
        if (uri.scheme != "https" || uri.host != "github.com" || uri.port != -1 ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.path != expectedPath || uri.path.split('/').any { it == "." || it == ".." }) {
            throw UpdateException(UpdateFailure.INVALID_URL)
        }
        return uri
    }

    /** GitHub redirects assets to its signed release CDN; arbitrary HTTPS hosts are never followed. */
    fun validateAssetRedirect(value: String): URI {
        val uri = parseUri(value)
        if (uri.scheme != "https" || uri.port != -1 || uri.rawUserInfo != null || uri.rawFragment != null ||
            uri.host != "release-assets.githubusercontent.com" || !uri.path.startsWith("/github-production-release-asset/")) {
            throw UpdateException(UpdateFailure.REDIRECT)
        }
        return uri
    }

    private fun parseUri(value: String): URI = try { URI(value) }
        catch (_: Exception) { throw UpdateException(UpdateFailure.INVALID_URL) }

    fun verifyPayload(size: Long, expectedSize: Long, hash: ByteArray, expectedHash: String) {
        if (size != expectedSize) throw UpdateException(UpdateFailure.INCOMPLETE)
        if (!checksum.matches(expectedHash) || !MessageDigest.isEqual(hash, hexBytes(expectedHash))) {
            throw UpdateException(UpdateFailure.CHECKSUM)
        }
    }

    private fun hexBytes(value: String): ByteArray = ByteArray(value.length / 2) { i ->
        value.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    fun verifyArchive(
        expectedPackage: String, actualPackage: String?, installedCode: Long, archiveCode: Long,
        expectedVersion: String, archiveVersion: String?, installedSigners: Set<String>, archiveSigners: Set<String>,
    ) {
        if (actualPackage != expectedPackage) throw UpdateException(UpdateFailure.PACKAGE)
        if (archiveCode <= installedCode) throw UpdateException(UpdateFailure.VERSION_CODE)
        if (archiveVersion != expectedVersion) throw UpdateException(UpdateFailure.VERSION_NAME)
        // Require the actual current signers, rather than authorizing certificate rotation implicitly.
        if (installedSigners.isEmpty() || archiveSigners.isEmpty() || installedSigners != archiveSigners) {
            throw UpdateException(UpdateFailure.SIGNATURE)
        }
    }
}
