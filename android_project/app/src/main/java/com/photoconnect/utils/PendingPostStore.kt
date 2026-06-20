package com.photoconnect.utils

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class PendingPostUpload(
    val jobId: String,
    val takerId: Int,
    val caption: String?,
    val imagePaths: List<String>,
    val createdAt: Long,
    val uploaded: Boolean = false,
    val progress: Int = 1,
    val serverPostId: Int = 0,
    val failed: Boolean = false,
    val errorMessage: String? = null,
)

object PendingPostStore {
    private const val PREFS = "pc_pending_post_uploads"
    private const val KEY_ITEMS = "items"

    fun add(
        context: Context,
        jobId: String,
        takerId: Int,
        caption: String?,
        imagePaths: List<String>,
    ) {
        val next = getAll(context)
            .filterNot { it.jobId == jobId }
            .plus(PendingPostUpload(jobId, takerId, caption, imagePaths, System.currentTimeMillis()))
        save(context, next)
    }

    fun updateProgress(context: Context, jobId: String, progress: Int) {
        val safeProgress = progress.coerceIn(1, 100)
        save(context, getAll(context).map {
            if (it.jobId == jobId) it.copy(progress = safeProgress, failed = false, errorMessage = null) else it
        })
    }

    fun markUploaded(context: Context, jobId: String, serverPostId: Int) {
        save(context, getAll(context).map {
            if (it.jobId == jobId) it.copy(uploaded = true, progress = 100, serverPostId = serverPostId, failed = false, errorMessage = null) else it
        })
    }

    fun markFailed(context: Context, jobId: String, message: String) {
        save(context, getAll(context).map {
            if (it.jobId == jobId) it.copy(failed = true, errorMessage = message, progress = 1) else it
        })
    }

    fun markRetrying(context: Context, jobId: String) {
        save(context, getAll(context).map {
            if (it.jobId == jobId) it.copy(failed = false, errorMessage = null, progress = 8) else it
        })
    }

    fun reconcileServerPosts(context: Context, takerId: Int, serverPostIds: Set<Int>) {
        val items = getAll(context)
        val removed = items.filter { pending ->
            pending.takerId == takerId &&
                pending.uploaded &&
                pending.serverPostId > 0 &&
                serverPostIds.contains(pending.serverPostId)
        }
        removed.forEach(::deleteFiles)
        save(context, items - removed.toSet())
    }

    fun clear(context: Context) {
        val items = getAll(context)
        items.forEach(::deleteFiles)
        save(context, emptyList())
    }

    fun remove(context: Context, jobId: String) {
        save(context, getAll(context).filterNot { it.jobId == jobId })
    }

    fun removeAndDeleteFiles(context: Context, jobId: String) {
        val item = getAll(context).firstOrNull { it.jobId == jobId }
        item?.let(::deleteFiles)
        remove(context, jobId)
    }

    fun getForTaker(context: Context, takerId: Int): List<PendingPostUpload> =
        getAll(context)
            .filter { it.takerId == takerId && it.imagePaths.isNotEmpty() }
            .sortedByDescending { it.createdAt }

    fun getByJobId(context: Context, jobId: String): PendingPostUpload? =
        getAll(context).firstOrNull { it.jobId == jobId }

    fun getIncompleteUploads(context: Context): List<PendingPostUpload> =
        getAll(context)
            .filter { item ->
                !item.uploaded &&
                    !item.failed &&
                    item.imagePaths.isNotEmpty() &&
                    item.imagePaths.any { path -> File(path).exists() }
            }
            .sortedBy { it.createdAt }

    private fun getAll(context: Context): List<PendingPostUpload> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, "[]")
            .orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val paths = item.optJSONArray("image_paths") ?: JSONArray()
                    add(
                        PendingPostUpload(
                            jobId = item.optString("job_id"),
                            takerId = item.optInt("taker_id"),
                            caption = item.optString("caption").takeIf { it.isNotBlank() },
                            imagePaths = buildList {
                                for (pathIndex in 0 until paths.length()) {
                                    paths.optString(pathIndex).takeIf { it.isNotBlank() }?.let(::add)
                                }
                            },
                            createdAt = item.optLong("created_at"),
                            uploaded = item.optBoolean("uploaded", false),
                            progress = item.optInt("progress", if (item.optBoolean("uploaded", false)) 100 else 1),
                            serverPostId = item.optInt("server_post_id", 0),
                            failed = item.optBoolean("failed", false),
                            errorMessage = item.optString("error_message").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, items: List<PendingPostUpload>) {
        val array = JSONArray()
        items.takeLast(20).forEach { item ->
            array.put(
                JSONObject()
                    .put("job_id", item.jobId)
                    .put("taker_id", item.takerId)
                    .put("caption", item.caption.orEmpty())
                    .put("created_at", item.createdAt)
                    .put("uploaded", item.uploaded)
                    .put("progress", item.progress)
                    .put("server_post_id", item.serverPostId)
                    .put("failed", item.failed)
                    .put("error_message", item.errorMessage.orEmpty())
                    .put("image_paths", JSONArray(item.imagePaths))
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }

    private fun deleteFiles(item: PendingPostUpload) {
        item.imagePaths.forEach { path -> runCatching { File(path).delete() } }
        item.imagePaths.firstOrNull()?.let { path ->
            runCatching { File(path).parentFile?.deleteRecursively() }
        }
    }
}
