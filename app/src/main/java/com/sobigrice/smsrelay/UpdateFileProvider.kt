package com.sobigrice.smsrelay

import androidx.core.content.FileProvider

/** Separate provider class keeps installer URI grants limited to the declared update cache. */
class UpdateFileProvider : FileProvider()
