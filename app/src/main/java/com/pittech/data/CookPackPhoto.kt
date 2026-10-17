package com.pittech.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Fresh JPEG pixels preserve orientation and discard the source's EXIF/GPS and embedded previews. */
object CookPackPhoto {
    fun validatePublic(data: ByteArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        require(bounds.outMimeType == "image/jpeg") { "Shared photos must use the portable JPEG format." }
        val exif = ExifInterface(ByteArrayInputStream(data))
        require(listOf(ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_DATESTAMP).all { exif.getAttribute(it) == null }) { "This shared photo contains location metadata." }
        derivative(data)
    }
    fun derivative(original: ByteArray): ByteArray {
        require(original.isNotEmpty() && original.size <= PitTechDataTransfer.MAX_ARCHIVE_ENTRY_BYTES) { "This photo is too large to share." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        require(bounds.outWidth in 1..32768 && bounds.outHeight in 1..32768 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000) { "This attachment is not a supported photo." }
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(original)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(original, 0, original.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("This photo could not be decoded.")
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        return try {
            ByteArrayOutputStream().use { output ->
                check(oriented.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "This photo could not be prepared for sharing." }
                output.toByteArray().also { require(it.size <= 16 * 1024 * 1024) { "This photo is too large to share." } }
            }
        } finally { if (oriented !== decoded) oriented.recycle(); decoded.recycle() }
    }
}
