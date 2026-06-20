package com.photoconnect.utils

import android.content.ContentResolver
import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.util.Locale

object ExifUtils {
    /**
     * Treats an upload as valid when it still has camera EXIF, Android's local gallery
     * metadata says it came from the device camera/DCIM collection, or it is a shared
     * image document that still carries real camera metadata.
     */
    fun isOriginalCameraPhoto(context: Context, uri: Uri): Boolean {
        return try {
            isSupportedImageFile(context, uri) &&
                (hasCameraExif(context, uri) ||
                    hasLocalCameraMediaEvidence(context, uri))
        } catch (e: Exception) {
            false
        }
    }

    private fun hasCameraExif(context: Context, uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return true
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                val capturedAt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                !make.isNullOrBlank() || !model.isNullOrBlank() || !capturedAt.isNullOrBlank()
            } ?: false
        }.getOrDefault(false)
    }

    private fun hasLocalCameraMediaEvidence(context: Context, uri: Uri): Boolean {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return isCameraLikePath(uri.path.orEmpty())
        }
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return false
        }

        val projection = buildList {
            add(MediaStore.Images.ImageColumns.DATE_TAKEN)
            add(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)
            add(MediaStore.Images.ImageColumns.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.ImageColumns.RELATIVE_PATH)
            }
        }.toTypedArray()

        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                val dateTaken = cursor.longValue(MediaStore.Images.ImageColumns.DATE_TAKEN)
                val bucket = cursor.stringValue(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)
                val displayName = cursor.stringValue(MediaStore.Images.ImageColumns.DISPLAY_NAME)
                val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.stringValue(MediaStore.Images.ImageColumns.RELATIVE_PATH)
                } else {
                    ""
                }
                dateTaken > 0 && listOf(relativePath, bucket, displayName).any(::isCameraLikePath)
            } ?: false
        }.getOrDefault(false)
    }

    private fun isSupportedImageFile(context: Context, uri: Uri): Boolean {
        val mime = context.contentResolver.getType(uri)?.lowercase(Locale.US).orEmpty()
        if (mime.startsWith("image/")) return true

        val ext = listOf(
            displayName(context, uri),
            uri.lastPathSegment.orEmpty(),
            uri.path.orEmpty(),
        ).firstNotNullOfOrNull { value ->
            value.substringAfterLast('.', "")
                .lowercase(Locale.US)
                .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "heic", "heif") }
        }
        if (ext != null) return true

        return hasImageMagicBytes(context, uri)
    }

    private fun hasImageMagicBytes(context: Context, uri: Uri): Boolean =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(16)
                val count = input.read(header)
                if (count < 4) return@use false
                val isJpeg = header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()
                val isPng = header[0] == 0x89.toByte() &&
                    header[1] == 0x50.toByte() &&
                    header[2] == 0x4E.toByte() &&
                    header[3] == 0x47.toByte()
                val isWebp = count >= 12 &&
                    String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
                isJpeg || isPng || isWebp
            } ?: false
        }.getOrDefault(false)

    private fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.stringValue(OpenableColumns.DISPLAY_NAME)
                    } else {
                        ""
                    }
                }.orEmpty()
        }.getOrDefault("")

    private fun android.database.Cursor.stringValue(column: String): String {
        val index = getColumnIndex(column)
        return if (index >= 0) getString(index).orEmpty() else ""
    }

    private fun android.database.Cursor.longValue(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0) getLong(index) else 0L
    }

    private fun isCameraLikePath(value: String): Boolean {
        val normalized = value.lowercase(Locale.US).replace('\\', '/')
        return normalized.contains("/dcim/") ||
            normalized.contains("dcim/camera") ||
            normalized.contains("/camera/") ||
            normalized == "camera" ||
            normalized.endsWith("/camera")
    }
}
