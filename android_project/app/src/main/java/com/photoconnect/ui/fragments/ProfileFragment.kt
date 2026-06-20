package com.photoconnect.ui.fragments

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import com.photoconnect.R
import com.photoconnect.databinding.BottomSheetProfileMenuBinding
import com.photoconnect.databinding.FragmentProfileBinding
import com.photoconnect.db.ReviewEntity
import com.photoconnect.model.StudioTrustSummary
import com.photoconnect.model.TakerTrustSummary
import com.photoconnect.model.TakerPost
import com.photoconnect.model.TakerPostImage
import com.photoconnect.repository.Result
import com.photoconnect.ui.activities.CreatePostActivity
import com.photoconnect.ui.activities.ErrorConsoleActivity
import com.photoconnect.ui.activities.HelpSupportActivity
import com.photoconnect.ui.activities.EditProfileActivity
import com.photoconnect.ui.activities.LoginActivity
import com.photoconnect.ui.activities.TakerPostViewerActivity
import com.photoconnect.ui.adapters.ProfilePagerAdapter
import com.photoconnect.utils.AppTourManager
import com.photoconnect.utils.AppLocaleManager
import com.photoconnect.utils.PendingPostStore
import com.photoconnect.utils.PostDownloadWatermarker
import com.photoconnect.utils.SessionManager
import com.photoconnect.utils.show
import com.photoconnect.utils.isNetworkAvailable
import com.photoconnect.utils.show
import com.photoconnect.utils.toast
import com.photoconnect.utils.hide
import com.photoconnect.viewmodel.TakerDetailViewModel
import com.photoconnect.workers.PostUploadWorker
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.lifecycle.lifecycleScope
import com.photoconnect.db.PhotoConnectDatabase

@AndroidEntryPoint
class ProfileFragment : Fragment() {
    private var _b: FragmentProfileBinding? = null
    private val b get() = _b!!
    private val vm: TakerDetailViewModel by viewModels()
    private var accountPosts: List<TakerPost> = emptyList()
    private var accountSavedPosts: List<TakerPost> = emptyList()
    private var accountLikedPosts: List<TakerPost> = emptyList()
    private var postsPage = 0
    private var postsTotalPages = 1
    private var pendingPostsPage = 0
    private var savedPage = 0
    private var savedTotalPages = 1
    private var savedTotal = 0
    private var pendingSavedPage = 0
    private var likedPage = 0
    private var likedTotalPages = 1
    private var pendingLikedPage = 0
    private var reviewsPage = 0
    private var reviewsTotalPages = 1
    private var pendingReviewsPage = 0
    private val uploadProgressHandler = Handler(Looper.getMainLooper())
    private val pendingJobIdsByLocalPostId = mutableMapOf<Int, String>()
    private var lastUploadedSyncRefreshAt = 0L
    private var accountReviews: List<ReviewEntity> = emptyList()
    private var accountReviewCount = 0
    private var editingPostId = 0
    private var pendingDeletedPostId = 0
    private var profileHeaderTargetCollapsed = false
    private var pendingTakerAadhaarUri: Uri? = null
    private var pendingTakerAadhaarCameraUri: Uri? = null
    private var pendingTakerAadhaarLabel: android.widget.TextView? = null
    private var isCapturingTakerAadhaarBack = false
    private var pendingTakerAadhaarFrontUri: Uri? = null
    private var pendingClientAvatarUri: Uri? = null
    private var pendingStudioDocumentUri: Uri? = null
    private var pendingStudioDocumentLabel: android.widget.TextView? = null
    private var pendingStudioDocumentPreview: android.widget.ImageView? = null
    private var pendingStudioDocumentType: String = "shop_license"
    private var pendingStudioOwnerAadhaarUri: Uri? = null
    private var pendingStudioOwnerAadhaarCameraUri: Uri? = null
    private var pendingStudioOwnerAadhaarLabel: android.widget.TextView? = null
    private var pendingStudioOwnerAadhaarPreview: android.widget.ImageView? = null
    private var isCapturingStudioOwnerAadhaarBack = false
    private var pendingStudioOwnerAadhaarFrontUri: Uri? = null
    private var pendingStudioBusinessSubmissionPending = false
    private var pendingStudioOwnerAadhaarSubmissionPending = false
    private var verificationPromptHiddenThisSession = false
    private var accountTakerTrust: TakerTrustSummary? = null
    private var accountStudioTrust: StudioTrustSummary? = null
    private var accountTakerSocialUrl: String? = null
    private var accountTakerPortfolioUrl: String? = null
    private var pendingTakerVerificationSubmit: android.widget.Button? = null
    private var pendingStudioVerificationSubmit: android.widget.Button? = null
    private var updateTakerVerificationSubmitState: (() -> Unit)? = null
    private var updateStudioVerificationSubmitState: (() -> Unit)? = null
    private var pendingStudioVerificationSheet: BottomSheetDialog? = null
    private var pendingStudioGstLayout: TextInputLayout? = null
    private var pendingStudioMapsLayout: TextInputLayout? = null
    private var pendingTakerVerificationSheet: BottomSheetDialog? = null
    private var pendingTakerSocialLayout: TextInputLayout? = null
    private var pendingTakerPortfolioLayout: TextInputLayout? = null
    private var pendingTakerAadhaarPreview: android.widget.ImageView? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var lastAccountRefreshAt: Long = 0L
    private val uploadProgressTicker = object : Runnable {
        override fun run() {
            if (_b == null || !session.isTaker()) return
            val pendingItems = PendingPostStore.getForTaker(requireContext(), session.getTakerActorId())
            val now = System.currentTimeMillis()
            if (pendingItems.any { it.uploaded } && now - lastUploadedSyncRefreshAt > 4000L) {
                lastUploadedSyncRefreshAt = now
                vm.fetchTakerPosts(
                    session.getTakerActorId(),
                    viewerRole = session.getRole(),
                    viewerId = session.getTakerActorId(),
                    forceNetwork = true,
                )
            }
            val pending = renderPendingUploads()
            if (pending.isNotEmpty()) {
                uploadProgressHandler.postDelayed(this, 1500L)
            }
        }
    }
    private val profilePagerAdapter by lazy {
        ProfilePagerAdapter(
            onPostClick = { openPost(it) },
            onPostMenu = { post, anchor -> showPostMenu(post, anchor) },
            canShowPostMenu = { post -> session.isTaker() && post.takerId == session.getTakerActorId() },
            onPageScroll = { isAtTop, dy ->
                when {
                    dy > 1 -> setProfileHeaderCollapsed(true)
                    dy < -1 && isAtTop -> setProfileHeaderCollapsed(false)
                }
            },
            onLoadMore = { page -> loadMoreAccountPage(page) },
            onImageLikeToggle = { post, image ->
                if (!session.isLoggedIn() || session.isGuest()) {
                    requireContext().toast(getString(R.string.sign_in_like_images))
                } else if (session.isTaker() && post.takerId == session.getTakerActorId()) {
                    requireContext().toast(getString(R.string.cannot_like_own_image))
                } else {
                    vm.toggleTakerPostImageLike(image.id, session.getRole(), session.getActiveActorId(), !image.viewerHasLiked)
                }
            },
            onSaveToggle = { post ->
                if (!session.isLoggedIn() || session.isGuest()) {
                    requireContext().toast(getString(R.string.sign_in_save_posts))
                } else if (session.isTaker() && post.takerId == session.getTakerActorId()) {
                    requireContext().toast(getString(R.string.cannot_save_own_post))
                } else {
                    vm.toggleTakerPostSave(post.id, session.getRole(), session.getActiveActorId(), !post.viewerHasSaved)
                }
            }
        )
    }

    @Inject lateinit var session: SessionManager
    @Inject lateinit var database: PhotoConnectDatabase

    private val pickPostImages = registerForActivityResult(PickMultipleVisualMedia(PostUploadWorker.MAX_IMAGES)) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        uris.forEach(::persistReadPermission)
        var skippedCount = 0
        val validUris = uris.filter { uri ->
            if (com.photoconnect.utils.ExifUtils.isOriginalCameraPhoto(requireContext(), uri)) {
                true
            } else {
                skippedCount++
                false
            }
        }

        if (skippedCount > 0 && validUris.isEmpty()) {
            showAccountInlineError(getString(R.string.photo_source_error_message, skippedCount))
            return@registerForActivityResult
        }

        val limitedUris = if (validUris.size > PostUploadWorker.MAX_IMAGES) {
            requireContext().toast(getString(R.string.only_8_images_allowed))
            validUris.take(PostUploadWorker.MAX_IMAGES)
        } else {
            validUris
        }
        hideAccountInlineError()
        val intent = Intent(requireContext(), CreatePostActivity::class.java).apply {
            putStringArrayListExtra(
                CreatePostActivity.EXTRA_URIS,
                ArrayList(limitedUris.map { it.toString() })
            )
            putExtra(CreatePostActivity.EXTRA_SKIPPED_IMAGE_COUNT, skippedCount)
        }
        createPostLauncher.launch(intent)
    }

    private val createPostLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            refreshAccount()
        }
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private val editPostLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            refreshAccount()
        }
    }

    private val captureTakerAadhaar = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val capturedUri = pendingTakerAadhaarCameraUri
        if (success && capturedUri != null) {
            cropTakerAadhaar.launch(
                CropImageContractOptions(
                    capturedUri,
                    CropImageOptions(
                        guidelines = CropImageView.Guidelines.ON,
                        fixAspectRatio = false,
                    ),
                )
            )
        } else {
            if (!isCapturingTakerAadhaarBack) {
                pendingTakerAadhaarUri = null
                pendingTakerAadhaarLabel?.text = getString(R.string.no_verification_document_selected)
                updateTakerVerificationSubmitState?.invoke()
            }
        }
    }

    private val cropTakerAadhaar = registerForActivityResult(CropImageContract()) { result ->
        val croppedUri = result.uriContent
        if (result.isSuccessful && croppedUri != null) {
            if (!isCapturingTakerAadhaarBack) {
                pendingTakerAadhaarFrontUri = croppedUri
                isCapturingTakerAadhaarBack = true
                Toast.makeText(requireContext(), getString(R.string.verification_aadhaar_next_back), Toast.LENGTH_LONG).show()
                // launchTakerAadhaarCamera()
            } else {
                isCapturingTakerAadhaarBack = false
                val backUri = croppedUri
                val frontUri = pendingTakerAadhaarFrontUri
                if (frontUri != null) {
                    lifecycleScope.launch {
                        pendingTakerAadhaarLabel?.text = getString(R.string.verification_collaging)
                        val collagedUri = com.photoconnect.utils.AadhaarCollageUtils.createCollage(requireContext(), frontUri, backUri)
                        if (collagedUri != null) {
                            pendingTakerAadhaarUri = collagedUri
                            pendingTakerAadhaarLabel?.text = getString(R.string.aadhaar_photo_ready)
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.verification_collage_failed), Toast.LENGTH_SHORT).show()
                            pendingTakerAadhaarUri = null
                            pendingTakerAadhaarLabel?.text = getString(R.string.no_verification_document_selected)
                        }
                        updateTakerVerificationSubmitState?.invoke()
                    }
                }
            }
        } else {
            isCapturingTakerAadhaarBack = false
            if (!isCapturingTakerAadhaarBack) {
                pendingTakerAadhaarUri = null
                pendingTakerAadhaarLabel?.text = getString(R.string.no_verification_document_selected)
                updateTakerVerificationSubmitState?.invoke()
            }
        }
    }

    private val pickClientAvatarImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        cropClientAvatarImage.launch(
            CropImageContractOptions(
                uri,
                CropImageOptions(
                    guidelines = CropImageView.Guidelines.ON,
                    fixAspectRatio = true,
                    aspectRatioX = 1,
                    aspectRatioY = 1,
                ),
            )
        )
    }

    private val cropClientAvatarImage = registerForActivityResult(CropImageContract()) { result ->
        val croppedUri = result.uriContent
        if (result.isSuccessful && croppedUri != null) {
            pendingClientAvatarUri = croppedUri
            renderProfileImage(croppedUri.toString(), null)
            vm.uploadClientProfileImage(session.getClientActorId(), croppedUri, requireContext())
        }
    }

    private val pickStudioVerificationDocument = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pendingStudioDocumentUri = uri
        pendingStudioDocumentLabel?.text = if (uri != null) {
            getString(R.string.verification_document_selected_named, studioBusinessDocumentLabel(pendingStudioDocumentType))
        } else {
            getString(R.string.no_verification_document_selected)
        }
        updateStudioVerificationSubmitState?.invoke()
    }

    private val captureStudioOwnerAadhaar = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val capturedUri = pendingStudioOwnerAadhaarCameraUri
        if (success && capturedUri != null) {
            cropStudioOwnerAadhaar.launch(
                CropImageContractOptions(
                    capturedUri,
                    CropImageOptions(
                        guidelines = CropImageView.Guidelines.ON,
                        fixAspectRatio = false,
                    ),
                )
            )
        } else {
            if (!isCapturingStudioOwnerAadhaarBack) {
                pendingStudioOwnerAadhaarUri = null
                pendingStudioOwnerAadhaarLabel?.text = getString(R.string.no_verification_document_selected)
                updateStudioVerificationSubmitState?.invoke()
            }
        }
    }

    private val cropStudioOwnerAadhaar = registerForActivityResult(CropImageContract()) { result ->
        val croppedUri = result.uriContent
        if (result.isSuccessful && croppedUri != null) {
            if (!isCapturingStudioOwnerAadhaarBack) {
                pendingStudioOwnerAadhaarFrontUri = croppedUri
                isCapturingStudioOwnerAadhaarBack = true
                Toast.makeText(requireContext(), getString(R.string.verification_aadhaar_next_back), Toast.LENGTH_LONG).show()
                // launchStudioOwnerAadhaarCamera()
            } else {
                isCapturingStudioOwnerAadhaarBack = false
                val backUri = croppedUri
                val frontUri = pendingStudioOwnerAadhaarFrontUri
                if (frontUri != null) {
                    lifecycleScope.launch {
                        pendingStudioOwnerAadhaarLabel?.text = getString(R.string.verification_collaging)
                        val collagedUri = com.photoconnect.utils.AadhaarCollageUtils.createCollage(requireContext(), frontUri, backUri)
                        if (collagedUri != null) {
                            pendingStudioOwnerAadhaarUri = collagedUri
                            pendingStudioOwnerAadhaarLabel?.text = getString(R.string.aadhaar_photo_ready)
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.verification_collage_failed), Toast.LENGTH_SHORT).show()
                            pendingStudioOwnerAadhaarUri = null
                            pendingStudioOwnerAadhaarLabel?.text = getString(R.string.no_verification_document_selected)
                        }
                        updateStudioVerificationSubmitState?.invoke()
                    }
                }
            }
        } else {
            isCapturingStudioOwnerAadhaarBack = false
            pendingStudioOwnerAadhaarUri = null
            pendingStudioOwnerAadhaarLabel?.text = getString(R.string.no_verification_document_selected)
            updateStudioVerificationSubmitState?.invoke()
        }
    }

    private val viewPostLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val deletedPostId = result.data?.getIntExtra(TakerPostViewerActivity.EXTRA_DELETED_POST_ID, 0) ?: 0
            if (deletedPostId > 0) {
                removePostLocally(deletedPostId)
                return@registerForActivityResult
            }
            val updatedJson = result.data?.getStringExtra(TakerPostViewerActivity.EXTRA_POSTS_JSON)
            if (!updatedJson.isNullOrBlank()) {
                val moshi = com.squareup.moshi.Moshi.Builder().addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()
                val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, TakerPost::class.java)
                val adapter = moshi.adapter<List<TakerPost>>(type)
                val updatedList = runCatching { adapter.fromJson(updatedJson).orEmpty() }.getOrDefault(emptyList())
                if (updatedList.isNotEmpty()) {
                    val map = updatedList.associateBy { it.id }
                    accountPosts = accountPosts.map { map[it.id] ?: it }
                    accountSavedPosts = accountSavedPosts.map { map[it.id] ?: it }.filter { it.viewerHasSaved }
                    accountLikedPosts = accountLikedPosts.map { map[it.id] ?: it }.filter { it.viewerHasLiked }
                    savedTotal = accountSavedPosts.size

                    // b.tvSavedCount.text = savedTotal.toString()
                    profilePagerAdapter.submitPosts(accountPosts)
                    profilePagerAdapter.submitCollections(accountSavedPosts, accountLikedPosts)
                    return@registerForActivityResult
                }
            }
            refreshAccount()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        FragmentProfileBinding.inflate(inflater, container, false).also { _b = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindTopActions()
        setupProfilePager()
        setupCollapsingHeader()
        setupPullToRefresh()
        checkNetworkState()

        if (session.isLoggedIn() && !session.isGuest()) {
            b.layoutLoggedIn.show()
            b.layoutGuest.isVisible = false
            b.btnProfileMenu.isVisible = true
            b.btnReloadAccount.isVisible = true
            val displayName = accountDisplayName()
            b.tvName.text = displayName
            // b.tvCompactName.text = displayName
            b.tvHandle.text = session.getUserPhone().ifBlank { session.getUserEmail() }
            renderServiceChips(listOf(session.getRoleLabel()))
            renderEmptyStats()
            b.btnAddPost.isVisible = session.isTaker()
            b.btnVerifySelf.isVisible = session.isTaker()
            b.statsPanel.isVisible = session.isTaker()
            b.profileTabs.isVisible = session.isTaker()
            b.profilePager.isVisible = session.isTaker()
            b.clientAccountPanel.isVisible = session.isClient()
            b.cardVerificationPrompt.isVisible = false
            b.btnClientVerifyStudio.setOnClickListener { showStudioVerificationDialog(accountStudioTrust) }
            b.btnClientProfileSettings.text = getString(R.string.account_settings_action)
            b.btnClientProfileSettings.setOnClickListener {
                openProfileSettings()
            }

            if (session.isTaker()) {
                b.btnVerifySelf.setOnClickListener { showTakerVerificationDialog() }
                vm.fetchTrustStatus(takerId = session.getTakerActorId())

                vm.takerState.observe(viewLifecycleOwner) { result ->
                    if (result !is Result.Success) return@observe
                    val taker = result.data
                    b.tvName.text = taker.fullName
                    // b.tvCompactName.text = taker.fullName
                    b.tvHandle.text = taker.phone?.ifBlank { taker.email } ?: taker.email
                    accountTakerSocialUrl = listOf(
                        taker.socialLinkAdditional1,
                        taker.socialLinkAdditional2,
                        taker.instagramUrl,
                        taker.youtubeUrl,
                    )
                        .firstOrNull { !it.isNullOrBlank() }

                    if (accountTakerSocialUrl.isNullOrBlank() && !taker.portfolioUrl.isNullOrBlank()) {
                        accountTakerSocialUrl = taker.portfolioUrl
                        accountTakerPortfolioUrl = null
                    } else {
                        accountTakerPortfolioUrl = taker.portfolioUrl
                    }

                    renderServiceChips(taker.offeredServices)
                    renderProfileImage(taker.profileImageUrl, taker.profileThumbUrl)
                }
                vm.fetchTakerProfile(session.getTakerActorId())

                vm.takerPostsState.observe(viewLifecycleOwner) { result ->
                    when (result) {
                        is Result.Loading -> {
                            val page = pendingPostsPage.takeIf { it > 0 } ?: 1
                            profilePagerAdapter.setPostsLoading(
                                loading = true,
                                append = page > 1 && accountPosts.isNotEmpty(),
                            )
                        }
                        is Result.Success -> {
                            stopAccountRefresh()
                            pendingPostsPage = 0
                            PendingPostStore.reconcileServerPosts(
                                requireContext(),
                                session.getTakerActorId(),
                                result.data.posts.map { it.id }.toSet(),
                            )
                            val pending = pendingUploadPosts()
                            val serverPosts = if (result.data.page <= 1) {
                                result.data.posts
                            } else {
                                (accountPosts.filter { it.id > 0 } + result.data.posts).distinctBy { it.id }
                            }
                            val visiblePosts = pending + serverPosts
                            postsPage = result.data.page
                            postsTotalPages = result.data.totalPages
                            b.tvPostCount.text = formatCompactStatCount((result.data.summary.postCount + pending.size).toLong())
                            b.tvFavoriteCount.text = formatCompactStatCount(result.data.summary.favoriteCount.toLong())
                            b.tvAvgRating.text = if (result.data.summary.avgRating > 0f) {
                                formatRatingSummary(result.data.summary.avgRating, result.data.summary.reviewCount.toLong())
                            } else {
                                getString(R.string.account_new_badge)
                            }
                            accountPosts = visiblePosts
                            profilePagerAdapter.submitPosts(visiblePosts)
                            if (serverPosts.isNotEmpty()) {
                                hideAccountInlineError()
                            }
                        }
                        is Result.Error -> {
                            stopAccountRefresh()
                            pendingPostsPage = 0
                            if (accountPosts.isEmpty()) {
                                profilePagerAdapter.setPostsError(result.message)
                            } else {
                                profilePagerAdapter.setPostsLoading(false)
                                toast(result.message)
                            }
                        }
                    }
                }

                vm.reviews.observe(viewLifecycleOwner) { result ->
                    when (result) {
                        is Result.Loading -> {
                            val page = pendingReviewsPage.takeIf { it > 0 } ?: 1
                            profilePagerAdapter.setReviewsLoading(
                                loading = true,
                                append = page > 1 && accountReviewCount > 0,
                            )
                        }
                        is Result.Success -> {
                            pendingReviewsPage = 0
                            reviewsPage = result.data.page
                            reviewsTotalPages = result.data.totalPages
                            val pageReviews = result.data.reviews.map {
                                ReviewEntity(
                                    id = it.id,
                                    takerId = it.takerId,
                                    clientId = it.clientId,
                                    clientName = it.clientName,
                                    rating = it.rating,
                                    comment = it.comment,
                                    createdAt = it.createdAt,
                                )
                            }
                            accountReviews = if (result.data.page <= 1) {
                                pageReviews
                            } else {
                                (accountReviews + pageReviews).distinctBy { it.id }
                            }
                            accountReviewCount = result.data.total
                            profilePagerAdapter.submitReviews(accountReviews)
                            profilePagerAdapter.setReviewsLoading(false)
                        }
                        is Result.Error -> {
                            pendingReviewsPage = 0
                            if (accountReviewCount == 0) {
                                profilePagerAdapter.setReviewsError(result.message)
                            } else {
                                profilePagerAdapter.setReviewsLoading(false)
                                toast(result.message)
                            }
                        }
                    }
                }

                vm.trustStatusState.observe(viewLifecycleOwner) { result ->
                    when (result) {
                        is Result.Success -> {
                            stopAccountRefresh()
                            accountTakerTrust = trustWithLocalTakerPending(result.data.takerTrust)
                            renderSelfVerificationButton(accountTakerTrust)
                            renderVerificationPromptForTaker(accountTakerTrust)
                        }
                        is Result.Error -> stopAccountRefresh()
                        Result.Loading -> Unit
                    }
                }
                vm.takerVerificationState.observe(viewLifecycleOwner) { result ->
                    when (result) {
                        is Result.Success -> {
                            pendingTakerVerificationSheet?.dismiss()
                            pendingTakerVerificationSheet = null
                            val oldTrust = accountTakerTrust
                            accountTakerTrust = oldTrust?.copy(
                                stage = "pending",
                                aadhaarStatus = if (pendingTakerAadhaarUri != null) "pending" else oldTrust.aadhaarStatus,
                                socialStatus = if (pendingTakerSocialLayout != null) "pending" else oldTrust.socialStatus,
                                portfolioStatus = if (pendingTakerPortfolioLayout != null) "pending" else oldTrust.portfolioStatus
                            ) ?: result.data.takerTrust?.copy(stage = "pending")
                            session.setTakerVerificationSubmissionPending(true, session.getTakerActorId())
                            pendingTakerSocialLayout = null
                            pendingTakerPortfolioLayout = null
                            renderSelfVerificationButton(accountTakerTrust)
                            renderVerificationPromptForTaker(accountTakerTrust)
                            vm.fetchTrustStatus(takerId = session.getTakerActorId(), force = true)
                            toast(getString(R.string.taker_verification_submitted))
                        }
                        is Result.Error -> {
                            if (pendingTakerVerificationSheet?.isShowing == true && pendingTakerSocialLayout != null) {
                                pendingTakerSocialLayout?.error = result.message
                            } else {
                                toast(result.message)
                            }
                        }
                        Result.Loading -> Unit
                    }
                }
                vm.verificationDocumentState.observe(viewLifecycleOwner) { result ->
                    when (result) {
                        is Result.Success -> {
                            val oldTrust = accountTakerTrust
                            accountTakerTrust = oldTrust?.copy(
                                stage = "pending",
                                aadhaarStatus = if (pendingTakerAadhaarUri != null) "pending" else oldTrust.aadhaarStatus,
                            ) ?: result.data.takerTrust
                            session.setTakerVerificationSubmissionPending(true, session.getTakerActorId())
                            renderSelfVerificationButton(accountTakerTrust)
                            renderVerificationPromptForTaker(accountTakerTrust)
                            vm.fetchTrustStatus(takerId = session.getTakerActorId(), force = true)
                            toast(getString(R.string.verification_document_submitted))
                        }
                        is Result.Error -> toast(result.message)
                        Result.Loading -> Unit
                    }
                }

                vm.deletePostState.observe(viewLifecycleOwner) { result ->
                    when (result) {
                        is Result.Loading -> Unit
                        is Result.Success -> {
                            val deletedId = pendingDeletedPostId
                            if (deletedId > 0) {
                                removePostLocally(deletedId)
                            }
                            pendingDeletedPostId = 0
                            profilePagerAdapter.setDeletingPostIds(emptySet())
                            toast(getString(R.string.post_deleted))
                        }
                        is Result.Error -> {
                            pendingDeletedPostId = 0
                            profilePagerAdapter.setDeletingPostIds(emptySet())
                            toast(result.message)
                        }
                    }
                }

                vm.updatePostState.observe(viewLifecycleOwner) { result ->
                    when (result) {
                        is Result.Loading -> Unit
                        is Result.Success -> {
                            toast(getString(R.string.post_updated))
                            editingPostId = 0
                            refreshAccount()
                        }
                        is Result.Error -> {
                            editingPostId = 0
                            toast(result.message)
                        }
                    }
                }

                vm.removeImageState.observe(viewLifecycleOwner) { result ->
                    when (result) {
                        is Result.Loading -> Unit
                        is Result.Success -> {
                            b.ivProfile.setImageResource(R.drawable.ic_person_placeholder)
                            refreshAccount()
                            toast(getString(R.string.profile_photo_removed))
                        }
                        is Result.Error -> toast(result.message)
                    }
                }
            } else if (session.isClient()) {
                renderProfileImage(session.getProfileImageUrl(), session.getProfileThumbUrl())
                b.tvClientAccountStatus.text = getString(R.string.client_account_panel_body)
                vm.fetchTrustStatus(clientId = session.getClientActorId())
                vm.trustStatusState.observe(viewLifecycleOwner) { result ->
                    when (result) {
                        is Result.Success -> {
                            stopAccountRefresh()
                            renderVerificationPromptForStudio(trustWithLocalStudioPending(result.data.studioTrust))
                        }
                        is Result.Error -> stopAccountRefresh()
                        Result.Loading -> Unit
                    }
                }
                vm.studioVerificationState.observe(viewLifecycleOwner) { result ->
                    when (result) {
                        is Result.Success -> {
                            pendingStudioVerificationSheet?.dismiss()
                            pendingStudioVerificationSheet = null
                            val oldTrust = accountStudioTrust
                            accountStudioTrust = oldTrust?.copy(
                                stage = "pending",
                                businessStatus = if (pendingStudioBusinessSubmissionPending || pendingStudioDocumentUri != null || pendingStudioGstLayout != null || pendingStudioMapsLayout != null) "pending" else oldTrust.businessStatus,
                                ownerAadhaarStatus = if (pendingStudioOwnerAadhaarSubmissionPending) "pending" else oldTrust.ownerAadhaarStatus,
                            ) ?: result.data.studioTrust?.copy(stage = "pending")
                            session.setStudioVerificationSubmissionPending(true, session.getClientActorId())
                            pendingStudioBusinessSubmissionPending = false
                            pendingStudioGstLayout = null
                            pendingStudioMapsLayout = null
                            renderVerificationPromptForStudio(accountStudioTrust)
                            toast(getString(R.string.studio_verification_submitted))
                        }
                        is Result.Error -> {
                            if (pendingStudioVerificationSheet?.isShowing == true) {
                                if (result.message.contains("gst", ignoreCase = true)) {
                                    pendingStudioGstLayout?.error = result.message
                                } else if (result.message.contains("map", ignoreCase = true) || result.message.contains("url", ignoreCase = true)) {
                                    pendingStudioMapsLayout?.error = result.message
                                } else {
                                    pendingStudioGstLayout?.error = result.message
                                    pendingStudioMapsLayout?.error = result.message
                                }
                            } else {
                                toast(result.message)
                            }
                        }
                        Result.Loading -> Unit
                    }
                }
                vm.verificationDocumentState.observe(viewLifecycleOwner) { result ->
                    when (result) {
                        is Result.Success -> {
                            session.setStudioVerificationSubmissionPending(true, session.getClientActorId())
                            val studioTrust = result.data.studioTrust
                            if (studioTrust != null) {
                                accountStudioTrust = trustWithLocalStudioPending(studioTrust)
                                renderVerificationPromptForStudio(accountStudioTrust)
                                pendingStudioVerificationSheet?.dismiss()
                                pendingStudioVerificationSheet = null
                            } else {
                                renderVerificationPromptForStudio(trustWithLocalStudioPending(accountStudioTrust))
                            }
                            toast(getString(R.string.verification_document_submitted))
                        }
                        is Result.Error -> toast(result.message)
                        Result.Loading -> Unit
                    }
                }
            }
            vm.uploadImageState.observe(viewLifecycleOwner) { result ->
                if (!session.isClient()) return@observe
                when (result) {
                    is Result.Success -> {
                        pendingClientAvatarUri = null
                        session.setSessionProfileImage(result.data.url, result.data.thumbUrl)
                        renderProfileImage(result.data.url, result.data.thumbUrl)
                        toast(getString(R.string.profile_photo_updated))
                    }
                    is Result.Error -> {
                        pendingClientAvatarUri = null
                        renderProfileImage(session.getProfileImageUrl(), session.getProfileThumbUrl())
                        toast(getString(R.string.profile_photo_upload_failed, result.message))
                    }
                    Result.Loading -> Unit
                }
            }
            vm.accountPostActivityState.observe(viewLifecycleOwner) { result ->
                when (result) {
                    is Result.Loading -> {
                        val savedAppend = pendingSavedPage > 1 && accountSavedPosts.isNotEmpty()
                        val likedAppend = pendingLikedPage > 1 && accountLikedPosts.isNotEmpty()
                        if (pendingSavedPage > 0) {
                            profilePagerAdapter.setSavedLoading(true, append = savedAppend)
                        }
                        if (pendingLikedPage > 0) {
                            profilePagerAdapter.setLikedLoading(true, append = likedAppend)
                        }
                    }
                    is Result.Success -> {
                        when (result.data.collection) {
                            "saved" -> applySavedPostsPage(result.data)
                            "liked" -> applyLikedPostsPage(result.data)
                            else -> {
                                applySavedPostsPage(result.data)
                                applyLikedPostsPage(result.data)
                            }
                        }
                    }
                    is Result.Error -> {
                        val wasSavedInitial = pendingSavedPage == 1 && accountSavedPosts.isEmpty()
                        val wasLikedInitial = pendingLikedPage == 1 && accountLikedPosts.isEmpty()
                        pendingSavedPage = 0
                        pendingLikedPage = 0
                        if (wasSavedInitial) profilePagerAdapter.setSavedError(result.message) else profilePagerAdapter.setSavedLoading(false)
                        if (wasLikedInitial) profilePagerAdapter.setLikedError(result.message) else profilePagerAdapter.setLikedLoading(false)
                        if (!wasSavedInitial && !wasLikedInitial) toast(result.message)
                    }
                }
            }

            refreshAccount()
        } else {
            b.layoutGuest.show()
            b.layoutLoggedIn.isVisible = false
            b.profileTabs.isVisible = false
            b.profilePager.isVisible = false
            b.clientAccountPanel.isVisible = false
            b.cardVerificationPrompt.isVisible = false
            b.btnAddPost.isVisible = false
            b.btnVerifySelf.isVisible = false
            b.btnProfileMenu.isVisible = false
            b.btnReloadAccount.isVisible = false
            stopAccountRefresh()
            b.btnSignInProfile.setOnClickListener {
                startActivity(Intent(requireContext(), LoginActivity::class.java))
            }
        }
    }

    private fun bindTopActions() {
        listOf(b.btnAddPost, b.btnProfileMenu, b.btnReloadAccount).forEach(::installPressScale)
        b.btnAddPost.setOnClickListener {
            pickPostImages.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        }
        b.btnReloadAccount.setOnClickListener { requestAccountRefresh(showSpinner = true) }
        b.btnProfileMenu.setOnClickListener { showProfileMenu() }
        b.avatarFrame.setOnClickListener { showProfilePhotoOptions() }
    }

    private fun setupProfilePager() {
        b.profilePager.adapter = profilePagerAdapter
        b.profilePager.offscreenPageLimit = 1
        TabLayoutMediator(b.profileTabs, b.profilePager) { tab, position ->
            tab.text = getString(profilePagerAdapter.titleRes(position))
        }.attach()
    }

    private fun setupPullToRefresh() {
        b.swipeRefresh.setOnChildScrollUpCallback { _, _ -> accountContentCanScrollUp() }
        b.swipeRefresh.setOnRefreshListener { requestAccountRefresh(showSpinner = false) }
    }

    private fun requestAccountRefresh(showSpinner: Boolean) {
        if (showSpinner) b.swipeRefresh.isRefreshing = true
        if (!session.isLoggedIn() || session.isGuest() || !requireContext().isNetworkAvailable()) {
            checkNetworkState()
            stopAccountRefresh()
            return
        }
        refreshAccount()
    }

    private fun stopAccountRefresh() {
        _b?.swipeRefresh?.isRefreshing = false
    }

    private fun accountContentCanScrollUp(): Boolean {
        if (profileHeaderTargetCollapsed) return true
        if (b.clientAccountPanel.isVisible) return b.clientAccountPanel.canScrollVertically(-1)
        if (b.profilePager.isVisible) {
            return findVisibleAccountList(b.profilePager)?.canScrollVertically(-1) == true
        }
        return false
    }

    private fun findVisibleAccountList(view: View): RecyclerView? {
        if (view is RecyclerView && view.id == R.id.rvPageItems && view.isVisible) {
            val visibleBounds = android.graphics.Rect()
            if (view.getGlobalVisibleRect(visibleBounds) && visibleBounds.width() > 0 && visibleBounds.height() > 0) {
                return view
            }
        }
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findVisibleAccountList(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun setupCollapsingHeader() {
        b.layoutLoggedIn.addOnOffsetChangedListener(
            AppBarLayout.OnOffsetChangedListener { appBar, verticalOffset ->
                val range = appBar.totalScrollRange.takeIf { it > 0 } ?: return@OnOffsetChangedListener
                val progress = (-verticalOffset / range.toFloat()).coerceIn(0f, 1f)
                val compactAlpha = ((progress - 0.45f) / 0.35f).coerceIn(0f, 1f)
                val detailAlpha = (1f - progress * 2.2f).coerceIn(0f, 1f)
                if (progress < 0.02f) profileHeaderTargetCollapsed = false
                if (progress > 0.98f) profileHeaderTargetCollapsed = true
                // b.layoutCompactIdentity.isVisible = compactAlpha > 0.02f
                // b.layoutCompactIdentity.alpha = compactAlpha
                // b.cardProfileHero.alpha = (1f - progress * 0.08f).coerceAtLeast(0.92f)
                b.tvName.alpha = detailAlpha
                b.ivAccountVerifiedTick.alpha = detailAlpha
                b.tvHandle.alpha = detailAlpha
                b.tvName.translationY = -8f * progress
                b.ivAccountVerifiedTick.translationY = -8f * progress
                b.serviceScroll.alpha = detailAlpha
                b.serviceScroll.translationY = -10f * progress
                val contentScale = 1f - progress * 0.18f
                b.avatarFrame.scaleX = contentScale
                b.avatarFrame.scaleY = contentScale
                b.avatarFrame.translationY = -6f * progress
                b.statsPanel.scaleX = contentScale
                b.statsPanel.scaleY = contentScale
                b.statsPanel.translationY = -6f * progress
            },
        )
    }

    private fun setProfileHeaderCollapsed(collapsed: Boolean) {
        if (profileHeaderTargetCollapsed == collapsed) return
        profileHeaderTargetCollapsed = collapsed
        b.layoutLoggedIn.setExpanded(!collapsed, true)
    }

    private fun renderEmptyStats() {
        b.tvPostCount.text = formatCompactStatCount(0)
        b.tvFavoriteCount.text = formatCompactStatCount(0)
        // b.tvSavedCount.text = "0"
        b.tvAvgRating.text = getString(R.string.account_new_badge)
    }

    private fun formatRatingSummary(avgRating: Float, reviewCount: Long): String {
        val rating = String.format(Locale.US, "%.1f", avgRating)
        return if (reviewCount > 0) {
            "$rating • ${formatCompactStatCount(reviewCount)}"
        } else {
            rating
        }
    }

    private fun formatCompactStatCount(value: Long): String {
        val absValue = kotlin.math.abs(value.toDouble())
        val (divisor, suffix) = when {
            absValue >= 1_000_000_000_000.0 -> 1_000_000_000_000.0 to "T"
            absValue >= 1_000_000_000.0 -> 1_000_000_000.0 to "B"
            absValue >= 1_000_000.0 -> 1_000_000.0 to "M"
            absValue >= 1_000.0 -> 1_000.0 to "K"
            else -> 1.0 to ""
        }
        if (suffix.isEmpty()) return value.toString()

        val scaled = value / divisor
        val decimals = if (kotlin.math.abs(scaled) >= 100 || scaled == scaled.toLong().toDouble()) 0 else 1
        val formatted = String.format(Locale.US, "%.${decimals}f", scaled).trimEnd('0').trimEnd('.')
        return formatted + suffix
    }

    private fun accountDisplayName(): String =
        session.getUserName().ifBlank {
            if (session.isClient()) getString(R.string.client_account_default_name) else getString(R.string.account_title)
        }

    private fun renderProfileImage(profileImageUrl: String?, profileThumbUrl: String?) {
        val full = profileImageUrl?.takeIf { it.isNotBlank() } ?: profileThumbUrl?.takeIf { it.isNotBlank() }
        val thumb = profileThumbUrl?.takeIf { it.isNotBlank() && it != profileImageUrl }

        fun loadInto(target: android.widget.ImageView) {
            val mainRequest = Glide.with(this)
                .load(full)
                .placeholder(R.drawable.ic_person_placeholder)
                .error(R.drawable.ic_person_placeholder)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .circleCrop()
            val thumbRequest = thumb?.let {
                Glide.with(this)
                    .load(it)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .circleCrop()
            }
            if (thumbRequest != null) {
                mainRequest.thumbnail(thumbRequest).into(target)
            } else {
                mainRequest.into(target)
            }
        }

        loadInto(b.ivProfile)
    }

    private fun renderSelfVerificationButton(trust: TakerTrustSummary?) {
        if (!session.isTaker()) {
            b.btnVerifySelf.isVisible = false
            return
        }
        val verified = trust?.stage?.lowercase() in setOf("verified", "trusted", "pro_verified")
        val hasNetworkForFreshData = requireContext().isNetworkAvailable()
        
        b.btnVerifySelf.isVisible = !verified || !hasNetworkForFreshData
        b.ivAccountVerifiedTick.isVisible = verified && hasNetworkForFreshData
        val icon = if (verified && hasNetworkForFreshData) R.drawable.ic_check_circle else 0
        b.tvName.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        val tint = if (verified && hasNetworkForFreshData) ColorStateList.valueOf(
            MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorPrimary)
        ) else null

        val pending = trust?.stage?.lowercase() == "pending" || takerPendingDocuments(trust).isNotEmpty()
        val rejected = takerRejectedDocuments(trust)

        if (pending && rejected.isEmpty()) {
            b.btnVerifySelf.text = getString(R.string.verification_pending_button)
            b.btnVerifySelf.isEnabled = false
        } else if (!verified) {
            b.btnVerifySelf.text = if (rejected.isNotEmpty()) getString(R.string.verification_fix_button) else getString(R.string.verify_yourself)
            b.btnVerifySelf.isEnabled = hasNetworkForFreshData
        }
    }

    private fun trustWithLocalTakerPending(serverTrust: TakerTrustSummary?): TakerTrustSummary? {
        if (!session.isTaker()) return serverTrust
        val takerId = session.getTakerActorId()
        if (takerId <= 0) return serverTrust

        val stage = serverTrust?.stage?.lowercase().orEmpty()
        val hasServerPending = stage == "pending" || takerPendingDocuments(serverTrust).isNotEmpty()
        if (hasServerPending || stage in setOf("verified", "trusted", "pro_verified")) {
            session.setTakerVerificationSubmissionPending(false, takerId)
            return serverTrust
        }
        if (takerRejectedDocuments(serverTrust).isNotEmpty()) {
            session.setTakerVerificationSubmissionPending(false, takerId)
            return serverTrust
        }
        if (!session.isTakerVerificationSubmissionPending(takerId)) return serverTrust

        return (serverTrust ?: TakerTrustSummary()).copy(
            stage = "pending",
            aadhaarStatus = normalizePendingStatus(serverTrust?.aadhaarStatus),
            portfolioStatus = normalizePendingStatus(serverTrust?.portfolioStatus),
            socialStatus = normalizePendingStatus(serverTrust?.socialStatus),
        )
    }

    private fun trustWithLocalStudioPending(serverTrust: StudioTrustSummary?): StudioTrustSummary? {
        if (!session.isClient()) return serverTrust
        val clientId = session.getClientActorId()
        if (clientId <= 0) return serverTrust

        val serverBusinessStatus = serverTrust?.businessStatus?.lowercase().orEmpty()
        val serverOwnerStatus = serverTrust?.ownerAadhaarStatus?.lowercase().orEmpty()
        if (serverBusinessStatus in setOf("pending", "approved", "rejected")) {
            pendingStudioBusinessSubmissionPending = false
        }
        if (serverOwnerStatus in setOf("pending", "approved", "rejected")) {
            pendingStudioOwnerAadhaarSubmissionPending = false
        }

        val mergedTrust = (serverTrust ?: StudioTrustSummary()).copy(
            stage = if (pendingStudioBusinessSubmissionPending || pendingStudioOwnerAadhaarSubmissionPending) {
                "pending"
            } else {
                serverTrust?.stage ?: "unverified"
            },
            businessStatus = if (pendingStudioBusinessSubmissionPending && verificationStatusNeedsInput(serverTrust?.businessStatus)) {
                "pending"
            } else {
                serverTrust?.businessStatus ?: "not_submitted"
            },
            ownerAadhaarStatus = if (pendingStudioOwnerAadhaarSubmissionPending && verificationStatusNeedsInput(serverTrust?.ownerAadhaarStatus)) {
                "pending"
            } else {
                serverTrust?.ownerAadhaarStatus ?: "not_submitted"
            },
        )

        val stage = serverTrust?.stage?.lowercase().orEmpty()
        val hasServerPending = stage == "pending" || studioPendingDocuments(serverTrust).isNotEmpty()
        if (hasServerPending || stage in setOf("verified", "trusted")) {
            if (!pendingStudioBusinessSubmissionPending && !pendingStudioOwnerAadhaarSubmissionPending) {
                session.setStudioVerificationSubmissionPending(false, clientId)
            }
            return mergedTrust
        }
        if (studioRejectedDocuments(serverTrust).isNotEmpty()) {
            session.setStudioVerificationSubmissionPending(false, clientId)
            return serverTrust
        }
        if (
            !session.isStudioVerificationSubmissionPending(clientId) &&
            !pendingStudioBusinessSubmissionPending &&
            !pendingStudioOwnerAadhaarSubmissionPending
        ) return serverTrust

        return mergedTrust.copy(
            stage = "pending",
            businessStatus = if (pendingStudioBusinessSubmissionPending) {
                normalizePendingStatus(serverTrust?.businessStatus)
            } else {
                mergedTrust.businessStatus
            },
            ownerAadhaarStatus = if (pendingStudioOwnerAadhaarSubmissionPending) {
                normalizePendingStatus(serverTrust?.ownerAadhaarStatus)
            } else {
                mergedTrust.ownerAadhaarStatus
            },
        )
    }

    private fun normalizePendingStatus(value: String?): String =
        if (value.isNullOrBlank() || value.equals("not_submitted", ignoreCase = true) || value.equals("unverified", ignoreCase = true)) {
            "pending"
        } else {
            value
        }

    private fun renderVerificationPromptForTaker(trust: TakerTrustSummary?) {
        val stage = trust?.stage?.lowercase().orEmpty()
        val pending = takerPendingDocuments(trust).isNotEmpty()
        val needsVerification = !pending && (stage.isBlank() || stage == "unverified")
        val rejected = takerRejectedDocuments(trust)
        renderVerificationPrompt(
            visible = needsVerification || rejected.isNotEmpty(),
            title = if (rejected.isNotEmpty()) getString(R.string.verification_documents_rejected) else getString(R.string.verification_prompt_title),
            body = if (rejected.isNotEmpty()) {
                getString(R.string.verification_rejected_detail, rejected.joinToString(", ")) + "\n" +
                    getString(R.string.verification_fix_and_resubmit)
            } else {
                getString(R.string.verification_prompt_taker_body)
            },
            role = SessionManager.ROLE_TAKER,
            actorId = session.getTakerActorId(),
            onVerify = { showTakerVerificationDialog() },
        )
    }

    private fun renderVerificationPromptForStudio(trust: StudioTrustSummary?) {
        this.accountStudioTrust = trust
        val canBook = trust?.canBook == true
        val rejected = studioRejectedDocuments(trust)
        val pendingDocs = studioPendingDocuments(trust)
        val requiredDocs = studioRequiredDocuments(trust)
        val verified = trust?.stage?.lowercase() in setOf("verified", "trusted", "pro_verified")
        b.ivAccountVerifiedTick.isVisible = verified
        // b.tvCompactName.compoundDrawablePadding = resources.getDimensionPixelSize(R.dimen.spacing_xs)
        // b.tvCompactName.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (canBook) R.drawable.ic_check_circle else 0, 0)
        /* b.tvCompactName.compoundDrawableTintList = if (canBook) {
            ColorStateList.valueOf(MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorPrimary))
        } else {
            null
        } */
        val pending = trust?.stage?.lowercase() == "pending" || pendingDocs.isNotEmpty()
        val canAddMissing = requiredDocs.isNotEmpty() || rejected.isNotEmpty()

        if (pending && rejected.isEmpty() && !canAddMissing) {
            b.btnClientVerifyStudio.text = getString(R.string.verification_pending_button)
            b.btnClientVerifyStudio.isEnabled = false
        } else if (!canBook || canAddMissing || pending) {
            b.btnClientVerifyStudio.text = when {
                rejected.isNotEmpty() -> getString(R.string.verification_fix_button)
                requiredDocs.contains(getString(R.string.verification_owner_aadhaar)) && canBook -> getString(R.string.upload_owner_aadhaar_document)
                pending && canAddMissing -> getString(R.string.verification_add_missing_document)
                pending -> getString(R.string.verification_pending_button)
                else -> getString(R.string.verify_studio_button)
            }
            b.btnClientVerifyStudio.isEnabled = true
        }

        b.btnClientVerifyStudio.isVisible = !canBook || canAddMissing || pending
        b.tvClientAccountStatus.text = studioAccountStatusText(trust, canBook, pendingDocs, requiredDocs, rejected)
        renderVerificationPrompt(
            visible = (!canBook && !pending) || rejected.isNotEmpty() || requiredDocs.isNotEmpty(),
            title = if (rejected.isNotEmpty()) getString(R.string.verification_documents_rejected) else getString(R.string.verification_prompt_title),
            body = if (rejected.isNotEmpty()) {
                getString(R.string.verification_rejected_detail, rejected.joinToString(", ")) + "\n" +
                    getString(R.string.verification_fix_and_resubmit)
            } else {
                getString(R.string.verification_prompt_client_body)
            },
            role = SessionManager.ROLE_CLIENT,
            actorId = session.getClientActorId(),
            onVerify = { showStudioVerificationDialog(trust) },
        )
    }

    private fun renderVerificationPrompt(
        visible: Boolean,
        title: String = getString(R.string.verification_prompt_title),
        body: String,
        role: String,
        actorId: Int,
        onVerify: () -> Unit,
    ) {
        val shouldShow = visible &&
            actorId > 0 &&
            !verificationPromptHiddenThisSession &&
            !session.isVerificationReminderMuted(role, actorId)
        b.cardVerificationPrompt.isVisible = shouldShow
        if (!shouldShow) return

        b.tvVerificationPromptTitle.text = title
        b.tvVerificationPromptBody.text = body
        b.btnVerificationVerify.setOnClickListener {
            session.setVerificationReminderMuted(false, role, actorId)
            verificationPromptHiddenThisSession = false
            onVerify()
        }
        b.btnVerificationLater.setOnClickListener {
            verificationPromptHiddenThisSession = true
            b.cardVerificationPrompt.isVisible = false
        }
        b.btnVerificationDontRemind.setOnClickListener {
            session.setVerificationReminderMuted(true, role, actorId)
            b.cardVerificationPrompt.isVisible = false
        }
    }

    private fun showAccountInlineError(message: String) {
        b.tvAccountInlineError.text = message
        b.cardAccountInlineError.isVisible = true
    }

    private fun hideAccountInlineError() {
        b.cardAccountInlineError.isVisible = false
    }

    private fun takerRejectedDocuments(trust: TakerTrustSummary?): List<String> =
        buildList {
            if (trust?.aadhaarStatus?.lowercase() == "rejected") add(getString(R.string.verification_document_aadhaar))
            if (trust?.portfolioStatus?.lowercase() == "rejected") add(getString(R.string.verification_document_portfolio))
            if (trust?.socialStatus?.lowercase() == "rejected") add(getString(R.string.verification_document_social))
        }

    private fun takerPendingDocuments(trust: TakerTrustSummary?): List<String> =
        buildList {
            if (trust?.aadhaarStatus?.lowercase() == "pending") add(getString(R.string.verification_document_aadhaar))
            if (trust?.portfolioStatus?.lowercase() == "pending") add(getString(R.string.verification_document_portfolio))
            if (trust?.socialStatus?.lowercase() == "pending") add(getString(R.string.verification_document_social))
        }

    private fun studioRejectedDocuments(trust: StudioTrustSummary?): List<String> =
        buildList {
            if (trust?.businessStatus?.lowercase() == "rejected") add(getString(R.string.verification_business_proof))
            if (trust?.ownerAadhaarStatus?.lowercase() == "rejected") add(getString(R.string.verification_owner_aadhaar))
        }

    private fun studioPendingDocuments(trust: StudioTrustSummary?): List<String> =
        buildList {
            if (trust?.businessStatus?.lowercase() == "pending") add(getString(R.string.verification_business_proof))
            if (trust?.ownerAadhaarStatus?.lowercase() == "pending") add(getString(R.string.verification_owner_aadhaar))
        }

    private fun studioRequiredDocuments(trust: StudioTrustSummary?): List<String> =
        buildList {
            if (verificationStatusNeedsInput(trust?.businessStatus)) add(getString(R.string.verification_business_proof))
            if (verificationStatusNeedsInput(trust?.ownerAadhaarStatus)) add(getString(R.string.verification_owner_aadhaar))
        }

    private fun studioAccountStatusText(
        trust: StudioTrustSummary?,
        canBook: Boolean,
        pendingDocs: List<String>,
        requiredDocs: List<String>,
        rejectedDocs: List<String>,
    ): String {
        val lines = mutableListOf<String>()
        lines += if (canBook) getString(R.string.client_account_verified_body) else getString(R.string.client_account_panel_body)
        if (pendingDocs.isNotEmpty()) {
            lines += getString(R.string.client_account_pending_detail, pendingDocs.joinToString(", "))
        }
        if (rejectedDocs.isNotEmpty()) {
            lines += getString(R.string.client_account_rejected_detail, rejectedDocs.joinToString(", "))
        }
        if (requiredDocs.isNotEmpty()) {
            lines += getString(R.string.client_account_required_detail, requiredDocs.joinToString(", "))
        }
        val statusLine = listOf(
            "${getString(R.string.verification_business_proof)}: ${verificationStatusLabel(trust?.businessStatus, requiredLabel = true)}",
            "${getString(R.string.verification_owner_aadhaar)}: ${verificationStatusLabel(trust?.ownerAadhaarStatus, requiredLabel = false)}",
        ).joinToString("\n")
        lines += statusLine
        return lines.joinToString("\n\n")
    }

    private fun verificationStatusLabel(status: String?, requiredLabel: Boolean): String =
        when (status?.lowercase()) {
            "pending" -> getString(R.string.verification_status_pending)
            "approved" -> getString(R.string.verification_status_approved)
            "rejected" -> getString(R.string.verification_status_rejected)
            "not_submitted", "", null -> if (requiredLabel) {
                getString(R.string.verification_status_required)
            } else {
                getString(R.string.verification_status_not_submitted)
            }
            else -> status
        }

    private fun addVerificationSectionTitle(container: android.widget.LinearLayout, title: String, status: String? = null, requiredLabel: Boolean = true) {
        val text = if (status != null) "$title - ${verificationStatusLabel(status, requiredLabel)}" else title
        container.addView(android.widget.TextView(requireContext()).apply {
            this.text = text
            setTextColor(MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurface))
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
        })
    }

    private fun addVerificationHelper(container: android.widget.LinearLayout, text: String) {
        container.addView(android.widget.TextView(requireContext()).apply {
            this.text = text
            setTextColor(MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurfaceVariant))
            textSize = 13f
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
        })
    }

    private fun addOrDivider(container: android.widget.LinearLayout) {
        container.addView(android.widget.TextView(requireContext()).apply {
            text = getString(R.string.verification_or)
            gravity = android.view.Gravity.CENTER
            setTextColor(MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorPrimary))
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, (10 * resources.displayMetrics.density).toInt())
        })
    }

    private fun studioBusinessDocumentLabel(type: String): String =
        when (type) {
            "gst_certificate" -> getString(R.string.upload_gst_certificate)
            "signboard" -> getString(R.string.upload_signboard)
            else -> getString(R.string.upload_shop_license)
        }

    private fun verificationStatusNeedsInput(status: String?): Boolean =
        status.isNullOrBlank() ||
            status.equals("not_submitted", ignoreCase = true) ||
            status.equals("rejected", ignoreCase = true)

    private fun showModernBottomSheet(
        title: String,
        contentView: View,
        onDismiss: (() -> Unit)? = null,
        saveText: String = getString(R.string.submit),
        onSave: () -> Boolean,
    ): BottomSheetDialog {
        val bottomSheet = BottomSheetDialog(requireContext())
        val wrapper = layoutInflater.inflate(R.layout.bottom_sheet_edit_wrapper, null)

        wrapper.findViewById<android.widget.TextView>(R.id.tvSheetTitle).text = title
        wrapper.findViewById<android.widget.FrameLayout>(R.id.sheetContentContainer).addView(contentView)

        val saveBtn = wrapper.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSheetSave)
        saveBtn.text = saveText
        saveBtn.setOnClickListener {
            if (onSave()) {
                bottomSheet.dismiss()
            }
        }

        bottomSheet.setContentView(wrapper)
        bottomSheet.setOnDismissListener { onDismiss?.invoke() }
        bottomSheet.show()
        bottomSheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return bottomSheet
    }

    private fun showTakerVerificationDialog() {
        val takerId = session.getTakerActorId()
        if (takerId <= 0) return
        session.setVerificationReminderMuted(false, SessionManager.ROLE_TAKER, takerId)
        verificationPromptHiddenThisSession = false
        val trust = accountTakerTrust
        val stage = trust?.stage?.lowercase().orEmpty()
        if (stage in setOf("verified", "trusted", "pro_verified")) {
            toast(getString(R.string.verification_already_complete))
            return
        }
        val needsPortfolio = verificationStatusNeedsInput(trust?.portfolioStatus)
        val needsAadhaar = verificationStatusNeedsInput(trust?.aadhaarStatus)
        if (needsPortfolio && postsPage > 0 && accountPosts.none { it.id > 0 }) {
            showAccountInlineError(getString(R.string.verification_no_posts_error))
            return
        }
        if (!needsPortfolio && !needsAadhaar) {
            toast(getString(R.string.verification_pending_review))
            return
        }

        // Pick the first available social link to pre-fill the portfolio field in verification
        val existingLink = listOfNotNull(accountTakerPortfolioUrl, accountTakerSocialUrl)
            .firstOrNull { it.isNotBlank() }

        val intent = android.content.Intent(requireContext(), com.photoconnect.ui.activities.VerificationActivity::class.java).apply {
            putExtra("ROLE", "taker")
            putExtra("NEEDS_PORTFOLIO", needsPortfolio)
            putExtra("NEEDS_AADHAAR", needsAadhaar)
            putExtra("EXISTING_PORTFOLIO", existingLink)
            putExtra("UPLOADED_POST_COUNT", if (postsPage > 0) accountPosts.count { it.id > 0 } else -1)
        }
        startActivity(intent)
    }

    private fun showStudioVerificationDialog(trust: StudioTrustSummary?) {
        val clientId = session.getClientActorId()
        if (clientId <= 0) {
            toast(getString(R.string.account_cannot_place_bookings))
            return
        }
        session.setVerificationReminderMuted(false, SessionManager.ROLE_CLIENT, clientId)
        verificationPromptHiddenThisSession = false
        val needsBusiness = verificationStatusNeedsInput(trust?.businessStatus)
        val needsOwnerAadhaar = verificationStatusNeedsInput(trust?.ownerAadhaarStatus)

        if (!needsBusiness && !needsOwnerAadhaar) {
            toast(getString(R.string.verification_pending_review))
            return
        }

        val intent = android.content.Intent(requireContext(), com.photoconnect.ui.activities.VerificationActivity::class.java).apply {
            putExtra("ROLE", "studio")
            putExtra("NEEDS_BUSINESS", needsBusiness)
            putExtra("NEEDS_OWNER_AADHAAR", needsOwnerAadhaar)
        }
        startActivity(intent)
    }

    private fun installPressScale(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(130L).start()
            }
            false
        }
    }

    private fun showSwitchProfileDialog() {
        val options = mutableListOf<String>()
        val hasTaker = session.getTakerProfileId() > 0
        val hasClient = session.getClientProfileId() > 0

        if (hasTaker) {
            options.add(if (session.isTaker()) getString(R.string.profile_switch_creator_active) else getString(R.string.profile_switch_to_creator))
        } else {
            options.add(getString(R.string.profile_add_creator))
        }

        if (hasClient) {
            options.add(if (!session.isTaker()) getString(R.string.profile_switch_client_active) else getString(R.string.profile_switch_to_client))
        } else {
            options.add(getString(R.string.profile_add_client))
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_switch_title)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        if (hasTaker && !session.isTaker()) {
                            session.setActiveRole("taker")
                            startActivity(Intent(requireContext(), com.photoconnect.ui.activities.TakerMainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
                        } else if (!hasTaker) {
                            // Add Taker Profile
                            startActivity(Intent(requireContext(), com.photoconnect.ui.activities.RegisterActivity::class.java).apply {
                                putExtra("is_google_signup", true) // reusing this to skip password/populate fields
                            })
                        }
                    }
                    1 -> {
                        if (hasClient && session.isTaker()) {
                            session.setActiveRole("client")
                            startActivity(Intent(requireContext(), com.photoconnect.ui.activities.MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
                        } else if (!hasClient) {
                            // Add Client Profile
                            startActivity(Intent(requireContext(), com.photoconnect.ui.activities.RegisterActivity::class.java).apply {
                                putExtra("is_google_signup", true)
                            })
                        }
                    }
                }

            }
            .show()
    }

    private fun openProfileSettings() {
        startActivity(Intent(requireContext(), EditProfileActivity::class.java))
    }

    private fun showProfilePhotoOptions() {
        if (session.isClient()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.profile_photo_options)
                .setItems(arrayOf(getString(R.string.change_photo))) { _, _ ->
                    pickClientAvatarImage.launch("image/*")
                }
                .show()
            return
        }
        if (!session.isTaker()) {
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_photo_options)
            .setItems(arrayOf(getString(R.string.change_photo), getString(R.string.remove_photo))) { _, which ->
                when (which) {
                    0 -> openProfileSettings()
                    1 -> confirmRemoveProfilePhoto()
                }
            }
            .show()
    }

    private fun confirmRemoveProfilePhoto() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.remove_profile_photo_title)
            .setMessage(R.string.remove_profile_photo_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove_photo) { _, _ ->
                vm.removeProfileImage(session.getTakerActorId())
            }
            .show()
    }

    private fun renderServiceChips(services: List<String>) {
        val labels = services.map { it.trim() }.filter { it.isNotBlank() }
            .ifEmpty { listOf(getString(R.string.services_not_selected)) }
        val bgColor = MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorPrimaryContainer)
        val textColor = MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnPrimaryContainer)
        b.chipGroupServices.removeAllViews()
        labels.take(6).forEach { label ->
            b.chipGroupServices.addView(
                Chip(requireContext()).apply {
                    text = label
                    isClickable = false
                    isCheckable = false
                    chipBackgroundColor = ColorStateList.valueOf(bgColor)
                    setTextColor(textColor)
                    chipStrokeWidth = 0f
                    textSize = 12f
                }
            )
        }
    }

    private fun showProfileMenu() {
        val sheet = BottomSheetProfileMenuBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(requireContext(), R.style.Theme_PhotoConnect_BottomSheet)
        dialog.setContentView(sheet.root)

        val isTaker = session.isTaker()
        val isClient = session.isClient()
        sheet.menuProfileSettings.isVisible = isTaker || isClient
        sheet.menuProfileSettingsText.text = if (isTaker) getString(R.string.profile_settings) else getString(R.string.account_settings_action)
        sheet.menuSaved.isVisible = isTaker
        sheet.menuLiked.isVisible = isTaker

        sheet.menuProfileSettings.setOnClickListener {
            dialog.dismiss()
            if (isTaker) {
                openProfileSettings()
            } else {
                openProfileSettings()
            }
        }

        sheet.menuSaved.setOnClickListener {
            dialog.dismiss()
            b.profilePager.currentItem = 1
        }
        sheet.menuLiked.setOnClickListener {
            dialog.dismiss()
            b.profilePager.currentItem = 2
        }
        sheet.menuLanguage.setOnClickListener {
            dialog.dismiss()
            showLanguagePicker()
        }
        sheet.menuAppTour.setOnClickListener {
            dialog.dismiss()
            val role = when {
                session.isTaker() -> AppTourManager.Role.CREATOR
                session.isClient() -> AppTourManager.Role.CLIENT
                else -> AppTourManager.Role.GUEST
            }
            AppTourManager.show(requireActivity(), role, session.getActiveActorId().toString())
        }
        sheet.menuHelp.setOnClickListener {
            startActivity(Intent(requireContext(), HelpSupportActivity::class.java))
            dialog.dismiss()
        }
        sheet.menuLogs.setOnClickListener {
            startActivity(Intent(requireContext(), ErrorConsoleActivity::class.java))
            dialog.dismiss()
        }
        sheet.menuPrivacy.setOnClickListener {
            showInfoDialog(getString(R.string.privacy), getString(R.string.privacy_body))
        }
        sheet.themeToggleGroup.check(
            when (session.getDarkMode()) {
                SessionManager.THEME_LIGHT -> sheet.btnThemeLight.id
                SessionManager.THEME_DARK -> sheet.btnThemeDark.id
                else -> sheet.btnThemeSystem.id
            }
        )
        sheet.themeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                sheet.btnThemeLight.id -> SessionManager.THEME_LIGHT
                sheet.btnThemeDark.id -> SessionManager.THEME_DARK
                else -> SessionManager.THEME_SYSTEM
            }
            session.setDarkMode(mode)
            dialog.dismiss()
        }
        sheet.menuSignOut.setOnClickListener {
            dialog.dismiss()
            signOut()
        }
        dialog.show()
    }

    private fun showInfoDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun signOut() {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            PendingPostStore.clear(appContext)
            database.clearAllTables()
            launch(Dispatchers.Main) {
                session.clearSession()
                startActivity(Intent(appContext, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            }
        }
    }

    private fun showLanguagePicker() {
        val languages = AppLocaleManager.supportedLanguages
        val labels = languages.map { AppLocaleManager.getPickerLabel(requireContext(), it) }
        val currentTag = session.getAppLanguageTag()

        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_language_picker, null)
        bottomSheet.setContentView(view)

        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvLanguages)
        val searchView = view.findViewById<androidx.appcompat.widget.SearchView>(R.id.searchLanguage)

        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            var filteredLanguages = languages
            var filteredLabels = labels

            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val v = layoutInflater.inflate(R.layout.item_language_picker, parent, false)
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val lang = filteredLanguages[position]
                val label = filteredLabels[position]
                val tv = holder.itemView.findViewById<android.widget.TextView>(R.id.tvLanguageName)
                val ivCheck = holder.itemView.findViewById<android.widget.ImageView>(R.id.ivCheck)

                holder.itemView.layoutDirection = View.LAYOUT_DIRECTION_LTR
                tv.layoutDirection = View.LAYOUT_DIRECTION_LTR
                tv.textDirection = View.TEXT_DIRECTION_LTR
                tv.text = label
                ivCheck.visibility = if (lang.tag == currentTag) View.VISIBLE else View.INVISIBLE

                holder.itemView.setOnClickListener {
                    bottomSheet.dismiss()
                    if (lang.tag != currentTag) {
                        // Save current navigation state before language change triggers activity recreation
                        val navController = findNavController()
                        val currentDest = navController.currentDestination?.id
                        if (currentDest != null) {
                            // Try to find the menu item ID for current fragment
                            val bottomNav = activity?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavTaker)
                            bottomNav?.let { session.saveLastBottomNavItem(it.selectedItemId) }
                        }
                        session.setAppLanguageTag(lang.tag)
                        Toast.makeText(requireContext(), getString(R.string.settings_language_updated), Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun getItemCount() = filteredLanguages.size

            fun filter(query: String) {
                if (query.isBlank()) {
                    filteredLanguages = languages
                    filteredLabels = labels
                } else {
                    val filteredPairs = languages.zip(labels).filter {
                        it.second.contains(query, ignoreCase = true)
                    }
                    filteredLanguages = filteredPairs.map { it.first }
                    filteredLabels = filteredPairs.map { it.second }
                }
                notifyDataSetChanged()
            }
        }

        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        rv.adapter = adapter

        searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filter(newText ?: "")
                return true
            }
        })

        bottomSheet.show()
    }

    override fun onResume() {
        super.onResume()
        if (_b != null && session.isLoggedIn() && !session.isGuest()) {
            b.tvName.text = accountDisplayName()
            b.tvHandle.text = session.getUserPhone().ifBlank { session.getUserEmail() }
            if (session.isClient()) {
                renderProfileImage(session.getProfileImageUrl(), session.getProfileThumbUrl())
            }
        }
        if (session.isTaker()) {
            PostUploadWorker.resumePendingUploads(requireContext())
        }
        if (session.isLoggedIn() && !session.isGuest()) {
            if (System.currentTimeMillis() - lastAccountRefreshAt >= ACCOUNT_AUTO_REFRESH_INTERVAL_MS) {
                refreshAccount()
            }
            uploadProgressHandler.removeCallbacks(uploadProgressTicker)
            uploadProgressHandler.post(uploadProgressTicker)
        }
        checkNetworkState()
        registerNetworkCallback()
    }

    private fun checkNetworkState() {
        val binding = _b ?: return
        val hasInternet = context?.isNetworkAvailable() == true
        val mainContent = binding.root.findViewById<View>(R.id.mainContent)
        val layoutNoInternet = binding.root.findViewById<View>(R.id.layoutNoInternet)
        val btnRetry = binding.root.findViewById<View>(R.id.btnRetryInternet)

        mainContent?.show()
        layoutNoInternet?.hide()
        binding.btnReloadAccount.isEnabled = hasInternet
        if (!hasInternet) stopAccountRefresh()

        btnRetry?.setOnClickListener {
            checkNetworkState()
            if (context?.isNetworkAvailable() == true) {
                refreshAccount()
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = requireContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                activity?.runOnUiThread {
                    if (_b == null) return@runOnUiThread
                    checkNetworkState()
                    if (System.currentTimeMillis() - lastAccountRefreshAt >= ACCOUNT_AUTO_REFRESH_INTERVAL_MS) {
                        refreshAccount()
                    }
                }
            }
            override fun onLost(network: android.net.Network) {
                activity?.runOnUiThread { if (_b != null) checkNetworkState() }
            }
        }
        cm.registerNetworkCallback(request, networkCallback!!)
    }

    private fun unregisterNetworkCallback() {
        if (networkCallback != null) {
            val cm = requireContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            try { cm.unregisterNetworkCallback(networkCallback!!) } catch (e: Exception) {}
            networkCallback = null
        }
    }

    override fun onPause() {
        super.onPause()
        uploadProgressHandler.removeCallbacks(uploadProgressTicker)
        unregisterNetworkCallback()
    }

    private fun refreshAccount() {
        lastAccountRefreshAt = System.currentTimeMillis()
        if (session.isTaker()) {
            resetAccountActivityPagination()
            loadAccountActivityPage(collection = null, page = 1, forceNetwork = true)
        }
        if (session.isTaker()) {
            resetPostPagination()
            loadPostsPage(1, forceNetwork = true)
            resetReviewPagination()
            accountReviews = emptyList()
            vm.fetchTakerProfile(session.getTakerActorId(), force = true)
            vm.fetchTrustStatus(takerId = session.getTakerActorId(), force = true)
            loadReviewsPage(1, forceNetwork = true)
            renderPendingUploads()
        } else if (session.isClient()) {
            vm.fetchTrustStatus(clientId = session.getClientActorId(), force = true)
        } else {
            resetPostPagination()
            resetReviewPagination()
            accountPosts = emptyList()
            accountReviews = emptyList()
            accountReviewCount = 0
            profilePagerAdapter.submitPosts(emptyList())
            profilePagerAdapter.submitReviews(emptyList())
            profilePagerAdapter.submitCollections(emptyList(), emptyList())
        }
    }

    private fun resetPostPagination() {
        postsPage = 0
        postsTotalPages = 1
        pendingPostsPage = 0
    }

    private fun resetAccountActivityPagination() {
        savedPage = 0
        savedTotalPages = 1
        pendingSavedPage = 0
        likedPage = 0
        likedTotalPages = 1
        pendingLikedPage = 0
    }

    private fun resetReviewPagination() {
        reviewsPage = 0
        reviewsTotalPages = 1
        pendingReviewsPage = 0
    }

    private fun loadPostsPage(page: Int, forceNetwork: Boolean = false) {
        if (!session.isTaker() || pendingPostsPage > 0) return
        if (page > 1 && postsPage >= postsTotalPages) return
        pendingPostsPage = page
        profilePagerAdapter.setPostsLoading(
            loading = true,
            append = page > 1 && accountPosts.isNotEmpty(),
        )
        vm.fetchTakerPosts(
            takerId = session.getTakerActorId(),
            viewerRole = session.getRole(),
            viewerId = session.getTakerActorId(),
            page = page,
            limit = ACCOUNT_POST_PAGE_SIZE,
            forceNetwork = forceNetwork,
        )
    }

    private fun loadAccountActivityPage(collection: String?, page: Int, forceNetwork: Boolean = false) {
        if (!session.isLoggedIn() || session.isGuest()) return
        if (pendingSavedPage > 0 || pendingLikedPage > 0) return
        val normalizedCollection = collection?.takeIf { it.isNotBlank() }
        if (normalizedCollection == "saved" && page > 1 && savedPage >= savedTotalPages) return
        if (normalizedCollection == "liked" && page > 1 && likedPage >= likedTotalPages) return

        pendingSavedPage = if (normalizedCollection == null || normalizedCollection == "saved") page else 0
        pendingLikedPage = if (normalizedCollection == null || normalizedCollection == "liked") page else 0
        if (pendingSavedPage > 0) {
            profilePagerAdapter.setSavedLoading(
                loading = true,
                append = page > 1 && accountSavedPosts.isNotEmpty(),
            )
        }
        if (pendingLikedPage > 0) {
            profilePagerAdapter.setLikedLoading(
                loading = true,
                append = page > 1 && accountLikedPosts.isNotEmpty(),
            )
        }
        vm.fetchAccountPostActivity(
            actorRole = session.getRole(),
            actorId = session.getActiveActorId(),
            collection = normalizedCollection,
            page = page,
            limit = ACCOUNT_POST_PAGE_SIZE,
            forceNetwork = forceNetwork,
        )
    }

    private fun loadReviewsPage(page: Int, forceNetwork: Boolean = false) {
        if (!session.isTaker() || pendingReviewsPage > 0) return
        if (page > 1 && reviewsPage >= reviewsTotalPages) return
        pendingReviewsPage = page
        profilePagerAdapter.setReviewsLoading(
            loading = true,
            append = page > 1 && accountReviewCount > 0,
        )
        vm.fetchReviews(session.getTakerActorId(), page = page, limit = ACCOUNT_REVIEW_PAGE_SIZE, force = forceNetwork)
    }

    private fun loadMoreAccountPage(page: ProfilePagerAdapter.ProfilePage) {
        when (page) {
            ProfilePagerAdapter.ProfilePage.POSTS -> loadPostsPage(postsPage + 1)
            ProfilePagerAdapter.ProfilePage.SAVED -> loadAccountActivityPage("saved", savedPage + 1)
            ProfilePagerAdapter.ProfilePage.LIKED -> loadAccountActivityPage("liked", likedPage + 1)
            ProfilePagerAdapter.ProfilePage.REVIEWS -> loadReviewsPage(reviewsPage + 1)
        }
    }

    private fun applySavedPostsPage(data: com.photoconnect.model.AccountPostActivityResult) {
        val page = data.savedPage.coerceAtLeast(1)
        pendingSavedPage = 0
        savedPage = page
        savedTotalPages = data.savedTotalPages.coerceAtLeast(1)
        accountSavedPosts = if (page <= 1) {
            data.savedPosts
        } else {
            (accountSavedPosts + data.savedPosts).distinctBy { it.id }
        }
        savedTotal = when {
            data.savedTotal > 0 -> data.savedTotal
            accountSavedPosts.isNotEmpty() -> accountSavedPosts.size
            else -> 0
        }
        // b.tvSavedCount.text = savedTotal.toString()
        profilePagerAdapter.submitSavedPosts(accountSavedPosts)
    }

    private fun applyLikedPostsPage(data: com.photoconnect.model.AccountPostActivityResult) {
        val page = data.likedPage.coerceAtLeast(1)
        pendingLikedPage = 0
        likedPage = page
        likedTotalPages = data.likedTotalPages.coerceAtLeast(1)
        accountLikedPosts = if (page <= 1) {
            data.likedPosts
        } else {
            (accountLikedPosts + data.likedPosts).distinctBy { it.id }
        }
        profilePagerAdapter.submitLikedPosts(accountLikedPosts)
    }

    private fun renderPendingUploads(): List<TakerPost> {
        val pending = pendingUploadPosts()
        if (pending.isNotEmpty()) {
            accountPosts = pending + accountPosts.filter { it.id > 0 }
            profilePagerAdapter.submitPosts(accountPosts)
        }
        return pending
    }

    private fun openPost(post: TakerPost) {
        val isSavedTab = b.profilePager.currentItem == 1
        val isLikedTab = b.profilePager.currentItem == 2
        val actualPostId = post.id
        if (actualPostId < 0) {
            requireContext().toast(getString(R.string.post_upload_pending))
            return
        }

        val sourcePosts = when {
            isSavedTab -> accountSavedPosts
            isLikedTab -> accountLikedPosts
            else -> accountPosts
        }

        val postIndex = sourcePosts.indexOfFirst { it.id == actualPostId }.coerceAtLeast(0)
        val isOwnPost = session.isTaker() && post.takerId == session.getTakerActorId()

        val intent = Intent(requireContext(), TakerPostViewerActivity::class.java).apply {
            putExtra(TakerPostViewerActivity.EXTRA_POST_ID, actualPostId)
            putExtra(TakerPostViewerActivity.EXTRA_POST_INDEX, postIndex)
            putExtra(TakerPostViewerActivity.EXTRA_IS_OWNER, isOwnPost)

            if (isSavedTab || isLikedTab) {
                val moshi = com.squareup.moshi.Moshi.Builder().addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()
                val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, TakerPost::class.java)
                val adapter = moshi.adapter<List<TakerPost>>(type)
                putExtra(TakerPostViewerActivity.EXTRA_POSTS_JSON, adapter.toJson(sourcePosts))
                if (isLikedTab) {
                    putExtra(TakerPostViewerActivity.EXTRA_ONLY_LIKED_IMAGES, true)
                }
            } else {
                putExtra(TakerPostViewerActivity.EXTRA_TAKER_ID, post.takerId)
            }
        }
        viewPostLauncher.launch(intent)
    }

    private fun showPostMenu(post: TakerPost, anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            if (post.id < 0) {
                menu.add(0, MENU_RETRY_UPLOAD, 0, R.string.retry_upload)
            }
            menu.add(0, MENU_EDIT_POST, 1, R.string.edit).isEnabled = post.id > 0
            menu.add(0, MENU_DOWNLOAD_POST, 2, R.string.download).isEnabled = post.id > 0
            menu.add(
                0,
                MENU_DELETE_POST,
                3,
                if (post.id < 0) R.string.cancel_upload else R.string.delete_post,
            )
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_RETRY_UPLOAD -> {
                        retryPendingPostUpload(post)
                        true
                    }
                    MENU_EDIT_POST -> {
                        openEditPost(post)
                        true
                    }
                    MENU_DOWNLOAD_POST -> {
                        downloadPostImages(post)
                        true
                    }
                    MENU_DELETE_POST -> {
                        confirmDeletePost(post)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun retryPendingPostUpload(post: TakerPost) {
        val jobId = pendingJobIdsByLocalPostId[post.id] ?: return
        PendingPostStore.markRetrying(requireContext(), jobId)
        PostUploadWorker.retryPendingUpload(requireContext(), jobId)
        renderPendingUploads()
        toast(getString(R.string.upload_retry_started))
    }

    private fun openEditPost(post: TakerPost) {
        editPostLauncher.launch(CreatePostActivity.editIntent(requireContext(), post))
    }

    private fun downloadPostImages(post: TakerPost) {
        PostDownloadWatermarker.savePostImages(requireContext(), post)
    }

    private fun confirmDeletePost(post: TakerPost) {
        if (post.id < 0) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.cancel_upload)
                .setMessage(R.string.cancel_upload_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    val jobId = pendingJobIdsByLocalPostId[post.id] ?: return@setPositiveButton
                    WorkManager.getInstance(requireContext()).cancelAllWorkByTag(jobId)
                    PendingPostStore.removeAndDeleteFiles(requireContext(), jobId)
                    pendingJobIdsByLocalPostId.remove(post.id)
                    accountPosts = accountPosts.filterNot { it.id == post.id }
                    profilePagerAdapter.submitPosts(accountPosts)
                    renderPendingUploads()
                    toast(getString(R.string.upload_cancelled))
                }
                .show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_post)
            .setMessage(R.string.delete_post_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                pendingDeletedPostId = post.id
                profilePagerAdapter.setDeletingPostIds(setOf(post.id))
                vm.deleteTakerPost(session.getTakerActorId(), post.id)
            }
            .show()
    }

    private fun removePostLocally(postId: Int) {
        accountPosts = accountPosts.filterNot { it.id == postId }
        accountSavedPosts = accountSavedPosts.filterNot { it.id == postId }
        accountLikedPosts = accountLikedPosts.filterNot { it.id == postId }
        savedTotal = accountSavedPosts.size
        // b.tvSavedCount.text = savedTotal.toString()
        profilePagerAdapter.submitPosts(accountPosts)
        profilePagerAdapter.submitCollections(accountSavedPosts, accountLikedPosts)
    }

    private fun pendingUploadPosts(): List<TakerPost> {
        pendingJobIdsByLocalPostId.clear()
        return PendingPostStore.getForTaker(requireContext(), session.getTakerActorId())
            .filterNot { it.uploaded }
            .mapIndexed { index, pending ->
                val localPostId = -((pending.createdAt % Int.MAX_VALUE).toInt().coerceAtLeast(1) + index)
                pendingJobIdsByLocalPostId[localPostId] = pending.jobId
                TakerPost(
                    id = localPostId,
                    takerId = pending.takerId,
                    caption = pending.caption,
                    viewCount = if (pending.failed) -1 else pending.progress,
                    createdAt = null,
                    images = pending.imagePaths.mapIndexed { imageIndex, path ->
                        TakerPostImage(
                            id = localPostId - imageIndex - 1,
                            postId = localPostId,
                            imageUrl = Uri.fromFile(File(path)).toString(),
                            thumbUrl = Uri.fromFile(File(path)).toString(),
                            sortOrder = imageIndex,
                        )
                    },
                )
            }
    }

    override fun onDestroyView() {
        uploadProgressHandler.removeCallbacks(uploadProgressTicker)
        super.onDestroyView()
        _b = null
    }

    private companion object {
        const val ACCOUNT_AUTO_REFRESH_INTERVAL_MS = 30_000L
        const val ACCOUNT_POST_PAGE_SIZE = 12
        const val ACCOUNT_REVIEW_PAGE_SIZE = 10
        const val MENU_EDIT_POST = 1
        const val MENU_DOWNLOAD_POST = 2
        const val MENU_DELETE_POST = 3
        const val MENU_RETRY_UPLOAD = 4
    }
}
