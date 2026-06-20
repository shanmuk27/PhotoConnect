package com.photoconnect.network

import android.content.Context
import com.photoconnect.BuildConfig
import com.photoconnect.model.*
import com.photoconnect.utils.SessionManager
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Authenticator
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import retrofit2.Response as RetrofitResponse

interface PhotoConnectApiService {
    @POST("login.php")
    suspend fun login(@Body r: LoginRequest): RetrofitResponse<ApiResponse<LoginData>>

    @POST("refreshToken.php")
    suspend fun refreshToken(@Body r: RefreshTokenRequest): RetrofitResponse<ApiResponse<RefreshTokenData>>

    @POST("identityCheck.php")
    suspend fun identityCheck(@Body r: IdentityCheckRequest): RetrofitResponse<ApiResponse<IdentityTakenData>>

    @POST("registerTaker.php")
    suspend fun registerTaker(@Body r: RegisterTakerRequest): RetrofitResponse<ApiResponse<IdData>>

    @POST("registerClient.php")
    suspend fun registerClient(@Body r: RegisterClientRequest): RetrofitResponse<ApiResponse<IdData>>

    @POST("updateTakerProfile.php")
    suspend fun updateTakerProfile(@Body r: UpdateTakerRequest): RetrofitResponse<ApiResponse<IdData>>

    @GET("searchTakers.php")
    suspend fun searchTakers(
        @Query("location") location: String? = null,
        @Query("date") date: String? = null,
        @Query("serviceType") serviceType: String? = null,
        @Query("serviceTypes") serviceTypes: String? = null,
        @Query("serviceMatchMode") serviceMatchMode: String? = null,
        @Query("trustFilter") trustFilter: String? = null,
        @Query("respondsFastOnly") respondsFastOnly: Boolean = false,
        @Query("explain") explain: Boolean = true,
        @Query("availableOnly") availableOnly: Boolean = false,
        @Query("lat") latitude: Double? = null,
        @Query("lon") longitude: Double? = null,
        @Query("radiusKm") radiusKm: Double? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
        @Query("_ts") cacheBust: Long? = null,
    ): RetrofitResponse<ApiResponse<SearchResult>>

    @GET("getTrendingSearches.php")
    suspend fun getTrendingSearches(
        @Query("limit") limit: Int = 10,
    ): RetrofitResponse<ApiResponse<TrendingSearchesResult>>

    @POST("recordSearchEvent.php")
    suspend fun recordSearchEvent(@Body r: SearchEventRequest): RetrofitResponse<ApiResponse<IdData>>

    @POST("saveSearchAlert.php")
    suspend fun saveSearchAlert(@Body r: SearchAlertRequest): RetrofitResponse<ApiResponse<SearchAlertData>>

    @GET("getTakerProfile.php")
    suspend fun getTakerProfile(
        @Query("takerId") takerId: Int,
        @Query("_ts") cacheBust: Long? = null,
    ): RetrofitResponse<ApiResponse<Taker>>

    @POST("updateAvailability.php")
    suspend fun updateAvailability(@Body r: UpdateAvailabilityRequest): RetrofitResponse<ApiResponse<CountData>>

    @GET("getAvailability.php")
    suspend fun getAvailability(
        @Query("takerId") takerId: Int,
        @Query("month") month: String? = null,
    ): RetrofitResponse<ApiResponse<AvailabilityResult>>

    @POST("bookTaker.php")
    suspend fun bookTaker(@Body r: BookTakerRequest): RetrofitResponse<ApiResponse<BookingData>>

    @POST("updateBookingStatus.php")
    suspend fun updateBookingStatus(@Body r: UpdateBookingStatusRequest): RetrofitResponse<ApiResponse<BookingStatusData>>

    @GET("getBookings.php")
    suspend fun getBookings(
        @Query("clientId") clientId: Int? = null,
        @Query("takerId") takerId: Int? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
    ): RetrofitResponse<ApiResponse<BookingsResult>>

    @GET("getEvents.php")
    suspend fun getEvents(
        @Query("scope") scope: String = "all",
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 40,
    ): RetrofitResponse<ApiResponse<EventsResult>>

    @POST("createEvent.php")
    suspend fun createEvent(@Body r: UpsertEventRequest): RetrofitResponse<ApiResponse<EventIdData>>

    @POST("updateEvent.php")
    suspend fun updateEvent(@Body r: UpsertEventRequest): RetrofitResponse<ApiResponse<EventIdData>>

    @HTTP(method = "DELETE", path = "deleteEvent.php", hasBody = true)
    suspend fun deleteEvent(@Body r: DeleteEventRequest): RetrofitResponse<ApiResponse<DeleteData>>

    @POST("addReview.php")
    suspend fun addReview(@Body r: AddReviewRequest): RetrofitResponse<ApiResponse<IdData>>

    @POST("addStudioReview.php")
    suspend fun addStudioReview(@Body r: AddStudioReviewRequest): RetrofitResponse<ApiResponse<TrustActionResult>>

    @GET("getTrustStatus.php")
    suspend fun getTrustStatus(
        @Query("takerId") takerId: Int? = null,
        @Query("clientId") clientId: Int? = null,
        @Query("_ts") cacheBust: Long? = null,
    ): RetrofitResponse<ApiResponse<TrustStatusResult>>

    @POST("submitStudioVerification.php")
    suspend fun submitStudioVerification(@Body r: SubmitStudioVerificationRequest): RetrofitResponse<ApiResponse<TrustActionResult>>

    @POST("submitTakerVerification.php")
    suspend fun submitTakerVerification(@Body r: SubmitTakerVerificationRequest): RetrofitResponse<ApiResponse<TrustActionResult>>

    @POST("toggleTakerEndorsement.php")
    suspend fun toggleTakerEndorsement(@Body r: ToggleTakerEndorsementRequest): RetrofitResponse<ApiResponse<TrustActionResult>>

    @Multipart
    @POST("uploadVerificationDocument.php")
    suspend fun uploadVerificationDocument(
        @Part("target_role") targetRole: RequestBody,
        @Part("target_id") targetId: RequestBody,
        @Part("document_type") documentType: RequestBody,
        @Part document: MultipartBody.Part,
    ): RetrofitResponse<ApiResponse<TrustActionResult>>

    @GET("getReviews.php")
    suspend fun getReviews(
        @Query("takerId") takerId: Int,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
        @Query("_ts") cacheBust: Long? = null,
    ): RetrofitResponse<ApiResponse<ReviewsResult>>

    @GET("getFavoriteStatus.php")
    suspend fun getFavoriteStatus(
        @Query("takerId") takerId: Int,
        @Query("actorRole") actorRole: String,
        @Query("actorId") actorId: Int,
    ): RetrofitResponse<ApiResponse<FavoriteStatusData>>

    @POST("toggleFavorite.php")
    suspend fun toggleFavorite(@Body r: ToggleFavoriteRequest): RetrofitResponse<ApiResponse<FavoriteStatusData>>

    @GET("getNotifications.php")
    suspend fun getNotifications(
        @Query("recipientRole") recipientRole: String? = null,
        @Query("recipientId") recipientId: Int? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
    ): RetrofitResponse<ApiResponse<NotificationsResult>>

    @POST("markNotificationRead.php")
    suspend fun markNotificationRead(
        @Body r: MarkNotificationReadRequest,
    ): RetrofitResponse<ApiResponse<NotificationReadData>>

    @POST("registerDeviceToken.php")
    suspend fun registerDeviceToken(
        @Body r: DeviceTokenRequest,
    ): RetrofitResponse<ApiResponse<IdData>>

    @Multipart
    @POST("uploadPortfolioSample.php")
    suspend fun uploadPortfolioSample(
        @Part("taker_id") takerId: Int,
        @Part("caption") caption: String?,
        @Part image: MultipartBody.Part,
    ): RetrofitResponse<ApiResponse<PortfolioSample>>

    @GET("getPortfolio.php")
    suspend fun getPortfolio(@Query("takerId") takerId: Int): RetrofitResponse<ApiResponse<PortfolioResult>>

    @HTTP(method = "DELETE", path = "deletePortfolioSample.php", hasBody = true)
    suspend fun deletePortfolioSample(@Body r: DeleteSampleRequest): RetrofitResponse<ApiResponse<DeleteData>>

    @Multipart
    @POST("uploadProfileImage.php")
    suspend fun uploadProfileImage(
        @Part("taker_id") takerId: RequestBody,
        @Part("scope") scope: RequestBody,
        @Part image: MultipartBody.Part,
    ): RetrofitResponse<ApiResponse<ProfileImageData>>

    @Multipart
    @POST("uploadClientProfileImage.php")
    suspend fun uploadClientProfileImage(
        @Part("client_id") clientId: RequestBody,
        @Part image: MultipartBody.Part,
    ): RetrofitResponse<ApiResponse<ProfileImageData>>

    @POST("removeProfileImage.php")
    suspend fun removeProfileImage(@Body r: RemoveProfileImageRequest): RetrofitResponse<ApiResponse<DeleteData>>

    @POST("updateClientProfile.php")
    suspend fun updateClientProfile(@Body r: UpdateClientProfileRequest): RetrofitResponse<ApiResponse<IdData>>

    @Multipart
    @POST("uploadTakerPost.php")
    suspend fun uploadTakerPost(
        @Part("taker_id") takerId: RequestBody,
        @Part("caption") caption: RequestBody,
        @Part("client_upload_id") clientUploadId: RequestBody?,
        @Part("photo_attestation") photoAttestation: RequestBody?,
        @Part("photo_attestation_sig") photoAttestationSig: RequestBody?,
        @Part images: List<MultipartBody.Part>,
    ): RetrofitResponse<ApiResponse<TakerPostData>>

    @GET("getTakerPosts.php")
    suspend fun getTakerPosts(
        @Query("takerId") takerId: Int,
        @Query("viewerRole") viewerRole: String? = null,
        @Query("viewerId") viewerId: Int? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 12,
        @Query("_ts") cacheBust: Long? = null,
    ): RetrofitResponse<ApiResponse<TakerPostsResult>>

    @GET("getAccountPostActivity.php")
    suspend fun getAccountPostActivity(
        @Query("actorRole") actorRole: String,
        @Query("actorId") actorId: Int,
        @Query("collection") collection: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 12,
        @Query("_ts") cacheBust: Long? = null,
    ): RetrofitResponse<ApiResponse<AccountPostActivityResult>>

    @POST("toggleTakerPostLike.php")
    suspend fun toggleTakerPostLike(@Body r: ToggleTakerPostLikeRequest): RetrofitResponse<ApiResponse<ToggleTakerPostLikeData>>

    @POST("toggleTakerPostImageLike.php")
    suspend fun toggleTakerPostImageLike(@Body r: ToggleTakerPostImageLikeRequest): RetrofitResponse<ApiResponse<ToggleTakerPostImageLikeData>>

    @POST("toggleTakerPostSave.php")
    suspend fun toggleTakerPostSave(@Body r: ToggleTakerPostSaveRequest): RetrofitResponse<ApiResponse<ToggleTakerPostSaveData>>

    @POST("recordTakerPostView.php")
    suspend fun recordTakerPostView(@Body r: RecordTakerPostViewRequest): RetrofitResponse<ApiResponse<TakerPostViewData>>

    @HTTP(method = "DELETE", path = "deleteTakerPost.php", hasBody = true)
    suspend fun deleteTakerPost(@Body r: DeleteTakerPostRequest): RetrofitResponse<ApiResponse<DeleteData>>

    @Multipart
    @POST("updateTakerPost.php")
    suspend fun updateTakerPost(
        @Part("taker_id") takerId: RequestBody,
        @Part("post_id") postId: RequestBody,
        @Part("caption") caption: RequestBody,
        @Part("keep_image_ids") keepImageIds: RequestBody,
        @Part images: List<MultipartBody.Part>,
    ): RetrofitResponse<ApiResponse<IdData>>

    @POST("deleteTakerAccount.php")
    suspend fun deleteTakerAccount(@Body r: DeleteTakerAccountRequest): RetrofitResponse<ApiResponse<DeleteData>>

    @POST("submitHelpTicket.php")
    suspend fun submitHelpTicket(@Body r: SubmitHelpTicketRequest): RetrofitResponse<ApiResponse<IdData>>

    @GET("getHelpTickets.php")
    suspend fun getHelpTickets(): RetrofitResponse<ApiResponse<HelpTicketsResult>>

    @POST("sendOtp.php")
    suspend fun sendOtp(@Body r: SendOtpRequest): RetrofitResponse<ApiResponse<SendOtpData>>

    @POST("verifyOtp.php")
    suspend fun verifyOtp(@Body r: VerifyOtpRequest): RetrofitResponse<ApiResponse<OtpVerificationData>>

    @POST("sendEmailOtp.php")
    suspend fun sendEmailOtp(@Body r: SendEmailOtpRequest): RetrofitResponse<ApiResponse<Any>>

    @POST("verifyEmailOtp.php")
    suspend fun verifyEmailOtp(@Body r: VerifyEmailOtpRequest): RetrofitResponse<ApiResponse<OtpVerificationData>>

    @POST("resetPassword.php")
    suspend fun resetPassword(@Body r: ResetPasswordRequest): RetrofitResponse<ApiResponse<ResetPasswordData>>

    @POST("googleAuth.php")
    suspend fun googleAuth(@Body r: GoogleLoginRequest): RetrofitResponse<ApiResponse<LoginData>>
}

interface PincodeApiService {
    @GET("IN/{pincode}")
    suspend fun lookup(@Path("pincode") pincode: String): ZippopotamusResponse
}

interface NominatimApiService {
    @GET("search")
    suspend fun searchPlaces(
        @Query("q") query: String,
        @Query("format") format: String = "jsonv2",
        @Query("addressdetails") addressDetails: Int = 1,
        @Query("countrycodes") countryCodes: String = "in",
        @Query("dedupe") dedupe: Int = 1,
        @Query("limit") limit: Int = 8,
    ): List<NominatimPlace>
}

data class UnifiedUser(
    @Json(name = "id") val id: Int,
    @Json(name = "email") val email: String?,
    @Json(name = "phone") val phone: String?,
    @Json(name = "name") val name: String? = null,
)

data class UnifiedProfiles(
    @Json(name = "takers") val takers: List<Map<String, Any?>> = emptyList(),
    @Json(name = "clients") val clients: List<Map<String, Any?>> = emptyList(),
)

data class LoginData(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "user") val user: UnifiedUser,
    @Json(name = "profiles") val profiles: UnifiedProfiles,
    @Json(name = "requires_more_info") val requiresMoreInfo: Boolean = false,
)

data class GoogleLoginRequest(
    @Json(name = "id_token") val idToken: String
)

data class RefreshTokenData(
    @Json(name = "role") val role: String,
    @Json(name = "user_id") val userId: Int,
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "expires_in") val expiresIn: Int,
    @Json(name = "refresh_expires_in") val refreshExpiresIn: Int,
)

data class IdData(
    @Json(name = "id") val id: Int,
    @Json(name = "user_id") val userId: Int = 0,
    @Json(name = "client_id") val clientId: Int = 0,
)

data class CountData(
    @Json(name = "updated_count") val updatedCount: Int,
    @Json(name = "skipped_booked_count") val skippedBookedCount: Int = 0,
)

data class BookingData(
    @Json(name = "booking_id") val bookingId: Int,
    @Json(name = "client_verification_stage") val clientVerificationStage: String = "unverified",
    @Json(name = "client_verification_label") val clientVerificationLabel: String = "Client not verified",
)

data class BookingStatusData(
    @Json(name = "booking_id") val bookingId: Int,
    @Json(name = "status") val status: String,
    @Json(name = "auto_cancelled_booking_ids") val autoCancelledBookingIds: List<Int> = emptyList(),
)

class AuthInterceptor @Inject constructor(
    private val session: SessionManager,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath.endsWith("/refreshToken.php")) {
            return chain.proceed(request)
        }

        val token = session.getAccessToken().takeIf { it.isNotBlank() } ?: return chain.proceed(request)
        val next = request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        return chain.proceed(next)
    }
}

@Singleton
class TokenAuthenticator @Inject constructor(
    private val session: SessionManager,
    private val moshi: Moshi,
) : Authenticator {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val refreshRequestAdapter = moshi.adapter(RefreshTokenRequest::class.java)
    private val refreshResponseAdapter by lazy {
        val type = Types.newParameterizedType(ApiResponse::class.java, RefreshTokenData::class.java)
        moshi.adapter<ApiResponse<RefreshTokenData>>(type)
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        if (response.request.url.encodedPath.endsWith("/refreshToken.php")) return null

        val role = session.getRole().takeIf { it.isNotBlank() } ?: return null
        val userId = session.getUserId().takeIf { it > 0 } ?: return null
        val refreshToken = session.getRefreshToken().takeIf { it.isNotBlank() } ?: return null

        synchronized(this) {
            val latestAccessToken = session.getAccessToken().takeIf { it.isNotBlank() }
            val requestToken = response.request.header("Authorization")
                ?.removePrefix("Bearer")
                ?.trim()
            if (latestAccessToken != null && latestAccessToken != requestToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $latestAccessToken")
                    .header("X-Auth-Retry", "1")
                    .build()
            }

            val refreshed = refreshTokens(role, userId, refreshToken) ?: run {
                session.clearSession()
                return null
            }

            return response.request.newBuilder()
                .header("Authorization", "Bearer ${refreshed.accessToken}")
                .header("X-Auth-Retry", "1")
                .build()
        }
    }

    private fun refreshTokens(role: String, userId: Int, refreshToken: String): RefreshTokenData? {
        val payload = refreshRequestAdapter.toJson(
            RefreshTokenRequest(
                role = role,
                userId = userId,
                refreshToken = refreshToken,
            )
        )

        val request = Request.Builder()
            .url(BuildConfig.BASE_URL + "refreshToken.php")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { refreshResponse ->
                if (!refreshResponse.isSuccessful) return null
                val body = refreshResponse.body?.string().orEmpty()
                val parsed = refreshResponseAdapter.fromJson(body)
                val data = parsed?.data
                if (parsed?.success != true || data == null) return null
                session.saveTokens(data.accessToken, data.refreshToken)
                return data
            }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideHttpCache(@ApplicationContext context: Context): Cache =
        Cache(File(context.cacheDir, "http_response_cache"), 20L * 1024L * 1024L)

    @Provides
    @Singleton
    fun provideOkHttp(
        interceptor: AuthInterceptor,
        authenticator: TokenAuthenticator,
        cache: Cache,
    ): OkHttpClient {
        // Industry-leading security: Certificate Pinning
        // TODO (Server Admin): Replace the placeholder hash with your server's actual SHA-256 certificate hash.
        // Uncomment the line in OkHttpClient.Builder to enforce this.
        val host = java.net.URL(BuildConfig.BASE_URL).host
        val certificatePinner = okhttp3.CertificatePinner.Builder()
            .add(host, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            .build()

        return OkHttpClient.Builder()
            .cache(cache)
            // .certificatePinner(certificatePinner) // UNCOMMENT THIS IN PRODUCTION
        .addInterceptor(interceptor)
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET" && isFreshDataPath(request.url.encodedPath)) {
                chain.proceed(
                    request.newBuilder()
                        .cacheControl(CacheControl.FORCE_NETWORK)
                        .header("Pragma", "no-cache")
                        .build()
                )
            } else {
                chain.proceed(request)
            }
        }
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (chain.request().method != "GET") return@addNetworkInterceptor response
            val path = chain.request().url.encodedPath
            response.newBuilder()
                .header("Cache-Control", cachePolicyFor(path))
                .build()
        }
        .authenticator(authenticator)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                redactHeader("Authorization")
                redactHeader("Cookie")
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BASIC
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            }
        )
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    }

    private fun cachePolicyFor(path: String): String {
        if (isFreshDataPath(path)) {
            return "no-store, no-cache, must-revalidate, max-age=0"
        }
        val maxAgeSeconds = when {
            path.endsWith("/getPortfolio.php") -> 120
            else -> 90
        }
        return "private, max-age=$maxAgeSeconds"
    }

    private fun isFreshDataPath(path: String): Boolean =
        path.endsWith("/searchTakers.php") ||
            path.endsWith("/getTakerProfile.php") ||
            path.endsWith("/getAvailability.php") ||
            path.endsWith("/getBookings.php") ||
            path.endsWith("/getEvents.php") ||
            path.endsWith("/getNotifications.php") ||
            path.endsWith("/getFavoriteStatus.php") ||
            path.endsWith("/getReviews.php") ||
            path.endsWith("/getTakerPosts.php") ||
            path.endsWith("/getAccountPostActivity.php") ||
            path.endsWith("/getTrustStatus.php")

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideApi(retrofit: Retrofit): PhotoConnectApiService =
        retrofit.create(PhotoConnectApiService::class.java)

    @Provides
    @Singleton
    @Named("pincode")
    fun providePincodeRetrofit(moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.zippopotam.us/")
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
            )
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun providePincodeApi(@Named("pincode") retrofit: Retrofit): PincodeApiService =
        retrofit.create(PincodeApiService::class.java)

    @Provides
    @Singleton
    @Named("nominatim")
    fun provideNominatimRetrofit(moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://nominatim.openstreetmap.org/")
            .client(
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("User-Agent", "PhotoConnect Android")
                                .build()
                        )
                    }
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
            )
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideNominatimApi(@Named("nominatim") retrofit: Retrofit): NominatimApiService =
        retrofit.create(NominatimApiService::class.java)

    @Provides
    @Singleton
    fun provideWebSocketManager(okHttpClient: OkHttpClient): WebSocketManager =
        WebSocketManager(okHttpClient)
}
