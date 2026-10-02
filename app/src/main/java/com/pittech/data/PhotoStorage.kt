package com.pittech.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import android.media.ExifInterface
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Copies picked photos into app-private storage so cooks remain available offline. */
class PhotoStorage(private val context: Context) {
    suspend fun copyIntoLibrary(
        uriString: String,
        cookId: String,
        dishId: String?,
        nowUtcMillis: Long,
        eventId: String? = null,
        caption: String? = null,
    ): PhotoEntity = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "jpg"
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, PHOTO_DIRECTORY).apply { mkdirs() }
        val destination = File(directory, "$id.$extension")

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("The selected photo could not be opened")
            input.use { source ->
                destination.outputStream().use { output -> source.copyTo(output) }
            }
            val photo = PhotoEntity(
                id = id,
                cookId = cookId,
                dishId = dishId,
                originalFileName = displayName(uri) ?: destination.name,
                relativePath = "$PHOTO_DIRECTORY/${destination.name}",
                mimeType = mimeType,
                eventId = eventId,
                caption = caption?.trim()?.ifBlank { null },
                capturedAtUtcMillis = CameraPhotoFiles.capturedAt(context, uri) ?: captureTime(destination),
                addedAtUtcMillis = nowUtcMillis,
            )
            CameraPhotoFiles.delete(context, uri)
            photo
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        }
    }

    suspend fun delete(relativePath: String) = withContext(Dispatchers.IO) {
        File(context.filesDir, relativePath).delete()
        Unit
    }

    /** EXIF dates without an offset are ambiguous. Never substitute the import time. */
    private fun captureTime(file: File): Long? = runCatching {
        val exif = ExifInterface(file.absolutePath)
        val date = exif.getAttribute("DateTimeOriginal") ?: return null
        val offset = exif.getAttribute("OffsetTimeOriginal") ?: return null
        LocalDateTime.parse(date, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
            .toInstant(ZoneOffset.of(offset)).toEpochMilli().takeIf { it > 0 && it <= System.currentTimeMillis() + 60_000L }
    }.getOrNull()

    suspend fun writeImported(relativePath: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        require(relativePath.matches(Regex("photos/[A-Za-z0-9_-]{1,80}\\.[A-Za-z0-9]{1,8}"))) { "Invalid photo path in archive." }
        val destination = File(context.filesDir, relativePath)
        val directory = destination.parentFile ?: error("Invalid photo destination.")
        check(directory.exists() || directory.mkdirs()) { "Could not create the photo library." }
        val temporary = File(directory, "${destination.name}.tmp-${UUID.randomUUID()}")
        try {
            temporary.outputStream().use { it.write(bytes) }
            check(temporary.renameTo(destination)) { "Could not finish restoring a photo." }
        } finally {
            temporary.delete()
        }
    }

    suspend fun read(relativePath: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, relativePath)
        if (file.isFile) file.readBytes() else null
    }

    private fun displayName(uri: Uri): String? = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        } else {
            null
        }
    }

    companion object {
        private const val PHOTO_DIRECTORY = "photos"
    }
}
