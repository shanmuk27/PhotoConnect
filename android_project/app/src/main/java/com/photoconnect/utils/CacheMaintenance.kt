package com.photoconnect.utils

import android.content.Context
import java.io.File

object CacheMaintenance {
    private const val MAX_CACHE_BYTES = 24L * 1024L * 1024L
    private const val MAX_FILE_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    fun pruneAsync(context: Context) {
        val appContext = context.applicationContext
        Thread {
            pruneDirectory(appContext.cacheDir)
            appContext.externalCacheDir?.let(::pruneDirectory)
        }.apply {
            name = "pc-cache-prune"
            isDaemon = true
            start()
        }
    }

    private fun pruneDirectory(root: File) {
        if (!root.exists()) return
        val now = System.currentTimeMillis()
        val files = root.walkTopDown()
            .filter { it.isFile }
            .toList()

        files.forEach { file ->
            if (now - file.lastModified() > MAX_FILE_AGE_MS) {
                runCatching { file.delete() }
            }
        }

        val remaining = root.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.lastModified() }
            .toMutableList()
        var totalBytes = remaining.sumOf { it.length() }
        for (file in remaining) {
            if (totalBytes <= MAX_CACHE_BYTES) break
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                totalBytes -= size
            }
        }

        root.walkBottomUp()
            .filter { it.isDirectory && it != root && it.listFiles()?.isEmpty() == true }
            .forEach { runCatching { it.delete() } }
    }
}
