package com.photoconnect.model

import com.squareup.moshi.Json

data class SubmitHelpTicketRequest(
    @Json(name = "phone") val phone: String,
    @Json(name = "problem") val problem: String,
    @Json(name = "logs") val logs: String
)

data class SubmitStudioVerificationRequest(
    @Json(name = "client_id") val clientId: Int,
    @Json(name = "verification_path") val verificationPath: String,
    @Json(name = "gstin") val gstin: String? = null,
    @Json(name = "google_maps_url") val googleMapsUrl: String? = null,
    @Json(name = "owner_aadhaar_submitted") val ownerAadhaarSubmitted: Boolean = false,
)

data class SubmitTakerVerificationRequest(
    @Json(name = "taker_id") val takerId: Int,
    @Json(name = "social_url") val socialUrl: String,
    @Json(name = "portfolio_url") val portfolioUrl: String? = null,
    @Json(name = "aadhaar_submitted") val aadhaarSubmitted: Boolean = false,
)

data class ToggleTakerEndorsementRequest(
    @Json(name = "taker_id") val takerId: Int,
    @Json(name = "client_id") val clientId: Int,
    @Json(name = "endorse") val endorse: Boolean,
    @Json(name = "email_otp") val emailOtp: String? = null,
)

data class HelpTicketData(
    @Json(name = "id") val id: Int,
    @Json(name = "problem") val problem: String,
    @Json(name = "created_at") val createdAt: String
)

data class SendOtpRequest(
    @Json(name = "phone") val phone: String,
    @Json(name = "purpose") val purpose: String = "registration",
)

data class SendOtpData(
    @Json(name = "sent") val sent: Boolean = false,
    @Json(name = "expires_in") val expiresIn: Int? = null,
    @Json(name = "cooldown_seconds") val cooldownSeconds: Int? = null,
    @Json(name = "retry_after") val retryAfter: Int? = null,
)

data class VerifyOtpRequest(
    @Json(name = "phone") val phone: String,
    @Json(name = "otp") val otp: String
)

data class SendEmailOtpRequest(
    @Json(name = "email") val email: String,
    @Json(name = "purpose") val purpose: String = "registration",
)

data class VerifyEmailOtpRequest(
    @Json(name = "email") val email: String,
    @Json(name = "otp") val otp: String
)

data class ResetPasswordRequest(
    @Json(name = "identity") val identity: String,
    @Json(name = "otp") val otp: String,
    @Json(name = "new_password") val newPassword: String,
)

data class OtpVerificationData(
    @Json(name = "verified") val verified: Boolean = false,
    @Json(name = "verification_token") val verificationToken: String? = null,
    @Json(name = "expires_in") val expiresIn: Int? = null,
)

data class ResetPasswordData(
    @Json(name = "updated_count") val updatedCount: Int = 0,
)

data class HelpTicketsResult(
    @Json(name = "tickets") val tickets: List<HelpTicketData>
)

data class Taker(
    @Json(name = "id")                val id: Int,
    @Json(name = "full_name")         val fullName: String,
    @Json(name = "phone")             val phone: String? = null,
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
    @Json(name = "social_link_additional1") val socialLinkAdditional1: String? = null,
    @Json(name = "social_link_additional2") val socialLinkAdditional2: String? = null,
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
    @Json(name = "trust_stage")       val trustStage: String      = "unverified",
    @Json(name = "trust_label")       val trustLabel: String      = "Unverified",
    @Json(name = "identity_verified") val identityVerified: Boolean = false,
    @Json(name = "portfolio_verified") val portfolioVerified: Boolean = false,
    @Json(name = "social_verified")   val socialVerified: Boolean = false,
    @Json(name = "completed_booking_count") val completedBookingCount: Int = 0,
    @Json(name = "endorsement_count") val endorsementCount: Int = 0,
    @Json(name = "proximity_label")   val proximityLabel: String? = null,
    @Json(name = "distance_km")       val distanceKm: Double? = null,
    @Json(name = "search_distance_km") val searchDistanceKm: Double? = null,
    @Json(name = "device_distance_km") val deviceDistanceKm: Double? = null,
    @Json(name = "matched_service_count") val matchedServiceCount: Int = 0,
    @Json(name = "service_match_label") val serviceMatchLabel: String? = null,
    @Json(name = "responds_fast") val respondsFast: Boolean = false,
    @Json(name = "responded_booking_count") val respondedBookingCount: Int = 0,
    @Json(name = "avg_response_minutes") val avgResponseMinutes: Double? = null,
    @Json(name = "search_explanation") val searchExplanation: String? = null,
    @Json(name = "is_available")      val isAvailable: Int?       = null,
    @Json(name = "availability_status") val availabilityStatus: String? = null,
    @Json(name = "portfolio_samples") val portfolioSamples: List<PortfolioSample> = emptyList()
) {
    val offeredServices: List<String>
        get() = if (serviceTypes.isNotEmpty()) serviceTypes else listOfNotNull(legacyServiceType)

    val serviceType: String
        get() = offeredServices.firstOrNull().orEmpty()
}

data class PortfolioSample(
    @Json(name = "id")        val id: Int,
    @Json(name = "taker_id")  val takerId: Int,
    @Json(name = "image_url") val imageUrl: String,
    @Json(name = "caption")   val caption: String? = null
)

data class Availability(
    @Json(name = "date")   val date: String,
    @Json(name = "status") val status: String,
    @Json(name = "day_part") val dayPart: String = "full_day",
)

data class Booking(
    @Json(name = "id")             val id: Int = 0,
    @Json(name = "client_id")      val clientId: Int,
    @Json(name = "taker_id")       val takerId: Int,
    @Json(name = "taker_name")     val takerName: String?  = null,
    @Json(name = "client_name")    val clientName: String? = null,
    @Json(name = "booking_date")   val bookingDate: String,
    @Json(name = "service_type")   val serviceType: String,
    @Json(name = "day_part")       val dayPart: String = "full_day",
    @Json(name = "event_location") val eventLocation: String? = null,
    @Json(name = "notes")          val notes: String?      = null,
    @Json(name = "status")         val status: String      = "Pending",
    @Json(name = "client_verification_stage") val clientVerificationStage: String = "unverified",
    @Json(name = "client_verification_label") val clientVerificationLabel: String = "Client not verified",
)

data class Review(
    @Json(name = "id")           val id: Int,
    @Json(name = "taker_id")     val takerId: Int,
    @Json(name = "client_id")    val clientId: Int,
    @Json(name = "client_name")  val clientName: String,
    @Json(name = "rating")       val rating: Int,
    @Json(name = "comment")      val comment: String? = null,
    @Json(name = "created_at")   val createdAt: String,
)

data class ApiResponse<T>(
    @Json(name = "success") val success: Boolean,
    @Json(name = "message") val message: String,
    @Json(name = "data")    val data: T?,
)

data class SearchResult(
    @Json(name = "takers")      val takers: List<Taker>,
    @Json(name = "featured")    val featured: List<Taker> = emptyList(),
    @Json(name = "total")       val total: Int,
    @Json(name = "page")        val page: Int,
    @Json(name = "total_pages") val totalPages: Int,
    @Json(name = "search_radius_km") val searchRadiusKm: Double? = null,
    @Json(name = "requested_radius_km") val requestedRadiusKm: Double? = null,
    @Json(name = "max_radius_km") val maxRadiusKm: Double? = null,
    @Json(name = "auto_expanded_radius") val autoExpandedRadius: Boolean = false,
    @Json(name = "result_explanation") val resultExplanation: String? = null,
    @Json(name = "nearby_alternatives") val nearbyAlternatives: List<SearchAlternative> = emptyList(),
)

data class SearchAlternative(
    @Json(name = "title") val title: String,
    @Json(name = "subtitle") val subtitle: String? = null,
    @Json(name = "distance_km") val distanceKm: Double? = null,
    @Json(name = "creator_count") val creatorCount: Int = 0,
    @Json(name = "query") val query: String,
)

data class SearchEventRequest(
    @Json(name = "event_type") val eventType: String,
    @Json(name = "query_text") val queryText: String? = null,
    @Json(name = "location_text") val locationText: String? = null,
    @Json(name = "service_types") val serviceTypes: List<String> = emptyList(),
    @Json(name = "service_match_mode") val serviceMatchMode: String = "smart",
    @Json(name = "requested_radius_km") val requestedRadiusKm: Double? = null,
    @Json(name = "applied_radius_km") val appliedRadiusKm: Double? = null,
    @Json(name = "result_count") val resultCount: Int = 0,
    @Json(name = "taker_id") val takerId: Int? = null,
    @Json(name = "filters") val filters: Map<String, String> = emptyMap(),
)

data class SearchAlertRequest(
    @Json(name = "query_text") val queryText: String? = null,
    @Json(name = "location_text") val locationText: String? = null,
    @Json(name = "service_types") val serviceTypes: List<String> = emptyList(),
    @Json(name = "service_match_mode") val serviceMatchMode: String = "smart",
    @Json(name = "radius_km") val radiusKm: Double = 25.0,
    @Json(name = "filters") val filters: Map<String, String> = emptyMap(),
)

data class SearchAlertData(
    @Json(name = "id") val id: Int,
)

data class TrendingSearch(
    @Json(name = "title") val title: String,
    @Json(name = "search_count") val searchCount: Int = 0,
    @Json(name = "successful_count") val successfulCount: Int = 0,
    @Json(name = "last_searched_at") val lastSearchedAt: String? = null,
)

data class TrendingSearchesResult(
    @Json(name = "trending") val trending: List<TrendingSearch> = emptyList(),
)

data class AvailabilityResult(
    @Json(name = "taker_id")     val takerId: Int,
    @Json(name = "taker_name")   val takerName: String,
    @Json(name = "availability") val availability: List<Availability>,
)

data class ReviewsResult(
    @Json(name = "taker_id")   val takerId: Int,
    @Json(name = "avg_rating") val avgRating: Float,
    @Json(name = "total")      val total: Int,
    @Json(name = "page")       val page: Int = 1,
    @Json(name = "limit")      val limit: Int = 20,
    @Json(name = "total_pages") val totalPages: Int = 1,
    @Json(name = "reviews")    val reviews: List<Review>,
)

data class TakerTrustSummary(
    @Json(name = "stage") val stage: String = "unverified",
    @Json(name = "label") val label: String = "Unverified",
    @Json(name = "identity_verified") val identityVerified: Boolean = false,
    @Json(name = "portfolio_verified") val portfolioVerified: Boolean = false,
    @Json(name = "social_verified") val socialVerified: Boolean = false,
    @Json(name = "aadhaar_status") val aadhaarStatus: String = "not_submitted",
    @Json(name = "portfolio_status") val portfolioStatus: String = "not_submitted",
    @Json(name = "social_status") val socialStatus: String = "not_submitted",
    @Json(name = "completed_booking_count") val completedBookingCount: Int = 0,
    @Json(name = "endorsement_count") val endorsementCount: Int = 0,
    @Json(name = "review_count") val reviewCount: Int = 0,
    @Json(name = "avg_rating") val avgRating: Float = 0f,
    @Json(name = "can_endorse") val canEndorse: Boolean = false,
    @Json(name = "viewer_has_endorsed") val viewerHasEndorsed: Boolean = false,
)

data class StudioTrustSummary(
    @Json(name = "stage") val stage: String = "unverified",
    @Json(name = "label") val label: String = "Unverified studio",
    @Json(name = "business_verified") val businessVerified: Boolean = false,
    @Json(name = "trusted") val trusted: Boolean = false,
    @Json(name = "can_book") val canBook: Boolean = false,
    @Json(name = "business_status") val businessStatus: String = "not_submitted",
    @Json(name = "owner_aadhaar_status") val ownerAadhaarStatus: String = "not_submitted",
    @Json(name = "completed_booking_count") val completedBookingCount: Int = 0,
    @Json(name = "avg_rating") val avgRating: Float = 0f,
    @Json(name = "rating_count") val ratingCount: Int = 0,
    @Json(name = "earned_condition_count") val earnedConditionCount: Int = 0,
    @Json(name = "verification_path") val verificationPath: String? = null,
)

data class TrustStatusResult(
    @Json(name = "taker_trust") val takerTrust: TakerTrustSummary? = null,
    @Json(name = "studio_trust") val studioTrust: StudioTrustSummary? = null,
)

data class TrustActionResult(
    @Json(name = "taker_trust") val takerTrust: TakerTrustSummary? = null,
    @Json(name = "studio_trust") val studioTrust: StudioTrustSummary? = null,
)

data class BookingsResult(
    @Json(name = "bookings") val bookings: List<Booking>,
    @Json(name = "total")    val total: Int,
    @Json(name = "page")     val page: Int = 1,
    @Json(name = "limit")    val limit: Int = 20,
    @Json(name = "total_pages") val totalPages: Int = 1,
)

data class EventItem(
    @Json(name = "id") val id: Int = 0,
    @Json(name = "booking_id") val bookingId: Int? = null,
    @Json(name = "created_by_role") val createdByRole: String,
    @Json(name = "created_by_id") val createdById: Int,
    @Json(name = "client_id") val clientId: Int? = null,
    @Json(name = "taker_id") val takerId: Int? = null,
    @Json(name = "title") val title: String,
    @Json(name = "event_date") val eventDate: String,
    @Json(name = "day_part") val dayPart: String = "full_day",
    @Json(name = "service_type") val serviceType: String? = null,
    @Json(name = "location") val location: String? = null,
    @Json(name = "client_name") val clientName: String? = null,
    @Json(name = "client_phone") val clientPhone: String? = null,
    @Json(name = "taker_name") val takerName: String? = null,
    @Json(name = "taker_phone") val takerPhone: String? = null,
    @Json(name = "total_amount") val totalAmount: Double = 0.0,
    @Json(name = "paid_amount") val paidAmount: Double = 0.0,
    @Json(name = "balance_amount") val balanceAmount: Double = 0.0,
    @Json(name = "notes") val notes: String? = null,
    @Json(name = "status") val status: String = "Upcoming",
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
)

data class EventsResult(
    @Json(name = "events") val events: List<EventItem>,
    @Json(name = "total") val total: Int,
    @Json(name = "page") val page: Int = 1,
    @Json(name = "limit") val limit: Int = 40,
    @Json(name = "total_pages") val totalPages: Int = 1,
)

data class UpsertEventRequest(
    @Json(name = "event_id") val eventId: Int? = null,
    @Json(name = "client_request_id") val clientRequestId: String? = null,
    @Json(name = "client_id") val clientId: Int? = null,
    @Json(name = "taker_id") val takerId: Int? = null,
    @Json(name = "title") val title: String,
    @Json(name = "event_date") val eventDate: String,
    @Json(name = "day_part") val dayPart: String = "full_day",
    @Json(name = "service_type") val serviceType: String? = null,
    @Json(name = "location") val location: String? = null,
    @Json(name = "client_name") val clientName: String? = null,
    @Json(name = "client_phone") val clientPhone: String? = null,
    @Json(name = "taker_name") val takerName: String? = null,
    @Json(name = "total_amount") val totalAmount: Double = 0.0,
    @Json(name = "paid_amount") val paidAmount: Double = 0.0,
    @Json(name = "notes") val notes: String? = null,
    @Json(name = "status") val status: String = "Upcoming",
)

data class EventIdData(
    @Json(name = "event_id") val eventId: Int,
)

data class DeleteEventRequest(
    @Json(name = "event_id") val eventId: Int,
)

// Requests
data class LoginRequest(
    @Json(name = "role")     val role: String,
    @Json(name = "identity") val identity: String, // Can be phone or email
    @Json(name = "password") val password: String,
)

data class RefreshTokenRequest(
    @Json(name = "role") val role: String,
    @Json(name = "user_id") val userId: Int,
    @Json(name = "refresh_token") val refreshToken: String,
)

data class IdentityCheckRequest(
    @Json(name = "phone") val phone: String,
    @Json(name = "email") val email: String? = null,
)

data class IdentityTakenData(
    @Json(name = "phone_registered") val phoneRegistered: Boolean,
    @Json(name = "email_registered") val emailRegistered: Boolean,
)

data class RegisterClientRequest(
    @Json(name = "name")     val name: String,
    @Json(name = "phone")    val phone: String,
    @Json(name = "email")    val email: String? = null,
    @Json(name = "password") val password: String,
    @Json(name = "phone_otp") val phoneOtp: String? = null,
    @Json(name = "email_otp") val emailOtp: String? = null,
    @Json(name = "phone_verification_token") val phoneVerificationToken: String? = null,
    @Json(name = "email_verification_token") val emailVerificationToken: String? = null,
)

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
    @Json(name = "social_link_additional1") val socialLinkAdditional1: String? = null,
    @Json(name = "social_link_additional2") val socialLinkAdditional2: String? = null,
    @Json(name = "phone_otp")        val phoneOtp: String? = null,
    @Json(name = "email_otp")        val emailOtp: String? = null,
    @Json(name = "phone_verification_token") val phoneVerificationToken: String? = null,
    @Json(name = "email_verification_token") val emailVerificationToken: String? = null,
)

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
    @Json(name = "social_link_additional1") val socialLinkAdditional1: String? = null,
    @Json(name = "social_link_additional2") val socialLinkAdditional2: String? = null,
    @Json(name = "profile_image_url") val profileImageUrl: String? = null,
    @Json(name = "phone")            val phone: String? = null,
    @Json(name = "phone_verification_token") val phoneVerificationToken: String? = null,
    @Json(name = "email_verification_token") val emailVerificationToken: String? = null,
)

data class UpdateClientProfileRequest(
    @Json(name = "client_id") val clientId: Int,
    @Json(name = "name") val name: String,
    @Json(name = "email") val email: String,
    @Json(name = "phone") val phone: String,
    @Json(name = "phone_verification_token") val phoneVerificationToken: String? = null,
    @Json(name = "email_verification_token") val emailVerificationToken: String? = null,
)

data class UpdateAvailabilityRequest(
    @Json(name = "taker_id") val takerId: Int,
    @Json(name = "dates")    val dates: List<AvailabilityEntry>,
)

data class AvailabilityEntry(
    @Json(name = "date")   val date: String,
    @Json(name = "status") val status: String,
    @Json(name = "day_part") val dayPart: String = "full_day",
)

data class BookTakerRequest(
    @Json(name = "client_id")      val clientId: Int,
    @Json(name = "taker_id")       val takerId: Int,
    @Json(name = "booking_date")   val bookingDate: String,
    @Json(name = "service_type")   val serviceType: String,
    @Json(name = "day_part")       val dayPart: String = "full_day",
    @Json(name = "event_location") val eventLocation: String? = null,
    @Json(name = "notes")          val notes: String?         = null,
    // Optional client-side hint so the app can render the booking immediately; API ignores unknown fields.
    @Json(name = "taker_name")     val takerName: String?     = null,
)

data class UpdateBookingStatusRequest(
    @Json(name = "booking_id") val bookingId: Int,
    @Json(name = "status") val status: String,
    @Json(name = "actor_role") val actorRole: String,
    @Json(name = "actor_id") val actorId: Int,
)

data class AddReviewRequest(
    @Json(name = "taker_id")  val takerId: Int,
    @Json(name = "client_id") val clientId: Int,
    @Json(name = "rating")    val rating: Int,
    @Json(name = "comment")   val comment: String? = null,
)

data class AddStudioReviewRequest(
    @Json(name = "client_id") val clientId: Int,
    @Json(name = "taker_id")  val takerId: Int,
    @Json(name = "rating")    val rating: Int,
    @Json(name = "comment")   val comment: String? = null,
)

data class ZippopotamusResponse(
    @Json(name = "post code") val postCode: String,
    @Json(name = "country") val country: String,
    @Json(name = "places") val places: List<ZippopotamusPlace>
)

data class ZippopotamusPlace(
    @Json(name = "place name") val placeName: String,
    @Json(name = "longitude") val longitude: String,
    @Json(name = "latitude") val latitude: String,
    @Json(name = "state") val state: String,
    @Json(name = "state abbreviation") val stateAbbreviation: String
)

data class PostOffice(
    @Json(name = "Name")       val name: String,
    @Json(name = "District")   val district: String,
    @Json(name = "State")      val state: String,
    @Json(name = "Pincode")    val pincode: String,
    @Json(name = "Block")      val block: String? = null,
    @Json(name = "Division")   val division: String? = null,
    @Json(name = "Region")     val region: String? = null,
    @Json(name = "BranchType") val branchType: String? = null,
) {
    fun bestCityName(): String {
        return district.takeIf { it.isNotBlank() && it.lowercase() != "na" }
            ?: division?.takeIf { it.isNotBlank() && it.lowercase() != "na" }?.replace(" Division", "", ignoreCase = true)?.trim()
            ?: region?.takeIf { it.isNotBlank() && it.lowercase() != "na" }?.replace(" Region", "", ignoreCase = true)?.trim()
            ?: block?.takeIf { it.isNotBlank() && it.lowercase() != "na" }
            ?: name.substringBefore("(").trim()
    }
}

data class NominatimAddress(
    @Json(name = "city") val city: String? = null,
    @Json(name = "town") val town: String? = null,
    @Json(name = "village") val village: String? = null,
    @Json(name = "municipality") val municipality: String? = null,
    @Json(name = "city_district") val cityDistrict: String? = null,
    @Json(name = "suburb") val suburb: String? = null,
    @Json(name = "county") val county: String? = null,
    @Json(name = "state_district") val stateDistrict: String? = null,
    @Json(name = "state") val state: String? = null,
    @Json(name = "postcode") val postcode: String? = null,
)

data class NominatimPlace(
    @Json(name = "display_name") val displayName: String? = null,
    @Json(name = "class") val placeClass: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "importance") val importance: Double? = null,
    @Json(name = "address") val address: NominatimAddress? = null,
)

data class PortfolioResult(
    @Json(name = "taker_id") val takerId: Int,
    @Json(name = "samples")  val samples: List<PortfolioSample>
)

data class DeleteSampleRequest(
    @Json(name = "taker_id")  val takerId: Int,
    @Json(name = "sample_id") val sampleId: Int
)
// ── Profile Image ─────────────────────────────────────────────
data class ProfileImageData(
    @Json(name = "url")       val url: String,
    @Json(name = "thumb_url") val thumbUrl: String,
    @Json(name = "med_url")   val medUrl: String,
    @Json(name = "scope")     val scope: String,
)

data class DeleteData(
    @Json(name = "deleted") val deleted: Boolean,
)

data class TakerPostImage(
    @Json(name = "id") val id: Int,
    @Json(name = "post_id") val postId: Int,
    @Json(name = "image_url") val imageUrl: String,
    @Json(name = "thumb_url") val thumbUrl: String? = null,
    @Json(name = "like_count") val likeCount: Int = 0,
    @Json(name = "viewer_has_liked") val viewerHasLiked: Boolean = false,
    @Json(name = "sort_order") val sortOrder: Int = 0,
)

data class TakerPost(
    @Json(name = "id") val id: Int,
    @Json(name = "taker_id") val takerId: Int,
    @Json(name = "caption") val caption: String? = null,
    @Json(name = "like_count") val likeCount: Int = 0,
    @Json(name = "view_count") val viewCount: Int = 0,
    @Json(name = "viewer_has_liked") val viewerHasLiked: Boolean = false,
    @Json(name = "viewer_has_saved") val viewerHasSaved: Boolean = false,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "images") val images: List<TakerPostImage> = emptyList(),
) {
    val coverImageUrl: String?
        get() = images.firstOrNull()?.imageUrl

    val coverThumbUrl: String?
        get() = images.firstOrNull()?.thumbUrl
}

data class TakerPostSummary(
    @Json(name = "post_count") val postCount: Int = 0,
    @Json(name = "total_likes") val totalLikes: Int = 0,
    @Json(name = "total_views") val totalViews: Int = 0,
    @Json(name = "favorite_count") val favoriteCount: Int = 0,
    @Json(name = "avg_rating") val avgRating: Float = 0f,
    @Json(name = "review_count") val reviewCount: Int = 0,
)

data class TakerPostsResult(
    @Json(name = "taker_id") val takerId: Int,
    @Json(name = "summary") val summary: TakerPostSummary = TakerPostSummary(),
    @Json(name = "total") val total: Int = 0,
    @Json(name = "page") val page: Int = 1,
    @Json(name = "limit") val limit: Int = 12,
    @Json(name = "total_pages") val totalPages: Int = 1,
    @Json(name = "posts") val posts: List<TakerPost> = emptyList(),
)

data class AccountPostActivityResult(
    @Json(name = "collection") val collection: String? = null,
    @Json(name = "saved_posts") val savedPosts: List<TakerPost> = emptyList(),
    @Json(name = "liked_posts") val likedPosts: List<TakerPost> = emptyList(),
    @Json(name = "saved_total") val savedTotal: Int = 0,
    @Json(name = "saved_page") val savedPage: Int = 1,
    @Json(name = "saved_limit") val savedLimit: Int = 12,
    @Json(name = "saved_total_pages") val savedTotalPages: Int = 1,
    @Json(name = "liked_total") val likedTotal: Int = 0,
    @Json(name = "liked_page") val likedPage: Int = 1,
    @Json(name = "liked_limit") val likedLimit: Int = 12,
    @Json(name = "liked_total_pages") val likedTotalPages: Int = 1,
)

data class TakerPostData(
    @Json(name = "post") val post: TakerPost,
)

data class ToggleTakerPostLikeData(
    @Json(name = "post_id") val postId: Int,
    @Json(name = "like_count") val likeCount: Int,
    @Json(name = "viewer_has_liked") val viewerHasLiked: Boolean,
)

data class ToggleTakerPostImageLikeData(
    @Json(name = "image_id") val imageId: Int,
    @Json(name = "post_id") val postId: Int,
    @Json(name = "image_like_count") val imageLikeCount: Int,
    @Json(name = "post_like_count") val postLikeCount: Int,
    @Json(name = "viewer_has_liked") val viewerHasLiked: Boolean,
)

data class TakerPostViewData(
    @Json(name = "post_id") val postId: Int,
    @Json(name = "view_count") val viewCount: Int,
)

data class ToggleTakerPostLikeRequest(
    @Json(name = "post_id") val postId: Int,
    @Json(name = "actor_role") val actorRole: String,
    @Json(name = "actor_id") val actorId: Int,
    @Json(name = "like") val like: Boolean,
)

data class ToggleTakerPostImageLikeRequest(
    @Json(name = "image_id") val imageId: Int,
    @Json(name = "actor_role") val actorRole: String,
    @Json(name = "actor_id") val actorId: Int,
    @Json(name = "like") val like: Boolean,
)

data class RecordTakerPostViewRequest(
    @Json(name = "post_id") val postId: Int,
    @Json(name = "viewer_role") val viewerRole: String,
    @Json(name = "viewer_id") val viewerId: Int,
)

data class DeleteTakerPostRequest(
    @Json(name = "taker_id") val takerId: Int,
    @Json(name = "post_id") val postId: Int,
)

data class UpdateTakerPostRequest(
    @Json(name = "taker_id") val takerId: Int,
    @Json(name = "post_id") val postId: Int,
    @Json(name = "caption") val caption: String? = null,
)

data class RemoveProfileImageRequest(
    @Json(name = "taker_id") val takerId: Int,
)

data class ToggleTakerPostSaveRequest(
    @Json(name = "post_id") val postId: Int,
    @Json(name = "actor_role") val actorRole: String,
    @Json(name = "actor_id") val actorId: Int,
    @Json(name = "save") val save: Boolean,
)

data class ToggleTakerPostSaveData(
    @Json(name = "post_id") val postId: Int,
    @Json(name = "save_count") val saveCount: Int,
    @Json(name = "viewer_has_saved") val viewerHasSaved: Boolean,
)

data class FavoriteStatusData(
    @Json(name = "taker_id") val takerId: Int,
    @Json(name = "is_favorite") val isFavorite: Boolean,
    @Json(name = "favorite_count") val favoriteCount: Int,
)

data class ToggleFavoriteRequest(
    @Json(name = "taker_id") val takerId: Int,
    @Json(name = "actor_role") val actorRole: String,
    @Json(name = "actor_id") val actorId: Int,
    @Json(name = "favorite") val favorite: Boolean,
)

data class DeleteTakerAccountRequest(
    @Json(name = "taker_id") val takerId: Int,
)

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

data class NotificationsResult(
    @Json(name = "notifications") val notifications: List<NotificationItem>,
    @Json(name = "total") val total: Int,
    @Json(name = "unread_count") val unreadCount: Int,
    @Json(name = "page") val page: Int = 1,
    @Json(name = "limit") val limit: Int = 20,
    @Json(name = "total_pages") val totalPages: Int = 1,
)

data class MarkNotificationReadRequest(
    @Json(name = "notification_id") val notificationId: Int = 0,
    @Json(name = "recipient_role") val recipientRole: String? = null,
    @Json(name = "recipient_id") val recipientId: Int? = null,
    @Json(name = "mark_all") val markAll: Boolean = false,
)

data class NotificationReadData(
    @Json(name = "notification_id") val notificationId: Int,
    @Json(name = "is_read") val isRead: Boolean,
    @Json(name = "updated_count") val updatedCount: Int = 0,
    @Json(name = "mark_all") val markAll: Boolean = false,
)

data class DeviceTokenRequest(
    @Json(name = "token") val token: String,
    @Json(name = "platform") val platform: String = "android",
    @Json(name = "device_name") val deviceName: String = "",
)
