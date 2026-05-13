package com.photoconnect.repository

import android.content.Context
import android.net.Uri
import com.photoconnect.db.*
import com.photoconnect.model.*
import com.photoconnect.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String, val code: Int = 0) : Result<Nothing>()
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

private suspend fun <T> safe(call: suspend () -> Response<ApiResponse<T>>): Result<T> =
    try {
        val r = call()
        val body = r.body()
        if (r.isSuccessful) {
            if (body?.success == true && body.data != null) {
                Result.Success(body.data)
            } else {
                Result.Error(body?.message ?: "Error (${r.code()})", r.code())
            }
        } else {
            val rawError = r.errorBody()?.string()
            val msg = body?.message ?: pc_extract_json_message(rawError) ?: "Error (${r.code()})"
            Result.Error(msg, r.code())
        }
    } catch (e: Exception) {
        Result.Error(e.localizedMessage ?: "Network error")
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
        } ?: error("Cannot read image")
    }
}

private fun buildImagePart(
    context: Context,
    partName: String,
    fileName: String,
    imageUri: Uri,
): MultipartBody.Part {
    val mimeType = context.contentResolver.getType(imageUri) ?: "image/jpeg"
    return MultipartBody.Part.createFormData(
        partName,
        fileName,
        UriRequestBody(context, imageUri, mimeType),
    )
}

@Singleton
class AuthRepository @Inject constructor(private val api: PhotoConnectApiService) {
    suspend fun login(role: String, identity: String, password: String) =
        withContext(Dispatchers.IO) { safe { api.login(LoginRequest(role, identity, password)) } }

    suspend fun checkIdentity(phone: String, email: String?) =
        withContext(Dispatchers.IO) { safe { api.identityCheck(IdentityCheckRequest(phone, email?.ifBlank { null })) } }

    suspend fun registerTaker(r: RegisterTakerRequest) =
        withContext(Dispatchers.IO) { safe { api.registerTaker(r) } }
    suspend fun registerClient(r: RegisterClientRequest) =
        withContext(Dispatchers.IO) { safe { api.registerClient(r) } }
}

@Singleton
class TakerRepository @Inject constructor(
    private val api: PhotoConnectApiService,
    private val dao: TakerDao,
) {
    fun getCached(city: String?, svc: String?) = dao.search(city, svc)
    fun getFeatured() = dao.getFeatured()
    fun getTaker(id: Int) = dao.getById(id)

    suspend fun fetchAndCache(
        loc: String?,
        date: String?,
        svc: String?,
        availOnly: Boolean = false,
        latitude: Double? = null,
        longitude: Double? = null,
        page: Int = 1
    ): Result<SearchResult> =
        withContext(Dispatchers.IO) {
            val r = safe { api.searchTakers(loc, date, svc, availOnly, latitude, longitude, page) }
            if (r is Result.Success) {
                if (page == 1) dao.deleteAll()
                dao.upsertAll((r.data.takers + r.data.featured).distinctBy { it.id }.map { t ->
                    TakerEntity(
                        id = t.id, fullName = t.fullName, phone = t.phone, email = t.email,
                        pincode = t.pincode, area = t.area, city = t.city, state = t.state,
                        serviceTypes = t.offeredServices.joinToString(","),
                        yearsExperience = t.yearsExperience, languages = t.languages,
                        instagramUrl = t.instagramUrl, youtubeUrl = t.youtubeUrl,
                        portfolioUrl = t.portfolioUrl, profileImageUrl = t.profileImageUrl,
                        profileThumbUrl = t.profileThumbUrl,
                        profileImageScope = t.profileImageScope,
                        avgRating = t.avgRating, reviewCount = t.reviewCount, isFeatured = t.isFeatured
                    )
                })
            }
            r
        }

    suspend fun updateProfile(r: UpdateTakerRequest) = withContext(Dispatchers.IO) {
        safe { api.updateTakerProfile(r) }
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

            val result = safe { api.uploadProfileImage(takerIdBody, scopeBody, imagePart) }
            if (result is Result.Success) {
                dao.updateProfileImage(
                    id = takerId,
                    profileImageUrl = result.data.url,
                    profileThumbUrl = result.data.thumbUrl,
                    profileImageScope = result.data.scope,
                )
            }
            result
        } catch (e: Exception) {
            Result.Error(e.message ?: "Upload failed")
        }
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

            safe { api.uploadTakerPost(takerIdBody, captionBody, imageParts) }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Post upload failed")
        }
    }

    suspend fun fetchTakerPosts(
        takerId: Int,
        viewerRole: String? = null,
        viewerId: Int? = null
    ): Result<TakerPostsResult> = withContext(Dispatchers.IO) {
        val posts = mutableListOf<TakerPost>()
        var page = 1
        var total = 0
        var totalPages = 1
        var summary = TakerPostSummary()

        while (page <= totalPages) {
            when (val result = safe { api.getTakerPosts(takerId, viewerRole, viewerId, page = page) }) {
                is Result.Success -> {
                    total = result.data.total
                    totalPages = result.data.totalPages
                    summary = result.data.summary
                    posts += result.data.posts
                    page++
                }
                is Result.Error -> return@withContext result
                Result.Loading -> return@withContext Result.Loading
            }
        }

        Result.Success(
            TakerPostsResult(
                takerId = takerId,
                summary = summary,
                total = total,
                page = 1,
                limit = posts.size.coerceAtLeast(1),
                totalPages = 1,
                posts = posts,
            )
        )
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

    suspend fun deleteTakerAccount(takerId: Int): Result<DeleteData> =
        withContext(Dispatchers.IO) {
            safe { api.deleteTakerAccount(DeleteTakerAccountRequest(takerId)) }
        }
}

@Singleton
class FavoriteRepository @Inject constructor(
    private val dao: FavoriteDao,
    private val api: PhotoConnectApiService,
) {
    fun getFavorites(userId: Int) = dao.getFavorites(userId)
    fun isFavorite(userId: Int, takerId: Int) = dao.isFavorite(userId, takerId)
    suspend fun addFavorite(userId: Int, takerId: Int) = withContext(Dispatchers.IO) {
        dao.add(FavoriteEntity(userId, takerId))
    }
    suspend fun removeFavorite(userId: Int, takerId: Int) = withContext(Dispatchers.IO) {
        dao.remove(userId, takerId)
    }

    suspend fun fetchFavoriteStatus(takerId: Int, actorRole: String, actorId: Int): Result<FavoriteStatusData> =
        withContext(Dispatchers.IO) {
            val result = safe { api.getFavoriteStatus(takerId, actorRole, actorId) }
            if (result is Result.Success) {
                if (result.data.isFavorite) {
                    dao.add(FavoriteEntity(actorId, takerId))
                } else {
                    dao.remove(actorId, takerId)
                }
            }
            result
        }

    suspend fun toggleFavorite(
        takerId: Int,
        actorRole: String,
        actorId: Int,
        favorite: Boolean,
    ): Result<FavoriteStatusData> = withContext(Dispatchers.IO) {
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
        if (result is Result.Success) {
            if (result.data.isFavorite) {
                dao.add(FavoriteEntity(actorId, takerId))
            } else {
                dao.remove(actorId, takerId)
            }
        }
        result
    }
}

@Singleton
class NotificationRepository @Inject constructor(
    private val api: PhotoConnectApiService,
) {
    suspend fun fetchAll(limit: Int = 20): Result<NotificationsResult> =
        withContext(Dispatchers.IO) {
            val notifications = mutableListOf<NotificationItem>()
            var page = 1
            var total = 0
            var totalPages = 1
            var unreadCount = 0

            while (page <= totalPages) {
                when (val result = safe { api.getNotifications(page = page, limit = limit) }) {
                    is Result.Success -> {
                        total = result.data.total
                        totalPages = result.data.totalPages
                        unreadCount = result.data.unreadCount
                        notifications += result.data.notifications
                        page++
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

    suspend fun markRead(notificationId: Int): Result<NotificationReadData> =
        withContext(Dispatchers.IO) {
            safe { api.markNotificationRead(MarkNotificationReadRequest(notificationId)) }
        }
}

@Singleton
class AvailabilityRepository @Inject constructor(
    private val api: PhotoConnectApiService,
    private val dao: AvailabilityDao,
) {
    fun getCached(takerId: Int) = dao.getForTaker(takerId)

    suspend fun fetch(takerId: Int, month: String? = null): Result<AvailabilityResult> =
        withContext(Dispatchers.IO) {
            val r = safe { api.getAvailability(takerId, month) }
            if (r is Result.Success) {
                val rows = r.data.availability.map { AvailabilityEntity(takerId, it.date, it.status) }
                val ym = month?.takeIf { it.matches(Regex("\\d{4}-\\d{2}.*")) }?.take(7)
                if (ym != null) {
                    dao.deleteMonth(takerId, ym)
                    dao.upsertAll(rows)
                } else {
                    dao.deleteForTaker(takerId)
                    dao.upsertAll(rows)
                }
            }
            r
        }

    suspend fun update(takerId: Int, entries: List<AvailabilityEntry>): Result<CountData> =
        withContext(Dispatchers.IO) {
            val r = safe { api.updateAvailability(UpdateAvailabilityRequest(takerId, entries)) }
            if (r is Result.Success)
                dao.upsertAll(entries.map { AvailabilityEntity(takerId, it.date, it.status) })
            r
        }
}

@Singleton
class BookingRepository @Inject constructor(
    private val api: PhotoConnectApiService,
    private val dao: BookingDao,
) {
    fun getByClient(id: Int) = dao.getByClient(id)
    fun getByTaker(id: Int)  = dao.getByTaker(id)

    suspend fun book(r: BookTakerRequest): Result<BookingData> =
        withContext(Dispatchers.IO) {
            val res = safe { api.bookTaker(r) }
            if (res is Result.Success)
                dao.upsert(BookingEntity(res.data.bookingId, r.clientId, r.takerId,
                    r.takerName, null, r.bookingDate, r.serviceType, r.eventLocation, r.notes))
            res
        }

    suspend fun updateStatus(r: UpdateBookingStatusRequest): Result<BookingStatusData> =
        withContext(Dispatchers.IO) {
            val res = safe { api.updateBookingStatus(r) }
            if (res is Result.Success) dao.updateStatus(r.bookingId, res.data.status)
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
            if (res is Result.Success)
                dao.upsertAll(res.data.bookings.map {
                    BookingEntity(it.id, it.clientId, it.takerId, it.takerName,
                        it.clientName, it.bookingDate, it.serviceType, it.eventLocation, it.notes, it.status)
                })
            res
        }
}

@Singleton
class ReviewRepository @Inject constructor(
    private val api: PhotoConnectApiService,
    private val dao: ReviewDao,
) {
    fun getCached(takerId: Int) = dao.getForTaker(takerId)

    suspend fun fetch(takerId: Int): Result<ReviewsResult> =
        withContext(Dispatchers.IO) {
            val allReviews = mutableListOf<Review>()
            var page = 1
            var total = 0
            var totalPages = 1
            var avgRating = 0f

            var failure: Result<ReviewsResult>? = null
            while (page <= totalPages && failure == null) {
                when (val result = safe { api.getReviews(takerId, page = page) }) {
                    is Result.Success -> {
                        total = result.data.total
                        totalPages = result.data.totalPages
                        avgRating = result.data.avgRating
                        allReviews += result.data.reviews
                        page++
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
            if (r is Result.Success) {
                dao.deleteForTaker(takerId)
                dao.upsertAll(r.data.reviews.map {
                    ReviewEntity(it.id, it.takerId, it.clientId, it.clientName, it.rating, it.comment, it.createdAt)
                })
            }
            r
        }

    suspend fun add(r: AddReviewRequest) = withContext(Dispatchers.IO) { safe { api.addReview(r) } }
}

@Singleton
class PincodeRepository @Inject constructor(private val api: PincodeApiService) {
    suspend fun lookup(pincode: String): List<PostOffice> =
        withContext(Dispatchers.IO) {
            try {
                api.lookup(pincode).firstOrNull { it.status == "Success" }?.postOffices.orEmpty()
            } catch (e: Exception) { emptyList() }
        }

    suspend fun searchPlace(place: String): List<PostOffice> =
        withContext(Dispatchers.IO) {
            try {
                api.searchPlace(place).firstOrNull { it.status == "Success" }?.postOffices.orEmpty()
            } catch (e: Exception) { emptyList() }
        }
}
