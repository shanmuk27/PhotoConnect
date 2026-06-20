package com.photoconnect.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import com.bumptech.glide.Glide
import com.photoconnect.R
import com.photoconnect.model.TakerPost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PostDownloadWatermarker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stampFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun savePostImages(context: Context, post: TakerPost) {
        val appContext = context.applicationContext
        val urls = post.images.mapNotNull { image ->
            image.imageUrl.takeIf { it.isNotBlank() }
        }
        if (urls.isEmpty()) {
            Toast.makeText(context, R.string.download_not_available, Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(context, R.string.download_started, Toast.LENGTH_SHORT).show()
        scope.launch {
            urls.forEachIndexed { index, url ->
                runCatching {
                    val target = Glide.with(appContext).asBitmap().load(url).disallowHardwareConfig().submit()
                    try {
                        val bitmap = target.get()
                        val stamped = bitmap.withWatermark(
                            "${appContext.getString(R.string.app_name)} - ${timestamp()}"
                        )
                        saveBitmap(appContext, stamped, "photoconnect_post_${post.id}_${index + 1}.jpg")
                    } finally {
                        Glide.with(appContext).clear(target)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, R.string.download_complete, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun timestamp(): String = synchronized(stampFormatter) {
        stampFormatter.format(Date())
    }

    private fun Bitmap.withWatermark(text: String): Bitmap {
        val output = copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val density = output.density.takeIf { it > 0 } ?: 320
        val textSize = (minOf(width, height) * 0.028f).coerceIn(18f, 34f) * density / 320f
        val padding = (textSize * 0.45f).coerceAtLeast(8f)
        val edge = padding * 1.2f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setShadowLayer(4f, 0f, 1.5f, Color.argb(160, 0, 0, 0))
        }
        val bounds = android.graphics.Rect()
        textPaint.getTextBounds(text, 0, text.length, bounds)

        val left = (width - bounds.width() - padding * 2f - edge).coerceAtLeast(edge)
        val top = height - bounds.height() - padding * 2f - edge
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(92, 0, 0, 0)
        }
        canvas.drawRoundRect(
            left,
            top,
            width - edge,
            height - edge,
            padding,
            padding,
            bgPaint,
        )
        canvas.drawText(text, left + padding, height - edge - padding, textPaint)
        return output
    }

    private fun saveBitmap(context: Context, bitmap: Bitmap, fileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PhotoConnect")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PhotoConnect")
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
        }
    }
}
