package com.pittech.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Copies picked photos into app-private storage so cooks remain available offline. */
class PhotoStorage(private val context: Context) {
    suspend fun copyIntoLibrary(
        uriString: String,
        cookId: String,
        dishId: String,
        nowUtcMillis: Long,
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
            PhotoEntity(
                id = id,
                cookId = cookId,
                dishId = dishId,
                originalFileName = displayName(uri) ?: destination.name,
                relativePath = "$PHOTO_DIRECTORY/${destination.name}",
                mimeType = mimeType,
                capturedAtUtcMillis = null,
                addedAtUtcMillis = nowUtcMillis,
            )
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        }
    }

    suspend fun delete(relativePath: String) = withContext(Dispatchers.IO) {
        File(context.filesDir, relativePath).delete()
        Unit
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
