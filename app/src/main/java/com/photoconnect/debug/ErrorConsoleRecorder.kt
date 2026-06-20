package com.photoconnect.debug

import android.content.Context
import android.util.Log
import com.photoconnect.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Append-only rolling log in app files dir for copy/paste debugging.
 * Not for secrets/PII — avoid logging tokens/passwords.
 */
object ErrorConsoleRecorder {
    private const val FILE_NAME = "error_console.log"
    private const val MAX_BYTES = 200_000
    private val lock = Any()
    private val appRef = AtomicReference<Context?>()

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(appContext: Context) {
        appRef.set(appContext.applicationContext)
    }

    private fun contextOrNull(): Context? = appRef.get()

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        append("D", tag, message, null)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        append("E", tag, message, throwable)
    }

    fun appendUncaught(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val body = buildString {
            append("UNCAUGHT thread=").append(thread.name).append('\n')
            append(sw.toString()).append('\n')
        }
        appendForced("F", "UncaughtException", body, null)
    }

    private fun append(level: String, tag: String, message: String, throwable: Throwable?) {
        if (!BuildConfig.DEBUG) return
        appendForced(level, tag, message, throwable)
    }

    /** Always writes (used for fatal crashes). */
    private fun appendForced(level: String, tag: String, message: String, throwable: Throwable?) {
        val ctx = contextOrNull() ?: return
        synchronized(lock) {
            try {
                val file = File(ctx.filesDir, FILE_NAME)
                val ts = timeFmt.format(Date())
                val sb = StringBuilder()
                sb.append(ts).append(' ').append(level).append('/').append(tag).append(": ").append(message).append('\n')
                if (throwable != null) {
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    sb.append(sw.toString()).append('\n')
                }
                file.appendText(sb.toString())
                trimIfNeeded(file)
            } catch (e: Exception) {
                Log.w("ErrorConsoleRecorder", "append failed", e)
            }
        }
    }

    private fun trimIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_BYTES) return
        val keep = (MAX_BYTES * 8) / 10
        val text = file.readText()
        file.writeText(text.takeLast(keep))
    }

    fun readAll(context: Context): String {
        val f = File(context.filesDir, FILE_NAME)
        if (!f.exists()) return "(empty log)"
        return try {
            f.readText()
        } catch (e: Exception) {
            "Could not read log: ${e.message}"
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            try {
                File(context.filesDir, FILE_NAME).delete()
            } catch (_: Exception) {}
        }
    }
}
