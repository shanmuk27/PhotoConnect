package com.photoconnect.network

import com.photoconnect.BuildConfig
import com.photoconnect.model.*
import com.photoconnect.utils.SessionManager
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Authenticator
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
        @Query("availableOnly") availableOnly: Boolean = false,
        @Query("lat") latitude: Double? = null,
        @Query("lon") longitude: Double? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
    ): RetrofitResponse<ApiResponse<SearchResult>>

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

    @POST("addReview.php")
    suspend fun addReview(@Body r: AddReviewRequest): RetrofitResponse<ApiResponse<IdData>>

    @GET("getReviews.php")
    suspend fun getReviews(
        @Query("takerId") takerId: Int,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
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
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
    ): RetrofitResponse<ApiResponse<NotificationsResult>>

    @POST("markNotificationRead.php")
    suspend fun markNotificationRead(
        @Body r: MarkNotificationReadRequest,
    ): RetrofitResponse<ApiResponse<NotificationReadData>>

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
    @POST("uploadTakerPost.php")
    suspend fun uploadTakerPost(
        @Part("taker_id") takerId: RequestBody,
        @Part("caption") caption: RequestBody,
        @Part images: List<MultipartBody.Part>,
    ): RetrofitResponse<ApiResponse<TakerPostData>>

    @GET("getTakerPosts.php")
    suspend fun getTakerPosts(
        @Query("takerId") takerId: Int,
        @Query("viewerRole") viewerRole: String? = null,
        @Query("viewerId") viewerId: Int? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 12,
    ): RetrofitResponse<ApiResponse<TakerPostsResult>>

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

    @POST("deleteTakerAccount.php")
    suspend fun deleteTakerAccount(@Body r: DeleteTakerAccountRequest): RetrofitResponse<ApiResponse<DeleteData>>
}

interface PincodeApiService {
    @GET("pincode/{pincode}")
    suspend fun lookup(@Path("pincode") pincode: String): List<PincodeResponse>

    @GET("postoffice/{place}")
    suspend fun searchPlace(@Path("place") place: String): List<PincodeResponse>
}

data class LoginData(
    @Json(name = "role") val role: String,
    @Json(name = "user") val user: Map<String, Any?>,
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "expires_in") val expiresIn: Int,
    @Json(name = "refresh_expires_in") val refreshExpiresIn: Int,
)

data class RefreshTokenData(
    @Json(name = "role") val role: String,
    @Json(name = "user_id") val userId: Int,
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "expires_in") val expiresIn: Int,
    @Json(name = "refresh_expires_in") val refreshExpiresIn: Int,
)

data class IdData(@Json(name = "id") val id: Int)

data class CountData(
    @Json(name = "updated_count") val updatedCount: Int,
    @Json(name = "skipped_booked_count") val skippedBookedCount: Int = 0,
)

data class BookingData(@Json(name = "booking_id") val bookingId: Int)

data class BookingStatusData(
    @Json(name = "booking_id") val bookingId: Int,
    @Json(name = "status") val status: String,
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
    fun provideOkHttp(
        interceptor: AuthInterceptor,
        authenticator: TokenAuthenticator,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(interceptor)
        .authenticator(authenticator)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BODY
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            }
        )
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

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
            .baseUrl("https://api.postalpincode.in/")
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
}
