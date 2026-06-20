package com.photoconnect.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Taker(
    @Json(name = "id")                val id: Int,
    @Json(name = "full_name")         val fullName: String,
    @Json(name = "phone")             val phone: String,
    @Json(name = "email")             val email: String,
    @Json(name = "pincode")           val pincode: String,
    @Json(name = "area")              val area: String,
    @Json(name = "city")              val city: String,
    @Json(name = "state")             val state: String,
    @Json(name = "service_types")     val serviceTypes: List<String> = emptyList(),
    @Json(name = "service_type")      val legacyServiceType: String? = null,
    @Json(name = "years_experience")  val yearsExperience: Int    = 0,
    @Json(name = "languages")         val languages: String?      = null,
    @Json(name = "instagram_url")     val instagramUrl: String?   = null,
    @Json(name = "youtube_url")       val youtubeUrl: String?     = null,
    @Json(name = "portfolio_url")     val portfolioUrl: String?   = null,
    @Json(name = "profile_image_url")   val profileImageUrl: String?  = null,
    @Json(name = "profile_thumb_url")   val profileThumbUrl: String?  = null,
    @Json(name = "profile_image_scope") val profileImageScope: String = "public",
    @Json(name = "avg_rating")        val avgRating: Float        = 0f,
    @Json(name = "review_count")      val reviewCount: Int        = 0,
    @Json(name = "is_featured")       val isFeatured: Int         = 0,
    @Json(name = "favorite_count")    val favoriteCount: Int      = 0,
    @Json(name = "viewer_has_favorited") val viewerHasFavorited: Boolean = false,
    @Json(name = "post_count")        val postCount: Int          = 0,
    @Json(name = "active_post_count") val activePostCount: Int    = 0,
    @Json(name = "post_reach")        val postReach: Int          = 0,
    @Json(name = "ranking_score")     val rankingScore: Double    = 0.0,
    @Json(name = "is_top_taker")      val isTopTaker: Boolean     = false,
    @Json(name = "proximity_label")   val proximityLabel: String? = null,
    @Json(name = "is_available")      val isAvailable: Int?       = null,
    @Json(name = "availability_status") val availabilityStatus: String? = null,
    @Json(name = "portfolio_samples") val portfolioSamples: List<PortfolioSample> = emptyList()
) {
    val offeredServices: List<String>
        get() = if (serviceTypes.isNotEmpty()) serviceTypes else listOfNotNull(legacyServiceType)

    val serviceType: String
        get() = offeredServices.firstOrNull().orEmpty()
}

@JsonClass(generateAdapter = true)
data class PortfolioSample(
    @Json(name = "id")        val id: Int,
    @Json(name = "taker_id")  val takerId: Int,
    @Json(name = "image_url") val imageUrl: String,
    @Json(name = "caption")   val caption: String? = null
)

@JsonClass(generateAdapter = true)
data class Availability(
    @Json(name = "date")   val date: String,
    @Json(name = "status") val status: String,
)

@JsonClass(generateAdapter = true)
data class Booking(
    @Json(name = "id")             val id: Int = 0,
    @Json(name = "client_id")      val clientId: Int,
    @Json(name = "taker_id")       val takerId: Int,
    @Json(name = "taker_name")     val takerName: String?  = null,
    @Json(name = "client_name")    val clientName: String? = null,
    @Json(name = "booking_date")   val bookingDate: String,
    @Json(name = "service_type")   val serviceType: String,
    @Json(name = "event_location") val eventLocation: String? = null,
    @Json(name = "notes")          val notes: String?      = null,
    @Json(name = "status")         val status: String      = "Pending",
)

@JsonClass(generateAdapter = true)
data class Review(
    @Json(name = "id")           val id: Int,
    @Json(name = "taker_id")     val takerId: Int,
    @Json(name = "client_id")    val clientId: Int,
    @Json(name = "client_name")  val clientName: String,
    @Json(name = "rating")       val rating: Int,
    @Json(name = "comment")      val comment: String? = null,
    @Json(name = "created_at")   val createdAt: String,
)

@JsonClass(generateAdapter = true)
data class ApiResponse<T>(
    @Json(name = "success") val success: Boolean,
    @Json(name = "message") val message: String,
    @Json(name = "data")    val data: T?,
)

@JsonClass(generateAdapter = true)
data class SearchResult(
    @Json(name = "takers")      val takers: List<Taker>,
    @Json(name = "featured")    val featured: List<Taker> = emptyList(),
    @Json(name = "total")       val total: Int,
    @Json(name = "page")        val page: Int,
    @Json(name = "total_pages") val totalPages: Int,
)

@JsonClass(generateAdapter = true)
data class AvailabilityResult(
    @Json(name = "taker_id")     val takerId: Int,
    @Json(name = "taker_name")   val takerName: String,
    @Json(name = "availability") val availability: List<Availability>,
)

@JsonClass(generateAdapter = true)
data class ReviewsResult(
    @Json(name = "taker_id")   val takerId: Int,
    @Json(name = "avg_rating") val avgRating: Float,
    @Json(name = "total")      val total: Int,
    @Json(name = "page")       val page: Int = 1,
    @Json(name = "limit")      val limit: Int = 20,
    @Json(name = "total_pages") val totalPages: Int = 1,
    @Json(name = "reviews")    val reviews: List<Review>,
)

@JsonClass(generateAdapter = true)
data class BookingsResult(
    @Json(name = "bookings") val bookings: List<Booking>,
    @Json(name = "total")    val total: Int,
    @Json(name = "page")     val page: Int = 1,
    @Json(name = "limit")    val limit: Int = 20,
    @Json(name = "total_pages") val totalPages: Int = 1,
)

// Requests
@JsonClass(generateAdapter = true)
data class LoginRequest(
    @Json(name = "role")     val role: String,
    @Json(name = "identity") val identity: String, // Can be phone or email
    @Json(name = "password") val password: String,
)

@JsonClass(generateAdapter = true)
data class RefreshTokenRequest(
    @Json(name = "role") val role: String,
    @Json(name = "user_id") val userId: Int,
    @Json(name = "refresh_token") val refreshToken: String,
)

@JsonClass(generateAdapter = true)
data class IdentityCheckRequest(
    @Json(name = "phone") val phone: String,
    @Json(name = "email") val email: String? = null,
)

@JsonClass(generateAdapter = true)
data class IdentityTakenData(
    @Json(name = "phone_registered") val phoneRegistered: Boolean,
    @Json(name = "email_registered") val emailRegistered: Boolean,
)

@JsonClass(generateAdapter = true)
data class RegisterClientRequest(
    @Json(name = "name")     val name: String,
    @Json(name = "phone")    val phone: String,
    @Json(name = "email")    val email: String? = null,
    @Json(name = "password") val password: String,
)

@JsonClass(generateAdapter = true)
data class RegisterTakerRequest(
    @Json(name = "full_name")        val fullName: String,
    @Json(name = "phone")            val phone: String,
    @Json(name = "email")            val email: String,
    @Json(name = "password")         val password: String,
    @Json(name = "pincode")          val pincode: String,
    @Json(name = "area")             val area: String,
    @Json(name = "city")             val city: String,
    @Json(name = "state")            val state: String,
    @Json(name = "service_types")    val serviceTypes: List<String>,
    @Json(name = "years_experience") val yearsExperience: Int = 0,
    @Json(name = "languages")        val languages: String?   = null,
    @Json(name = "instagram_url")    val instagramUrl: String?= null,
    @Json(name = "youtube_url")      val youtubeUrl: String?  = null,
    @Json(name = "portfolio_url")    val portfolioUrl: String? = null,
)

@JsonClass(generateAdapter = true)
data class UpdateTakerRequest(
    @Json(name = "taker_id")         val takerId: Int,
    @Json(name = "full_name")        val fullName: String,
    @Json(name = "email")            val email: String,
    @Json(name = "pincode")          val pincode: String,
    @Json(name = "area")             val area: String,
    @Json(name = "city")             val city: String,
    @Json(name = "state")            val state: String,
    @Json(name = "service_types")    val serviceTypes: List<String>,
    @Json(name = "years_experience") val yearsExperience: Int = 0,
    @Json(name = "languages")        val languages: String?   = null,
    @Json(name = "instagram_url")    val instagramUrl: String?= null,
    @Json(name = "youtube_url")      val youtubeUrl: String?  = null,
    @Json(name = "portfolio_url")    val portfolioUrl: String? = null,
    @Json(name = "profile_image_url") val profileImageUrl: String? = null,
)

@JsonClass(generateAdapter = true)
data class UpdateAvailabilityRequest(
    @Json(name = "taker_id") val takerId: Int,
    @Json(name = "dates")    val dates: List<AvailabilityEntry>,
)

@JsonClass(generateAdapter = true)
data class AvailabilityEntry(
    @Json(name = "date")   val date: String,
    @Json(name = "status") val status: String,
)

@JsonClass(generateAdapter = true)
data class BookTakerRequest(
    @Json(name = "client_id")      val clientId: Int,
    @Json(name = "taker_id")       val takerId: Int,
    @Json(name = "booking_date")   val bookingDate: String,
    @Json(name = "service_type")   val serviceType: String,
    @Json(name = "event_location") val eventLocation: String? = null,
    @Json(name = "notes")          val notes: String?         = null,
    // Optional client-side hint so the app can render the booking immediately; API ignores unknown fields.
    @Json(name = "taker_name")     val takerName: String?     = null,
)

@JsonClass(generateAdapter = true)
data class UpdateBookingStatusRequest(
    @Json(name = "booking_id") val bookingId: Int,
    @Json(name = "status") val status: String,
    @Json(name = "actor_role") val actorRole: String,
    @Json(name = "actor_id") val actorId: Int,
)

@JsonClass(generateAdapter = true)
data class AddReviewRequest(
    @Json(name = "taker_id")  val takerId: Int,
    @Json(name = "client_id") val clientId: Int,
    @Json(name = "rating")    val rating: Int,
    @Json(name = "comment")   val comment: String? = null,
)

@JsonClass(generateAdapter = true)
data class PincodeResponse(
    @Json(name = "Status")     val status: String,
    @Json(name = "PostOffice") val postOffices: List<PostOffice>?,
)

@JsonClass(generateAdapter = true)
data class PostOffice(
    @Json(name = "Name")       val name: String,
    @Json(name = "District")   val district: String,
    @Json(name = "State")      val state: String,
    @Json(name = "Pincode")    val pincode: String,
    @Json(name = "Block")      val block: String? = null,
    @Json(name = "BranchType") val branchType: String? = null,
)

@JsonClass(generateAdapter = true)
data class PortfolioResult(
    @Json(name = "taker_id") val takerId: Int,
    @Json(name = "samples")  val samples: List<PortfolioSample>
)

@JsonClass(generateAdapter = true)
data class DeleteSampleRequest(
    @Json(name = "taker_id")  val takerId: Int,
    @Json(name = "sample_id") val sampleId: Int
)
// ── Profile Image ─────────────────────────────────────────────
@JsonClass(generateAdapter = true)
data class ProfileImageData(
    @Json(name = "url")       val url: String,
    @Json(name = "thumb_url") val thumbUrl: String,
    @Json(name = "med_url")   val medUrl: String,
    @Json(name = "scope")     val scope: String,
)

@JsonClass(generateAdapter = true)
data class DeleteData(
    @Json(name = "deleted") val deleted: Boolean,
)

@JsonClass(generateAdapter = true)
data class TakerPostImage(
    @Json(name = "id") val id: Int,
    @Json(name = "post_id") val postId: Int,
    @Json(name = "image_url") val imageUrl: String,
    @Json(name = "thumb_url") val thumbUrl: String? = null,
    @Json(name = "like_count") val likeCount: Int = 0,
    @Json(name = "viewer_has_liked") val viewerHasLiked: Boolean = false,
    @Json(name = "sort_order") val sortOrder: Int = 0,
)

@JsonClass(generateAdapter = true)
data class TakerPost(
    @Json(name = "id") val id: Int,
    @Json(name = "taker_id") val takerId: Int,
    @Json(name = "caption") val caption: String? = null,
    @Json(name = "like_count") val likeCount: Int = 0,
    @Json(name = "view_count") val viewCount: Int = 0,
    @Json(name = "viewer_has_liked") val viewerHasLiked: Boolean = false,
    @Json(name = "viewer_has_saved") val viewerHasSaved: Boolean = false,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "images") val images: List<TakerPostImage> = emptyList(),
) {
    val coverImageUrl: String?
        get() = images.firstOrNull()?.imageUrl

    val coverThumbUrl: String?
        get() = images.firstOrNull()?.thumbUrl
}

@JsonClass(generateAdapter = true)
data class TakerPostSummary(
    @Json(name = "post_count") val postCount: Int = 0,
    @Json(name = "total_likes") val totalLikes: Int = 0,
    @Json(name = "total_views") val totalViews: Int = 0,
    @Json(name = "favorite_count") val favoriteCount: Int = 0,
    @Json(name = "avg_rating") val avgRating: Float = 0f,
    @Json(name = "review_count") val reviewCount: Int = 0,
)

@JsonClass(generateAdapter = true)
data class TakerPostsResult(
    @Json(name = "taker_id") val takerId: Int,
    @Json(name = "summary") val summary: TakerPostSummary = TakerPostSummary(),
    @Json(name = "total") val total: Int = 0,
    @Json(name = "page") val page: Int = 1,
    @Json(name = "limit") val limit: Int = 12,
    @Json(name = "total_pages") val totalPages: Int = 1,
    @Json(name = "posts") val posts: List<TakerPost> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TakerPostData(
    @Json(name = "post") val post: TakerPost,
)

@JsonClass(generateAdapter = true)
data class ToggleTakerPostLikeData(
    @Json(name = "post_id") val postId: Int,
    @Json(name = "like_count") val likeCount: Int,
    @Json(name = "viewer_has_liked") val viewerHasLiked: Boolean,
)

@JsonClass(generateAdapter = true)
data class ToggleTakerPostImageLikeData(
    @Json(name = "image_id") val imageId: Int,
    @Json(name = "post_id") val postId: Int,
    @Json(name = "image_like_count") val imageLikeCount: Int,
    @Json(name = "post_like_count") val postLikeCount: Int,
    @Json(name = "viewer_has_liked") val viewerHasLiked: Boolean,
)

@JsonClass(generateAdapter = true)
data class TakerPostViewData(
    @Json(name = "post_id") val postId: Int,
    @Json(name = "view_count") val viewCount: Int,
)

@JsonClass(generateAdapter = true)
data class ToggleTakerPostLikeRequest(
    @Json(name = "post_id") val postId: Int,
    @Json(name = "actor_role") val actorRole: String,
    @Json(name = "actor_id") val actorId: Int,
    @Json(name = "like") val like: Boolean,
)

@JsonClass(generateAdapter = true)
data class ToggleTakerPostImageLikeRequest(
    @Json(name = "image_id") val imageId: Int,
    @Json(name = "actor_role") val actorRole: String,
    @Json(name = "actor_id") val actorId: Int,
    @Json(name = "like") val like: Boolean,
)

@JsonClass(generateAdapter = true)
data class RecordTakerPostViewRequest(
    @Json(name = "post_id") val postId: Int,
    @Json(name = "viewer_role") val viewerRole: String,
    @Json(name = "viewer_id") val viewerId: Int,
)

@JsonClass(generateAdapter = true)
data class DeleteTakerPostRequest(
    @Json(name = "taker_id") val takerId: Int,
    @Json(name = "post_id") val postId: Int,
)

@JsonClass(generateAdapter = true)
data class ToggleTakerPostSaveRequest(
    @Json(name = "post_id") val postId: Int,
    @Json(name = "actor_role") val actorRole: String,
    @Json(name = "actor_id") val actorId: Int,
    @Json(name = "save") val save: Boolean,
)

@JsonClass(generateAdapter = true)
data class ToggleTakerPostSaveData(
    @Json(name = "post_id") val postId: Int,
    @Json(name = "save_count") val saveCount: Int,
    @Json(name = "viewer_has_saved") val viewerHasSaved: Boolean,
)

@JsonClass(generateAdapter = true)
data class FavoriteStatusData(
    @Json(name = "taker_id") val takerId: Int,
    @Json(name = "is_favorite") val isFavorite: Boolean,
    @Json(name = "favorite_count") val favoriteCount: Int,
)

@JsonClass(generateAdapter = true)
data class ToggleFavoriteRequest(
    @Json(name = "taker_id") val takerId: Int,
    @Json(name = "actor_role") val actorRole: String,
    @Json(name = "actor_id") val actorId: Int,
    @Json(name = "favorite") val favorite: Boolean,
)

@JsonClass(generateAdapter = true)
data class DeleteTakerAccountRequest(
    @Json(name = "taker_id") val takerId: Int,
)

@JsonClass(generateAdapter = true)
data class NotificationItem(
    @Json(name = "id") val id: Int,
    @Json(name = "recipient_role") val recipientRole: String,
    @Json(name = "recipient_id") val recipientId: Int,
    @Json(name = "type") val type: String,
    @Json(name = "title") val title: String,
    @Json(name = "message") val message: String,
    @Json(name = "payload") val payload: Map<String, Any?> = emptyMap(),
    @Json(name = "is_read") val isRead: Boolean = false,
    @Json(name = "read_at") val readAt: String? = null,
    @Json(name = "created_at") val createdAt: String,
)

@JsonClass(generateAdapter = true)
data class NotificationsResult(
    @Json(name = "notifications") val notifications: List<NotificationItem>,
    @Json(name = "total") val total: Int,
    @Json(name = "unread_count") val unreadCount: Int,
    @Json(name = "page") val page: Int = 1,
    @Json(name = "limit") val limit: Int = 20,
    @Json(name = "total_pages") val totalPages: Int = 1,
)

@JsonClass(generateAdapter = true)
data class MarkNotificationReadRequest(
    @Json(name = "notification_id") val notificationId: Int,
)

@JsonClass(generateAdapter = true)
data class NotificationReadData(
    @Json(name = "notification_id") val notificationId: Int,
    @Json(name = "is_read") val isRead: Boolean,
)
