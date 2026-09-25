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