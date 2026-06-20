package com.photoconnect.viewmodel

import androidx.lifecycle.*
import com.photoconnect.db.toModel
import com.photoconnect.model.*
import com.photoconnect.network.*
import com.photoconnect.repository.*
import com.photoconnect.utils.FetchGate
import com.photoconnect.utils.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepo: AuthRepository,
    private val session: SessionManager,
) : ViewModel() {
    private val _loginState = MutableLiveData<Result<LoginData>>()
    val loginState: LiveData<Result<LoginData>> = _loginState
    private val _regTakerState = MutableLiveData<Result<IdData>>()
    val regTakerState: LiveData<Result<IdData>> = _regTakerState
    private val _regClientState = MutableLiveData<Result<IdData>>()
    val regClientState: LiveData<Result<IdData>> = _regClientState
    private val _identityState = MutableLiveData<Result<IdentityTakenData>>()
    val identityState: LiveData<Result<IdentityTakenData>> = _identityState

    fun login(role: String, identity: String, password: String) {
        _loginState.value = Result.Loading
        viewModelScope.launch {
            val r = authRepo.login(role, identity, password)
            if (r is Result.Success) {
                val data = r.data
                fun profileString(profile: Map<String, Any?>?, key: String): String? =
                    profile?.get(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                val firstTaker = data.profiles.takers.firstOrNull()
                val firstClient = data.profiles.clients.firstOrNull()
                val displayName = data.user.name?.takeIf { it.isNotBlank() }
                    ?: profileString(firstTaker, "full_name")
                    ?: profileString(firstClient, "name")
                    ?: ""
                session.saveSession(
                    role = role, // Assuming role comes from the request for backwards compatibility
                    userId = data.user.id,
                    name = displayName,
                    phone = data.user.phone ?: "",
                    email = data.user.email ?: "",
                    takerProfileId = firstTaker?.get("id")?.toString()?.toDoubleOrNull()?.toInt() ?: 0,
                    clientProfileId = firstClient?.get("id")?.toString()?.toDoubleOrNull()?.toInt() ?: 0,
                    profileImageUrl = profileString(firstTaker ?: firstClient, "profile_image_url"),
                    profileThumbUrl = profileString(firstTaker ?: firstClient, "profile_thumb_url"),
                    accessToken = data.accessToken,
                    refreshToken = data.refreshToken,
                )
                viewModelScope.launch { authRepo.registerDeviceForPush() }
            }
            _loginState.value = r
        }
    }

    /** Role is resolved on the server (`auto`). */
    fun loginAuto(identity: String, password: String) = login("auto", identity, password)

    fun googleLogin(idToken: String) {
        _loginState.value = Result.Loading
        viewModelScope.launch {
            val r = authRepo.googleLogin(idToken)
            if (r is Result.Success) {
                val data = r.data
                fun profileString(profile: Map<String, Any?>?, key: String): String? =
                    profile?.get(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                val firstTaker = data.profiles.takers.firstOrNull()
                val firstClient = data.profiles.clients.firstOrNull()
                val displayName = data.user.name?.takeIf { it.isNotBlank() }
                    ?: profileString(firstTaker, "full_name")
                    ?: profileString(firstClient, "name")
                    ?: ""
                session.saveSession(
                    role = "auto", // the user will need to select if they have multiple, but for now just auto
                    userId = data.user.id,
                    name = displayName,
                    phone = data.user.phone ?: "",
                    email = data.user.email ?: "",
                    takerProfileId = firstTaker?.get("id")?.toString()?.toDoubleOrNull()?.toInt() ?: 0,
                    clientProfileId = firstClient?.get("id")?.toString()?.toDoubleOrNull()?.toInt() ?: 0,
                    profileImageUrl = profileString(firstTaker ?: firstClient, "profile_image_url"),
                    profileThumbUrl = profileString(firstTaker ?: firstClient, "profile_thumb_url"),
                    accessToken = data.accessToken,
                    refreshToken = data.refreshToken,
                )
                viewModelScope.launch { authRepo.registerDeviceForPush() }
            }
            _loginState.value = r
        }
    }

    fun checkIdentityForRegistration(phone: String, email: String?) {
        _identityState.value = Result.Loading
        viewModelScope.launch { _identityState.value = authRepo.checkIdentity(phone, email) }
    }

    fun registerTaker(r: RegisterTakerRequest) {
        _regTakerState.value = Result.Loading
        viewModelScope.launch { _regTakerState.value = authRepo.registerTaker(r) }
    }
    fun registerClient(r: RegisterClientRequest) {
        _regClientState.value = Result.Loading
        viewModelScope.launch { _regClientState.value = authRepo.registerClient(r) }
    }

    private val _sendOtpState = MutableLiveData<Result<SendOtpData>>()
    val sendOtpState: LiveData<Result<SendOtpData>> = _sendOtpState

    private val _verifyOtpState = MutableLiveData<Result<OtpVerificationData>>()
    val verifyOtpState: LiveData<Result<OtpVerificationData>> = _verifyOtpState

    private val _sendEmailOtpState = MutableLiveData<Result<Any>>()
    val sendEmailOtpState: LiveData<Result<Any>> = _sendEmailOtpState

    private val _verifyEmailOtpState = MutableLiveData<Result<OtpVerificationData>>()
    val verifyEmailOtpState: LiveData<Result<OtpVerificationData>> = _verifyEmailOtpState

    private val _resetPasswordState = MutableLiveData<Result<ResetPasswordData>>()
    val resetPasswordState: LiveData<Result<ResetPasswordData>> = _resetPasswordState

    fun sendOtp(phone: String, purpose: String = "registration") {
        _sendOtpState.value = Result.Loading
        viewModelScope.launch { _sendOtpState.value = authRepo.sendOtp(phone, purpose) }
    }

    fun verifyOtp(phone: String, otp: String) {
        _verifyOtpState.value = Result.Loading
        viewModelScope.launch { _verifyOtpState.value = authRepo.verifyOtp(phone, otp) }
    }

    fun sendEmailOtp(email: String, purpose: String = "registration") {
        _sendEmailOtpState.value = Result.Loading
        viewModelScope.launch { _sendEmailOtpState.value = authRepo.sendEmailOtp(email, purpose) }
    }

    fun verifyEmailOtp(email: String, otp: String) {
        _verifyEmailOtpState.value = Result.Loading
        viewModelScope.launch { _verifyEmailOtpState.value = authRepo.verifyEmailOtp(email, otp) }
    }

    fun resetPassword(identity: String, otp: String, newPassword: String) {
        _resetPasswordState.value = Result.Loading
        viewModelScope.launch {
            _resetPasswordState.value = authRepo.resetPassword(identity, otp, newPassword)
        }
    }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val takerRepo: TakerRepository,
    private val searchRepo: SearchRepository,
) : ViewModel() {
    private val _state = MutableLiveData<Result<SearchResult>>()
    val state: LiveData<Result<SearchResult>> = _state
    private val _trendingState = MutableLiveData<Result<TrendingSearchesResult>>()
    val trendingState: LiveData<Result<TrendingSearchesResult>> = _trendingState
    private val _searchAlertState = MutableLiveData<Result<SearchAlertData>>()
    val searchAlertState: LiveData<Result<SearchAlertData>> = _searchAlertState
    private var loc: String? = null; private var date: String? = null; private var svc: String? = null
    private var serviceTypes: List<String> = emptyList()
    private var serviceMatchMode: String = "smart"
    private var trustFilter: String? = null
    private var respondsFastOnly: Boolean = false
    private var availOnly: Boolean = false
    private var latitude: Double? = null
    private var longitude: Double? = null
    private var radiusKm: Double? = null
    private var fetchJob: Job? = null
    private var lastFetchKey: String? = null
    private val fetchGate = FetchGate(45_000L)
    private var currentPage = 0
    private var totalPages = 1
    private var accumulatedTakers: List<Taker> = emptyList()
    private var accumulatedFeatured: List<Taker> = emptyList()
    fun fetch(
        location: String? = loc,
        date_: String? = date,
        service: String? = svc,
        serviceTypes: List<String> = this.serviceTypes,
        serviceMatchMode: String = this.serviceMatchMode,
        trustFilter: String? = this.trustFilter,
        respondsFastOnly: Boolean = this.respondsFastOnly,
        availOnly: Boolean = this.availOnly,
        latitude: Double? = this.latitude,
        longitude: Double? = this.longitude,
        radiusKm: Double? = this.radiusKm,
        force: Boolean = false,
    ) {
        loc = location
        date = date_
        svc = service
        this.serviceTypes = serviceTypes.distinct()
        this.serviceMatchMode = serviceMatchMode
        this.trustFilter = trustFilter
        this.respondsFastOnly = respondsFastOnly
        this.availOnly = availOnly
        this.latitude = latitude
        this.longitude = longitude
        this.radiusKm = radiusKm
        val fetchKey = listOf(
            location.orEmpty().lowercase(),
            date_.orEmpty(),
            service.orEmpty(),
            this.serviceTypes.joinToString(","),
            serviceMatchMode,
            trustFilter.orEmpty(),
            respondsFastOnly.toString(),
            availOnly.toString(),
            latitude?.let { "%.4f".format(java.util.Locale.US, it) }.orEmpty(),
            longitude?.let { "%.4f".format(java.util.Locale.US, it) }.orEmpty(),
            radiusKm?.let { "%.1f".format(java.util.Locale.US, it) }.orEmpty(),
        ).joinToString("|")
        currentPage = 0
        totalPages = 1
        accumulatedTakers = emptyList()
        accumulatedFeatured = emptyList()
        fetchPage(fetchKey, page = 1, append = false, force = force)
    }

    fun canLoadMoreRemote(): Boolean =
        currentPage > 0 && currentPage < totalPages && fetchJob?.isActive != true

    fun loadNextPage() {
        val fetchKey = lastFetchKey ?: return
        if (!canLoadMoreRemote()) return
        fetchPage(fetchKey, page = currentPage + 1, append = true, force = true)
    }

    private fun fetchPage(fetchKey: String, page: Int, append: Boolean, force: Boolean) {
        if (fetchKey == lastFetchKey && fetchJob?.isActive == true) return
        val gateKey = "$fetchKey|page:$page"
        if (!fetchGate.tryStart(gateKey, force)) return
        lastFetchKey = fetchKey
        if (!append) fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            var success = false
            try {
                var showedCached = false
                if (!append) {
                    val canUseCachedSearch = loc.isNullOrBlank() && latitude == null && longitude == null && radiusKm == null
                    val cached = if (canUseCachedSearch) takerRepo.cachedSearch(loc, svc) else emptyList()
                    if (cached.isNotEmpty()) {
                        showedCached = true
                        _state.value = Result.Success(
                            SearchResult(
                                takers = cached,
                                featured = cached.filter { it.isFeatured == 1 }.ifEmpty { cached.take(6) },
                                total = cached.size,
                                page = 1,
                                totalPages = 1,
                                searchRadiusKm = radiusKm,
                                maxRadiusKm = radiusKm,
                            )
                        )
                    }
                }
                if (!showedCached) {
                    _state.value = Result.Loading
                }
                val result = takerRepo.fetchAndCache(
                    loc = loc,
                    date = date,
                    svc = svc,
                    serviceTypes = serviceTypes,
                    serviceMatchMode = serviceMatchMode,
                    trustFilter = trustFilter,
                    respondsFastOnly = respondsFastOnly,
                    availOnly = availOnly,
                    latitude = latitude,
                    longitude = longitude,
                    radiusKm = radiusKm,
                    page = page,
                )
                success = result is Result.Success
                _state.value = when (result) {
                    is Result.Success -> {
                        currentPage = result.data.page
                        totalPages = result.data.totalPages
                        accumulatedTakers = if (append) {
                            (accumulatedTakers + result.data.takers).distinctBy { it.id }
                        } else {
                            result.data.takers
                        }
                        accumulatedFeatured = if (append && accumulatedFeatured.isNotEmpty()) {
                            accumulatedFeatured
                        } else {
                            result.data.featured
                        }
                        Result.Success(
                            result.data.copy(
                                takers = accumulatedTakers,
                                featured = accumulatedFeatured,
                            )
                        )
                    }
                    else -> result
                }
            } finally {
                fetchGate.finish(gateKey, success)
            }
        }
    }
    fun clearFilters() = fetch(
        location = null,
        date_ = null,
        service = null,
        serviceTypes = emptyList(),
        serviceMatchMode = "smart",
        trustFilter = null,
        respondsFastOnly = false,
        availOnly = false,
    )
    fun hasFilters() = loc != null || date != null || svc != null || availOnly || latitude != null || longitude != null ||
        serviceTypes.isNotEmpty() || !trustFilter.isNullOrBlank() || respondsFastOnly

    fun fetchTrendingSearches() {
        viewModelScope.launch {
            _trendingState.value = searchRepo.getTrendingSearches()
        }
    }

    fun recordSearchEvent(request: SearchEventRequest) {
        viewModelScope.launch {
            searchRepo.recordEvent(request)
        }
    }

    fun saveSearchAlert(request: SearchAlertRequest) {
        _searchAlertState.value = Result.Loading
        viewModelScope.launch {
            _searchAlertState.value = searchRepo.saveSearchAlert(request)
        }
    }
}

@HiltViewModel
class TakerDetailViewModel @Inject constructor(
    private val takerRepo: TakerRepository,
    private val favRepo: FavoriteRepository,
    private val availRepo: AvailabilityRepository,
    private val reviewRepo: ReviewRepository,
    private val trustRepo: TrustRepository,
) : ViewModel() {
    private val _avail  = MutableLiveData<Result<AvailabilityResult>>()
    val avail: LiveData<Result<AvailabilityResult>> = _avail
    private val _reviews = MutableLiveData<Result<ReviewsResult>>()
    val reviews: LiveData<Result<ReviewsResult>> = _reviews
    private val _addReview = MutableLiveData<Result<IdData>>()
    val addReview: LiveData<Result<IdData>> = _addReview
    private val _updateState = MutableLiveData<Result<IdData>>()
    val updateState: LiveData<Result<IdData>> = _updateState
    private val _takerState = MutableLiveData<Result<Taker>>()
    val takerState: LiveData<Result<Taker>> = _takerState

    private val _uploadImageState = MutableLiveData<Result<ProfileImageData>>()
    val uploadImageState: LiveData<Result<ProfileImageData>> = _uploadImageState
    private val _removeImageState = MutableLiveData<Result<DeleteData>>()
    val removeImageState: LiveData<Result<DeleteData>> = _removeImageState
    private val _portfolioState = MutableLiveData<Result<PortfolioResult>>()
    val portfolioState: LiveData<Result<PortfolioResult>> = _portfolioState
    private val _portfolioUploadState = MutableLiveData<Result<PortfolioSample>>()
    val portfolioUploadState: LiveData<Result<PortfolioSample>> = _portfolioUploadState
    private val _portfolioDeleteState = MutableLiveData<Result<Boolean>>()
    val portfolioDeleteState: LiveData<Result<Boolean>> = _portfolioDeleteState
    private val _takerPostsState = MutableLiveData<Result<TakerPostsResult>>()
    val takerPostsState: LiveData<Result<TakerPostsResult>> = _takerPostsState
    private val _accountPostActivityState = MutableLiveData<Result<AccountPostActivityResult>>()
    val accountPostActivityState: LiveData<Result<AccountPostActivityResult>> = _accountPostActivityState
    private val _createPostState = MutableLiveData<Result<TakerPostData>>()
    val createPostState: LiveData<Result<TakerPostData>> = _createPostState
    private val _togglePostLikeState = MutableLiveData<Result<ToggleTakerPostLikeData>>()
    val togglePostLikeState: LiveData<Result<ToggleTakerPostLikeData>> = _togglePostLikeState
    private val _togglePostImageLikeState = MutableLiveData<Result<ToggleTakerPostImageLikeData>>()
    val togglePostImageLikeState: LiveData<Result<ToggleTakerPostImageLikeData>> = _togglePostImageLikeState
    private val _togglePostSaveState = MutableLiveData<Result<ToggleTakerPostSaveData>>()
    val togglePostSaveState: LiveData<Result<ToggleTakerPostSaveData>> = _togglePostSaveState
    private val _recordPostViewState = MutableLiveData<Result<TakerPostViewData>>()
    val recordPostViewState: LiveData<Result<TakerPostViewData>> = _recordPostViewState
    private val _deletePostState = MutableLiveData<Result<Boolean>>()
    val deletePostState: LiveData<Result<Boolean>> = _deletePostState
    private val _updatePostState = MutableLiveData<Result<IdData>>()
    val updatePostState: LiveData<Result<IdData>> = _updatePostState
    private val _favoriteStatusState = MutableLiveData<Result<FavoriteStatusData>>()
    val favoriteStatusState: LiveData<Result<FavoriteStatusData>> = _favoriteStatusState
    private val _toggleFavoriteState = MutableLiveData<Result<FavoriteStatusData>>()
    val toggleFavoriteState: LiveData<Result<FavoriteStatusData>> = _toggleFavoriteState
    private val _deleteAccountState = MutableLiveData<Result<Boolean>>()
    val deleteAccountState: LiveData<Result<Boolean>> = _deleteAccountState
    private val _trustStatusState = MutableLiveData<Result<TrustStatusResult>>()
    val trustStatusState: LiveData<Result<TrustStatusResult>> = _trustStatusState
    private val _studioVerificationState = MutableLiveData<Result<TrustActionResult>>()
    val studioVerificationState: LiveData<Result<TrustActionResult>> = _studioVerificationState
    private val _takerVerificationState = MutableLiveData<Result<TrustActionResult>>()
    val takerVerificationState: LiveData<Result<TrustActionResult>> = _takerVerificationState
    private val _endorsementState = MutableLiveData<Result<TrustActionResult>>()
    val endorsementState: LiveData<Result<TrustActionResult>> = _endorsementState
    private val _verificationDocumentState = MutableLiveData<Result<TrustActionResult>>()
    val verificationDocumentState: LiveData<Result<TrustActionResult>> = _verificationDocumentState
    private val availabilityGate = FetchGate(60_000L)
    private val reviewsGate = FetchGate(60_000L)
    private val takerGate = FetchGate(60_000L)

    fun fetchTakerProfile(takerId: Int, force: Boolean = false) {
        val key = takerId.toString()
        if (!takerGate.tryStart(key, force)) return
        _takerState.value = Result.Loading
        viewModelScope.launch {
            var success = false
            try {
                val result = takerRepo.fetchTakerProfile(
                    takerId,
                    cacheBust = if (force) System.currentTimeMillis() else null,
                )
                success = result is Result.Success
                _takerState.value = result
            } finally {
                takerGate.finish(key, success)
            }
        }
    }

    fun uploadProfileImage(takerId: Int, scope: String, imageUri: android.net.Uri, context: android.content.Context) {
        _uploadImageState.value = Result.Loading
        viewModelScope.launch {
            _uploadImageState.value = takerRepo.uploadProfileImage(takerId, scope, imageUri, context)
        }
    }

    fun uploadClientProfileImage(clientId: Int, imageUri: android.net.Uri, context: android.content.Context) {
        _uploadImageState.value = Result.Loading
        viewModelScope.launch {
            _uploadImageState.value = takerRepo.uploadClientProfileImage(clientId, imageUri, context)
        }
    }

    fun removeProfileImage(takerId: Int) {
        _removeImageState.value = Result.Loading
        viewModelScope.launch {
            _removeImageState.value = takerRepo.removeProfileImage(takerId)
        }
    }

    fun fetchPortfolio(takerId: Int) {
        _portfolioState.value = Result.Loading
        viewModelScope.launch {
            _portfolioState.value = takerRepo.fetchPortfolio(takerId)
        }
    }

    fun uploadPortfolioSample(
        takerId: Int,
        caption: String?,
        imageUri: android.net.Uri,
        context: android.content.Context
    ) {
        _portfolioUploadState.value = Result.Loading
        viewModelScope.launch {
            _portfolioUploadState.value = takerRepo.uploadPortfolioSample(takerId, caption, imageUri, context)
        }
    }

    fun deletePortfolioSample(takerId: Int, sampleId: Int) {
        _portfolioDeleteState.value = Result.Loading
        viewModelScope.launch {
            _portfolioDeleteState.value = takerRepo.deletePortfolioSample(takerId, sampleId)
        }
    }

    fun fetchTakerPosts(
        takerId: Int,
        viewerRole: String? = null,
        viewerId: Int? = null,
        page: Int = 1,
        limit: Int = 12,
        forceNetwork: Boolean = false,
    ) {
        _takerPostsState.value = Result.Loading
        viewModelScope.launch {
            _takerPostsState.value = takerRepo.fetchTakerPosts(
                takerId,
                viewerRole,
                viewerId,
                page,
                limit,
                cacheBust = if (forceNetwork) System.currentTimeMillis() else null,
            )
        }
    }

    fun fetchAccountPostActivity(
        actorRole: String,
        actorId: Int,
        collection: String? = null,
        page: Int = 1,
        limit: Int = 12,
        forceNetwork: Boolean = false,
    ) {
        _accountPostActivityState.value = Result.Loading
        viewModelScope.launch {
            _accountPostActivityState.value = takerRepo.fetchAccountPostActivity(
                actorRole,
                actorId,
                collection,
                page,
                limit,
                cacheBust = if (forceNetwork) System.currentTimeMillis() else null,
            )
        }
    }

    fun uploadTakerPost(
        takerId: Int,
        caption: String?,
        imageUris: List<android.net.Uri>,
        context: android.content.Context,
    ) {
        _createPostState.value = Result.Loading
        viewModelScope.launch {
            _createPostState.value = takerRepo.uploadTakerPost(takerId, caption, imageUris, context)
        }
    }

    fun toggleTakerPostLike(postId: Int, actorRole: String, actorId: Int, like: Boolean) {
        _togglePostLikeState.value = Result.Loading
        viewModelScope.launch {
            _togglePostLikeState.value = takerRepo.toggleTakerPostLike(postId, actorRole, actorId, like)
        }
    }

    fun toggleTakerPostImageLike(imageId: Int, actorRole: String, actorId: Int, like: Boolean) {
        _togglePostImageLikeState.value = Result.Loading
        viewModelScope.launch {
            _togglePostImageLikeState.value = takerRepo.toggleTakerPostImageLike(imageId, actorRole, actorId, like)
        }
    }

    fun toggleTakerPostSave(postId: Int, actorRole: String, actorId: Int, save: Boolean) {
        _togglePostSaveState.value = Result.Loading
        viewModelScope.launch {
            _togglePostSaveState.value = takerRepo.toggleTakerPostSave(postId, actorRole, actorId, save)
        }
    }

    fun recordTakerPostView(postId: Int, viewerRole: String, viewerId: Int) {
        _recordPostViewState.value = Result.Loading
        viewModelScope.launch {
            _recordPostViewState.value = takerRepo.recordTakerPostView(postId, viewerRole, viewerId)
        }
    }

    fun deleteTakerPost(takerId: Int, postId: Int) {
        _deletePostState.value = Result.Loading
        viewModelScope.launch {
            _deletePostState.value = takerRepo.deleteTakerPost(takerId, postId)
        }
    }

    fun updateTakerPost(
        takerId: Int,
        postId: Int,
        caption: String?,
        keepImageIds: List<Int>,
        newImageUris: List<android.net.Uri>,
        context: android.content.Context,
    ) {
        _updatePostState.value = Result.Loading
        viewModelScope.launch {
            _updatePostState.value = takerRepo.updateTakerPost(
                takerId = takerId,
                postId = postId,
                caption = caption,
                keepImageIds = keepImageIds,
                newImageUris = newImageUris,
                context = context,
            )
        }
    }

    fun fetchFavoriteStatus(takerId: Int, actorRole: String, actorId: Int) {
        _favoriteStatusState.value = Result.Loading
        viewModelScope.launch {
            _favoriteStatusState.value = favRepo.fetchFavoriteStatus(takerId, actorRole, actorId)
        }
    }

    fun toggleFavoriteRemote(takerId: Int, actorRole: String, actorId: Int, favorite: Boolean) {
        _toggleFavoriteState.value = Result.Loading
        favRepo.toggleFavoriteDurable(takerId, actorRole, actorId, favorite) { result ->
            _toggleFavoriteState.postValue(result)
        }
    }

    fun deleteTakerAccount(takerId: Int) {
        _deleteAccountState.value = Result.Loading
        viewModelScope.launch {
            _deleteAccountState.value = when (val result = takerRepo.deleteTakerAccount(takerId)) {
                is Result.Success -> Result.Success(result.data.deleted)
                is Result.Error -> result
                Result.Loading -> Result.Loading
            }
        }
    }

    fun fetchTrustStatus(takerId: Int? = null, clientId: Int? = null, force: Boolean = false) {
        _trustStatusState.value = Result.Loading
        viewModelScope.launch {
            _trustStatusState.value = trustRepo.getTrustStatus(
                takerId,
                clientId,
                cacheBust = if (force) System.currentTimeMillis() else null,
            )
        }
    }

    fun submitStudioVerification(
        clientId: Int,
        verificationPath: String,
        gstin: String? = null,
        googleMapsUrl: String? = null,
        ownerAadhaarSubmitted: Boolean = false,
    ) {
        _studioVerificationState.value = Result.Loading
        viewModelScope.launch {
            _studioVerificationState.value = trustRepo.submitStudioVerification(
                clientId = clientId,
                verificationPath = verificationPath,
                gstin = gstin,
                googleMapsUrl = googleMapsUrl,
                ownerAadhaarSubmitted = ownerAadhaarSubmitted,
            )
        }
    }

    fun submitTakerVerification(takerId: Int, socialUrl: String, portfolioUrl: String?, aadhaarSubmitted: Boolean) {
        _takerVerificationState.value = Result.Loading
        viewModelScope.launch {
            _takerVerificationState.value = trustRepo.submitTakerVerification(takerId, socialUrl, portfolioUrl, aadhaarSubmitted)
        }
    }

    fun toggleTakerEndorsement(takerId: Int, clientId: Int, endorse: Boolean, emailOtp: String? = null) {
        _endorsementState.value = Result.Loading
        viewModelScope.launch {
            _endorsementState.value = trustRepo.toggleTakerEndorsement(takerId, clientId, endorse, emailOtp)
        }
    }

    fun uploadVerificationDocument(
        targetRole: String,
        targetId: Int,
        documentType: String,
        documentUri: android.net.Uri,
        context: android.content.Context,
    ) {
        _verificationDocumentState.value = Result.Loading
        viewModelScope.launch {
            _verificationDocumentState.value = trustRepo.uploadVerificationDocument(
                targetRole = targetRole,
                targetId = targetId,
                documentType = documentType,
                documentUri = documentUri,
                context = context,
            )
        }
    }

    fun fetchAvailability(tid: Int, month: String? = null, force: Boolean = false) {
        val key = "$tid|${month.orEmpty()}"
        if (!availabilityGate.tryStart(key, force)) return
        viewModelScope.launch {
            _avail.value = Result.Loading
            var success = false
            try {
                val result = availRepo.fetch(tid, month)
                success = result is Result.Success
                _avail.value = result
            } finally {
                availabilityGate.finish(key, success)
            }
        }
    }
    fun fetchReviews(tid: Int, page: Int? = null, limit: Int = 10, force: Boolean = false) {
        val key = "$tid|${page ?: 0}|$limit"
        if (!reviewsGate.tryStart(key, force)) return
        viewModelScope.launch {
            _reviews.value = Result.Loading
            var success = false
            try {
                val result = reviewRepo.fetch(
                    tid,
                    page,
                    limit,
                    cacheBust = if (force) System.currentTimeMillis() else null,
                )
                success = result is Result.Success
                _reviews.value = result
            } finally {
                reviewsGate.finish(key, success)
            }
        }
    }
    fun submitReview(r: AddReviewRequest) { _addReview.value = Result.Loading; viewModelScope.launch { _addReview.value = reviewRepo.add(r) } }
    fun updateProfile(r: UpdateTakerRequest) {
        _updateState.value = Result.Loading
        viewModelScope.launch {
            val result = takerRepo.updateProfile(r)
            _updateState.value = result
            if (result is Result.Success) {
                fetchTakerProfile(r.takerId, force = true)
            }
        }
    }
    fun updateClientProfile(r: UpdateClientProfileRequest) {
        _updateState.value = Result.Loading
        viewModelScope.launch {
            _updateState.value = takerRepo.updateClientProfile(r)
        }
    }
    fun cachedAvailability(tid: Int) = availRepo.getCached(tid)
    fun cachedTaker(tid: Int) = takerRepo.getTaker(tid).map { it?.toModel() }
    fun isFavorite(uid: Int, tid: Int) = favRepo.isFavorite(uid, tid)
    fun toggleFavorite(uid: Int, tid: Int, isFav: Boolean) {
        viewModelScope.launch {
            if (isFav) favRepo.removeFavorite(uid, tid) else favRepo.addFavorite(uid, tid)
        }
    }
}

@HiltViewModel
class NotificationViewModel @Inject constructor(
    private val repo: NotificationRepository,
    private val session: SessionManager,
) : ViewModel() {
    private val _notificationsState = MutableLiveData<Result<NotificationsResult>>()
    val notificationsState: LiveData<Result<NotificationsResult>> = _notificationsState
    private val notificationsGate = FetchGate(45_000L)

    private val _markReadState = MutableLiveData<Result<NotificationReadData>>()
    val markReadState: LiveData<Result<NotificationReadData>> = _markReadState

    fun fetchNotifications(force: Boolean = false) {
        if (!session.isLoggedIn() || session.isGuest()) {
            _notificationsState.value = Result.Success(
                NotificationsResult(
                    notifications = emptyList(),
                    total = 0,
                    unreadCount = 0,
                )
            )
            return
        }
        val key = "notifications:${session.getRole()}:${session.getUserId()}:${session.getTakerProfileId()}:${session.getClientProfileId()}"
        if (!notificationsGate.tryStart(key, force)) return
        _notificationsState.value = Result.Loading
        viewModelScope.launch {
            var success = false
            try {
                val result = repo.fetchAll()
                success = result is Result.Success
                _notificationsState.value = result
            } finally {
                notificationsGate.finish(key, success)
            }
        }
    }

    fun markRead(notificationId: Int) {
        if (!session.isLoggedIn() || session.isGuest()) {
            _markReadState.value = Result.Error("Notification profile not available")
            return
        }
        _markReadState.value = Result.Loading
        notificationsGate.invalidate()
        viewModelScope.launch {
            _markReadState.value = repo.markRead(notificationId)
        }
    }

    fun markAllRead() {
        if (!session.isLoggedIn() || session.isGuest()) {
            _markReadState.value = Result.Error("Notification profile not available")
            return
        }
        _markReadState.value = Result.Loading
        notificationsGate.invalidate()
        viewModelScope.launch {
            _markReadState.value = repo.markAllRead()
        }
    }
}

@HiltViewModel
class BookingViewModel @Inject constructor(
    private val repo: BookingRepository,
    private val session: SessionManager,
    private val trustRepo: TrustRepository,
) : ViewModel() {
    private val _bookState = MutableLiveData<Result<BookingData>>()
    val bookState: LiveData<Result<BookingData>> = _bookState
    private val _placedState = MutableLiveData<Result<BookingsResult>>()
    val placedState: LiveData<Result<BookingsResult>> = _placedState
    private val _receivedState = MutableLiveData<Result<BookingsResult>>()
    val receivedState: LiveData<Result<BookingsResult>> = _receivedState
    private val _updateState = MutableLiveData<Result<BookingStatusData>>()
    val updateState: LiveData<Result<BookingStatusData>> = _updateState
    private val _studioReviewState = MutableLiveData<Result<TrustActionResult>>()
    val studioReviewState: LiveData<Result<TrustActionResult>> = _studioReviewState
    private val placedBookingsGate = FetchGate(30_000L)
    private val receivedBookingsGate = FetchGate(30_000L)
    private var bookJob: Job? = null
    private var updateJob: Job? = null

    fun book(
        takerId: Int,
        date: String,
        svc: String,
        dayPart: String = "full_day",
        loc: String? = null,
        notes: String? = null,
        takerName: String? = null,
    ) {
        if (bookJob?.isActive == true) return
        val clientProfileId = session.getClientProfileId()
        if (clientProfileId == 0) {
            _bookState.value = Result.Error("Client profile not available for booking")
            return
        }
        _bookState.value = Result.Loading
        placedBookingsGate.invalidate()
        bookJob = viewModelScope.launch {
            _bookState.value = repo.book(
                BookTakerRequest(
                    clientId = clientProfileId,
                    takerId = takerId,
                    bookingDate = date,
                    serviceType = svc,
                    dayPart = dayPart,
                    eventLocation = loc,
                    notes = notes,
                    takerName = takerName,
                )
            )
        }
    }
    fun fetchPlacedBookings(force: Boolean = false) {
        if (!session.hasClientFeatures()) {
            _placedState.value = Result.Success(BookingsResult(emptyList(), 0))
            return
        }
        val key = "placed:${session.getClientProfileId()}"
        if (!placedBookingsGate.tryStart(key, force)) return
        _placedState.value = Result.Loading
        viewModelScope.launch {
            var success = false
            try {
                val result = repo.fetchAll(clientId = session.getClientProfileId())
                success = result is Result.Success
                _placedState.value = result
            } finally {
                placedBookingsGate.finish(key, success)
            }
        }
    }
    fun fetchReceivedBookings(force: Boolean = false) {
        if (!session.isTaker()) return
        val takerId = session.getTakerActorId()
        val key = "received:$takerId"
        if (!receivedBookingsGate.tryStart(key, force)) return
        _receivedState.value = Result.Loading
        viewModelScope.launch {
            var success = false
            try {
                val result = repo.fetchAll(takerId = takerId)
                success = result is Result.Success
                _receivedState.value = result
            } finally {
                receivedBookingsGate.finish(key, success)
            }
        }
    }
    fun updateBookingStatus(bookingId: Int, status: String, asTaker: Boolean) {
        if (updateJob?.isActive == true) return
        val actorId = if (asTaker) session.getTakerActorId() else session.getClientActorId()
        if (actorId == 0) {
            _updateState.value = Result.Error("This account cannot update that booking")
            return
        }
        _updateState.value = Result.Loading
        placedBookingsGate.invalidate()
        receivedBookingsGate.invalidate()
        updateJob = viewModelScope.launch {
            _updateState.value = repo.updateStatus(
                UpdateBookingStatusRequest(
                    bookingId = bookingId,
                    status = status,
                    actorRole = if (asTaker) SessionManager.ROLE_TAKER else SessionManager.ROLE_CLIENT,
                    actorId = actorId,
                )
            )
        }
    }

    fun submitStudioReview(clientId: Int, rating: Int, comment: String?) {
        val takerId = session.getTakerActorId()
        if (clientId <= 0 || takerId <= 0) {
            _studioReviewState.value = Result.Error("Studio review needs a completed booking")
            return
        }
        _studioReviewState.value = Result.Loading
        viewModelScope.launch {
            _studioReviewState.value = trustRepo.addStudioReview(clientId, takerId, rating, comment)
        }
    }

    fun cachedPlacedBookings() = repo.getByClient(session.getClientProfileId())
    fun cachedReceivedBookings() = repo.getByTaker(session.getTakerActorId())
}

@HiltViewModel
class EventViewModel @Inject constructor(
    private val repo: EventRepository,
    private val session: SessionManager,
) : ViewModel() {
    private val _eventsState = MutableLiveData<Result<EventsResult>>()
    val eventsState: LiveData<Result<EventsResult>> = _eventsState
    private val _saveState = MutableLiveData<Result<EventIdData>>()
    val saveState: LiveData<Result<EventIdData>> = _saveState
    private val _deleteState = MutableLiveData<Result<DeleteData>>()
    val deleteState: LiveData<Result<DeleteData>> = _deleteState
    private val eventsGate = FetchGate(30_000L)
    private var saveJob: Job? = null
    private var deleteJob: Job? = null

    private fun actorIdForCurrentRole(): Int =
        if (session.isTaker()) session.getTakerActorId() else session.getClientActorId()

    fun cachedEvents() = repo.getCached(session.getRole(), actorIdForCurrentRole(), session.getClientProfileId())

    fun fetchEvents(scope: String = "all", force: Boolean = false) {
        val actorId = actorIdForCurrentRole()
        val key = "events:${session.getRole()}:$actorId:${session.getClientProfileId()}:$scope"
        if (!eventsGate.tryStart(key, force)) return
        _eventsState.value = Result.Loading
        viewModelScope.launch {
            var success = false
            try {
                val result = repo.fetchAll(
                    session.getRole(),
                    actorId,
                    scope,
                    session.getClientProfileId(),
                )
                success = result is Result.Success
                _eventsState.value = result
            } finally {
                eventsGate.finish(key, success)
            }
        }
    }

    fun saveEvent(request: UpsertEventRequest) {
        if (saveJob?.isActive == true) return
        _saveState.value = Result.Loading
        eventsGate.invalidate()
        val requestToSave = if ((request.eventId ?: 0) <= 0 && request.clientRequestId.isNullOrBlank()) {
            request.copy(clientRequestId = UUID.randomUUID().toString())
        } else {
            request
        }
        saveJob = viewModelScope.launch {
            _saveState.value = if ((requestToSave.eventId ?: 0) > 0) {
                repo.update(
                    requestToSave,
                    session.getRole(),
                    actorIdForCurrentRole(),
                    session.getClientProfileId(),
                )
            } else {
                repo.create(
                    requestToSave,
                    session.getRole(),
                    actorIdForCurrentRole(),
                    session.getClientProfileId(),
                )
            }
        }
    }

    fun deleteEvent(eventId: Int) {
        if (deleteJob?.isActive == true) return
        _deleteState.value = Result.Loading
        eventsGate.invalidate()
        deleteJob = viewModelScope.launch {
            _deleteState.value = repo.delete(eventId)
        }
    }

    fun clearOfflineNotes() {
        viewModelScope.launch { repo.clearOfflineNotes() }
    }
}

@HiltViewModel
class AvailabilityViewModel @Inject constructor(
    private val availRepo: AvailabilityRepository,
    private val bookRepo: BookingRepository,
    private val session: SessionManager,
) : ViewModel() {
    private val _avail  = MutableLiveData<Result<AvailabilityResult>>()
    val avail: LiveData<Result<AvailabilityResult>> = _avail
    private val _update = MutableLiveData<Result<CountData>>()
    val update: LiveData<Result<CountData>> = _update
    val takerBookings = bookRepo.getByTaker(session.getTakerActorId())
    private val availabilityGate = FetchGate(45_000L)
    private val bookingsGate = FetchGate(30_000L)
    private var updateJob: Job? = null

    fun fetch(month: String? = null, force: Boolean = false) {
        val takerId = session.getTakerActorId()
        val key = "$takerId|${month.orEmpty()}"
        if (!availabilityGate.tryStart(key, force)) return
        viewModelScope.launch {
            _avail.value = Result.Loading
            var success = false
            try {
                val result = availRepo.fetch(takerId, month)
                success = result is Result.Success
                _avail.value = result
            } finally {
                availabilityGate.finish(key, success)
            }
        }
    }
    fun cached() = availRepo.getCached(session.getTakerActorId())
    fun fetchBookings(force: Boolean = false) {
        if (!session.isTaker()) return
        val takerId = session.getTakerActorId()
        val key = "dashboard-bookings:$takerId"
        if (!bookingsGate.tryStart(key, force)) return
        viewModelScope.launch {
            var success = false
            try {
                success = bookRepo.fetchAll(takerId = takerId) is Result.Success
            } finally {
                bookingsGate.finish(key, success)
            }
        }
    }
    fun updateDates(entries: List<AvailabilityEntry>, refreshMonth: String? = null) {
        if (updateJob?.isActive == true) return
        _update.value = Result.Loading
        availabilityGate.invalidate()
        bookingsGate.invalidate()
        updateJob = viewModelScope.launch {
            val takerId = session.getTakerActorId()
            availRepo.applyLocal(takerId, entries)
            when (val remote = availRepo.syncRemote(takerId, entries)) {
                is Result.Success -> {
                    _update.value = remote
                    if (remote.data.skippedBookedCount > 0) {
                        availRepo.discardLocalOverrides(takerId, entries)
                        refreshMonth?.let { fetch(it, force = true) }
                    }
                }
                is Result.Error -> {
                    _update.value = remote
                    availRepo.discardLocalOverrides(takerId, entries)
                    refreshMonth?.let { fetch(it, force = true) }
                }
                Result.Loading -> Unit
            }
        }
    }
}

@HiltViewModel
class PincodeViewModel @Inject constructor(private val repo: PincodeRepository) : ViewModel() {
    private val _result = MutableLiveData<List<PostOffice>>()
    val result: LiveData<List<PostOffice>> = _result
    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading
    private val cache = mutableMapOf<String, List<PostOffice>>()

    fun lookup(pincode: String) {
        if (pincode.length != 6) return
        val key = "pin:${pincode.trim()}"
        cache[key]?.let {
            _loading.value = false
            _result.value = it
            return
        }
        _loading.value = true
        viewModelScope.launch {
            val rows = repo.lookup(pincode)
            if (rows.isNotEmpty()) cache[key] = rows
            _result.value = rows
            _loading.value = false
        }
    }

    fun searchPlace(place: String) {
        val clean = place.trim()
        if (clean.length < 3) return
        val key = "place:${clean.lowercase()}"
        cache[key]?.let {
            _loading.value = false
            _result.value = it
            return
        }
        _loading.value = true
        viewModelScope.launch {
            val rows = repo.searchPlace(clean)
            if (rows.isNotEmpty()) cache[key] = rows
            _result.value = rows
            _loading.value = false
        }
    }
}

@HiltViewModel
class PlaceSuggestionViewModel @Inject constructor(private val repo: PlaceSuggestionRepository) : ViewModel() {
    private val _places = MutableLiveData<List<NominatimPlace>>()
    val places: LiveData<List<NominatimPlace>> = _places
    private val cache = mutableMapOf<String, List<NominatimPlace>>()

    fun searchPlaces(query: String) {
        val clean = query.trim()
        if (clean.length < 3) return
        val key = clean.lowercase()
        cache[key]?.let {
            _places.value = it
            return
        }
        viewModelScope.launch {
            val rows = repo.searchPlaces(clean)
            cache[key] = rows
            _places.value = rows
        }
    }
}

private fun Map<String, Any?>.number(key: String): Int =
    when (val value = this[key]) {
        is Double -> value.toInt()
        is Int -> value
        is Long -> value.toInt()
        else -> 0
    }

private fun Map<String, Any?>.text(key: String): String =
    (this[key] as? String)?.trim().orEmpty()
