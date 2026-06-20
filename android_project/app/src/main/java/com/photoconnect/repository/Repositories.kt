package com.photoconnect.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.photoconnect.R
import com.photoconnect.db.*
import com.photoconnect.model.*
import com.photoconnect.network.*
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.nio.charset.StandardCharsets
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(
        val message: String,
        val code: Int = 0,
        val retryAfterSeconds: Int? = null,
        val field: String? = null,
    ) : Result<Nothing>()
    object Loading : Result<Nothing>()
}

private fun pc_extract_json_message(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return null
    // Server error responses are JSON: {"success":false,"message":"...","data":[]}
    // Retrofit doesn't parse error bodies into ApiResponse, so we extract message manually.
    val m = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(text) ?: return null
    return m.groupValues.getOrNull(1)
        ?.replace("\\n", "\n")
        ?.replace("\\\"", "\"")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

private fun pc_extract_json_int(raw: String?, key: String): Int? {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return null
    val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(\\d+)").find(text) ?: return null
    return match.groupValues.getOrNull(1)?.toIntOrNull()
}

private fun pc_extract_json_string(raw: String?, key: String): String? {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return null
    val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"").find(text) ?: return null
    return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
}

private fun pc_user_facing_http_error(code: Int): String =
    when (code) {
        in 500..599 -> "We're having trouble reaching the server. Please try again in a moment."
        408, 429 -> "The connection is busy right now. Please try again in a moment."
        else -> "Something went wrong. Please try again."
    }

private fun pc_user_facing_exception(e: Exception): String =
    when (e) {
        is UnknownHostException,
        is ConnectException,
        is SocketTimeoutException,
        is IOException -> "You're offline or the server is unreachable. Please check your connection and try again."
        else -> e.localizedMessage ?: "Network error"
    }

private suspend fun <T> safe(call: suspend () -> Response<ApiResponse<T>>): Result<T> =
    try {
        val r = call()
        val body = r.body()
        if (r.isSuccessful) {
            if (body?.success == true && body.data != null) {
                Result.Success(body.data)
            } else {
                Result.Error(body?.message ?: pc_user_facing_http_error(r.code()), r.code())
            }
        } else {
            val rawError = r.errorBody()?.string()
            val msg = body?.message ?: pc_extract_json_message(rawError) ?: pc_user_facing_http_error(r.code())
            Result.Error(
                message = msg,
                code = r.code(),
                retryAfterSeconds = pc_extract_json_int(rawError, "retry_after"),
                field = pc_extract_json_string(rawError, "field"),
            )
        }
    } catch (e: Exception) {
        Result.Error(pc_user_facing_exception(e))
    }

private class UriRequestBody(
    private val context: Context,
    private val uri: Uri,
    private val contentType: String,
) : RequestBody() {
    override fun contentType() = contentType.toMediaTypeOrNull()

    override fun contentLength(): Long =
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0 } ?: -1L
        } ?: -1L

    override fun writeTo(sink: BufferedSink) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            sink.writeAll(input.source())
        } ?: error("Cannot read file")
    }
}

private fun buildImagePart(
    context: Context,
    partName: String,
    fileName: String,
    imageUri: Uri,
): MultipartBody.Part {
    val mimeType = normalizedImageMimeType(context, imageUri)
    return MultipartBody.Part.createFormData(
        partName,
        fileName,
        UriRequestBody(context, imageUri, mimeType),
    )
}

private fun buildDocumentPart(
    context: Context,
    partName: String,
    fileName: String,
    documentUri: Uri,
    extension: String,
): MultipartBody.Part {
    val mimeType = if (extension == ".pdf") "application/pdf" else normalizedImageMimeType(context, documentUri)
    return MultipartBody.Part.createFormData(
        partName,
        fileName,
        UriRequestBody(context, documentUri, mimeType),
    )
}

private fun normalizedImageMimeType(context: Context, uri: Uri): String {
    val mimeType = context.contentResolver.getType(uri)?.lowercase().orEmpty()
    if (mimeType.startsWith("image/")) return mimeType
    return when (extensionForImageUri(context, uri)) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        else -> "image/jpeg"
    }
}

private fun extensionForImageUri(context: Context, uri: Uri): String {
    val displayName = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index).orEmpty() else ""
                } else {
                    ""
                }
            }.orEmpty()
    }.getOrDefault("")
    return listOf(displayName, uri.lastPathSegment.orEmpty(), uri.path.orEmpty())
        .firstNotNullOfOrNull { value ->
            value.substringAfterLast('.', "")
                .lowercase()
                .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "heic", "heif") }
        } ?: "jpg"
}

private fun extensionForUri(context: Context, uri: Uri): String =
    when (context.contentResolver.getType(uri)) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "application/pdf" -> ".pdf"
        else -> ".jpg"
    }

private data class PreparedUploadDocument(
    val uri: Uri,
    val extension: String,
)

private fun uriSizeBytes(context: Context, uri: Uri): Long =
    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
        descriptor.length.takeIf { it >= 0 }
    } ?: -1L

private fun prepareVerificationDocumentForUpload(context: Context, uri: Uri): PreparedUploadDocument {
    val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
    val size = uriSizeBytes(context, uri)
    if (mime == "application/pdf" || extensionForUri(context, uri) == ".pdf") {
        if (size > VERIFICATION_MAX_UPLOAD_BYTES) {
            throw IOException("PDF verification document must be under 5 MB. Please upload a smaller PDF or take a photo instead.")
        }
        return PreparedUploadDocument(uri, ".pdf")
    }
    val allowedImage = mime in setOf("image/jpeg", "image/png", "image/webp") ||
        (mime.isBlank() && extensionForImageUri(context, uri) in setOf("jpg", "jpeg", "png", "webp"))
    if (allowedImage && size in 1 until VERIFICATION_SAFE_UPLOAD_BYTES) {
        return PreparedUploadDocument(uri, extensionForUri(context, uri).takeIf { it != ".pdf" } ?: ".jpg")
    }

    val bitmap = decodeVerificationBitmap(context, uri)
        ?: throw IOException("Could not read verification image. Please retake the photo.")
    try {
        val dir = File(context.cacheDir, "verification_uploads").apply { mkdirs() }
        dir.listFiles()?.forEach { file ->
            if (System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000L) {
                file.delete()
            }
        }

        var working = bitmap
        var maxSide = maxOf(working.width, working.height)
        val targetFile = File(dir, "verification_${System.currentTimeMillis()}.jpg")
        var finalFile: File? = null

        while (maxSide >= 900) {
            for (quality in listOf(88, 82, 76, 70, 64, 58, 52)) {
                FileOutputStream(targetFile).use { out ->
                    working.compress(Bitmap.CompressFormat.JPEG, quality, out)
                }
                if (targetFile.length() in 1..VERIFICATION_SAFE_UPLOAD_BYTES) {
                    finalFile = targetFile
                    break
                }
            }
            if (finalFile != null) break

            val scale = 0.82f
            val nextWidth = (working.width * scale).toInt().coerceAtLeast(700)
            val nextHeight = (working.height * scale).toInt().coerceAtLeast(700)
            if (nextWidth >= working.width || nextHeight >= working.height) break
            val resized = Bitmap.createScaledBitmap(working, nextWidth, nextHeight, true)
            if (working != bitmap) working.recycle()
            working = resized
            maxSide = maxOf(working.width, working.height)
        }

        if (working != bitmap) working.recycle()
        val compressed = finalFile ?: targetFile
        if (compressed.length() <= 0L || compressed.length() > VERIFICATION_MAX_UPLOAD_BYTES) {
            compressed.delete()
            throw IOException("Could not reduce verification image below 5 MB. Please retake it with the document filling the frame.")
        }
        return PreparedUploadDocument(Uri.fromFile(compressed), ".jpg")
    } finally {
        bitmap.recycle()
    }
}

private fun decodeVerificationBitmap(context: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    val maxDimension = 2400
    while ((bounds.outWidth / sampleSize) > maxDimension || (bounds.outHeight / sampleSize) > maxDimension) {
        sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)
    }
}

private const val VERIFICATION_MAX_UPLOAD_BYTES = 5L * 1024L * 1024L
private const val VERIFICATION_SAFE_UPLOAD_BYTES = 4_800_000L

@Singleton
class AuthRepository @Inject constructor(private val api: PhotoConnectApiService) {
    suspend fun login(role: String, identity: String, password: String) =
        withContext(Dispatchers.IO) { safe { api.login(LoginRequest(role, identity, password)) } }
        
    suspend fun googleLogin(idToken: String) =
        withContext(Dispatchers.IO) { safe { api.googleAuth(GoogleLoginRequest(idToken)) } }

    suspend fun registerDeviceForPush(): Result<IdData> =
        withContext(Dispatchers.IO) {
            try {
                val token = FirebaseMessaging.getInstance().token.await().trim()
                if (token.isBlank()) {
                    Result.Error("Push token is empty")
                } else {
                    val deviceName = listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
                        .joinToString(" ")
                        .trim()
                    safe { api.registerDeviceToken(DeviceTokenRequest(token = token, deviceName = deviceName)) }
                }
            } catch (e: Exception) {
                Result.Error(e.message ?: "Unable to register push token")
            }
        }

    suspend fun checkIdentity(phone: String, email: String?) =
        withContext(Dispatchers.IO) { safe { api.identityCheck(IdentityCheckRequest(phone, email?.ifBlank { null })) } }

    suspend fun registerTaker(r: RegisterTakerRequest) =
        withContext(Dispatchers.IO) { safe { api.registerTaker(r) } }
    suspend fun registerClient(r: RegisterClientRequest) =
        withContext(Dispatchers.IO) { safe { api.registerClient(r) } }
    suspend fun sendOtp(phone: String, purpose: String = "registration"): Result<SendOtpData> =
        withContext(Dispatchers.IO) { safe<SendOtpData> { api.sendOtp(SendOtpRequest(phone, purpose)) } }
    suspend fun verifyOtp(phone: String, otp: String): Result<OtpVerificationData> =
        withContext(Dispatchers.IO) { safe<OtpVerificationData> { api.verifyOtp(VerifyOtpRequest(phone, otp)) } }
    suspend fun sendEmailOtp(email: String, purpose: String = "registration") =
        withContext(Dispatchers.IO) { safe { api.sendEmailOtp(SendEmailOtpRequest(email, purpose)) } }
    suspend fun verifyEmailOtp(email: String, otp: String): Result<OtpVerificationData> =
        withContext(Dispatchers.IO) { safe<OtpVerificationData> { api.verifyEmailOtp(VerifyEmailOtpRequest(email, otp)) } }
    suspend fun resetPassword(identity: String, otp: String, newPassword: String): Result<ResetPasswordData> =
        withContext(Dispatchers.IO) {
            safe { api.resetPassword(ResetPasswordRequest(identity, otp, newPassword)) }
        }
}

@Singleton
class TrustRepository @Inject constructor(private val api: PhotoConnectApiService) {
    suspend fun getTrustStatus(takerId: Int? = null, clientId: Int? = null, cacheBust: Long? = null): Result<TrustStatusResult> =
        withContext(Dispatchers.IO) { safe { api.getTrustStatus(takerId, clientId, cacheBust) } }

    suspend fun submitStudioVerification(
        clientId: Int,
        verificationPath: String,
        gstin: String? = null,
        googleMapsUrl: String? = null,
        ownerAadhaarSubmitted: Boolean = false,
    ): Result<TrustActionResult> =
        withContext(Dispatchers.IO) {
            safe {
                api.submitStudioVerification(
                    SubmitStudioVerificationRequest(
                        clientId = clientId,
                        verificationPath = verificationPath,
                        gstin = gstin,
                        googleMapsUrl = googleMapsUrl,
                        ownerAadhaarSubmitted = ownerAadhaarSubmitted,
                    )
                )
            }
        }

    suspend fun submitTakerVerification(
        takerId: Int,
        socialUrl: String,
        portfolioUrl: String?,
        aadhaarSubmitted: Boolean,
    ): Result<TrustActionResult> =
        withContext(Dispatchers.IO) {
            safe { api.submitTakerVerification(SubmitTakerVerificationRequest(takerId, socialUrl, portfolioUrl, aadhaarSubmitted)) }
        }

    suspend fun toggleTakerEndorsement(
        takerId: Int,
        clientId: Int,
        endorse: Boolean,
        emailOtp: String? = null,
    ): Result<TrustActionResult> =
        withContext(Dispatchers.IO) {
            safe { api.toggleTakerEndorsement(ToggleTakerEndorsementRequest(takerId, clientId, endorse, emailOtp)) }
        }

    suspend fun addStudioReview(
        clientId: Int,
        takerId: Int,
        rating: Int,
        comment: String?,
    ): Result<TrustActionResult> =
        withContext(Dispatchers.IO) {
            safe { api.addStudioReview(AddStudioReviewRequest(clientId, takerId, rating, comment)) }
        }

    suspend fun uploadVerificationDocument(
        targetRole: String,
        targetId: Int,
        documentType: String,
        documentUri: Uri,
        context: Context,
    ): Result<TrustActionResult> =
        withContext(Dispatchers.IO) {
            val textPlain = "text/plain".toMediaTypeOrNull()
            val prepared = try {
                prepareVerificationDocumentForUpload(context, documentUri)
            } catch (e: IOException) {
                return@withContext Result.Error(e.message ?: "Could not prepare verification document")
            }
            val documentPart = buildDocumentPart(
                context,
                "document",
                "verification_$documentType${prepared.extension}",
                prepared.uri,
                prepared.extension,
            )
            safe {
                api.uploadVerificationDocument(
                    targetRole = targetRole.toRequestBody(textPlain),
                    targetId = targetId.toString().toRequestBody(textPlain),
                    documentType = documentType.toRequestBody(textPlain),
                    document = documentPart,
                )
            }
        }
}

@Singleton
class TakerRepository @Inject constructor(
    private val api: PhotoConnectApiService,
    private val dao: TakerDao,
) {
    fun getCached(city: String?, svc: String?) = dao.search(city, svc)
    fun getFeatured() = dao.getFeatured()
    fun getTaker(id: Int) = dao.getById(id)

    suspend fun cachedSearch(loc: String?, svc: String?, limit: Int = 60): List<Taker> =
        withContext(Dispatchers.IO) {
            dao.searchOnce(
                loc?.trim()?.takeIf { it.isNotBlank() },
                svc?.trim()?.takeIf { it.isNotBlank() },
                limit,
            ).map { it.toModel() }
        }

    suspend fun fetchTakerProfile(takerId: Int, cacheBust: Long? = null): Result<Taker> =
        withContext(Dispatchers.IO) {
            val result = safe { api.getTakerProfile(takerId, cacheBust) }
            if (result is Result.Success) {
                dao.upsertAll(listOf(result.data.toEntity()))
            }
            result
        }

    suspend fun fetchAndCache(
        loc: String?,
        date: String?,
        svc: String?,
        serviceTypes: List<String> = emptyList(),
        serviceMatchMode: String = "smart",
        trustFilter: String? = null,
        respondsFastOnly: Boolean = false,
        availOnly: Boolean = false,
        latitude: Double? = null,
        longitude: Double? = null,
        radiusKm: Double? = null,
        page: Int = 1
    ): Result<SearchResult> =
        withContext(Dispatchers.IO) {
            val cacheBust = if (!loc.isNullOrBlank() || radiusKm != null || latitude != null || longitude != null) {
                System.currentTimeMillis()
            } else {
                null
            }
            val serviceTypesParam = serviceTypes
                .filter { it.isNotBlank() }
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.joinToString(",")
            val result = safe {
                api.searchTakers(
                    location = loc,
                    date = date,
                    serviceType = svc,
                    serviceTypes = serviceTypesParam,
                    serviceMatchMode = serviceMatchMode,
                    trustFilter = trustFilter,
                    respondsFastOnly = respondsFastOnly,
                    availableOnly = availOnly,
                    latitude = latitude,
                    longitude = longitude,
                    radiusKm = radiusKm,
                    page = page,
                    cacheBust = cacheBust,
                )
            }
            if (result is Result.Success) {
                val takers = (result.data.takers + result.data.featured).distinctBy { it.id }
                if (takers.isNotEmpty()) {
                    dao.upsertAll(takers.map { it.toEntity() })
                }
            }
            result
        }

    suspend fun updateProfile(r: UpdateTakerRequest) = withContext(Dispatchers.IO) {
        safe { api.updateTakerProfile(r) }
    }

    suspend fun updateClientProfile(r: UpdateClientProfileRequest) = withContext(Dispatchers.IO) {
        safe { api.updateClientProfile(r) }
    }

    suspend fun uploadProfileImage(
        takerId: Int,
        scope: String,
        imageUri: Uri,
        context: Context
    ): Result<com.photoconnect.model.ProfileImageData> = withContext(Dispatchers.IO) {
        try {
            val mimeType = context.contentResolver.getType(imageUri) ?: "image/jpeg"
            val ext = when (mimeType) {
                "image/png"  -> ".png"
                "image/webp" -> ".webp"
                else         -> ".jpg"
            }

            val textPlain   = "text/plain".toMediaTypeOrNull()
            val takerIdBody = takerId.toString().toRequestBody(textPlain)
            val scopeBody   = scope.toRequestBody(textPlain)
            val imagePart   = buildImagePart(context, "image", "profile$ext", imageUri)

            safe { api.uploadProfileImage(takerIdBody, scopeBody, imagePart) }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Upload failed")
        }
    }

    suspend fun uploadClientProfileImage(
        clientId: Int,
        imageUri: Uri,
        context: Context
    ): Result<com.photoconnect.model.ProfileImageData> = withContext(Dispatchers.IO) {
        try {
            val textPlain = "text/plain".toMediaTypeOrNull()
            val clientIdBody = clientId.toString().toRequestBody(textPlain)
            val imagePart = buildImagePart(context, "image", "client_profile${extensionForUri(context, imageUri)}", imageUri)
            safe { api.uploadClientProfileImage(clientIdBody, imagePart) }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Upload failed")
        }
    }

    suspend fun removeProfileImage(takerId: Int): Result<DeleteData> = withContext(Dispatchers.IO) {
        safe { api.removeProfileImage(RemoveProfileImageRequest(takerId)) }
    }

    suspend fun fetchPortfolio(takerId: Int): Result<PortfolioResult> =
        withContext(Dispatchers.IO) { safe { api.getPortfolio(takerId) } }

    suspend fun uploadPortfolioSample(
        takerId: Int,
        caption: String?,
        imageUri: Uri,
        context: Context
    ): Result<PortfolioSample> = withContext(Dispatchers.IO) {
        try {
            val mimeType = context.contentResolver.getType(imageUri) ?: "image/jpeg"
            val ext = when (mimeType) {
                "image/png" -> ".png"
                "image/webp" -> ".webp"
                else -> ".jpg"
            }

            val imagePart = buildImagePart(context, "image", "portfolio$ext", imageUri)
            safe { api.uploadPortfolioSample(takerId, caption?.ifBlank { null }, imagePart) }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Upload failed")
        }
    }

    suspend fun deletePortfolioSample(takerId: Int, sampleId: Int): Result<Boolean> =
        withContext(Dispatchers.IO) {
            when (val result = safe { api.deletePortfolioSample(DeleteSampleRequest(takerId, sampleId)) }) {
                is Result.Success -> Result.Success(result.data.deleted)
                is Result.Error -> result
                Result.Loading -> Result.Loading
            }
        }

    suspend fun uploadTakerPost(
        takerId: Int,
        caption: String?,
        imageUris: List<Uri>,
        context: Context
    ): Result<TakerPostData> = withContext(Dispatchers.IO) {
        try {
            if (imageUris.isEmpty()) {
                return@withContext Result.Error("Select at least one image")
            }
            if (imageUris.size > 8) {
                return@withContext Result.Error(context.getString(R.string.only_8_images_allowed))
            }

            val textPlain = "text/plain".toMediaTypeOrNull()
            val takerIdBody = takerId.toString().toRequestBody(textPlain)
            val captionBody = (caption?.trim().orEmpty()).toRequestBody(textPlain)
            val imageParts = imageUris.mapIndexed { index, imageUri ->
                val mimeType = context.contentResolver.getType(imageUri) ?: "image/jpeg"
                val ext = when (mimeType) {
                    "image/png" -> ".png"
                    "image/webp" -> ".webp"
                    else -> ".jpg"
                }
                buildImagePart(context, "images[]", "post_${index + 1}$ext", imageUri)
            }

            safe { api.uploadTakerPost(takerIdBody, captionBody, null, null, null, imageParts) }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Post upload failed")
        }
    }

    suspend fun fetchTakerPosts(
        takerId: Int,
        viewerRole: String? = null,
        viewerId: Int? = null,
        page: Int = 1,
        limit: Int = 12,
        cacheBust: Long? = null,
    ): Result<TakerPostsResult> = withContext(Dispatchers.IO) {
        safe { api.getTakerPosts(takerId, viewerRole, viewerId, page = page, limit = limit, cacheBust = cacheBust) }
    }

    suspend fun fetchAccountPostActivity(
        actorRole: String,
        actorId: Int,
        collection: String? = null,
        page: Int = 1,
        limit: Int = 12,
        cacheBust: Long? = null,
    ): Result<AccountPostActivityResult> = withContext(Dispatchers.IO) {
        safe { api.getAccountPostActivity(actorRole, actorId, collection, page, limit, cacheBust) }
    }

    suspend fun toggleTakerPostLike(
        postId: Int,
        actorRole: String,
        actorId: Int,
        like: Boolean,
    ): Result<ToggleTakerPostLikeData> = withContext(Dispatchers.IO) {
        safe {
            api.toggleTakerPostLike(
                ToggleTakerPostLikeRequest(
                    postId = postId,
                    actorRole = actorRole,
                    actorId = actorId,
                    like = like,
                )
            )
        }
    }

    suspend fun toggleTakerPostImageLike(
        imageId: Int,
        actorRole: String,
        actorId: Int,
        like: Boolean,
    ): Result<ToggleTakerPostImageLikeData> = withContext(Dispatchers.IO) {
        safe {
            api.toggleTakerPostImageLike(
                ToggleTakerPostImageLikeRequest(
                    imageId = imageId,
                    actorRole = actorRole,
                    actorId = actorId,
                    like = like,
                )
            )
        }
    }

    suspend fun toggleTakerPostSave(
        postId: Int,
        actorRole: String,
        actorId: Int,
        save: Boolean,
    ): Result<ToggleTakerPostSaveData> = withContext(Dispatchers.IO) {
        safe {
            api.toggleTakerPostSave(
                ToggleTakerPostSaveRequest(
                    postId = postId,
                    actorRole = actorRole,
                    actorId = actorId,
                    save = save,
                )
            )
        }
    }

    suspend fun recordTakerPostView(
        postId: Int,
        viewerRole: String,
        viewerId: Int,
    ): Result<TakerPostViewData> = withContext(Dispatchers.IO) {
        safe {
            api.recordTakerPostView(
                RecordTakerPostViewRequest(
                    postId = postId,
                    viewerRole = viewerRole,
                    viewerId = viewerId,
                )
            )
        }
    }

    suspend fun deleteTakerPost(takerId: Int, postId: Int): Result<Boolean> =
        withContext(Dispatchers.IO) {
            when (val result = safe { api.deleteTakerPost(DeleteTakerPostRequest(takerId, postId)) }) {
                is Result.Success -> Result.Success(result.data.deleted)
                is Result.Error -> result
                Result.Loading -> Result.Loading
            }
        }

    suspend fun updateTakerPost(
        takerId: Int,
        postId: Int,
        caption: String?,
        keepImageIds: List<Int>,
        newImageUris: List<Uri>,
        context: Context,
    ): Result<IdData> =
        withContext(Dispatchers.IO) {
            val finalImageCount = keepImageIds.distinct().size + newImageUris.size
            if (finalImageCount < 1) {
                return@withContext Result.Error(context.getString(R.string.select_at_least_one_image))
            }
            if (finalImageCount > 8) {
                return@withContext Result.Error(context.getString(R.string.only_8_images_allowed))
            }
            safe {
                val textPlain = "text/plain".toMediaTypeOrNull()
                val imageParts = newImageUris.mapIndexed { index, imageUri ->
                    val mimeType = normalizedImageMimeType(context, imageUri)
                    val ext = when (mimeType) {
                        "image/png" -> ".png"
                        "image/webp" -> ".webp"
                        "image/heic" -> ".heic"
                        else -> ".jpg"
                    }
                    buildImagePart(context, "images[]", "post_edit_${index + 1}$ext", imageUri)
                }
                api.updateTakerPost(
                    takerId = takerId.toString().toRequestBody(textPlain),
                    postId = postId.toString().toRequestBody(textPlain),
                    caption = caption?.trim().orEmpty().toRequestBody(textPlain),
                    keepImageIds = keepImageIds.distinct().joinToString(",").toRequestBody(textPlain),
                    images = imageParts,
                )
            }
        }

    suspend fun deleteTakerAccount(takerId: Int): Result<DeleteData> =
        withContext(Dispatchers.IO) {
            safe { api.deleteTakerAccount(DeleteTakerAccountRequest(takerId)) }
        }
}

@Singleton
class SearchRepository @Inject constructor(
    private val api: PhotoConnectApiService,
) {
    suspend fun getTrendingSearches(limit: Int = 10): Result<TrendingSearchesResult> =
        withContext(Dispatchers.IO) { safe { api.getTrendingSearches(limit) } }

    suspend fun recordEvent(request: SearchEventRequest): Result<IdData> =
        withContext(Dispatchers.IO) { safe { api.recordSearchEvent(request) } }

    suspend fun saveSearchAlert(request: SearchAlertRequest): Result<SearchAlertData> =
        withContext(Dispatchers.IO) { safe { api.saveSearchAlert(request) } }
}

@Singleton
class FavoriteRepository @Inject constructor(
    private val dao: FavoriteDao,
    private val api: PhotoConnectApiService,
) {
    private val favoriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutationVersions = ConcurrentHashMap<String, AtomicLong>()
    private val togglesInFlight = ConcurrentHashMap.newKeySet<String>()

    fun getFavorites(userId: Int) = dao.getFavorites(userId)
    fun isFavorite(userId: Int, takerId: Int) = dao.isFavorite(userId, takerId)
    suspend fun getFavoriteIdsOnce(userId: Int): Set<Int> = withContext(Dispatchers.IO) {
        dao.getFavoritesOnce(userId).map { it.takerId }.toSet()
    }
    suspend fun syncServerFavorites(actorId: Int, takers: List<Taker>) = withContext(Dispatchers.IO) {
        val serverFavoriteIds = takers
            .asSequence()
            .filter { it.viewerHasFavorited }
            .map { it.id }
            .distinct()
            .toList()
        if (serverFavoriteIds.isNotEmpty()) {
            dao.addAll(serverFavoriteIds.map { FavoriteEntity(actorId, it) })
        }
    }
    suspend fun addFavorite(userId: Int, takerId: Int) = withContext(Dispatchers.IO) {
        dao.add(FavoriteEntity(userId, takerId))
    }
    suspend fun removeFavorite(userId: Int, takerId: Int) = withContext(Dispatchers.IO) {
        dao.remove(userId, takerId)
    }

    suspend fun fetchFavoriteStatus(takerId: Int, actorRole: String, actorId: Int): Result<FavoriteStatusData> =
        withContext(Dispatchers.IO) {
            val key = favoriteKey(actorId, takerId)
            val startedVersion = favoriteVersion(key)
            val startedDuringToggle = togglesInFlight.contains(key)
            val result = safe { api.getFavoriteStatus(takerId, actorRole, actorId) }
            if (
                result is Result.Success &&
                !startedDuringToggle &&
                !togglesInFlight.contains(key) &&
                favoriteVersion(key) == startedVersion
            ) {
                if (result.data.isFavorite) {
                    dao.add(FavoriteEntity(actorId, takerId))
                } else {
                    dao.remove(actorId, takerId)
                }
            }
            result
        }

    fun toggleFavoriteDurable(
        takerId: Int,
        actorRole: String,
        actorId: Int,
        favorite: Boolean,
        onResult: (Result<FavoriteStatusData>) -> Unit,
    ) {
        favoriteScope.launch {
            onResult(toggleFavorite(takerId, actorRole, actorId, favorite))
        }
    }

    suspend fun toggleFavorite(
        takerId: Int,
        actorRole: String,
        actorId: Int,
        favorite: Boolean,
    ): Result<FavoriteStatusData> = withContext(Dispatchers.IO) {
        val key = favoriteKey(actorId, takerId)
        markFavoriteMutation(key)
        togglesInFlight.add(key)
        try {
            val previous = dao.isFavoriteOnce(actorId, takerId)
            if (favorite) {
                dao.add(FavoriteEntity(actorId, takerId))
            } else {
                dao.remove(actorId, takerId)
            }

            val result = safe {
                api.toggleFavorite(
                    ToggleFavoriteRequest(
                        takerId = takerId,
                        actorRole = actorRole,
                        actorId = actorId,
                        favorite = favorite,
                    )
                )
            }
            when (result) {
                is Result.Success -> {
                    markFavoriteMutation(key)
                    if (result.data.isFavorite) {
                        dao.add(FavoriteEntity(actorId, takerId))
                    } else {
                        dao.remove(actorId, takerId)
                    }
                }
                is Result.Error -> {
                    markFavoriteMutation(key)
                    if (previous) {
                        dao.add(FavoriteEntity(actorId, takerId))
                    } else {
                        dao.remove(actorId, takerId)
                    }
                }
                Result.Loading -> Unit
            }
            result
        } finally {
            togglesInFlight.remove(key)
        }
    }

    private fun favoriteKey(actorId: Int, takerId: Int): String = "$actorId:$takerId"

    private fun favoriteVersion(key: String): Long =
        mutationVersions[key]?.get() ?: 0L

    private fun markFavoriteMutation(key: String): Long =
        mutationVersions.getOrPut(key) { AtomicLong(0L) }.incrementAndGet()
}

@Singleton
class NotificationRepository @Inject constructor(
    private val api: PhotoConnectApiService,
) {
    suspend fun fetchAll(
        recipientRole: String? = null,
        recipientId: Int? = null,
        limit: Int = 20,
        allPages: Boolean = false,
    ): Result<NotificationsResult> =
        withContext(Dispatchers.IO) {
            val notifications = mutableListOf<NotificationItem>()
            var page = 1
            var total = 0
            var totalPages = 1
            var unreadCount = 0

            while (page <= totalPages) {
                when (val result = safe { api.getNotifications(recipientRole, recipientId, page, limit) }) {
                    is Result.Success -> {
                        total = result.data.total
                        totalPages = result.data.totalPages
                        unreadCount = result.data.unreadCount
                        notifications += result.data.notifications
                        if (!allPages) {
                            page = totalPages + 1
                        } else {
                            page++
                        }
                    }
                    is Result.Error -> return@withContext result
                    Result.Loading -> return@withContext Result.Loading
                }
            }

            Result.Success(
                NotificationsResult(
                    notifications = notifications,
                    total = total,
                    unreadCount = unreadCount,
                    page = 1,
                    limit = notifications.size.coerceAtLeast(1),
                    totalPages = 1,
                )
            )
        }

    suspend fun markRead(
        notificationId: Int,
        recipientRole: String? = null,
        recipientId: Int? = null,
    ): Result<NotificationReadData> =
        withContext(Dispatchers.IO) {
            safe { api.markNotificationRead(MarkNotificationReadRequest(notificationId, recipientRole, recipientId)) }
        }

    suspend fun markAllRead(
        recipientRole: String? = null,
        recipientId: Int? = null,
    ): Result<NotificationReadData> =
        withContext(Dispatchers.IO) {
            safe {
                api.markNotificationRead(
                    MarkNotificationReadRequest(
                        recipientRole = recipientRole,
                        recipientId = recipientId,
                        markAll = true,
                    )
                )
            }
        }
}

@Singleton
class AvailabilityRepository @Inject constructor(
    private val api: PhotoConnectApiService,
    private val dao: AvailabilityDao,
    private val pendingDao: PendingAvailabilityOverrideDao,
    private val db: PhotoConnectDatabase,
) {
    fun getCached(takerId: Int) = dao.getForTaker(takerId)

    suspend fun fetch(takerId: Int, month: String? = null): Result<AvailabilityResult> =
        withContext(Dispatchers.IO) {
            val r = safe { api.getAvailability(takerId, month) }
            if (r is Result.Success) {
                val rows = r.data.availability.map { AvailabilityEntity(takerId, it.date, it.status, it.dayPart) }
                val ym = month?.takeIf { it.matches(Regex("\\d{4}-\\d{2}.*")) }?.take(7)
                val localWins = activeLocalOverrides(takerId, ym)
                db.withTransaction {
                    if (ym != null) {
                        dao.deleteMonth(takerId, ym)
                        dao.upsertAll(rows)
                    } else {
                        dao.deleteForTaker(takerId)
                        dao.upsertAll(rows)
                    }
                    if (localWins.isNotEmpty()) {
                        applyAvailabilityRows(takerId, localWins)
                    }
                }
            }
            r
        }

    suspend fun update(takerId: Int, entries: List<AvailabilityEntry>): Result<CountData> =
        withContext(Dispatchers.IO) {
            val r = safe { api.updateAvailability(UpdateAvailabilityRequest(takerId, entries)) }
            if (r is Result.Success) {
                applyLocalEntries(takerId, entries)
            }
            r
        }

    suspend fun updateLocalFirst(takerId: Int, entries: List<AvailabilityEntry>): Result<CountData> =
        withContext(Dispatchers.IO) {
            applyLocalEntries(takerId, entries)
            safe { api.updateAvailability(UpdateAvailabilityRequest(takerId, entries)) }
        }

    suspend fun applyLocal(takerId: Int, entries: List<AvailabilityEntry>) =
        withContext(Dispatchers.IO) { applyLocalEntries(takerId, entries) }

    suspend fun syncRemote(takerId: Int, entries: List<AvailabilityEntry>): Result<CountData> =
        withContext(Dispatchers.IO) {
            safe { api.updateAvailability(UpdateAvailabilityRequest(takerId, entries)) }
        }

    suspend fun discardLocalOverrides(takerId: Int, entries: List<AvailabilityEntry>) =
        withContext(Dispatchers.IO) { forgetLocalOverrides(takerId, entries) }

    private suspend fun applyLocalEntries(
        takerId: Int,
        entries: List<AvailabilityEntry>,
        rememberOverride: Boolean = true,
    ) {
        db.withTransaction {
            if (rememberOverride) rememberLocalOverrides(takerId, entries)
            applyAvailabilityRows(takerId, entries)
        }
    }

    private suspend fun applyAvailabilityRows(takerId: Int, entries: List<AvailabilityEntry>) {
        entries.forEach { entry ->
            if (entry.dayPart == "full_day") {
                dao.deleteHalfDays(takerId, entry.date)
            } else {
                dao.deleteDate(takerId, entry.date)
            }
        }
        dao.upsertAll(entries.map { AvailabilityEntity(takerId, it.date, it.status, it.dayPart) })
    }

    private suspend fun rememberLocalOverrides(takerId: Int, entries: List<AvailabilityEntry>) {
        cleanupExpiredLocalOverrides()
        val expiresAt = System.currentTimeMillis() + RECENT_LOCAL_OVERRIDE_TTL_MS
        entries.forEach { entry ->
            clearDateOverrides(takerId, entry.date)
        }
        pendingDao.upsertAll(entries.map {
            PendingAvailabilityOverrideEntity(
                takerId = takerId,
                date = it.date,
                status = it.status,
                dayPart = it.dayPart,
                expiresAt = expiresAt,
            )
        })
    }

    private suspend fun forgetLocalOverrides(takerId: Int, entries: List<AvailabilityEntry>) {
        entries.forEach { clearDateOverrides(takerId, it.date) }
    }

    private suspend fun activeLocalOverrides(takerId: Int, month: String?): List<AvailabilityEntry> {
        cleanupExpiredLocalOverrides()
        val now = System.currentTimeMillis()
        val rows = if (month != null) {
            pendingDao.activeForMonth(takerId, "$month-01", nextMonthStart(month), now)
        } else {
            pendingDao.activeForTaker(takerId, now)
        }
        return rows.map { AvailabilityEntry(it.date, it.status, it.dayPart) }
    }

    private suspend fun syncPendingOverrides(takerId: Int, month: String?) {
        val pending = activeLocalOverrides(takerId, month)
        if (pending.isEmpty()) return
        when (val result = safe { api.updateAvailability(UpdateAvailabilityRequest(takerId, pending)) }) {
            is Result.Success -> {
                if (result.data.skippedBookedCount > 0) {
                    forgetLocalOverrides(takerId, pending)
                }
            }
            is Result.Error -> {
                if (result.code in 400..499) {
                    forgetLocalOverrides(takerId, pending)
                }
            }
            Result.Loading -> Unit
        }
    }

    private suspend fun cleanupExpiredLocalOverrides() {
        pendingDao.deleteExpired(System.currentTimeMillis())
    }

    private suspend fun clearDateOverrides(takerId: Int, date: String) {
        pendingDao.deleteDate(takerId, date)
    }

    private fun nextMonthStart(month: String): String {
        val parts = month.split("-")
        val year = parts.getOrNull(0)?.toIntOrNull() ?: return "$month-31"
        val monthNumber = parts.getOrNull(1)?.toIntOrNull() ?: return "$month-31"
        val nextYear = if (monthNumber == 12) year + 1 else year
        val nextMonth = if (monthNumber == 12) 1 else monthNumber + 1
        return "%04d-%02d-01".format(nextYear, nextMonth)
    }

    private companion object {
        const val RECENT_LOCAL_OVERRIDE_TTL_MS = 30 * 60 * 1000L
    }
}

@Singleton
class CalendarMutationGuard @Inject constructor() {
    private data class EventOverride(val entity: EventEntity, val expiresAt: Long)
    private data class BookingStatusOverride(val status: String, val expiresAt: Long)

    private val eventOverrides = ConcurrentHashMap<Int, EventOverride>()
    private val deletedEventIds = ConcurrentHashMap<Int, Long>()
    private val bookingStatusOverrides = ConcurrentHashMap<Int, BookingStatusOverride>()

    fun rememberEvent(entity: EventEntity) {
        cleanup()
        eventOverrides[entity.id] = EventOverride(entity, System.currentTimeMillis() + TTL_MS)
        deletedEventIds.remove(entity.id)
    }

    fun rememberDeletedEvent(eventId: Int) {
        cleanup()
        eventOverrides.remove(eventId)
        deletedEventIds[eventId] = System.currentTimeMillis() + TTL_MS
    }

    fun rememberBookingStatus(bookingId: Int, status: String) {
        cleanup()
        bookingStatusOverrides[bookingId] = BookingStatusOverride(status, System.currentTimeMillis() + TTL_MS)
    }

    fun applyToRemoteEvent(entity: EventEntity): EventEntity? {
        cleanup()
        if (deletedEventIds.containsKey(entity.id)) return null
        eventOverrides[entity.id]?.let { return it.entity }
        val bookingId = entity.bookingId ?: return entity
        val status = bookingStatusOverrides[bookingId]?.status ?: return entity
        return entity.copy(status = status, updatedAt = System.currentTimeMillis().toString())
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        eventOverrides.entries.removeIf { it.value.expiresAt <= now }
        deletedEventIds.entries.removeIf { it.value <= now }
        bookingStatusOverrides.entries.removeIf { it.value.expiresAt <= now }
    }

    private companion object {
        const val TTL_MS = 5 * 60 * 1000L
    }
}

@Singleton
class BookingRepository @Inject constructor(
    private val api: PhotoConnectApiService,
    private val dao: BookingDao,
    private val eventDao: EventDao,
    private val calendarGuard: CalendarMutationGuard,
) {
    fun getByClient(id: Int) = dao.getByClient(id)
    fun getByTaker(id: Int)  = dao.getByTaker(id)

    suspend fun book(r: BookTakerRequest): Result<BookingData> =
        withContext(Dispatchers.IO) {
            val res = safe { api.bookTaker(r) }
            if (res is Result.Success)
                dao.upsert(BookingEntity(res.data.bookingId, r.clientId, r.takerId,
                    r.takerName, null, r.bookingDate, r.serviceType, r.dayPart, r.eventLocation, r.notes,
                    clientVerificationStage = res.data.clientVerificationStage,
                    clientVerificationLabel = res.data.clientVerificationLabel))
            res
        }

    suspend fun updateStatus(r: UpdateBookingStatusRequest): Result<BookingStatusData> =
        withContext(Dispatchers.IO) {
            val res = safe { api.updateBookingStatus(r) }
            if (res is Result.Success) {
                val updatedAt = System.currentTimeMillis().toString()
                dao.updateStatus(r.bookingId, res.data.status)
                calendarGuard.rememberBookingStatus(r.bookingId, res.data.status)
                eventDao.updateStatusForBooking(
                    bookingId = r.bookingId,
                    status = res.data.status,
                    updatedAt = updatedAt,
                )
                res.data.autoCancelledBookingIds.forEach { cancelledBookingId ->
                    dao.updateStatus(cancelledBookingId, "Cancelled")
                    calendarGuard.rememberBookingStatus(cancelledBookingId, "Cancelled")
                    eventDao.updateStatusForBooking(
                        bookingId = cancelledBookingId,
                        status = "Cancelled",
                        updatedAt = updatedAt,
                    )
                }
            }
            res
        }

    suspend fun fetchAll(clientId: Int? = null, takerId: Int? = null): Result<BookingsResult> =
        withContext(Dispatchers.IO) {
            val allBookings = mutableListOf<Booking>()
            var page = 1
            var total = 0
            var totalPages = 1

            while (page <= totalPages) {
                when (val result = safe { api.getBookings(clientId, takerId, page = page) }) {
                    is Result.Success -> {
                        total = result.data.total
                        totalPages = result.data.totalPages
                        allBookings += result.data.bookings
                        page++
                    }
                    is Result.Error -> return@withContext result
                    Result.Loading -> return@withContext Result.Loading
                }
            }

            val res = Result.Success(
                BookingsResult(
                    bookings = allBookings,
                    total = total,
                    page = 1,
                    limit = allBookings.size.coerceAtLeast(1),
                    totalPages = 1,
                )
            )
            dao.upsertAll(res.data.bookings.map {
                BookingEntity(it.id, it.clientId, it.takerId, it.takerName,
                    it.clientName, it.bookingDate, it.serviceType, it.dayPart, it.eventLocation, it.notes, it.status,
                    it.clientVerificationStage, it.clientVerificationLabel)
            })
            res
        }
}

@Singleton
class EventRepository @Inject constructor(
    private val api: PhotoConnectApiService,
    private val dao: EventDao,
    private val calendarGuard: CalendarMutationGuard,
) {
    fun getCached(role: String, actorId: Int, clientProfileId: Int = 0) =
        dao.getForActor(role, actorId, clientProfileId)

    suspend fun fetchAll(
        role: String,
        actorId: Int,
        scope: String = "all",
        clientProfileId: Int = 0,
    ): Result<EventsResult> =
        withContext(Dispatchers.IO) {
            when (val result = safe { api.getEvents(scope = scope, page = 1, limit = 80) }) {
                is Result.Success -> {
                    dao.upsertAll(
                        result.data.events
                            .trimOfflineNotes()
                            .mapNotNull { calendarGuard.applyToRemoteEvent(it.toEntity()) }
                    )
                    result
                }
                is Result.Error -> result
                Result.Loading -> Result.Loading
            }
        }

    suspend fun create(
        request: UpsertEventRequest,
        role: String,
        actorId: Int,
        clientProfileId: Int,
    ): Result<EventIdData> =
        withContext(Dispatchers.IO) {
            val result = safe { api.createEvent(request) }
            if (result is Result.Success) {
                calendarGuard.rememberEvent(
                    cacheSavedEvent(request, result.data.eventId, role, actorId, clientProfileId)
                )
            }
            result
        }

    suspend fun update(
        request: UpsertEventRequest,
        role: String,
        actorId: Int,
        clientProfileId: Int,
    ): Result<EventIdData> =
        withContext(Dispatchers.IO) {
            val result = safe { api.updateEvent(request) }
            if (result is Result.Success) {
                calendarGuard.rememberEvent(
                    cacheSavedEvent(request, request.eventId ?: result.data.eventId, role, actorId, clientProfileId)
                )
            }
            result
        }

    suspend fun delete(eventId: Int): Result<DeleteData> =
        withContext(Dispatchers.IO) {
            val result = safe { api.deleteEvent(DeleteEventRequest(eventId)) }
            if (result is Result.Success) {
                calendarGuard.rememberDeletedEvent(eventId)
                dao.deleteById(eventId)
            }
            result
        }

    suspend fun clearOfflineNotes() =
        withContext(Dispatchers.IO) { dao.clearOfflineNotes() }

    private fun List<EventItem>.trimOfflineNotes(): List<EventItem> {
        var bytesUsed = 0
        return map { event ->
            val note = event.notes
            if (note.isNullOrBlank()) return@map event
            val bytes = note.toByteArray(StandardCharsets.UTF_8).size
            if (bytesUsed + bytes <= OFFLINE_NOTES_LIMIT_BYTES) {
                bytesUsed += bytes
                event
            } else {
                event.copy(notes = null)
            }
        }
    }

    private suspend fun cacheSavedEvent(
        request: UpsertEventRequest,
        eventId: Int,
        role: String,
        actorId: Int,
        clientProfileId: Int,
    ): EventEntity {
        val previous = request.eventId?.let { dao.getByIdOnce(it) }
        val createdByRole = previous?.createdByRole ?: role
        val createdById = previous?.createdById
            ?: if (role == "client" && clientProfileId > 0) clientProfileId else actorId
        val clientId = request.clientId
            ?: previous?.clientId
            ?: if (role == "client" && clientProfileId > 0) clientProfileId else null
        val now = System.currentTimeMillis().toString()
        val entity = EventEntity(
            id = eventId,
            bookingId = previous?.bookingId,
            createdByRole = createdByRole,
            createdById = createdById,
            clientId = clientId,
            takerId = request.takerId ?: previous?.takerId,
            title = request.title,
            eventDate = request.eventDate,
            dayPart = request.dayPart,
            serviceType = request.serviceType,
            location = request.location,
            clientName = request.clientName,
            clientPhone = request.clientPhone,
            takerName = request.takerName,
            takerPhone = previous?.takerPhone,
            totalAmount = request.totalAmount,
            paidAmount = request.paidAmount,
            balanceAmount = maxOf(0.0, request.totalAmount - request.paidAmount),
            notes = request.notes,
            status = request.status,
            createdAt = previous?.createdAt ?: now,
            updatedAt = now,
        )
        dao.upsert(entity)
        return entity
    }

    private companion object {
        const val OFFLINE_NOTES_LIMIT_BYTES = 2 * 1024 * 1024
    }
}

@Singleton
class ReviewRepository @Inject constructor(
    private val api: PhotoConnectApiService,
    private val dao: ReviewDao,
) {
    fun getCached(takerId: Int) = dao.getForTaker(takerId)

    suspend fun fetch(takerId: Int, page: Int? = null, limit: Int = 10, cacheBust: Long? = null): Result<ReviewsResult> =
        withContext(Dispatchers.IO) {
            if (page == null) {
                val allReviews = mutableListOf<Review>()
                var nextPage = 1
                var total = 0
                var totalPages = 1
                var avgRating = 0f

                var failure: Result<ReviewsResult>? = null
                while (nextPage <= totalPages && failure == null) {
                    when (val result = safe { api.getReviews(takerId, page = nextPage, limit = limit, cacheBust = cacheBust) }) {
                        is Result.Success -> {
                            total = result.data.total
                            totalPages = result.data.totalPages
                            avgRating = result.data.avgRating
                            allReviews += result.data.reviews
                            nextPage++
                        }
                        is Result.Error -> failure = result
                        Result.Loading -> failure = Result.Loading
                    }
                }
                val r = failure ?: Result.Success(
                    ReviewsResult(
                        takerId = takerId,
                        avgRating = avgRating,
                        total = total,
                        page = 1,
                        limit = allReviews.size.coerceAtLeast(1),
                        totalPages = 1,
                        reviews = allReviews,
                    )
                )
                return@withContext r
            }

            safe { api.getReviews(takerId, page = page, limit = limit, cacheBust = cacheBust) }
        }

    suspend fun add(r: AddReviewRequest) = withContext(Dispatchers.IO) { safe { api.addReview(r) } }
}

@Singleton
class PincodeRepository @Inject constructor(
    private val api: PincodeApiService,
    private val nominatimApi: NominatimApiService,
) {
    suspend fun lookup(pincode: String): List<PostOffice> =
        withContext(Dispatchers.IO) {
            val clean = pincode.filter(Char::isDigit).take(6)
            if (clean.length != 6) return@withContext emptyList()
            fetchPostalPincode(clean).ifEmpty {
                searchNominatim(clean).mapNotNull { it.toPostOffice(clean) }
            }
        }

    suspend fun searchPlace(place: String): List<PostOffice> =
        withContext(Dispatchers.IO) {
            val clean = place.trim()
            if (clean.length < 3) return@withContext emptyList()
            fetchPostalPlace(clean).ifEmpty {
                searchNominatim(clean).mapNotNull { it.toPostOffice() }
            }
        }

    private suspend fun fetchPostalPincode(pincode: String): List<PostOffice> =
        try {
            val response = api.lookup(pincode)
            val geoAddress = searchNominatim(pincode).firstOrNull()?.address
            val districtOrCity = listOf(
                geoAddress?.county,
                geoAddress?.stateDistrict,
                geoAddress?.cityDistrict,
                geoAddress?.city,
                geoAddress?.town,
                geoAddress?.village,
                geoAddress?.municipality,
            ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
            response.places.map { place ->
                PostOffice(
                    name = place.placeName,
                    district = districtOrCity,
                    state = place.state,
                    pincode = response.postCode,
                    block = null,
                    branchType = null
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    private suspend fun fetchPostalPlace(place: String): List<PostOffice> = emptyList()

    private suspend fun searchNominatim(query: String): List<NominatimPlace> =
        try {
            nominatimApi.searchPlaces(query)
        } catch (_: Exception) {
            emptyList()
        }

    private fun NominatimPlace.toPostOffice(fallbackPincode: String? = null): PostOffice? {
        val address = address ?: return null
        val city = listOf(
            address.city,
            address.town,
            address.village,
            address.municipality,
            address.cityDistrict,
            address.suburb,
            address.county,
            displayName?.substringBefore(","),
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
        val state = address.state?.trim().orEmpty()
        if (city.isBlank() && state.isBlank()) return null
        val district = listOf(address.county, address.stateDistrict, address.cityDistrict, city)
            .firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
        val pin = address.postcode?.filter(Char::isDigit)?.takeIf { it.length >= 5 }
            ?: fallbackPincode?.takeIf { it.length == 6 }
            ?: ""
        return PostOffice(
            name = displayName?.substringBefore(",")?.trim()?.ifBlank { null } ?: city.ifBlank { district.ifBlank { state } },
            district = district.ifBlank { city },
            state = state,
            pincode = pin,
        )
    }
}

@Singleton
class PlaceSuggestionRepository @Inject constructor(private val api: NominatimApiService) {
    suspend fun searchPlaces(query: String): List<NominatimPlace> =
        withContext(Dispatchers.IO) {
            try {
                api.searchPlaces(query.trim())
            } catch (e: Exception) {
                emptyList()
            }
        }
}
