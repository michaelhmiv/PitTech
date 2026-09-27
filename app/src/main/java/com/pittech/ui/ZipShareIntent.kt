package com.pittech.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri

/** Creates a read-only share intent for a ZIP backup provided by FileProvider. */
internal object ZipShareIntent {
    fun create(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("PitTech backup", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
