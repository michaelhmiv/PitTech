package com.pittech.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Creates and cleans temporary images supplied to the system camera app. */
object CameraPhotoFiles {
    private const val CAPTURE_DIRECTORY = "camera-photos"

    fun create(context: Context): File {
        val directory = File(context.cacheDir, CAPTURE_DIRECTORY)
        check(directory.exists() || directory.mkdirs()) { "Could not create a temporary photo directory." }
        return File.createTempFile("pittech_camera_", ".jpg", directory)
    }

    fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    fun capturedAt(context: Context, uri: Uri): Long? {
        if (uri.authority != context.packageName + ".fileprovider") return null
        val parts = uri.pathSegments
        if (parts.size != 2 || parts[0] != CAPTURE_DIRECTORY || !parts[1].matches(Regex("pittech_camera_[A-Za-z0-9_-]+\\.jpg"))) return null
        val file = File(File(context.cacheDir, CAPTURE_DIRECTORY), parts[1])
        return file.takeIf { it.isFile && it.length() > 0 }?.lastModified()?.takeIf { it > 0 }
    }

    fun delete(context: Context, uriString: String) = delete(context, Uri.parse(uriString))

    fun delete(context: Context, uri: Uri) {
        if (uri.authority != context.packageName + ".fileprovider") return
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != CAPTURE_DIRECTORY) return
        val name = segments[1]
        if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) return
        File(File(context.cacheDir, CAPTURE_DIRECTORY), name).delete()
    }
}
