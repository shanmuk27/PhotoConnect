package com.photoconnect.workers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.photoconnect.BuildConfig
import com.photoconnect.model.ApiResponse
import com.photoconnect.model.TakerPostData
import com.photoconnect.network.PhotoConnectApiService
import com.photoconnect.utils.PendingPostStore
import com.photoconnect.utils.PhotoAttestation
import com.photoconnect.utils.SessionManager
import androidx.work.WorkInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.work.await
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class PostUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val takerId = inputData.getInt(KEY_TAKER_ID, 0)
        val caption = inputData.getString(KEY_CAPTION).orEmpty()
        val files = inputData.getStringArray(KEY_IMAGE_PATHS)
            ?.map(::File)
            ?.filter { it.exists() && it.length() > 0L }
            .orEmpty()
        val jobDir = inputData.getString(KEY_JOB_DIR)?.let(::File)
        val jobId = inputData.getString(KEY_JOB_ID).orEmpty()

        if (takerId <= 0 || files.isEmpty()) {
            jobDir?.deleteRecursively()
            return@withContext Result.failure()
        }

        try {
            if (jobId.isNotBlank()) PendingPostStore.updateProgress(applicationContext, jobId, 12)
            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            val uploadSession = SessionManager(applicationContext)
            val api = buildApi(uploadSession, moshi)
            val textPlain = "text/plain".toMediaTypeOrNull()
            val takerIdBody = takerId.toString().toRequestBody(textPlain)
            val captionBody = caption.trim().toRequestBody(textPlain)
            val clientUploadIdBody = jobId.takeIf { it.isNotBlank() }?.toRequestBody(textPlain)
            val totalUploadBytes = files.sumOf { it.length().coerceAtLeast(1L) }
            var baseBytes = 0L
            val imageParts = files.mapIndexed { index, file ->
                val mediaType = when (file.extension.lowercase()) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    "heic", "heif" -> "image/heic"
                    else -> "image/jpeg"
                }.toMediaTypeOrNull()
                val body = ProgressFileRequestBody(
                    file = file,
                    mediaType = mediaType,
                    baseBytes = baseBytes,
                    totalBytes = totalUploadBytes,
                    context = applicationContext,
                    jobId = jobId,
                )
                baseBytes += file.length().coerceAtLeast(1L)
                MultipartBody.Part.createFormData(
                    "images[]",
                    "post_${index + 1}.${file.extension.ifBlank { "jpg" }}",
                    body,
                )
            }

            val attestation = PhotoAttestation.buildForFiles(
                applicationContext,
                uploadSession.getAccessToken(),
                takerId,
                files,
            )
            if (attestation == null) {
                val message = "Sync failed: Could not verify photo authenticity. Try an original camera photo from your device."
                if (jobId.isNotBlank()) PendingPostStore.markFailed(applicationContext, jobId, message)
                showFailureNotification(message)
                return@withContext Result.failure()
            }
            val attestationBody = attestation.payloadJson.toRequestBody(textPlain)
            val attestationSigBody = attestation.signatureHex.toRequestBody(textPlain)
            val response = api.uploadTakerPost(
                takerIdBody,
                captionBody,
                clientUploadIdBody,
                attestationBody,
                attestationSigBody,
                imageParts,
            )
            val serverPostId = response.body()?.data?.post?.id ?: 0
            if (response.isSuccessful && response.body()?.success == true) {
                if (jobId.isNotBlank()) PendingPostStore.markUploaded(applicationContext, jobId, serverPostId)
                Result.success()
            } else if (response.code() in 400..499) {
                val message = uploadErrorMessage(response.code(), response.errorBody()?.string(), response.body()?.message)
                if (jobId.isNotBlank()) PendingPostStore.markFailed(applicationContext, jobId, message)
                showFailureNotification(message)
                Result.failure()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            showFailureNotification("Sync failed: Network error occurred")
            Result.retry()
        }
    }

    private fun showFailureNotification(message: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("sync_errors", "Sync Errors", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }
        val canPostNotification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        val notification = NotificationCompat.Builder(applicationContext, "sync_errors")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Sync Error")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        if (canPostNotification) {
            nm.notify(System.currentTimeMillis().toInt(), notification)
        }
        
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun uploadErrorMessage(code: Int, errorBody: String?, bodyMessage: String?): String {
        val serverMessage = bodyMessage
            ?: errorBody?.let { raw ->
                runCatching { JSONObject(raw).optString("message") }.getOrNull()?.takeIf { it.isNotBlank() }
            }
        return serverMessage?.let { "Sync failed: $it" }
            ?: "Sync failed: The server rejected this post ($code). Try another original camera photo."
    }

    private fun buildApi(session: SessionManager, moshi: Moshi): PhotoConnectApiService {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val token = session.getAccessToken()
                val request = if (token.isBlank()) {
                    chain.request()
                } else {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                }
                chain.proceed(request)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(PhotoConnectApiService::class.java)
    }

    companion object {
        private const val KEY_TAKER_ID = "taker_id"
        private const val KEY_CAPTION = "caption"
        private const val KEY_IMAGE_PATHS = "image_paths"
        private const val KEY_JOB_DIR = "job_dir"
        private const val KEY_JOB_ID = "job_id"

        suspend fun enqueue(
            context: Context,
            takerId: Int,
            caption: String?,
            imageUris: List<Uri>,
        ) = withContext(Dispatchers.IO) {
            if (imageUris.size > MAX_IMAGES) {
                error("Only $MAX_IMAGES images are allowed in one post")
            }
            val appContext = context.applicationContext
            val jobId = UUID.randomUUID().toString()
            val jobDir = File(appContext.cacheDir, "post_upload_queue/$jobId").apply { mkdirs() }
            val paths = imageUris.mapIndexedNotNull { index, uri ->
                val copyOriginal = canCopyOriginalUpload(appContext, uri)
                val target = File(
                    jobDir,
                    "image_${index + 1}${if (copyOriginal) extensionForUpload(appContext, uri) else ".jpg"}"
                )
                runCatching {
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output ->
                            if (copyOriginal) {
                                input.copyTo(output)
                            } else {
                                val bitmap = android.graphics.BitmapFactory.decodeStream(input)
                                    ?: return@runCatching null
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 86, output)
                                bitmap.recycle()
                            }
                        }
                    } ?: return@runCatching null
                    target.absolutePath
                }.getOrNull()
            }

            if (paths.isEmpty()) {
                jobDir.deleteRecursively()
                error("Could not prepare selected images")
            }
            PendingPostStore.add(appContext, jobId, takerId, caption, paths)
            PendingPostStore.updateProgress(appContext, jobId, 8)
            scheduleUploadWork(appContext, jobId, takerId, caption, paths, jobDir.absolutePath)
        }

        fun resumePendingUploads(appContext: Context) {
            uploadResumeScope.launch {
                resumePendingUploadsInternal(appContext.applicationContext)
            }
        }

        private suspend fun resumePendingUploadsInternal(appContext: Context) {
            val wm = WorkManager.getInstance(appContext)
            PendingPostStore.getIncompleteUploads(appContext).forEach { pending ->
                val files = pending.imagePaths.map(::File).filter { it.exists() && it.length() > 0L }
                if (files.isEmpty()) {
                    PendingPostStore.remove(appContext, pending.jobId)
                    return@forEach
                }
                val workInfos = wm.getWorkInfosByTag(pending.jobId).await()
                val active = workInfos.any { info ->
                    info.state == WorkInfo.State.ENQUEUED ||
                        info.state == WorkInfo.State.RUNNING ||
                        info.state == WorkInfo.State.BLOCKED
                }
                if (active) return@forEach

                val stalledProgress = pending.progress <= 12
                if (stalledProgress) {
                    PendingPostStore.updateProgress(appContext, pending.jobId, 8)
                }
                val jobDir = files.first().parentFile?.absolutePath.orEmpty()
                scheduleUploadWork(
                    appContext = appContext,
                    jobId = pending.jobId,
                    takerId = pending.takerId,
                    caption = pending.caption,
                    paths = files.map { it.absolutePath },
                    jobDirPath = jobDir,
                    policy = ExistingWorkPolicy.KEEP,
                )
            }
        }

        fun retryPendingUpload(appContext: Context, jobId: String) {
            uploadResumeScope.launch {
                val context = appContext.applicationContext
                val pending = PendingPostStore.getByJobId(context, jobId) ?: return@launch
                retryPendingUploadInternal(context, pending)
            }
        }

        private fun retryPendingUploadInternal(appContext: Context, pending: com.photoconnect.utils.PendingPostUpload) {
            val files = pending.imagePaths.map(::File).filter { it.exists() && it.length() > 0L }
            if (files.isEmpty()) {
                PendingPostStore.remove(appContext, pending.jobId)
                return
            }
            PendingPostStore.markRetrying(appContext, pending.jobId)
            WorkManager.getInstance(appContext).cancelAllWorkByTag(pending.jobId)
            scheduleUploadWork(
                appContext = appContext,
                jobId = pending.jobId,
                takerId = pending.takerId,
                caption = pending.caption,
                paths = files.map { it.absolutePath },
                jobDirPath = files.first().parentFile?.absolutePath.orEmpty(),
                policy = ExistingWorkPolicy.REPLACE,
            )
        }

        private fun scheduleUploadWork(
            appContext: Context,
            jobId: String,
            takerId: Int,
            caption: String?,
            paths: List<String>,
            jobDirPath: String,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        ) {
            val request = OneTimeWorkRequestBuilder<PostUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .addTag(jobId)
                .addTag(UPLOAD_TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(
                    Data.Builder()
                        .putInt(KEY_TAKER_ID, takerId)
                        .putString(KEY_CAPTION, caption.orEmpty())
                        .putStringArray(KEY_IMAGE_PATHS, paths.toTypedArray())
                        .putString(KEY_JOB_DIR, jobDirPath)
                        .putString(KEY_JOB_ID, jobId)
                        .build(),
                )
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                uniqueWorkName(jobId),
                policy,
                request,
            )
        }

        const val MAX_IMAGES = 8
        const val UPLOAD_TAG = "post_upload"
        private val uploadResumeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private fun uniqueWorkName(jobId: String): String = "$UPLOAD_TAG:$jobId"

        private fun extensionForUpload(context: Context, uri: Uri): String {
            val mime = context.contentResolver.getType(uri)?.lowercase(Locale.US).orEmpty()
            return when {
                mime == "image/png" -> ".png"
                mime == "image/webp" -> ".webp"
                mime == "image/jpeg" -> ".jpg"
                else -> {
                    val fromName = uri.lastPathSegment
                        ?.substringAfterLast('.', "")
                        ?.lowercase(Locale.US)
                        ?.takeIf { it in setOf("jpg", "jpeg", "png", "webp") }
                    if (fromName == null) ".jpg" else ".$fromName"
                }
            }
        }

        private fun canCopyOriginalUpload(context: Context, uri: Uri): Boolean {
            val mime = context.contentResolver.getType(uri)?.lowercase(Locale.US).orEmpty()
            if (mime in setOf("image/jpeg", "image/png", "image/webp")) {
                return true
            }
            val extension = uri.lastPathSegment
                ?.substringAfterLast('.', "")
                ?.lowercase(Locale.US)
                .orEmpty()
            return extension in setOf("jpg", "jpeg", "png", "webp")
        }
    }
}

private class ProgressFileRequestBody(
    private val file: File,
    private val mediaType: MediaType?,
    private val baseBytes: Long,
    private val totalBytes: Long,
    private val context: Context,
    private val jobId: String,
) : RequestBody() {
    override fun contentType(): MediaType? = mediaType
    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        var sentForFile = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
                sentForFile += read
                if (jobId.isNotBlank() && totalBytes > 0L) {
                    val uploadFraction = (baseBytes + sentForFile).toDouble() / totalBytes.toDouble()
                    val progress = 15 + (uploadFraction * 80).toInt()
                    PendingPostStore.updateProgress(context, jobId, progress)
                }
            }
        }
    }
}
