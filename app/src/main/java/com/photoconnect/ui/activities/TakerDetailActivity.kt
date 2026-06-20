package com.photoconnect.ui.activities

import android.content.Intent
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.photoconnect.R
import com.photoconnect.databinding.ActivityTakerDetailBinding
import com.photoconnect.databinding.BottomSheetBookingBinding
import com.photoconnect.databinding.BottomSheetReviewBinding
import com.photoconnect.db.toModel
import com.photoconnect.model.AddReviewRequest
import com.photoconnect.model.TakerPost
import com.photoconnect.model.TakerPostImage
import com.photoconnect.repository.Result
import com.photoconnect.ui.adapters.AccountPostAdapter
import com.photoconnect.ui.adapters.HeroImage
import com.photoconnect.ui.adapters.HeroImagePagerAdapter
import com.photoconnect.ui.adapters.ReviewAdapter
import com.photoconnect.ui.views.AvailabilityCalendarView
import com.photoconnect.utils.*
import com.photoconnect.viewmodel.BookingViewModel
import com.photoconnect.viewmodel.TakerDetailViewModel
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
    }

    private lateinit var b: ActivityTakerDetailBinding
    private val detailVm: TakerDetailViewModel by viewModels()
    private val bookingVm: BookingViewModel by viewModels()

    @Inject lateinit var session: SessionManager

    private var takerId = 0
    private var takerName = ""
    private var svcType = ""
    private var serviceTypes: List<String> = emptyList()
    private var availabilityByDate: Map<String, String> = emptyMap()
    private var isFavorite = false
    private var confirmedIsFavorite = false
    private var favoriteToggleInFlight = false
    private var selectedDate: String? = null
    private var bookingSheet: BottomSheetDialog? = null
    private var bookingSheetBinding: BottomSheetBookingBinding? = null
    private var reviewSheet: BottomSheetDialog? = null
    private var reviewSheetBinding: BottomSheetReviewBinding? = null
    private var heroOverrideUrl: String? = null
    private var currentPosts: List<TakerPost> = emptyList()

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
    private val postAdapter = AccountPostAdapter { post -> openPost(post) }
    private val postJsonAdapter by lazy {
        Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build().adapter(TakerPost::class.java)
    }
    private val postListJsonAdapter by lazy {
        val type = Types.newParameterizedType(List::class.java, TakerPost::class.java)
        Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build().adapter<List<TakerPost>>(type)
    }

    private val postViewerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            fetchPosts()
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        b = ActivityTakerDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

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
        b.calendarView.onInvalidSelectionAttempt = { msg -> toast(msg) }
        setupCalendarMonthNavigation()
        setupAdapters()
        setupObservers()
        setupListeners()

        detailVm.fetchAvailability(takerId, month = null)
        detailVm.fetchReviews(takerId)
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
            val monthName = java.text.DateFormatSymbols().months[m - 1]
            "$monthName $y"
        } catch (_: Exception) {
            cur
        }
    }

    private fun setupStaticInfo() {
        b.tvTakerName.text = takerName
        b.tvServiceType.text = serviceTypes.toServiceSummary()
        b.tvCity.text = intent.getStringExtra(EXTRA_CITY) ?: ""
        val rating = intent.getFloatExtra(EXTRA_RATING, 0f)
        b.ratingBar.rating = rating
        b.tvRating.text = if (rating > 0) "%.1f".format(rating) else "New"
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

        b.rvPortfolio.layoutManager = GridLayoutManager(this, 2)
        b.rvPortfolio.adapter = postAdapter
        b.rvPortfolio.isNestedScrollingEnabled = false

        val reviewAdapter = ReviewAdapter()
        b.rvReviews.adapter = reviewAdapter

        detailVm.cachedAvailability(takerId).observe(this) { 
            availabilityByDate = it.associate { e -> e.date to e.status }
            b.calendarView.setAvailabilityMap(availabilityByDate)
        }
        detailVm.cachedReviews(takerId).observe(this) { list ->
            reviewAdapter.submitList(list)
            b.tvNoReviews.isVisible = list.isEmpty()
        }
    }

    private fun setupObservers() {
        detailVm.getTaker(takerId).observe(this) { t ->
            if (t == null) return@observe
            val taker = t.toModel()
            renderHeroImage(taker.profileImageUrl, taker.profileThumbUrl)
            if (taker.offeredServices.isNotEmpty()) {
                serviceTypes = taker.offeredServices
                if (svcType !in serviceTypes) svcType = serviceTypes.primaryServiceType()
                b.tvServiceType.text = serviceTypes.toServiceSummary()
            }
            b.tvExperience.text = getString(R.string.years_exp, t.yearsExperience)
            b.tvLanguages.text = t.languages ?: "English, Hindi"

            b.btnCall.setOnClickListener {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${t.phone}")))
            }
            
            b.btnWhatsApp.setOnClickListener {
                val url = "https://api.whatsapp.com/send?phone=91${t.phone}&text=Hello%20${t.fullName},%20I%20saw%20your%20profile%20on%20PhotoConnect"
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    toast("WhatsApp not installed")
                }
            }

            b.tvCity.setOnClickListener {
                val gmmIntentUri = Uri.parse("geo:0,0?q=${t.area}, ${t.city}, ${t.state}")
                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                mapIntent.setPackage("com.google.android.apps.maps")
                if (mapIntent.resolveActivity(packageManager) != null) {
                    startActivity(mapIntent)
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, gmmIntentUri))
                }
            }

            val hasSocial = !t.instagramUrl.isNullOrBlank() || !t.youtubeUrl.isNullOrBlank() || !t.portfolioUrl.isNullOrBlank()
            b.layoutSocial.isVisible = hasSocial

            b.btnInstagram.isVisible = !t.instagramUrl.isNullOrBlank()
            b.btnInstagram.setOnClickListener { openUrl(t.instagramUrl) }
            
            b.btnYoutube.isVisible = !t.youtubeUrl.isNullOrBlank()
            b.btnYoutube.setOnClickListener { openUrl(t.youtubeUrl) }
            
            b.btnPortfolio.isVisible = !t.portfolioUrl.isNullOrBlank()
            b.btnPortfolio.setOnClickListener { openUrl(t.portfolioUrl) }
        }

        if (session.isLoggedIn() && !session.isGuest()) {
            detailVm.fetchFavoriteStatus(takerId, session.getRole(), session.getUserId())
            detailVm.favoriteStatusState.observe(this) { result ->
                if (favoriteToggleInFlight) return@observe
                when (result) {
                    is Result.Success -> {
                        confirmedIsFavorite = result.data.isFavorite
                        isFavorite = confirmedIsFavorite
                        renderFavoriteButton()
                    }
                    is Result.Error -> Unit
                    Result.Loading -> Unit
                }
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

        observeBookingState()
        observeReviewState()
        observePostState()
        observePostActionStates()
    }

    private fun setupListeners() {
        b.btnFavorite.setOnClickListener {
            if (!session.isLoggedIn() || session.isGuest()) {
                toast("Sign in to favorite creators")
                return@setOnClickListener
            }
            val target = !isFavorite
            isFavorite = target
            renderFavoriteButton()
            detailVm.toggleFavoriteRemote(takerId, session.getRole(), session.getUserId(), target)
            toast(if (target) "Saved to favorites" else "Removed from favorites")
        }

        b.btnBookNow.setOnClickListener {
            if (session.isGuest() || !session.isLoggedIn()) {
                toast("Please sign in to book")
                startActivity(Intent(this, LoginActivity::class.java))
                return@setOnClickListener
            }
            if (!session.hasClientFeatures()) {
                toast("This account cannot place bookings yet")
                return@setOnClickListener
            }
            if (session.isTaker() && session.getUserId() == takerId) {
                toast("You cannot book your own taker profile")
                return@setOnClickListener
            }
            showBookingSheet()
        }

        b.btnWriteReview.setOnClickListener {
            if (!session.hasClientFeatures()) {
                toast("Sign in to review")
                return@setOnClickListener
            }
            if (session.isTaker() && session.getUserId() == takerId) {
                toast("You cannot review your own taker profile")
                return@setOnClickListener
            }
            showReviewSheet()
        }
    }

    private fun openUrl(url: String?) {
        if (url.isNullOrBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("Could not open link")
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

    private fun showBookingSheet() {
        val fromCalendar = b.calendarView.getSelectedBookingDate()
        if (fromCalendar == null) {
            toast("Tap a green (available) date on the calendar above, then book")
            return
        }
        selectedDate = fromCalendar
        val bs = BottomSheetBookingBinding.inflate(LayoutInflater.from(this))
        val dlg = BottomSheetDialog(this, R.style.Theme_PhotoConnect_BottomSheet)
        dlg.setContentView(bs.root)
        bookingSheet = dlg
        bookingSheetBinding = bs

        bs.tvTakerNameBs.text = takerName
        bs.tvServiceTypeBs.text = serviceTypes.toServiceSummary()
        val bookingServices = serviceTypes.ifEmpty { listOf(svcType) }.filter { it.isNotBlank() }
        val serviceLabels = bookingServices.map { it.toServiceLabel() }
        
        bs.actvBookingServiceType.setAdapter(
            ArrayAdapter(this, R.layout.item_dropdown, serviceLabels)
        )
        
        val initialService = svcType.takeIf { it in bookingServices } ?: bookingServices.primaryServiceType()
        bs.actvBookingServiceType.setText(initialService.toServiceLabel(), false)

        bs.tvBookingDateLabel.text = getString(R.string.booking_date_label)
        bs.tvSelectedDate.text = selectedDate!!.toDisplayDate()

        bs.btnConfirmBook.setOnClickListener {
            if (selectedDate == null) {
                toast("Choose a date on the calendar first")
                return@setOnClickListener
            }
            val selectedService = bs.actvBookingServiceType.text.toString().toServiceTypeOrNull()
            if (selectedService == null || selectedService !in bookingServices) {
                toast("Select the service for this booking")
                return@setOnClickListener
            }
            bookingVm.book(takerId, selectedDate!!, selectedService,
                bs.etEventLocation.text?.toString()?.trim()?.ifEmpty { null },
                bs.etNotes.text?.toString()?.trim()?.ifEmpty { null },
                takerName = takerName)
        }

        dlg.setOnDismissListener {
            bookingSheet = null
            bookingSheetBinding = null
        }
        dlg.show()
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
                toast("Give a rating")
                return@setOnClickListener
            }
            detailVm.submitReview(
                AddReviewRequest(takerId, session.getClientProfileId(), rating, bs.etReview.text?.toString()?.trim())
            )
        }

        dlg.setOnDismissListener {
            reviewSheet = null
            reviewSheetBinding = null
        }
        dlg.show()
    }

    private fun observeBookingState() {
        bookingVm.bookState.observe(this) { r ->
            val bs = bookingSheetBinding ?: return@observe
            when (r) {
                is Result.Loading -> {
                    bs.progressBar.show()
                    bs.btnConfirmBook.isEnabled = false
                }
                is Result.Success -> {
                    bookingSheet?.dismiss()
                    b.calendarView.clearSelection()
                    startActivity(Intent(this, BookingConfirmationActivity::class.java).apply {
                        putExtra(BookingConfirmationActivity.EXTRA_ID, r.data.bookingId)
                        putExtra(BookingConfirmationActivity.EXTRA_NAME, takerName)
                        putExtra(BookingConfirmationActivity.EXTRA_DATE, selectedDate)
                        putExtra(BookingConfirmationActivity.EXTRA_SVC, bs.actvBookingServiceType.text.toString().toServiceTypeOrNull() ?: svcType)
                    })
                }
                is Result.Error -> {
                    bs.progressBar.hide()
                    bs.btnConfirmBook.isEnabled = true
                    toast(r.message)
                }
            }
        }
    }

    private fun observeReviewState() {
        detailVm.addReview.observe(this) { r ->
            val bs = reviewSheetBinding ?: return@observe
            when (r) {
                is Result.Loading -> {
                    bs.btnSubmitReview.isEnabled = false
                }
                is Result.Success -> {
                    reviewSheet?.dismiss()
                    toast("Review submitted!")
                    detailVm.fetchReviews(takerId)
                }
                is Result.Error -> {
                    bs.btnSubmitReview.isEnabled = true
                    toast(r.message)
                }
                else -> {}
            }
        }
    }

    private fun observePostState() {
        detailVm.takerPostsState.observe(this) { r ->
            when (r) {
                is Result.Success -> renderPosts(r.data.posts)
                is Result.Error -> {
                    renderPosts(emptyList())
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
                    toast(if (result.data.viewerHasSaved) "Post saved" else "Post removed from saved")
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
        if (session.isTaker() && session.getUserId() == takerId) {
            toast("You cannot like your own image")
            return
        }
        detailVm.toggleTakerPostImageLike(imageId, session.getRole(), session.getUserId(), like)
    }

    private fun vmTogglePostSave(postId: Int, save: Boolean) {
        detailVm.toggleTakerPostSave(postId, session.getRole(), session.getUserId(), save)
    }

    private fun fetchPosts() {
        val viewerRole = if (session.isLoggedIn() && !session.isGuest()) session.getRole() else null
        val viewerId = if (session.isLoggedIn() && !session.isGuest()) session.getUserId() else null
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
        postViewerLauncher.launch(
            Intent(this, TakerPostViewerActivity::class.java).apply {
                putExtra(TakerPostViewerActivity.EXTRA_POST_JSON, postJsonAdapter.toJson(post))
                putExtra(TakerPostViewerActivity.EXTRA_POSTS_JSON, postListJsonAdapter.toJson(currentPosts))
                putExtra(TakerPostViewerActivity.EXTRA_POST_INDEX, postIndex)
                putExtra(TakerPostViewerActivity.EXTRA_TAKER_ID, takerId)
                putExtra(TakerPostViewerActivity.EXTRA_POST_ID, post.id)
                putExtra(TakerPostViewerActivity.EXTRA_IS_OWNER, session.isTaker() && session.getUserId() == takerId)
            }
        )
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
