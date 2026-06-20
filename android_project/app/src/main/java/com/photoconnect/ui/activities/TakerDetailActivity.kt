package com.photoconnect.ui.activities

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.photoconnect.R
import com.photoconnect.databinding.ActivityTakerDetailBinding
import com.photoconnect.databinding.BottomSheetBookingBinding
import com.photoconnect.databinding.BottomSheetReviewBinding
import com.photoconnect.db.AvailabilityEntity
import com.photoconnect.db.ReviewEntity
import com.photoconnect.model.AddReviewRequest
import com.photoconnect.model.StudioTrustSummary
import com.photoconnect.model.Taker
import com.photoconnect.model.TakerPost
import com.photoconnect.model.TakerPostImage
import com.photoconnect.model.TakerTrustSummary
import com.photoconnect.repository.Result
import com.photoconnect.ui.adapters.AccountPostAdapter
import com.photoconnect.ui.adapters.HeroImage
import com.photoconnect.ui.adapters.HeroImagePagerAdapter
import com.photoconnect.ui.adapters.ReviewAdapter
import com.photoconnect.ui.views.AvailabilityCalendarView
import com.photoconnect.utils.*
import com.photoconnect.viewmodel.AuthViewModel
import com.photoconnect.viewmodel.BookingViewModel
import com.photoconnect.viewmodel.TakerDetailViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TakerDetailActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ID = "taker_id"; const val EXTRA_NAME = "taker_name"
        const val EXTRA_SVC = "service_type"; const val EXTRA_CITY = "city"
        const val EXTRA_RATING = "rating"; const val EXTRA_IMG = "image_url"
        const val EXTRA_THUMB = "thumb_url"
        const val EXTRA_SERVICES = "service_types"
        const val RESULT_FAVORITE_CHANGED = "favorite_changed"
        const val RESULT_REVIEW_CHANGED = "review_changed"
        const val RESULT_TAKER_ID = "changed_taker_id"
    }

    private lateinit var b: ActivityTakerDetailBinding
    private val detailVm: TakerDetailViewModel by viewModels()
    private val bookingVm: BookingViewModel by viewModels()
    private val authVm: AuthViewModel by viewModels()

    @Inject lateinit var session: SessionManager

    private var takerId = 0
    private var takerName = ""
    private var svcType = ""
    private var serviceTypes: List<String> = emptyList()
    private var availabilityByDate: Map<String, String> = emptyMap()
    private var availabilityDayPartByDate: Map<String, String> = emptyMap()
    private var isFavorite = false
    private var confirmedIsFavorite = false
    private var favoriteToggleInFlight = false
    private var localFavoriteObserved = false
    private var selectedDate: String? = null
    private var pendingBookingServiceType: String? = null
    private var bookingSheet: BottomSheetDialog? = null
    private var bookingSheetBinding: BottomSheetBookingBinding? = null
    private var reviewSheet: BottomSheetDialog? = null
    private var reviewSheetBinding: BottomSheetReviewBinding? = null
    private var heroOverrideUrl: String? = null
    private var currentPosts: List<TakerPost> = emptyList()
    private var currentReviewCount = 0
    private var takerTrust: TakerTrustSummary? = null
    private var studioTrust: StudioTrustSummary? = null
    private var pendingStudioDocumentUri: Uri? = null
    private var pendingStudioDocumentType: String = "shop_license"
    private var pendingStudioDocumentLabel: android.widget.TextView? = null
    private var updateStudioVerificationSubmitState: (() -> Unit)? = null
    private var unverifiedTakerWarningShown = false
    private var favoriteChangedForResult = false
    private var reviewChangedForResult = false
    private var pendingReviewRating: Int? = null
    private var pendingReviewComment: String? = null

    private val heroAdapter = HeroImagePagerAdapter()
    private var heroImages: List<HeroImage> = emptyList()
    private val heroHandler = Handler(Looper.getMainLooper())
    private val heroAutoScroll = object : Runnable {
        override fun run() {
            if (heroImages.size <= 1) return
            val next = (b.pagerHero.currentItem + 1) % heroImages.size
            b.pagerHero.setCurrentItem(next, true)
            heroHandler.postDelayed(this, 4200L)
        }
    }
    private val postAdapter = AccountPostAdapter(compactHorizontal = true) { post -> openPost(post) }
    private val reviewAdapter = ReviewAdapter()

    private val postViewerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            fetchPosts()
        }
    }

    private val pickStudioVerificationDocument = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pendingStudioDocumentUri = uri
        pendingStudioDocumentLabel?.text = if (uri != null) {
            getString(R.string.verification_document_selected)
        } else {
            getString(R.string.no_verification_document_selected)
        }
        updateStudioVerificationSubmitState?.invoke()
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        b = ActivityTakerDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.forceLeftToRightTree()

        takerId = intent.getIntExtra(EXTRA_ID, 0)
        takerName = intent.getStringExtra(EXTRA_NAME) ?: ""
        svcType = intent.getStringExtra(EXTRA_SVC) ?: ""
        serviceTypes = intent.getStringArrayListExtra(EXTRA_SERVICES)
            ?.filter { it.isNotBlank() }
            .orEmpty()
            .ifEmpty { listOfNotNull(svcType.takeIf { it.isNotBlank() }) }

        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""
        b.toolbar.setNavigationOnClickListener { finish() }
        styleTopBar()

        setupStaticInfo()
        b.calendarView.selectionMode = AvailabilityCalendarView.SelectionMode.SINGLE
        b.calendarView.showMonthHeader = false
        b.calendarView.onInvalidSelectionAttempt = { msg -> toast(msg) }
        setupCalendarMonthNavigation()
        setupAdapters()
        setupObservers()
        setupListeners()

        detailVm.fetchTakerProfile(takerId)
        detailVm.fetchAvailability(takerId, month = b.calendarView.getCurrentMonthString().take(7))
        detailVm.fetchReviews(takerId)
        detailVm.fetchTrustStatus(
            takerId = takerId,
            clientId = session.getClientActorId().takeIf { session.hasClientFeatures() && it > 0 },
        )
        fetchPosts()
    }

    private fun setupCalendarMonthNavigation() {
        b.calendarView.onMonthChanged = { ym ->
            updateDetailCalendarTitle()
            detailVm.fetchAvailability(takerId, ym.take(7))
        }
        b.btnCalendarPrevMonth.setOnClickListener { b.calendarView.prevMonth() }
        b.btnCalendarNextMonth.setOnClickListener { b.calendarView.nextMonth() }
        updateDetailCalendarTitle()
    }

    private fun updateDetailCalendarTitle() {
        val cur = b.calendarView.getCurrentMonthString()
        b.tvCalendarMonthTitle.text = try {
            val parts = cur.split("-")
            val y = parts[0]
            val m = parts[1].toInt()
            val locale = resources.configuration.locales[0]
            val monthName = java.text.DateFormatSymbols.getInstance(locale).months[m - 1]
            "$monthName $y"
        } catch (_: Exception) {
            cur
        }
    }

    private fun setupStaticInfo() {
        b.tvTakerName.text = takerName
        b.tvServiceType.text = serviceTypes.toServiceSummary(this)
        b.tvCity.text = intent.getStringExtra(EXTRA_CITY) ?: ""
        val rating = intent.getFloatExtra(EXTRA_RATING, 0f)
        b.ratingBar.rating = rating
        b.tvRating.text = if (rating > 0) "%.1f".format(rating) else getString(R.string.account_new_badge)
        renderTrustState()
        renderHeroImage(
            fullUrl = intent.getStringExtra(EXTRA_IMG),
            thumbUrl = intent.getStringExtra(EXTRA_THUMB),
        )
    }

    private fun setupAdapters() {
        b.pagerHero.adapter = heroAdapter
        b.pagerHero.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateHeroIndicator(position)
            }
        })

        b.rvPortfolio.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.rvPortfolio.adapter = postAdapter
        b.rvPortfolio.isNestedScrollingEnabled = false

        b.rvReviews.adapter = reviewAdapter
        b.rvReviews.setHasFixedSize(false)

        detailVm.cachedAvailability(takerId).observe(this) { 
            val calendarState = it.toCalendarState()
            availabilityByDate = calendarState.first
            availabilityDayPartByDate = calendarState.second
            b.calendarView.setAvailabilityMap(availabilityByDate, availabilityDayPartByDate)
        }
    }

    private fun setupObservers() {
        detailVm.cachedTaker(takerId).observe(this) { taker ->
            taker?.let { renderTakerSnapshot(it, allowFavoriteFromPayload = false) }
        }

        detailVm.takerState.observe(this) { result ->
            if (result !is Result.Success) return@observe
            renderTakerSnapshot(result.data, allowFavoriteFromPayload = true)
        }

        if (session.isLoggedIn() && !session.isGuest()) {
            val favoriteActorRole = session.getFavoriteActorRole()
            val favoriteActorId = session.getFavoriteActorId()
            detailVm.isFavorite(favoriteActorId, takerId).observe(this) { cachedFavorite ->
                localFavoriteObserved = true
                if (!favoriteToggleInFlight) {
                    confirmedIsFavorite = cachedFavorite
                }
                isFavorite = cachedFavorite
                renderFavoriteButton()
            }
            detailVm.fetchFavoriteStatus(takerId, favoriteActorRole, favoriteActorId)
            detailVm.favoriteStatusState.observe(this) { result ->
                if (result is Result.Error && !favoriteToggleInFlight) Unit
            }

            detailVm.toggleFavoriteState.observe(this) { result ->
                when (result) {
                    is Result.Success -> {
                        favoriteToggleInFlight = false
                        confirmedIsFavorite = result.data.isFavorite
                        isFavorite = confirmedIsFavorite
                        renderFavoriteButton()
                    }
                    is Result.Error -> {
                        favoriteToggleInFlight = false
                        isFavorite = confirmedIsFavorite
                        renderFavoriteButton()
                        toast(result.message)
                    }
                    Result.Loading -> favoriteToggleInFlight = true
                }
            }
        } else {
            b.btnFavorite.isVisible = true
            isFavorite = false
            renderFavoriteButton()
        }

        observeTrustState()
        observeBookingState()
        observeReviewState()
        observePostState()
        observePostActionStates()
    }

    private fun renderTakerSnapshot(taker: Taker, allowFavoriteFromPayload: Boolean) {
        renderHeroImage(taker.profileImageUrl, taker.profileThumbUrl)
        b.ratingBar.rating = taker.avgRating
        b.tvRating.text = if (taker.avgRating > 0f) {
            getString(R.string.rating_with_count, taker.avgRating, taker.reviewCount)
        } else {
            getString(R.string.account_new_badge)
        }
        if (takerTrust == null) {
            takerTrust = TakerTrustSummary(
                stage = taker.trustStage,
                label = taker.trustLabel,
                identityVerified = taker.identityVerified,
                portfolioVerified = taker.portfolioVerified,
                socialVerified = taker.socialVerified,
                completedBookingCount = taker.completedBookingCount,
                endorsementCount = taker.endorsementCount,
                reviewCount = taker.reviewCount,
                avgRating = taker.avgRating,
            )
            renderTrustState()
        }
        if (taker.offeredServices.isNotEmpty()) {
            serviceTypes = taker.offeredServices
            if (svcType !in serviceTypes) svcType = serviceTypes.primaryServiceType()
            b.tvServiceType.text = serviceTypes.toServiceSummary(this)
        }
        if (
            allowFavoriteFromPayload &&
            session.isLoggedIn() &&
            !session.isGuest() &&
            !favoriteToggleInFlight &&
            !localFavoriteObserved
        ) {
            confirmedIsFavorite = taker.viewerHasFavorited
            isFavorite = taker.viewerHasFavorited
            renderFavoriteButton()
        }
        b.tvExperience.text = getString(R.string.years_exp, taker.yearsExperience)
        b.tvLanguages.text = taker.languages ?: getString(R.string.languages_default)

        b.btnCall.setOnClickListener {
            if (!ensureStudioContactAllowed()) return@setOnClickListener
            val phone = taker.phone?.trim().orEmpty()
            if (phone.isBlank()) {
                toast(getString(R.string.error_phone))
                return@setOnClickListener
            }
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
            } catch (_: Exception) {
                toast(getString(R.string.could_not_start_call))
            }
        }

        b.btnEmail.setOnClickListener {
            if (!ensureStudioContactAllowed()) return@setOnClickListener
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(taker.email)}")).apply {
                putExtra(Intent.EXTRA_SUBJECT, "PhotoConnect enquiry")
                putExtra(Intent.EXTRA_TEXT, "Hello ${taker.fullName}, I saw your profile on PhotoConnect.")
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {
                toast(getString(R.string.could_not_start_email))
            }
        }

        b.tvCity.setOnClickListener {
            val query = listOf(taker.city, taker.state).filter { it.isNotBlank() }.joinToString(", ")
            if (query.isBlank()) return@setOnClickListener
            val gmmIntentUri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
            mapIntent.setPackage("com.google.android.apps.maps")
            try {
                if (mapIntent.resolveActivity(packageManager) != null) {
                    startActivity(mapIntent)
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, gmmIntentUri))
                }
            } catch (_: Exception) {
                toast(getString(R.string.could_not_open_link))
            }
        }

        val secondarySocialLinks = listOf(
            taker.socialLinkAdditional1,
            taker.socialLinkAdditional2,
            taker.instagramUrl,
            taker.youtubeUrl,
        )
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .take(2)
        val hasSocial = !taker.portfolioUrl.isNullOrBlank() || secondarySocialLinks.isNotEmpty()
        b.layoutSocial.isVisible = hasSocial

        bindSocialButton(b.btnInstagram, secondarySocialLinks.getOrNull(0))
        bindSocialButton(b.btnYoutube, secondarySocialLinks.getOrNull(1))
        bindSocialButton(b.btnPortfolio, taker.portfolioUrl)
    }

    private fun setupListeners() {
        b.btnFavorite.setOnClickListener {
            if (!session.isLoggedIn() || session.isGuest()) {
                toast(getString(R.string.sign_in_favorite_creators))
                return@setOnClickListener
            }
            if (session.isTaker() && session.getTakerActorId() == takerId) {
                toast(getString(R.string.cannot_save_own_profile))
                return@setOnClickListener
            }
            if (favoriteToggleInFlight) return@setOnClickListener
            val target = !isFavorite
            val previous = isFavorite
            confirmedIsFavorite = previous
            favoriteToggleInFlight = true
            isFavorite = target
            renderFavoriteButton()
            markProfileChanged(favoriteChanged = true)
            detailVm.toggleFavoriteRemote(takerId, session.getFavoriteActorRole(), session.getFavoriteActorId(), target)
            toast(if (target) getString(R.string.saved_to_favorites) else getString(R.string.removed_from_favorites))
        }

        b.btnBookNow.setOnClickListener {
            if (session.isGuest() || !session.isLoggedIn()) {
                toast(getString(R.string.sign_in_to_book))
                startActivity(Intent(this, LoginActivity::class.java))
                return@setOnClickListener
            }
            if (!session.hasClientFeatures()) {
                toast(getString(R.string.account_cannot_place_bookings))
                return@setOnClickListener
            }
            if (session.isTaker() && session.getTakerActorId() == takerId) {
                toast(getString(R.string.cannot_book_own_profile))
                return@setOnClickListener
            }
            if (studioTrust?.canBook == false) {
                showUnverifiedStudioBookingWarning()
                return@setOnClickListener
            }
            showBookingSheet()
        }

        b.btnWriteReview.setOnClickListener {
            if (!session.hasClientFeatures() || session.getClientActorId() <= 0) {
                toast(getString(R.string.sign_in_to_review))
                return@setOnClickListener
            }
            if (session.isTaker() && session.getTakerActorId() == takerId) {
                toast(getString(R.string.cannot_review_own_profile))
                return@setOnClickListener
            }
            if (takerTrust?.canEndorse != true) {
                toast(getString(R.string.review_after_completed_booking))
                return@setOnClickListener
            }
            showReviewSheet()
        }

        b.btnEndorseTaker.setOnClickListener {
            val trust = takerTrust ?: return@setOnClickListener
            val isTakerCapable = session.hasTakerFeatures()
            val clientId = if (session.hasClientFeatures() && session.getClientActorId() > 0) session.getClientActorId() else session.getTakerActorId()
            
            if ((!session.hasClientFeatures() && !isTakerCapable) || clientId <= 0) {
                toast(getString(R.string.sign_in_to_endorse))
                return@setOnClickListener
            }
            if (!trust.canEndorse) {
                toast(getString(R.string.endorse_after_completed_booking))
                return@setOnClickListener
            }
            if (trust.viewerHasEndorsed) {
                toast(getString(R.string.endorsement_saved))
            } else {
                showEndorsementOtpDialog(clientId)
            }
        }
    }

    private fun openUrl(url: String?) {
        val raw = url?.trim().orEmpty()
        if (raw.isBlank()) return
        val normalized = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            raw
        } else {
            "https://$raw"
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)))
        } catch (e: Exception) {
            toast(getString(R.string.could_not_open_link))
        }
    }

    private fun bindSocialButton(button: android.widget.ImageButton, url: String?) {
        val clean = url?.trim().orEmpty()
        button.isVisible = clean.isNotBlank()
        if (clean.isBlank()) {
            button.setOnClickListener(null)
            return
        }
        button.setImageResource(socialIconFor(clean))
        button.setColorFilter(Color.parseColor(socialTintFor(clean)))
        button.setOnClickListener { openUrl(clean) }
    }

    private fun socialIconFor(url: String): Int {
        val lower = url.lowercase()
        return when {
            "instagram." in lower -> R.drawable.ic_instagram
            "youtu.be" in lower || "youtube." in lower -> R.drawable.ic_youtube
            else -> R.drawable.ic_website
        }
    }

    private fun socialTintFor(url: String): String {
        val lower = url.lowercase()
        return when {
            "instagram." in lower -> "#E1306C"
            "youtu.be" in lower || "youtube." in lower -> "#FF0000"
            else -> "#2563EB"
        }
    }

    private fun renderFavoriteButton() {
        b.btnFavorite.setImageResource(
            if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_outline
        )
        b.btnFavorite.imageAlpha = 255
        b.btnFavorite.setColorFilter(
            if (isFavorite) getColor(R.color.colorStar)
            else MaterialColors.getColor(b.btnFavorite, com.google.android.material.R.attr.colorOnSurface)
        )
    }

    private fun observeTrustState() {
        detailVm.trustStatusState.observe(this) { result ->
            when (result) {
                is Result.Success -> {
                    takerTrust = result.data.takerTrust ?: takerTrust
                    studioTrust = result.data.studioTrust ?: studioTrust
                    renderTrustState()
                    maybeShowUnverifiedTakerWarning()
                }
                is Result.Error -> renderTrustState()
                Result.Loading -> Unit
            }
        }
        detailVm.studioVerificationState.observe(this) { result ->
            when (result) {
                is Result.Success -> {
                    studioTrust = result.data.studioTrust ?: studioTrust
                    renderTrustState()
                    toast(getString(R.string.studio_verification_submitted))
                }
                is Result.Error -> {
                    renderTrustState()
                    toast(result.message)
                }
                Result.Loading -> Unit
            }
        }
        detailVm.endorsementState.observe(this) { result ->
            when (result) {
                is Result.Success -> {
                    takerTrust = result.data.takerTrust ?: takerTrust
                    renderTrustState()
                    toast(
                        if (takerTrust?.viewerHasEndorsed == true) {
                            getString(R.string.endorsement_saved)
                        } else {
                            getString(R.string.endorsement_removed)
                        }
                    )
                }
                is Result.Error -> toast(result.message)
                Result.Loading -> Unit
            }
        }
        detailVm.verificationDocumentState.observe(this) { result ->
            when (result) {
                is Result.Success -> {
                    studioTrust = result.data.studioTrust ?: studioTrust
                    renderTrustState()
                    toast(getString(R.string.verification_document_submitted))
                }
                is Result.Error -> toast(result.message)
                Result.Loading -> Unit
            }
        }
        authVm.sendEmailOtpState.observe(this) { result ->
            when (result) {
                is Result.Success -> toast(getString(R.string.endorsement_code_sent))
                is Result.Error -> toast(result.message)
                Result.Loading -> Unit
            }
        }
    }

    private fun renderTrustState() {
        val trust = takerTrust
        val stage = trust?.stage?.lowercase().orEmpty()
        b.tvTrustBadge.text = when (stage) {
            "pro_verified" -> getString(R.string.trust_pro_verified)
            "trusted" -> getString(R.string.trust_trusted)
            "verified" -> getString(R.string.trust_verified)
            else -> getString(R.string.trust_unverified)
        }
        val isTakerVerified = stage in setOf("verified", "trusted", "pro_verified")
        val badgeColor = when (stage) {
            "pro_verified" -> "#7C3AED"
            "trusted" -> "#1D4ED8"
            "verified" -> "#166534"
            else -> "#DC2626"
        }
        b.tvTrustBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor(badgeColor))
        b.tvTakerName.compoundDrawablePadding = resources.getDimensionPixelSize(R.dimen.spacing_xs)
        b.tvTakerName.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0,
            0,
            if (isTakerVerified) R.drawable.ic_check_circle else 0,
            0,
        )
        b.tvTakerName.compoundDrawableTintList = if (isTakerVerified) {
            ColorStateList.valueOf(Color.parseColor("#166534"))
        } else {
            null
        }

        val trustParts = mutableListOf<String>()
        if (trust?.identityVerified == true) trustParts += getString(R.string.identity_confirmed)
        if (trust?.portfolioVerified == true) trustParts += getString(R.string.portfolio_verified)
        trustParts += if ((trust?.completedBookingCount ?: 0) > 0) {
            resources.getQuantityString(
                R.plurals.completed_bookings_count,
                trust!!.completedBookingCount,
                trust.completedBookingCount,
            )
        } else {
            getString(R.string.no_bookings_yet)
        }
        trustParts += if ((trust?.reviewCount ?: 0) > 0) {
            getString(R.string.rating_with_count, trust!!.avgRating, trust.reviewCount)
        } else {
            getString(R.string.no_reviews_short)
        }
        trustParts += if ((trust?.endorsementCount ?: 0) > 0) {
            resources.getQuantityString(
                R.plurals.endorsements_count,
                trust!!.endorsementCount,
                trust.endorsementCount,
            )
        } else {
            getString(R.string.not_yet_endorsed)
        }
        b.tvTrustMeta.text = trustParts.joinToString(" | ")

        val isOwnProfile = session.isTaker() && session.getTakerActorId() == takerId
        val canViewReviewAction = session.hasClientFeatures() && !isOwnProfile
        val canLeaveTrackRecord = canViewReviewAction && trust?.canEndorse == true
        b.btnWriteReview.isVisible = canViewReviewAction
        b.btnEndorseTaker.isVisible = canLeaveTrackRecord
        b.btnEndorseTaker.text = if (trust?.viewerHasEndorsed == true) {
            getString(R.string.endorsed_taker)
        } else {
            getString(R.string.endorse_taker)
        }
        b.btnEndorseTaker.isEnabled = trust?.viewerHasEndorsed != true

        val showContactActions = !isOwnProfile
        b.btnEmail.isVisible = showContactActions
        b.btnCall.isVisible = showContactActions
        b.btnBookNow.isVisible = !isOwnProfile
        b.btnBookNow.text = getString(R.string.book_selected_date)
    }

    private fun maybeShowUnverifiedTakerWarning() {
        val stage = takerTrust?.stage?.lowercase().orEmpty()
        val isOwnProfile = session.isTaker() && session.getTakerActorId() == takerId
        if (unverifiedTakerWarningShown || isOwnProfile || stage != "unverified") return
        unverifiedTakerWarningShown = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unverified_taker_title)
            .setMessage(R.string.unverified_taker_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showUnverifiedStudioBookingWarning() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unverified_client_booking_title)
            .setMessage(R.string.unverified_client_booking_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.continue_booking) { _, _ -> showBookingSheet() }
            .show()
    }

    private fun showEndorsementOtpDialog(clientId: Int) {
        val email = session.getUserEmail().trim()
        if (email.isBlank()) {
            toast(getString(R.string.verify_email_first))
            return
        }
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
        }
        val body = android.widget.TextView(this).apply {
            text = getString(R.string.endorsement_otp_body)
            setTextColor(MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurfaceVariant))
            textSize = 14f
        }
        val otpInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        val otpLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = getString(R.string.enter_otp_code)
            addView(otpInput)
        }
        content.addView(body)
        content.addView(otpLayout)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.endorsement_otp_title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.endorse_taker, null)
            .show()
        val submit = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
        fun updateSubmitState() {
            submit.isEnabled = otpInput.text?.toString()?.trim()?.length == 6
        }
        submit.setOnClickListener {
            val otp = otpInput.text?.toString()?.trim().orEmpty()
            if (otp.length != 6) {
                otpLayout.error = getString(R.string.enter_otp_code)
                return@setOnClickListener
            }
            dialog.dismiss()
            detailVm.toggleTakerEndorsement(takerId, clientId, true, otp)
        }
        otpInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                otpLayout.error = null
                updateSubmitState()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        updateSubmitState()
        authVm.sendEmailOtp(email, purpose = "endorsement")
    }

    private fun ensureStudioContactAllowed(): Boolean {
        if (session.isGuest() || !session.isLoggedIn()) {
            toast(getString(R.string.sign_in_to_book))
            startActivity(Intent(this, LoginActivity::class.java))
            return false
        }
        if (!session.hasClientFeatures()) {
            toast(getString(R.string.account_cannot_place_bookings))
            return false
        }
        if (studioTrust == null) {
            toast(getString(R.string.checking_studio_verification))
            detailVm.fetchTrustStatus(
                takerId = takerId,
                clientId = session.getClientActorId().takeIf { session.hasClientFeatures() && it > 0 },
            )
            return false
        }
        if (studioTrust?.canBook != true) {
            showStudioVerificationDialog()
            return false
        }
        return true
    }

    private fun showStudioVerificationDialog() {
        val clientId = session.getClientActorId()
        if (clientId <= 0) {
            toast(getString(R.string.account_cannot_place_bookings))
            return
        }
        pendingStudioDocumentUri = null
        pendingStudioDocumentType = "shop_license"

        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(28, 8, 28, 0)
        }
        val body = android.widget.TextView(this).apply {
            text = getString(R.string.studio_verification_body)
            setTextColor(MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurfaceVariant))
            textSize = 14f
        }
        val gstInput = com.google.android.material.textfield.TextInputEditText(this)
        val gstLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = getString(R.string.studio_gstin_hint)
            addView(gstInput)
        }
        val mapsInput = com.google.android.material.textfield.TextInputEditText(this)
        val mapsLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = getString(R.string.studio_maps_hint)
            addView(mapsInput)
        }
        val documentLabel = android.widget.TextView(this).apply {
            text = getString(R.string.no_verification_document_selected)
            setTextColor(MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurfaceVariant))
            textSize = 13f
        }
        pendingStudioDocumentLabel = documentLabel
        val uploadButton = com.google.android.material.button.MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = getString(R.string.upload_shop_license_document)
            setOnClickListener {
                pendingStudioDocumentType = "shop_license"
                pickStudioVerificationDocument.launch("*/*")
            }
        }
        content.addView(body)
        content.addView(gstLayout)
        content.addView(mapsLayout)
        content.addView(uploadButton)
        content.addView(documentLabel)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.verify_studio_title)
            .setView(content)
            .setNegativeButton(R.string.cancel) { _, _ ->
                pendingStudioDocumentLabel = null
                updateStudioVerificationSubmitState = null
            }
            .setPositiveButton(R.string.submit, null)
            .show()
        val submit = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
        fun updateSubmitState() {
            val gstin = gstInput.text?.toString()?.trim().orEmpty()
            val mapsUrl = mapsInput.text?.toString()?.trim().orEmpty()
            submit.isEnabled = gstin.isNotBlank() || mapsUrl.isNotBlank() || pendingStudioDocumentUri != null
        }
        updateStudioVerificationSubmitState = ::updateSubmitState
        submit.setOnClickListener {
                val gstin = gstInput.text?.toString()?.trim().orEmpty()
                val mapsUrl = mapsInput.text?.toString()?.trim().orEmpty()
                if (gstin.isNotBlank() && !isValidGstin(gstin)) {
                    gstLayout.error = getString(R.string.invalid_gst_number)
                    toast(getString(R.string.invalid_gst_number))
                    return@setOnClickListener
                }
                gstLayout.error = null
                when {
                    gstin.isNotBlank() -> detailVm.submitStudioVerification(
                        clientId = clientId,
                        verificationPath = "gst",
                        gstin = gstin,
                    )
                    mapsUrl.isNotBlank() -> detailVm.submitStudioVerification(
                        clientId = clientId,
                        verificationPath = "google_maps",
                        googleMapsUrl = mapsUrl,
                    )
                    pendingStudioDocumentUri != null -> {
                        detailVm.submitStudioVerification(
                            clientId = clientId,
                            verificationPath = "shop_license",
                        )
                        detailVm.uploadVerificationDocument(
                            targetRole = "studio",
                            targetId = clientId,
                            documentType = pendingStudioDocumentType,
                            documentUri = pendingStudioDocumentUri!!,
                            context = this,
                        )
                    }
                    else -> toast(getString(R.string.enter_studio_verification_detail))
                }
                pendingStudioDocumentLabel = null
                updateStudioVerificationSubmitState = null
                dialog.dismiss()
            }
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateSubmitState()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        }
        gstInput.addTextChangedListener(watcher)
        mapsInput.addTextChangedListener(watcher)
        updateSubmitState()
    }

    private fun isValidGstin(value: String): Boolean =
        Regex("^[0-3][0-9][A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$", RegexOption.IGNORE_CASE)
            .matches(value.trim())

    private fun showBookingSheet() {
        val fromCalendar = b.calendarView.getSelectedBookingDate()
        if (fromCalendar == null) {
            toast(getString(R.string.tap_available_date_before_book))
            return
        }
        selectedDate = fromCalendar
        val bs = BottomSheetBookingBinding.inflate(LayoutInflater.from(this))
        val dlg = BottomSheetDialog(this, R.style.Theme_PhotoConnect_BottomSheet)
        dlg.setContentView(bs.root)
        bookingSheet = dlg
        bookingSheetBinding = bs

        bs.tvTakerNameBs.text = takerName
        bs.tvServiceTypeBs.text = serviceTypes.toServiceSummary(this)
        val bookingServices = serviceTypes.ifEmpty { listOf(svcType) }.filter { it.isNotBlank() }
        val serviceLabels = bookingServices.map { it.toServiceLabel(this) }
        fun refreshCustomServiceVisibility() {
            bs.tilBookingCustomService.isVisible =
                bs.actvBookingServiceType.text.toString().toServiceTypeOrNull(this) == "other"
        }
        fun selectedBookingService(): String? {
            val selected = bs.actvBookingServiceType.text.toString()
            return if (selected.toServiceTypeOrNull(this) == "other") {
                bs.etBookingCustomService.text?.toString()?.toServiceTypeInput(this)
            } else {
                selected.toServiceTypeInput(this)
            }
        }
        
        bs.actvBookingServiceType.setAdapter(
            ArrayAdapter(this, R.layout.item_dropdown, serviceLabels)
        )
        
        val initialService = svcType.takeIf { it in bookingServices } ?: bookingServices.primaryServiceType()
        bs.actvBookingServiceType.setText(initialService.toServiceLabel(this), false)
        refreshCustomServiceVisibility()
        bs.actvBookingServiceType.setOnItemClickListener { _, _, _, _ -> refreshCustomServiceVisibility() }

        bs.tvBookingDateLabel.text = getString(R.string.booking_date_label)
        bs.tvSelectedDate.text = selectedDate!!.toDisplayDate()
        applyBookingDayPartAvailability(bs)

        bs.btnConfirmBook.setOnClickListener {
            if (selectedDate == null) {
                toast(getString(R.string.choose_date_calendar_first))
                return@setOnClickListener
            }
            val selectedService = selectedBookingService()
            val selectedBase = bs.actvBookingServiceType.text.toString().toServiceTypeOrNull(this)
            val isOffered = selectedService in bookingServices || (selectedBase == "other" && "other" in bookingServices)
            if (selectedService == null || !isOffered) {
                toast(getString(R.string.select_service_for_booking))
                return@setOnClickListener
            }
            val dayPart = selectedBookingDayPart(bs)
            val availableDayPart = availabilityDayPartByDate[selectedDate].orEmpty().normalizedDayPart()
            if (availableDayPart != DAY_PART_FULL && dayPart != availableDayPart) {
                toast(getString(R.string.availability_day_part_not_allowed, availableDayPart.toDayPartLabel(this)))
                return@setOnClickListener
            }
            pendingBookingServiceType = selectedService
            bookingVm.book(
                takerId = takerId,
                date = selectedDate!!,
                svc = selectedService,
                dayPart = dayPart,
                loc = bs.etEventLocation.text?.toString()?.trim()?.ifEmpty { null },
                notes = bs.etNotes.text?.toString()?.trim()?.ifEmpty { null },
                takerName = takerName,
            )
        }

        dlg.setOnDismissListener {
            bookingSheet = null
            bookingSheetBinding = null
        }
        dlg.show()
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun applyBookingDayPartAvailability(bs: BottomSheetBookingBinding) {
        val availableDayPart = availabilityDayPartByDate[selectedDate].orEmpty().normalizedDayPart()
        val allowedFullDay = availableDayPart == DAY_PART_FULL
        bs.btnBookingFullDay.isEnabled = allowedFullDay
        bs.btnBookingFirstHalf.isEnabled = allowedFullDay || availableDayPart == DAY_PART_FIRST_HALF
        bs.btnBookingSecondHalf.isEnabled = allowedFullDay || availableDayPart == DAY_PART_SECOND_HALF
        val checkedId = when (availableDayPart) {
            DAY_PART_FIRST_HALF -> bs.btnBookingFirstHalf.id
            DAY_PART_SECOND_HALF -> bs.btnBookingSecondHalf.id
            else -> bs.btnBookingFullDay.id
        }
        bs.toggleBookingDayPart.check(checkedId)
    }

    private fun selectedBookingDayPart(bs: BottomSheetBookingBinding): String =
        when (bs.toggleBookingDayPart.checkedButtonId) {
            bs.btnBookingFirstHalf.id -> DAY_PART_FIRST_HALF
            bs.btnBookingSecondHalf.id -> DAY_PART_SECOND_HALF
            else -> DAY_PART_FULL
        }

    private fun setBookingSheetProcessing(bs: BottomSheetBookingBinding, processing: Boolean) {
        bs.progressBar.isVisible = processing
        bs.tilBookingServiceType.isEnabled = !processing
        bs.actvBookingServiceType.isEnabled = !processing
        bs.tilBookingCustomService.isEnabled = !processing
        bs.etBookingCustomService.isEnabled = !processing
        bs.etEventLocation.isEnabled = !processing
        bs.etNotes.isEnabled = !processing
        bs.btnConfirmBook.isEnabled = !processing
        bs.btnConfirmBook.text = getString(if (processing) R.string.updating else R.string.confirm_booking)
        bookingSheet?.setCancelable(!processing)
        bookingSheet?.setCanceledOnTouchOutside(!processing)
        if (processing) {
            bs.btnBookingFullDay.isEnabled = false
            bs.btnBookingFirstHalf.isEnabled = false
            bs.btnBookingSecondHalf.isEnabled = false
        } else {
            applyBookingDayPartAvailability(bs)
        }
    }

    private fun List<AvailabilityEntity>.toCalendarState(): Pair<Map<String, String>, Map<String, String>> {
        val statusByDate = mutableMapOf<String, String>()
        val partByDate = mutableMapOf<String, String>()
        groupBy { it.date }.forEach { (date, rows) ->
            val available = rows.filter { it.status == "Available" }.map { it.dayPart }.toSet()
            val booked = rows.filter { it.status == "Booked" }.map { it.dayPart }.toSet()
            val notAvailable = rows.filter { it.status == "Not Available" }.map { it.dayPart }.toSet()
            val statusPart = when {
                DAY_PART_FULL in available || available.containsAll(setOf(DAY_PART_FIRST_HALF, DAY_PART_SECOND_HALF)) ->
                    "Available" to DAY_PART_FULL
                DAY_PART_FIRST_HALF in available -> "Available" to DAY_PART_FIRST_HALF
                DAY_PART_SECOND_HALF in available -> "Available" to DAY_PART_SECOND_HALF
                DAY_PART_FULL in booked || booked.containsAll(setOf(DAY_PART_FIRST_HALF, DAY_PART_SECOND_HALF)) ->
                    "Booked" to DAY_PART_FULL
                DAY_PART_FIRST_HALF in booked -> "Booked" to DAY_PART_FIRST_HALF
                DAY_PART_SECOND_HALF in booked -> "Booked" to DAY_PART_SECOND_HALF
                DAY_PART_FIRST_HALF in notAvailable -> "Not Available" to DAY_PART_FIRST_HALF
                DAY_PART_SECOND_HALF in notAvailable -> "Not Available" to DAY_PART_SECOND_HALF
                else -> "Not Available" to DAY_PART_FULL
            }
            statusByDate[date] = statusPart.first
            partByDate[date] = statusPart.second
        }
        return statusByDate to partByDate
    }

    private fun showReviewSheet() {
        val bs = BottomSheetReviewBinding.inflate(LayoutInflater.from(this))
        val dlg = BottomSheetDialog(this, R.style.Theme_PhotoConnect_BottomSheet)
        dlg.setContentView(bs.root)
        reviewSheet = dlg
        reviewSheetBinding = bs

        bs.btnSubmitReview.setOnClickListener {
            val rating = bs.ratingBar.rating.toInt()
            if (rating == 0) {
                toast(getString(R.string.give_rating))
                return@setOnClickListener
            }
            detailVm.submitReview(
                AddReviewRequest(takerId, session.getClientActorId(), rating, bs.etReview.text?.toString()?.trim())
            )
            pendingReviewRating = rating
            pendingReviewComment = bs.etReview.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        }

        dlg.setOnDismissListener {
            reviewSheet = null
            reviewSheetBinding = null
        }
        dlg.show()
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun observeBookingState() {
        bookingVm.bookState.observe(this) { r ->
            val bs = bookingSheetBinding ?: return@observe
            when (r) {
                is Result.Loading -> {
                    setBookingSheetProcessing(bs, true)
                }
                is Result.Success -> {
                    bookingSheet?.dismiss()
                    b.calendarView.clearSelection()
                    startActivity(Intent(this, BookingConfirmationActivity::class.java).apply {
                        putExtra(BookingConfirmationActivity.EXTRA_ID, r.data.bookingId)
                        putExtra(BookingConfirmationActivity.EXTRA_NAME, takerName)
                        putExtra(BookingConfirmationActivity.EXTRA_DATE, selectedDate)
                        putExtra(BookingConfirmationActivity.EXTRA_SVC, pendingBookingServiceType ?: svcType)
                    })
                }
                is Result.Error -> {
                    setBookingSheetProcessing(bs, false)
                    toast(r.message)
                }
            }
        }
    }

    private fun observeReviewState() {
        detailVm.reviews.observe(this) { r ->
            when (r) {
                is Result.Success -> {
                    currentReviewCount = r.data.total
                    reviewAdapter.submitList(r.data.reviews.map {
                        ReviewEntity(
                            id = it.id,
                            takerId = it.takerId,
                            clientId = it.clientId,
                            clientName = it.clientName,
                            rating = it.rating,
                            comment = it.comment,
                            createdAt = it.createdAt,
                        )
                    })
                    b.ratingBar.rating = r.data.avgRating
                    b.tvRating.text = if (r.data.avgRating > 0f) {
                        getString(R.string.rating_with_count, r.data.avgRating, r.data.total)
                    } else {
                        getString(R.string.account_new_badge)
                    }
                    renderReviewEmptyState(r.data.reviews.isEmpty())
                }
                is Result.Error -> {
                    if (currentReviewCount == 0) {
                        renderReviewEmptyState(true)
                    }
                }
                Result.Loading -> Unit
            }
        }
        detailVm.addReview.observe(this) { r ->
            val bs = reviewSheetBinding ?: return@observe
            when (r) {
                is Result.Loading -> {
                    bs.btnSubmitReview.isEnabled = false
                }
                is Result.Success -> {
                    reviewSheet?.dismiss()
                    toast(getString(R.string.review_submitted))
                    applySubmittedReviewOptimistically(r.data.id)
                    markProfileChanged(reviewChanged = true)
                    detailVm.fetchTakerProfile(takerId, force = true)
                    detailVm.fetchTrustStatus(
                        takerId = takerId,
                        clientId = session.getClientActorId().takeIf { session.hasClientFeatures() && it > 0 },
                        force = true,
                    )
                    detailVm.fetchReviews(takerId, force = true)
                }
                is Result.Error -> {
                    bs.btnSubmitReview.isEnabled = true
                    toast(r.message)
                }
                else -> {}
            }
        }
    }

    private fun applySubmittedReviewOptimistically(reviewId: Int) {
        val rating = pendingReviewRating ?: return
        val oldCount = currentReviewCount.coerceAtLeast(0)
        val oldAverage = b.ratingBar.rating
        val newCount = oldCount + 1
        val newAverage = (((oldAverage * oldCount) + rating) / newCount).coerceIn(0f, 5f)
        currentReviewCount = newCount
        b.ratingBar.rating = newAverage
        b.tvRating.text = getString(R.string.rating_with_count, newAverage, newCount)
        renderReviewEmptyState(false)

        val current = reviewAdapter.currentList
        val review = ReviewEntity(
            id = reviewId,
            takerId = takerId,
            clientId = session.getClientActorId(),
            clientName = session.getUserName().takeIf { it.isNotBlank() } ?: getString(R.string.booking_default_client),
            rating = rating,
            comment = pendingReviewComment,
            createdAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()),
        )
        reviewAdapter.submitList((listOf(review) + current).distinctBy { it.id })
        pendingReviewRating = null
        pendingReviewComment = null
    }

    private fun markProfileChanged(
        favoriteChanged: Boolean = false,
        reviewChanged: Boolean = false,
    ) {
        favoriteChangedForResult = favoriteChangedForResult || favoriteChanged
        reviewChangedForResult = reviewChangedForResult || reviewChanged
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(RESULT_TAKER_ID, takerId)
                putExtra(RESULT_FAVORITE_CHANGED, favoriteChangedForResult)
                putExtra(RESULT_REVIEW_CHANGED, reviewChangedForResult)
            },
        )
    }

    private fun renderReviewEmptyState(empty: Boolean) {
        b.tvNoReviews.isVisible = empty
        b.rvReviews.isVisible = !empty
    }

    private fun observePostState() {
        detailVm.takerPostsState.observe(this) { r ->
            when (r) {
                is Result.Success -> renderPosts(r.data.posts)
                is Result.Error -> {
                    if (currentPosts.isEmpty()) {
                        renderPosts(emptyList())
                    }
                    toast(r.message)
                }
                else -> Unit
            }
        }
    }

    private fun observePostActionStates() {
        detailVm.togglePostImageLikeState.observe(this) { result ->
            when (result) {
                is Result.Success -> {
                    currentPosts = currentPosts.map { post ->
                        if (post.id != result.data.postId) return@map post
                        post.copy(
                            likeCount = result.data.postLikeCount,
                            viewerHasLiked = post.images.any {
                                if (it.id == result.data.imageId) result.data.viewerHasLiked else it.viewerHasLiked
                            },
                            images = post.images.map { image ->
                                if (image.id == result.data.imageId) {
                                    image.copy(
                                        likeCount = result.data.imageLikeCount,
                                        viewerHasLiked = result.data.viewerHasLiked,
                                    )
                                } else image
                            }
                        )
                    }
                    postAdapter.submitList(currentPosts)
                }
                is Result.Error -> toast(result.message)
                Result.Loading -> Unit
            }
        }

        detailVm.togglePostSaveState.observe(this) { result ->
            when (result) {
                is Result.Success -> {
                    currentPosts = currentPosts.map { post ->
                        if (post.id == result.data.postId) {
                            post.copy(viewerHasSaved = result.data.viewerHasSaved)
                        } else post
                    }
                    postAdapter.submitList(currentPosts)
                    toast(
                        if (result.data.viewerHasSaved) {
                            getString(R.string.post_saved_toast)
                        } else {
                            getString(R.string.post_removed_saved_toast)
                        }
                    )
                }
                is Result.Error -> toast(result.message)
                Result.Loading -> Unit
            }
        }
    }

    private fun renderPosts(posts: List<TakerPost>) {
        currentPosts = posts
        postAdapter.submitList(posts)
        b.rvPortfolio.isVisible = posts.isNotEmpty()
        b.tvNoPortfolio.isVisible = posts.isEmpty()

        // If there are multiple post images, show a swipeable/auto-sliding hero carousel.
        val candidate = posts.asSequence()
            .flatMap { it.images.asSequence() }
            .mapNotNull { img ->
                val full = img.imageUrl.takeIf { it.isNotBlank() }
                if (full == null) null else HeroImage(fullUrl = full, thumbUrl = img.thumbUrl)
            }
            .distinctBy { it.fullUrl }
            .take(8)
            .toList()

        if (candidate.isNotEmpty()) {
            val first = candidate.first().fullUrl
            if (first != null && heroOverrideUrl != first) {
                heroOverrideUrl = first
                setHeroImages(candidate)
            }
        }
    }

    private fun vmTogglePostImageLike(postId: Int, imageId: Int, like: Boolean) {
        if (session.isTaker() && session.getTakerActorId() == takerId) {
            toast(getString(R.string.cannot_like_own_image))
            return
        }
        detailVm.toggleTakerPostImageLike(imageId, session.getRole(), session.getActiveActorId(), like)
    }

    private fun vmTogglePostSave(postId: Int, save: Boolean) {
        if (session.isTaker() && session.getTakerActorId() == takerId) {
            toast(getString(R.string.cannot_save_own_post))
            return
        }
        detailVm.toggleTakerPostSave(postId, session.getRole(), session.getActiveActorId(), save)
    }

    private fun fetchPosts() {
        val viewerRole = if (session.isLoggedIn() && !session.isGuest()) session.getRole() else null
        val viewerId = if (session.isLoggedIn() && !session.isGuest()) session.getActiveActorId() else null
        detailVm.fetchTakerPosts(takerId, viewerRole, viewerId)
    }

    private fun renderHeroImage(fullUrl: String?, thumbUrl: String?) {
        val full = fullUrl?.takeIf { it.isNotBlank() }
        val thumb = thumbUrl?.takeIf { it.isNotBlank() }
        setHeroImages(listOf(HeroImage(fullUrl = full ?: thumb, thumbUrl = thumb)))
    }

    private fun setHeroImages(list: List<HeroImage>) {
        val next = list.ifEmpty { listOf(HeroImage(fullUrl = null, thumbUrl = null)) }
        if (heroImages.map { it.fullUrl } == next.map { it.fullUrl }) return

        heroImages = next
        heroAdapter.submitList(heroImages)
        b.pagerHero.setCurrentItem(0, false)
        updateHeroIndicator(0)
        restartHeroAutoScroll()
    }

    private fun updateHeroIndicator(position: Int) {
        if (heroImages.isEmpty()) return
        b.cardHeroIndicator.isVisible = heroImages.size > 1
        b.tvHeroIndicator.text = "${position + 1}/${heroImages.size}"
    }

    private fun restartHeroAutoScroll() {
        heroHandler.removeCallbacks(heroAutoScroll)
        if (heroImages.size > 1) {
            heroHandler.postDelayed(heroAutoScroll, 4200L)
        }
    }

    private fun styleTopBar() {
        b.toolbar.navigationIcon?.setTint(getColor(android.R.color.white))
    }

    private fun openPost(post: TakerPost) {
        val postIndex = currentPosts.indexOfFirst { it.id == post.id }.coerceAtLeast(0)
        preloadNeighborPostImages(postIndex)
        postViewerLauncher.launch(
            Intent(this, TakerPostViewerActivity::class.java).apply {
                putExtra(TakerPostViewerActivity.EXTRA_POST_INDEX, postIndex)
                putExtra(TakerPostViewerActivity.EXTRA_TAKER_ID, takerId)
                putExtra(TakerPostViewerActivity.EXTRA_POST_ID, post.id)
                putExtra(TakerPostViewerActivity.EXTRA_IS_OWNER, session.isTaker() && session.getTakerActorId() == takerId)
            }
        )
    }

    private fun preloadNeighborPostImages(postIndex: Int) {
        listOf(postIndex, postIndex + 1)
            .mapNotNull { currentPosts.getOrNull(it) }
            .flatMap { it.images.take(2) }
            .mapNotNull { it.thumbUrl?.takeIf(String::isNotBlank) ?: it.imageUrl.takeIf(String::isNotBlank) }
            .distinct()
            .take(4)
            .forEach { url ->
                Glide.with(this)
                    .load(url)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .preload()
            }
    }

    override fun onResume() {
        super.onResume()
        restartHeroAutoScroll()
    }

    override fun onPause() {
        heroHandler.removeCallbacks(heroAutoScroll)
        super.onPause()
    }

    // BUG FIX: Clean up BottomSheets to prevent WindowLeaked crashes
    override fun onDestroy() {
        super.onDestroy()
        bookingSheet?.dismiss()
        reviewSheet?.dismiss()
    }
}
