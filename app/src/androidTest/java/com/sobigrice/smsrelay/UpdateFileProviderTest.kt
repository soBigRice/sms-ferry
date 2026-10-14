package com.sobigrice.smsrelay

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdateFileProviderTest {
    @Test fun installerCanReadUpdateCacheButOtherPrivateFilesHaveNoUri() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val updates = File(context.cacheDir, "updates").apply { mkdirs() }
        val allowed = File.createTempFile("provider-test-", ".apk", updates)
        val outside = File.createTempFile("provider-test-", ".txt", context.cacheDir)
        try {
            allowed.writeText("isolated installer payload")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", allowed)
            assertEquals("content", uri.scheme)
            assertEquals("isolated installer payload", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            try {
                FileProvider.getUriForFile(context, "${context.packageName}.updates", outside)
                fail("Files outside the update cache must not be shareable")
            } catch (_: IllegalArgumentException) { /* Expected path boundary. */ }
        } finally { allowed.delete(); outside.delete() }
    }
}
