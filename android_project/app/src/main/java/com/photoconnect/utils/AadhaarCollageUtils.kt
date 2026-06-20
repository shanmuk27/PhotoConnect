package com.photoconnect.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AadhaarCollageUtils {

    suspend fun createCollage(context: Context, frontUri: Uri, backUri: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            val frontBitmap = getBitmapFromUri(context, frontUri) ?: return@withContext null
            val backBitmap = getBitmapFromUri(context, backUri) ?: return@withContext null

            // Scale to match heights if they differ significantly, taking the max height
            val targetHeight = maxOf(frontBitmap.height, backBitmap.height)
            
            val scaledFront = scaleBitmapToHeight(frontBitmap, targetHeight)
            val scaledBack = scaleBitmapToHeight(backBitmap, targetHeight)

            // Add padding between images
            val padding = 20
            val totalWidth = scaledFront.width + scaledBack.width + padding

            // Create combined bitmap
            val combinedBitmap = Bitmap.createBitmap(totalWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(combinedBitmap)
            canvas.drawColor(Color.WHITE) // White background

            canvas.drawBitmap(scaledFront, 0f, 0f, null)
            canvas.drawBitmap(scaledBack, (scaledFront.width + padding).toFloat(), 0f, null)

            // Save to temp file
            val dir = verificationMediaDirectory(context, "verification_collages")
            val file = File.createTempFile("aadhaar_collage_", ".jpg", dir)
            val out = FileOutputStream(file)
            combinedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.flush()
            out.close()

            // Cleanup
            if (scaledFront != frontBitmap) scaledFront.recycle()
            if (scaledBack != backBitmap) scaledBack.recycle()
            frontBitmap.recycle()
            backBitmap.recycle()
            combinedBitmap.recycle()

            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    
    suspend fun createVerticalCollage(context: Context, topUri: Uri, bottomUri: Uri): Uri? {
        return withContext(Dispatchers.IO) {
            val topBitmap = getBitmapFromUri(context, topUri) ?: return@withContext null
            val bottomBitmap = getBitmapFromUri(context, bottomUri) ?: return@withContext null

            // Scale to match widths if they differ significantly, taking the max width
            val targetWidth = maxOf(topBitmap.width, bottomBitmap.width)
            
            val scaledTop = scaleBitmapToWidth(topBitmap, targetWidth)
            val scaledBottom = scaleBitmapToWidth(bottomBitmap, targetWidth)

            // Add padding between images
            val padding = 20
            val totalHeight = scaledTop.height + scaledBottom.height + padding

            // Create combined bitmap
            val combinedBitmap = Bitmap.createBitmap(targetWidth, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(combinedBitmap)
            canvas.drawColor(Color.WHITE) // White background

            canvas.drawBitmap(scaledTop, 0f, 0f, null)
            canvas.drawBitmap(scaledBottom, 0f, (scaledTop.height + padding).toFloat(), null)

            // Save to temp file
            val dir = verificationMediaDirectory(context, "verification_collages")
            val file = File(dir, "vertical_collage_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                combinedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }

            // Clean up memory
            if (topBitmap != scaledTop) topBitmap.recycle()
            if (bottomBitmap != scaledBottom) bottomBitmap.recycle()
            scaledTop.recycle()
            scaledBottom.recycle()
            combinedBitmap.recycle()

            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    }

    private fun scaleBitmapToWidth(bitmap: Bitmap, targetWidth: Int): Bitmap {
        if (bitmap.width == targetWidth) return bitmap
        val scale = targetWidth.toFloat() / bitmap.width.toFloat()
        val matrix = Matrix()
        matrix.postScale(scale, scale)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleBitmapToHeight(bitmap: Bitmap, targetHeight: Int): Bitmap {
        if (bitmap.height == targetHeight) return bitmap
        val scale = targetHeight.toFloat() / bitmap.height.toFloat()
        val matrix = Matrix()
        matrix.postScale(scale, scale)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun getBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun verificationMediaDirectory(context: Context, name: String): File {
        val externalRoot = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val base = externalRoot ?: File(context.filesDir, "Pictures")
        return File(base, name).apply { mkdirs() }
    }
}
