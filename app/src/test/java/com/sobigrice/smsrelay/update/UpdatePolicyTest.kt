package com.sobigrice.smsrelay.update

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class UpdatePolicyTest {
    private val digest = "a".repeat(64)
    private fun release(version: String = "2.10.0", debug: Boolean = true): AppRelease {
        val name = UpdatePolicy.assetName(version, debug)
        return AppRelease(version, "v$version", "", true, name,
            "https://github.com/soBigRice/sms-ferry/releases/download/v$version/$name", 10, digest)
    }

    @Test fun semanticVersionsCompareNumericComponentsAndPrereleaseIdentifiers() {
        assertTrue(version("2.10.0") > version("2.9.0"))
        assertTrue(version("2.10.0-beta.10") > version("2.10.0-beta.9"))
        assertTrue(version("2.10.0") > version("2.10.0-rc.1"))
        assertTrue(version("2.10.0-beta.a") > version("2.10.0-beta.99"))
        assertEquals(0, version("2.10.0+build.2").compareTo(version("2.10.0+build.1")))
    }

    @Test fun unknownVersionFormatsAreRejectedRatherThanGuessed() {
        listOf("2.10", "02.10.0", "2.10.0-beta.01", "latest", "v2.10.0", "2.10.0-")
            .forEach { assertNull(it, SemanticVersion.parse(it)) }
        assertEquals("2.10.0-beta.1", UpdatePolicy.versionFromTag("v2.10.0-beta.1"))
    }

    @Test fun noSameVersionDowngradeOrWrongChannelCanBeDownloaded() {
        UpdatePolicy.validateRelease(release(), "2.9.0", true)
        fails(UpdateFailure.NOT_NEWER) { UpdatePolicy.validateRelease(release(), "2.10.0", true) }
        fails(UpdateFailure.NOT_NEWER) { UpdatePolicy.validateRelease(release(), "3.0.0", true) }
        fails(UpdateFailure.CHANNEL) { UpdatePolicy.validateRelease(release(debug = false), "2.9.0", true) }
        fails(UpdateFailure.CHANNEL) { UpdatePolicy.validateRelease(release(), "2.9.0", false) }
        UpdatePolicy.validateRelease(release(debug = false), "2.9.0", false)
    }

    @Test fun tagAssetSizeAndDigestAreAllRequired() {
        val valid = release()
        fails(UpdateFailure.INVALID_METADATA) { UpdatePolicy.validateRelease(valid.copy(tag = "v3.0.0"), "2.9.0", true) }
        fails(UpdateFailure.MAX_SIZE) { UpdatePolicy.validateRelease(valid.copy(size = 0), "2.9.0", true) }
        fails(UpdateFailure.MAX_SIZE) { UpdatePolicy.validateRelease(valid.copy(size = UpdatePolicy.MAX_APK_BYTES + 1), "2.9.0", true) }
        listOf("", "null", "sha256:abc", "md5:$digest").forEach {
            fails(UpdateFailure.MISSING_DIGEST) { UpdatePolicy.checksumFromDigest(it) }
        }
        assertEquals(digest, UpdatePolicy.checksumFromDigest("sha256:${digest.uppercase()}"))
    }

    @Test fun assetUrlCannotEscapeFixedRepositoryTagAssetOrHttpsOrigin() {
        val valid = release()
        listOf(
            valid.downloadUrl.replace("https:", "http:"),
            valid.downloadUrl.replace("github.com/", "github.com.evil.example/"),
            valid.downloadUrl.replace("github.com/", "evil.example@github.com/"),
            valid.downloadUrl.replace("sms-ferry/releases", "another-repo/releases"),
            valid.downloadUrl.replace("download/v2.10.0/", "download/v3.0.0/"),
            valid.downloadUrl + "?redirect=https://evil.example",
            valid.downloadUrl + "#fragment",
        ).forEach { url ->
            fails(UpdateFailure.INVALID_URL) { UpdatePolicy.validateRelease(valid.copy(downloadUrl = url), "2.9.0", true) }
        }
    }

    @Test fun onlyTheGithubReleaseCdnMayReceiveAssetRedirects() {
        UpdatePolicy.validateAssetRedirect("https://release-assets.githubusercontent.com/github-production-release-asset/123/abc?token=public-signed-token")
        listOf(
            "http://release-assets.githubusercontent.com/github-production-release-asset/123/abc",
            "https://evil.example/github-production-release-asset/123/abc",
            "https://release-assets.githubusercontent.com.evil.example/github-production-release-asset/123/abc",
            "https://release-assets.githubusercontent.com:443/github-production-release-asset/123/abc",
            "https://release-assets.githubusercontent.com/unrelated/123/abc",
        ).forEach { fails(UpdateFailure.REDIRECT) { UpdatePolicy.validateAssetRedirect(it) } }
    }

    @Test fun byteCountAndSha256MustBothMatch() {
        val payload = "verified APK bytes".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(payload)
        val expected = hash.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        UpdatePolicy.verifyPayload(payload.size.toLong(), payload.size.toLong(), hash, expected)
        fails(UpdateFailure.INCOMPLETE) { UpdatePolicy.verifyPayload(1, 2, hash, expected) }
        fails(UpdateFailure.CHECKSUM) { UpdatePolicy.verifyPayload(2, 2, hash, digest) }
    }

    @Test fun archiveCannotChangePackageSignerVersionNameOrDowngradeCode() {
        fun verify(pkg: String = "com.example", code: Long = 8, name: String = "2.10.0", signers: Set<String> = setOf("current")) =
            UpdatePolicy.verifyArchive("com.example", pkg, 7, code, "2.10.0", name, setOf("current"), signers)
        verify()
        fails(UpdateFailure.PACKAGE) { verify(pkg = "com.evil") }
        fails(UpdateFailure.VERSION_CODE) { verify(code = 7) }
        fails(UpdateFailure.VERSION_CODE) { verify(code = 6) }
        fails(UpdateFailure.VERSION_NAME) { verify(name = "2.11.0") }
        fails(UpdateFailure.SIGNATURE) { verify(signers = setOf("new")) }
        fails(UpdateFailure.SIGNATURE) { verify(signers = emptySet()) }
        fails(UpdateFailure.SIGNATURE) { verify(signers = setOf("current", "new")) }
    }

    private fun version(value: String) = SemanticVersion.parse(value)!!
    private fun fails(expected: UpdateFailure, block: () -> Unit) {
        try { block(); fail("Expected $expected") }
        catch (error: UpdateException) { assertEquals(expected, error.reason) }
    }
}
