package com.sobigrice.smsrelay.update

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReleaseParserTest {
    private val digest = "a".repeat(64)

    private fun release(version: String, debug: Boolean = true, prerelease: Boolean = true): JSONObject {
        val name = UpdatePolicy.assetName(version, debug)
        val asset = JSONObject().put("name", name).put("state", "uploaded").put("size", 10)
            .put("digest", "sha256:$digest")
            .put("browser_download_url", "https://github.com/soBigRice/sms-ferry/releases/download/v$version/$name")
        return JSONObject().put("tag_name", "v$version").put("draft", false).put("prerelease", prerelease)
            .put("body", "Release notes").put("assets", JSONArray().put(asset))
    }

    @Test fun selectsNewestSemverIncludingPreviewEvenIfApiOrderIsDifferent() {
        val json = JSONArray().put(release("2.9.0")).put(release("2.10.0-beta.2"))
            .put(release("2.10.0-beta.10")).toString()
        val result = ReleaseParser.select(json, "2.8.0", true)!!
        assertEquals("2.10.0-beta.10", result.version)
        assertTrue(result.prerelease)
        assertEquals("Release notes", result.notes)
    }

    @Test fun alreadyCurrentOlderDraftAndWrongChannelProduceNoUpdate() {
        assertNull(ReleaseParser.select(JSONArray().put(release("2.10.0")).toString(), "2.10.0", true))
        assertNull(ReleaseParser.select(JSONArray().put(release("2.9.0")).toString(), "2.10.0", true))
        assertNull(ReleaseParser.select(JSONArray().put(release("3.0.0", debug = false)).toString(), "2.10.0", true))
        assertNull(ReleaseParser.select(JSONArray().put(release("3.0.0").put("draft", true)).toString(), "2.10.0", true))
    }

    @Test fun missingDigestAndMaliciousDownloadUrlsAreRejected() {
        val missing = release("2.10.0")
        missing.getJSONArray("assets").getJSONObject(0).remove("digest")
        fails(UpdateFailure.MISSING_DIGEST) { ReleaseParser.select(JSONArray().put(missing).toString(), "2.9.0", true) }
        val badUrl = release("2.10.0")
        badUrl.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.example/app.apk")
        fails(UpdateFailure.INVALID_URL) { ReleaseParser.select(JSONArray().put(badUrl).toString(), "2.9.0", true) }
    }

    @Test fun malformedMetadataAndFractionalSizesAreRejected() {
        fails(UpdateFailure.INVALID_METADATA) { ReleaseParser.select("{}", "2.9.0", true) }
        val badSize = release("2.10.0")
        badSize.getJSONArray("assets").getJSONObject(0).put("size", 10.5)
        fails(UpdateFailure.INVALID_METADATA) { ReleaseParser.select(JSONArray().put(badSize).toString(), "2.9.0", true) }
    }

    @Suppress("DEPRECATION")
    @Test fun androidParsesSignerMetadataFromPartialApkBeforeReadyRename() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("ota-verification-", ".partial", context.cacheDir)
        try {
            File(context.applicationInfo.sourceDir).copyTo(file, overwrite = true)
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)!!
            assertEquals(context.packageName, archive.packageName)
            val installed = context.packageManager.getPackageInfo(context.packageName, flags)
            val actual = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo!!.apkContentsSigners else archive.signatures!!
            val expected = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo!!.apkContentsSigners else installed.signatures!!
            assertEquals(expected.map { it.toCharsString() }.toSet(), actual.map { it.toCharsString() }.toSet())
        } finally { file.delete() }
    }

    private fun fails(expected: UpdateFailure, block: () -> Unit) {
        try { block(); fail("Expected $expected") }
        catch (error: UpdateException) { assertEquals(expected, error.reason) }
    }
}
