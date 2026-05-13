package com.photoconnect.viewmodel

import androidx.lifecycle.*
import com.photoconnect.model.*
import com.photoconnect.network.*
import com.photoconnect.repository.*
import com.photoconnect.utils.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
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
                val user = r.data.user
                session.saveSession(
                    role   = r.data.role,
                    userId = user.number("id"),
                    name   = user.text("full_name").ifEmpty { user.text("name") },
                    phone  = user.text("phone"),
                    email  = user.text("email"),
                    clientProfileId = user.number("client_profile_id").takeIf { it != 0 }
                        ?: if (r.data.role == SessionManager.ROLE_CLIENT) user.number("id") else 0,
                    accessToken = r.data.accessToken,
                    refreshToken = r.data.refreshToken,
                )
            }
            _loginState.value = r
        }
    }

    /** Role is resolved on the server (`auto`). */
    fun loginAuto(identity: String, password: String) = login("auto", identity, password)

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
}

@HiltViewModel
class HomeViewModel @Inject constructor(private val takerRepo: TakerRepository) : ViewModel() {
    private val _state = MutableLiveData<Result<SearchResult>>()
    val state: LiveData<Result<SearchResult>> = _state
    private var loc: String? = null; private var date: String? = null; private var svc: String? = null
    private var availOnly: Boolean = false
    private var latitude: Double? = null
    private var longitude: Double? = null
    val cached  = takerRepo.getCached(null, null)
    val featured = takerRepo.getFeatured()
    init { fetch() }

    fun fetch(
        location: String? = loc,
        date_: String? = date,
        service: String? = svc,
        availOnly: Boolean = this.availOnly,
        latitude: Double? = this.latitude,
        longitude: Double? = this.longitude,
    ) {
        loc = location
        date = date_
        svc = service
        this.availOnly = availOnly
        this.latitude = latitude
        this.longitude = longitude
        _state.value = Result.Loading
        viewModelScope.launch {
            _state.value = takerRepo.fetchAndCache(location, date_, service, availOnly, latitude, longitude)
        }
    }
    fun clearFilters() = fetch(null, null, null, false)
    fun hasFilters() = loc != null || date != null || svc != null || availOnly || latitude != null || longitude != null
}

@HiltViewModel
class TakerDetailViewModel @Inject constructor(
    private val takerRepo: TakerRepository,
    private val favRepo: FavoriteRepository,
    private val availRepo: AvailabilityRepository,
    private val reviewRepo: ReviewRepository,
) : ViewModel() {
    private val _avail  = MutableLiveData<Result<AvailabilityResult>>()
    val avail: LiveData<Result<AvailabilityResult>> = _avail
    private val _reviews = MutableLiveData<Result<ReviewsResult>>()
    val reviews: LiveData<Result<ReviewsResult>> = _reviews
    private val _addReview = MutableLiveData<Result<IdData>>()
    val addReview: LiveData<Result<IdData>> = _addReview
    private val _updateState = MutableLiveData<Result<IdData>>()
    val updateState: LiveData<Result<IdData>> = _updateState

    private val _uploadImageState = MutableLiveData<Result<ProfileImageData>>()
    val uploadImageState: LiveData<Result<ProfileImageData>> = _uploadImageState
    private val _portfolioState = MutableLiveData<Result<PortfolioResult>>()
    val portfolioState: LiveData<Result<PortfolioResult>> = _portfolioState
    private val _portfolioUploadState = MutableLiveData<Result<PortfolioSample>>()
    val portfolioUploadState: LiveData<Result<PortfolioSample>> = _portfolioUploadState
    private val _portfolioDeleteState = MutableLiveData<Result<Boolean>>()
    val portfolioDeleteState: LiveData<Result<Boolean>> = _portfolioDeleteState
    private val _takerPostsState = MutableLiveData<Result<TakerPostsResult>>()
    val takerPostsState: LiveData<Result<TakerPostsResult>> = _takerPostsState
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
    private val _favoriteStatusState = MutableLiveData<Result<FavoriteStatusData>>()
    val favoriteStatusState: LiveData<Result<FavoriteStatusData>> = _favoriteStatusState
    private val _toggleFavoriteState = MutableLiveData<Result<FavoriteStatusData>>()
    val toggleFavoriteState: LiveData<Result<FavoriteStatusData>> = _toggleFavoriteState
    private val _deleteAccountState = MutableLiveData<Result<Boolean>>()
    val deleteAccountState: LiveData<Result<Boolean>> = _deleteAccountState

    fun getTaker(id: Int) = takerRepo.getTaker(id)

    fun uploadProfileImage(takerId: Int, scope: String, imageUri: android.net.Uri, context: android.content.Context) {
        _uploadImageState.value = Result.Loading
        viewModelScope.launch {
            _uploadImageState.value = takerRepo.uploadProfileImage(takerId, scope, imageUri, context)
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

    fun fetchTakerPosts(takerId: Int, viewerRole: String? = null, viewerId: Int? = null) {
        _takerPostsState.value = Result.Loading
        viewModelScope.launch {
            _takerPostsState.value = takerRepo.fetchTakerPosts(takerId, viewerRole, viewerId)
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

    fun fetchFavoriteStatus(takerId: Int, actorRole: String, actorId: Int) {
        _favoriteStatusState.value = Result.Loading
        viewModelScope.launch {
            _favoriteStatusState.value = favRepo.fetchFavoriteStatus(takerId, actorRole, actorId)
        }
    }

    fun toggleFavoriteRemote(takerId: Int, actorRole: String, actorId: Int, favorite: Boolean) {
        _toggleFavoriteState.value = Result.Loading
        viewModelScope.launch {
            _toggleFavoriteState.value = favRepo.toggleFavorite(takerId, actorRole, actorId, favorite)
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

    fun fetchAvailability(tid: Int, month: String? = null) {
        viewModelScope.launch {
            _avail.value = Result.Loading
            _avail.value = availRepo.fetch(tid, month)
        }
    }
    fun fetchReviews(tid: Int)      { viewModelScope.launch { _reviews.value = Result.Loading; _reviews.value = reviewRepo.fetch(tid) } }
    fun submitReview(r: AddReviewRequest) { _addReview.value = Result.Loading; viewModelScope.launch { _addReview.value = reviewRepo.add(r) } }
    fun updateProfile(r: UpdateTakerRequest) { _updateState.value = Result.Loading; viewModelScope.launch { _updateState.value = takerRepo.updateProfile(r) } }
    fun cachedAvailability(tid: Int) = availRepo.getCached(tid)
    fun cachedReviews(tid: Int)      = reviewRepo.getCached(tid)

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
) : ViewModel() {
    private val _notificationsState = MutableLiveData<Result<NotificationsResult>>()
    val notificationsState: LiveData<Result<NotificationsResult>> = _notificationsState

    private val _markReadState = MutableLiveData<Result<NotificationReadData>>()
    val markReadState: LiveData<Result<NotificationReadData>> = _markReadState

    fun fetchNotifications() {
        _notificationsState.value = Result.Loading
        viewModelScope.launch {
            _notificationsState.value = repo.fetchAll()
        }
    }

    fun markRead(notificationId: Int) {
        _markReadState.value = Result.Loading
        viewModelScope.launch {
            _markReadState.value = repo.markRead(notificationId)
        }
    }
}

@HiltViewModel
class BookingViewModel @Inject constructor(
    private val repo: BookingRepository,
    private val session: SessionManager,
) : ViewModel() {
    private val _bookState = MutableLiveData<Result<BookingData>>()
    val bookState: LiveData<Result<BookingData>> = _bookState
    private val _placedState = MutableLiveData<Result<BookingsResult>>()
    val placedState: LiveData<Result<BookingsResult>> = _placedState
    private val _receivedState = MutableLiveData<Result<BookingsResult>>()
    val receivedState: LiveData<Result<BookingsResult>> = _receivedState
    private val _updateState = MutableLiveData<Result<BookingStatusData>>()
    val updateState: LiveData<Result<BookingStatusData>> = _updateState

    fun book(
        takerId: Int,
        date: String,
        svc: String,
        loc: String? = null,
        notes: String? = null,
        takerName: String? = null,
    ) {
        val clientProfileId = session.getClientProfileId()
        if (clientProfileId == 0) {
            _bookState.value = Result.Error("Client profile not available for booking")
            return
        }
        _bookState.value = Result.Loading
        viewModelScope.launch {
            _bookState.value = repo.book(
                BookTakerRequest(clientProfileId, takerId, date, svc, loc, notes, takerName = takerName)
            )
        }
    }
    fun fetchPlacedBookings() {
        if (!session.hasClientFeatures()) {
            _placedState.value = Result.Success(BookingsResult(emptyList(), 0))
            return
        }
        _placedState.value = Result.Loading
        viewModelScope.launch { _placedState.value = repo.fetchAll(clientId = session.getClientProfileId()) }
    }
    fun fetchReceivedBookings() {
        if (!session.isTaker()) return
        _receivedState.value = Result.Loading
        viewModelScope.launch { _receivedState.value = repo.fetchAll(takerId = session.getUserId()) }
    }
    fun updateBookingStatus(bookingId: Int, status: String, asTaker: Boolean) {
        val actorId = if (asTaker) session.getUserId() else session.getClientProfileId()
        if (actorId == 0) {
            _updateState.value = Result.Error("This account cannot update that booking")
            return
        }
        _updateState.value = Result.Loading
        viewModelScope.launch {
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
    fun cachedPlacedBookings() = repo.getByClient(session.getClientProfileId())
    fun cachedReceivedBookings() = repo.getByTaker(session.getUserId())
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
    val takerBookings = bookRepo.getByTaker(session.getUserId())

    fun fetch(month: String? = null) {
        viewModelScope.launch { _avail.value = Result.Loading; _avail.value = availRepo.fetch(session.getUserId(), month) }
    }
    fun cached() = availRepo.getCached(session.getUserId())
    fun updateDates(entries: List<AvailabilityEntry>) {
        _update.value = Result.Loading
        viewModelScope.launch { _update.value = availRepo.update(session.getUserId(), entries) }
    }
}

@HiltViewModel
class PincodeViewModel @Inject constructor(private val repo: PincodeRepository) : ViewModel() {
    private val _result = MutableLiveData<List<PostOffice>>()
    val result: LiveData<List<PostOffice>> = _result

    fun lookup(pincode: String) {
        if (pincode.length != 6) return
        viewModelScope.launch { _result.value = repo.lookup(pincode) }
    }

    fun searchPlace(place: String) {
        if (place.trim().length < 3) return
        viewModelScope.launch { _result.value = repo.searchPlace(place.trim()) }
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
