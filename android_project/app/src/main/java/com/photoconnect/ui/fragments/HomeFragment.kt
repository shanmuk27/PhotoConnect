package com.photoconnect.ui.fragments

import android.Manifest
import android.app.Activity
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.util.Consumer
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.photoconnect.R
import com.photoconnect.databinding.BottomSheetNotificationsBinding
import com.photoconnect.databinding.FragmentHomeBinding
import com.photoconnect.model.NominatimPlace
import com.photoconnect.model.NotificationItem
import com.photoconnect.model.PostOffice
import com.photoconnect.model.SearchAlertRequest
import com.photoconnect.model.SearchAlternative
import com.photoconnect.model.SearchEventRequest
import com.photoconnect.model.TrendingSearch
import com.photoconnect.model.Taker
import com.photoconnect.repository.FavoriteRepository
import com.photoconnect.repository.Result
import com.photoconnect.ui.activities.TakerDetailActivity
import com.photoconnect.ui.adapters.CategoryAdapter
import com.photoconnect.ui.adapters.FeaturedTakerAdapter
import com.photoconnect.ui.adapters.NotificationAdapter
import com.photoconnect.ui.adapters.ACTION_CURRENT_LOCATION
import com.photoconnect.ui.adapters.SearchSuggestion
import com.photoconnect.ui.adapters.SearchSuggestionAdapter
import com.photoconnect.ui.adapters.TakerAdapter
import com.photoconnect.utils.SERVICE_ICONS
import com.photoconnect.utils.SERVICE_TYPES
import com.photoconnect.utils.SearchTrie
import com.photoconnect.utils.SessionManager
import com.photoconnect.utils.forceLeftToRightTree
import com.photoconnect.utils.hide
import com.photoconnect.utils.isNetworkAvailable
import com.photoconnect.utils.primaryServiceType
import com.photoconnect.utils.serviceLabels
import com.photoconnect.utils.show
import com.photoconnect.utils.toAbsoluteMediaUrl
import com.photoconnect.utils.toDisplayDate
import com.photoconnect.utils.toServiceLabel
import com.photoconnect.utils.toast
import com.photoconnect.viewmodel.HomeViewModel
import com.photoconnect.viewmodel.NotificationViewModel
import com.photoconnect.viewmodel.PincodeViewModel
import com.photoconnect.viewmodel.PlaceSuggestionViewModel
import com.photoconnect.viewmodel.TakerDetailViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()
    private val notificationsVm: NotificationViewModel by viewModels()
    private val detailVm: TakerDetailViewModel by viewModels()
    private val pincodeVm: PincodeViewModel by viewModels()
    private val placeVm: PlaceSuggestionViewModel by viewModels()

    @Inject lateinit var session: SessionManager
    @Inject lateinit var favoriteRepo: FavoriteRepository

    private lateinit var featuredAdapter: FeaturedTakerAdapter
    private lateinit var takerAdapter: TakerAdapter
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var takerGridLayoutManager: GridLayoutManager
    private lateinit var notificationAdapter: NotificationAdapter
    private lateinit var searchSuggestionAdapter: SearchSuggestionAdapter

    private var selectedLocation: String? = null
    private var liveSearchQuery: String = ""
    private var selectedService: String? = null
    private var selectedDate: String? = null
    private var searchRadiusKm: Double = DEFAULT_SEARCH_RADIUS_KM
    private var isAvailableOnly: Boolean = false
    private var trustFilter: String? = null
    private var respondsFastOnly: Boolean = false
    private var favoriteTakerIds: Set<Int> = emptySet()
    private var hasFavoriteSnapshot: Boolean = false
    private var currentTakers: List<Taker> = emptyList()
    private var currentFeatured: List<Taker> = emptyList()
    private var currentNotifications: List<NotificationItem> = emptyList()
    private var currentUnreadCount: Int = 0
    private val shownSystemNotificationIds = mutableSetOf<Int>()
    private var systemNotificationInitialSyncDone = false
    private var deviceLatitude: Double? = null
    private var deviceLongitude: Double? = null
    private var featuredExpanded = false
    private var isApplyingSearchSuggestion = false
    private var remotePlaceSuggestionQuery: String = ""
    private var postalPlaceSuggestions: List<SearchSuggestion> = emptyList()
    private var geoPlaceSuggestions: List<SearchSuggestion> = emptyList()
    private var searchSuggestionTrie: SearchTrie<SearchSuggestion> = SearchTrie()
    private val remotePlaceSuggestions: List<SearchSuggestion>
        get() = geoPlaceSuggestions + postalPlaceSuggestions
    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearchRunnable: Runnable? = null
    private var lastAppliedFilterKey: String? = null
    private var lastSuggestionLookupKey: String? = null
    private var lastNotificationsFetchAt: Long = 0L
    private var filteredTakersForDisplay: List<Taker> = emptyList()
    private var visibleTakerCount: Int = 0
    private var latestSearchResultCount: Int = 0
    private var latestAppliedRadiusKm: Double = DEFAULT_SEARCH_RADIUS_KM
    private var latestResultExplanation: String? = null
    private var nearbyAlternatives: List<SearchAlternative> = emptyList()
    private var trendingSearches: List<TrendingSearch> = emptyList()
    private var compareMode: Boolean = false
    private var comparedTakers: List<Taker> = emptyList()
    private var lastRecordedSearchKey: String? = null
    private var lastRecordedSearchAt: Long = 0L
    private var suppressSearchCallbacks = false
    private var notificationsSheet: BottomSheetDialog? = null
    private var notificationsSheetBinding: BottomSheetNotificationsBinding? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private val takerDetailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val favoriteChanged = data.getBooleanExtra(TakerDetailActivity.RESULT_FAVORITE_CHANGED, false)
        val reviewChanged = data.getBooleanExtra(TakerDetailActivity.RESULT_REVIEW_CHANGED, false)
        if (!favoriteChanged && !reviewChanged) return@registerForActivityResult

        if (favoriteChanged) refreshFavoriteSnapshot(forceRender = true)
        if (reviewChanged) applyFilters(force = true)
    }

    private val locationPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.any { it.value }) {
            requestCurrentLocation()
        } else {
            toast(getString(R.string.home_location_permission_denied))
            binding.searchView.requestFocus()
            updateSearchSuggestions(liveSearchQuery)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showSystemNotificationsFor(currentNotifications)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        FragmentHomeBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.forceLeftToRightTree()
        setupUI()
        setupCategories()
        setupFeatured()
        setupGrid()
        setupSearch()
        setupFilters()
        setupNotifications()
        observeFavorites()
        observeState()
        observeSearchExtras()
        observePlaceSuggestions()
        applyFilters(force = true)
        viewModel.fetchTrendingSearches()
        checkNetworkState()
    }

    override fun onResume() {
        super.onResume()
        binding.tvGreeting.text = when {
            session.isLoggedIn() && !session.isGuest() -> getString(
                R.string.hello_user,
                session.getUserName().split(" ").firstOrNull().orEmpty(),
            )
            else -> getString(R.string.hello_guest)
        }
        renderHeaderProfile(session.getProfileImageUrl(), session.getProfileThumbUrl())
        if (session.isLoggedIn() && !session.isGuest() && shouldRefreshNotifications()) {
            fetchNotificationsThrottled()
        }
        refreshFavoriteSnapshot()
        checkNetworkState()
        registerNetworkCallback()
    }
    
    override fun onPause() {
        super.onPause()
        unregisterNetworkCallback()
    }
    
    private fun checkNetworkState() {
        val b = _binding ?: return
        val hasInternet = context?.isNetworkAvailable() == true
        val hasReadableCache = currentTakers.isNotEmpty() || currentFeatured.isNotEmpty()
        b.mainContent.isVisible = hasInternet || hasReadableCache
        b.root.findViewById<View>(R.id.layoutNoInternet)?.isVisible = !hasInternet && !hasReadableCache
        val btnRetry = b.root.findViewById<View>(R.id.btnRetryInternet)
        btnRetry?.setOnClickListener {
            checkNetworkState()
            if (context?.isNetworkAvailable() == true) {
                applyFilters(force = true)
            }
        }
    }
    
    private fun registerNetworkCallback() {
        val cm = requireContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                activity?.runOnUiThread { checkNetworkState() }
            }
            override fun onLost(network: android.net.Network) {
                activity?.runOnUiThread { checkNetworkState() }
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

    private fun setupUI() {
        binding.tvGreeting.text = when {
            session.isLoggedIn() && !session.isGuest() -> getString(
                R.string.hello_user,
                session.getUserName().split(" ").firstOrNull().orEmpty(),
            )
            else -> getString(R.string.hello_guest)
        }
        binding.tvFeaturedTitle.text = getString(R.string.home_top_creators_title)

        renderHeaderProfile(session.getProfileImageUrl(), session.getProfileThumbUrl())
        binding.cardHeaderProfile.setOnClickListener { openProfilePage() }
        binding.cardHeaderProfile.contentDescription = getString(R.string.home_profile_button)

        binding.btnNotifications.setOnClickListener { openNotificationsSheet() }
        binding.tvViewAllFeatured.setOnClickListener {
            featuredExpanded = !featuredExpanded
            renderVisibleLists()
        }

        if (session.isTaker()) {
            detailVm.takerState.observe(viewLifecycleOwner) { result ->
                if (result is Result.Success) {
                    renderHeaderProfile(result.data.profileImageUrl, result.data.profileThumbUrl)
                }
            }
            detailVm.fetchTakerProfile(session.getTakerActorId())
        }
    }

    private fun renderHeaderProfile(fullUrl: String?, thumbUrl: String?) {
        val full = fullUrl.toAbsoluteMediaUrl()
        val thumb = thumbUrl.toAbsoluteMediaUrl()
        val main = Glide.with(this)
            .load(full ?: thumb ?: R.drawable.ic_person_placeholder)
            .circleCrop()
            .placeholder(R.drawable.ic_person_placeholder)
            .error(R.drawable.ic_person_placeholder)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
        if (!thumb.isNullOrBlank() && thumb != full) {
            main.thumbnail(
                Glide.with(this)
                    .load(thumb)
                    .circleCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
            )
        }
        main.into(binding.ivHeaderProfile)
    }

    private fun setupCategories() {
        val labels = serviceLabels(requireContext())
        val visibleIndices = SERVICE_TYPES.indices.filter { SERVICE_TYPES[it] != "other" }
        categoryAdapter = CategoryAdapter(
            visibleIndices.map { SERVICE_TYPES[it] },
            visibleIndices.map { labels[it] },
            visibleIndices.map { SERVICE_ICONS[it] },
        ) { service ->
            selectedService = if (selectedService == service) null else service
            categoryAdapter.setSelected(selectedService)
            applyFilters(force = true)
        }
        binding.rvCategories.apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = categoryAdapter
        }
    }

    private fun setupFeatured() {
        featuredAdapter = FeaturedTakerAdapter { openDetail(it) }
        binding.rvFeatured.apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = featuredAdapter
            layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.layout_staggered_fade)
        }
    }

    private fun setupGrid() {
        takerAdapter = TakerAdapter(
            onClick = { openDetail(it) },
            onSocialClick = { openExternalUrl(it) },
            onCompareToggle = { toggleComparedTaker(it) },
        )
        takerGridLayoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvTakers.apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
            layoutManager = takerGridLayoutManager
            adapter = takerAdapter
            setHasFixedSize(false)
            layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.layout_staggered_fade)
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if ((right - left) != (oldRight - oldLeft)) {
                    updateGridColumns(right - left)
                }
            }
        }
        binding.rvTakers.post { updateGridColumns(binding.rvTakers.width) }
        binding.scrollHomeContent.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val content = binding.scrollHomeContent.getChildAt(0) ?: return@setOnScrollChangeListener
            val remaining = content.bottom - (binding.scrollHomeContent.height + scrollY)
            if (remaining < LOAD_MORE_THRESHOLD_PX) {
                loadMoreTakers()
            }
        }
        binding.swipeRefresh.setOnRefreshListener { applyFilters(force = true) }
    }

    private fun openExternalUrl(url: String?) {
        val raw = url?.trim().orEmpty()
        if (raw.isBlank()) return
        val normalized = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            raw
        } else {
            "https://$raw"
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)))
        } catch (_: Exception) {
            requireContext().toast(getString(R.string.could_not_open_link))
        }
    }

    private fun updateGridColumns(widthPx: Int) {
        if (widthPx <= 0) return
        val density = resources.displayMetrics.density
        val widthDp = widthPx / density
        val columns = if (widthDp >= 600f) 3 else 2
        if (takerGridLayoutManager.spanCount != columns) {
            takerGridLayoutManager.spanCount = columns
        }
    }

    private fun setupSearch() {
        searchSuggestionAdapter = SearchSuggestionAdapter { suggestion ->
            applySearchSuggestion(suggestion)
        }
        rebuildSearchSuggestionTrie()
        binding.rvSearchSuggestions.apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
            layoutManager = LinearLayoutManager(requireContext())
            adapter = searchSuggestionAdapter
            setHasFixedSize(true)
        }
        binding.searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                updateSearchSuggestions(liveSearchQuery)
            } else {
                binding.cardSearchSuggestions.isVisible = false
            }
        }
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                liveSearchQuery = query?.trim().orEmpty()
                pendingSearchRunnable?.let(searchHandler::removeCallbacks)
                val parsed = parseSearchQuery(liveSearchQuery)
                selectedLocation = parsed.serverLocationQuery
                fetchRemotePlaceSuggestions(parsed.suggestionLookupQuery)
                binding.cardSearchSuggestions.isVisible = false
                saveRecentSearch(liveSearchQuery)
                renderSearchChips()
                applyFilters(force = true)
                binding.searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(query: String?): Boolean {
                if (suppressSearchCallbacks) return true
                liveSearchQuery = query?.trim().orEmpty()
                renderSearchChips()
                updateSearchSuggestions(liveSearchQuery)
                if (isApplyingSearchSuggestion) {
                    return true
                }
                renderVisibleLists(animate = false)
                scheduleSearchRefresh(liveSearchQuery)
                if (liveSearchQuery.isBlank() && selectedLocation != null) {
                    selectedLocation = null
                    pendingSearchRunnable?.let(searchHandler::removeCallbacks)
                    applyFilters(force = true)
                }
                return true
            }
        })
    }

    private fun applySearchSuggestion(suggestion: SearchSuggestion) {
        if (suggestion.action == ACTION_CURRENT_LOCATION) {
            applyCurrentLocationSuggestion()
            return
        }
        isApplyingSearchSuggestion = true
        val currentParsed = parseSearchQuery(liveSearchQuery)
        val nextQuery = buildQueryForSuggestion(currentParsed, suggestion)
        liveSearchQuery = nextQuery
        selectedLocation = parseSearchQuery(nextQuery).serverLocationQuery
        pendingSearchRunnable?.let(searchHandler::removeCallbacks)
        setSearchText(nextQuery)
        binding.searchView.clearFocus()
        binding.cardSearchSuggestions.isVisible = false
        isApplyingSearchSuggestion = false
        saveRecentSearch(nextQuery)
        renderSearchChips()
        applyFilters(force = true)
    }

    private fun applyCurrentLocationSuggestion() {
        pendingSearchRunnable?.let(searchHandler::removeCallbacks)
        selectedLocation = null
        liveSearchQuery = ""
        setSearchText("")
        renderSearchChips()
        binding.cardSearchSuggestions.isVisible = false
        binding.searchView.clearFocus()
        requestApproximateLocationFeed()
    }

    private fun setSearchText(text: String) {
        suppressSearchCallbacks = true
        binding.searchView.setQuery(text, false)
        suppressSearchCallbacks = false
    }

    private fun buildQueryForSuggestion(current: ParsedSearchQuery, suggestion: SearchSuggestion): String {
        val picked = suggestion.displayQuery.trim()
        if (picked.isBlank()) return picked
        if (suggestion.typeLabel !in setOf("Place", "Pincode") || current.serviceTypes.isEmpty()) {
            return picked
        }
        return "${serviceSearchPhrase(current.serviceTypes)} at $picked"
    }

    private fun scheduleSearchRefresh(query: String) {
        pendingSearchRunnable?.let(searchHandler::removeCallbacks)
        val parsed = parseSearchQuery(query)
        val lookupQuery = parsed.suggestionLookupQuery
        val nextLocation = parsed.serverLocationQuery
        if (query.length < 3 && parsed.serviceTypes.isEmpty()) {
            return
        }
        showInstantSearchFeedback(parsed)
        pendingSearchRunnable = Runnable {
            selectedLocation = nextLocation
            if (!nextLocation.isNullOrBlank()) {
                fetchRemotePlaceSuggestions(lookupQuery)
            }
            applyFilters()
        }.also { searchHandler.postDelayed(it, 180L) }
    }

    private fun fetchRemotePlaceSuggestions(query: String) {
        val cleanQuery = query.trim()
        if (cleanQuery.length < 3) return
        val lookupQuery = canonicalPlaceLookup(cleanQuery)
        val lookupKey = lookupQuery.lowercase()
        if (lookupKey == lastSuggestionLookupKey) return
        lastSuggestionLookupKey = lookupKey
        remotePlaceSuggestionQuery = cleanQuery
        if (lookupQuery.length == 6 && lookupQuery.all { it.isDigit() }) {
            pincodeVm.lookup(lookupQuery)
        } else {
            placeVm.searchPlaces(lookupQuery)
            pincodeVm.searchPlace(lookupQuery)
        }
    }

    private fun setupFilters() {
        binding.btnDateFilter.setOnClickListener {
            val calendar = java.util.Calendar.getInstance()
            val dialog = DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    selectedDate = "%04d-%02d-%02d".format(year, month + 1, day)
                    syncDateFilterUi()
                    applyFilters()
                },
                calendar.get(java.util.Calendar.YEAR),
                calendar.get(java.util.Calendar.MONTH),
                calendar.get(java.util.Calendar.DAY_OF_MONTH)
            )
            val today = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            dialog.datePicker.minDate = today.timeInMillis
            dialog.show()
        }
        binding.btnClearDateFilter.setOnClickListener { clearDateFilter() }
        binding.btnSearchRadius.setOnClickListener { showSearchRadiusPicker() }
        binding.btnExpandSearchRadius.setOnClickListener { expandSearchRadiusManually() }
        binding.btnTrustFilter.setOnClickListener { showTrustFilterPicker() }
        binding.btnRespondsFastFilter.setOnClickListener {
            respondsFastOnly = !respondsFastOnly
            syncAdvancedFilterUi()
            applyFilters(force = true)
        }
        binding.btnCompareMode.setOnClickListener { toggleCompareMode() }
        binding.btnSaveSearchAlert.setOnClickListener { saveCurrentSearchAlert() }
        binding.switchAvailableOnly.setOnCheckedChangeListener { _, isChecked ->
            isAvailableOnly = isChecked
            applyFilters()
        }
        syncDateFilterUi()
        syncRadiusFilterUi()
        syncAdvancedFilterUi()
    }

    private fun clearDateFilter() {
        selectedDate = null
        isAvailableOnly = false
        binding.switchAvailableOnly.isChecked = false
        syncDateFilterUi()
        applyFilters()
    }

    private fun syncDateFilterUi() {
        binding.btnDateFilter.text = selectedDate?.toDisplayDate() ?: getString(R.string.home_date_filter)
        binding.btnClearDateFilter.isVisible = selectedDate != null
        binding.switchAvailableOnly.isVisible = selectedDate != null
    }

    private fun syncRadiusFilterUi(parsed: ParsedSearchQuery = parseSearchQuery(liveSearchQuery)) {
        val hasLocationSearch = !parsed.serverLocationQuery.isNullOrBlank()
        binding.btnSearchRadius.isVisible = hasLocationSearch
        binding.btnSearchRadius.text = getString(R.string.home_search_radius_button, formatRadiusLabel(searchRadiusKm))
        binding.btnSaveSearchAlert.isVisible = hasLocationSearch || parsed.serviceTypes.isNotEmpty()
    }

    private fun syncAdvancedFilterUi() {
        binding.btnTrustFilter.text = when (trustFilter) {
            "verified" -> getString(R.string.home_filter_verified)
            "trusted" -> getString(R.string.home_filter_trusted)
            "pro_verified" -> getString(R.string.home_filter_pro_verified)
            else -> getString(R.string.home_filter_trust)
        }
        binding.btnTrustFilter.isSelected = !trustFilter.isNullOrBlank()
        binding.btnRespondsFastFilter.isSelected = respondsFastOnly
        binding.btnRespondsFastFilter.text = if (respondsFastOnly) {
            getString(R.string.home_filter_responds_fast_on)
        } else {
            getString(R.string.home_filter_responds_fast)
        }
        binding.btnCompareMode.text = if (compareMode) getString(R.string.home_compare_done) else getString(R.string.home_compare)
        styleFilterButton(binding.btnTrustFilter, !trustFilter.isNullOrBlank())
        styleFilterButton(binding.btnRespondsFastFilter, respondsFastOnly)
        styleFilterButton(binding.btnCompareMode, compareMode || comparedTakers.isNotEmpty())
        styleFilterButton(binding.btnSaveSearchAlert, false)
        binding.tvCompareTray.isVisible = compareMode || comparedTakers.isNotEmpty()
        binding.tvCompareTray.text = compareTrayText()
        takerAdapter.updateCompareState(compareMode, comparedTakers.map { it.id }.toSet())
    }

    private fun styleFilterButton(button: MaterialButton, active: Boolean) {
        val fillColor = if (active) {
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        } else {
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
        }
        val textColor = if (active) {
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimary)
        } else {
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        }
        val strokeColor = if (active) {
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        } else {
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant)
        }
        button.backgroundTintList = ColorStateList.valueOf(fillColor)
        button.setTextColor(textColor)
        button.iconTint = ColorStateList.valueOf(textColor)
        button.strokeColor = ColorStateList.valueOf(strokeColor)
    }

    private fun showTrustFilterPicker() {
        val labels = arrayOf(
            getString(R.string.home_filter_trust_any),
            getString(R.string.home_filter_verified),
            getString(R.string.home_filter_trusted),
            getString(R.string.home_filter_pro_verified),
        )
        val values = arrayOf<String?>(null, "verified", "trusted", "pro_verified")
        val checked = values.indexOf(trustFilter).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.home_filter_trust)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                trustFilter = values[which]
                syncAdvancedFilterUi()
                dialog.dismiss()
                applyFilters(force = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toggleCompareMode() {
        compareMode = !compareMode
        syncAdvancedFilterUi()
    }

    private fun toggleComparedTaker(taker: Taker) {
        comparedTakers = if (comparedTakers.any { it.id == taker.id }) {
            comparedTakers.filterNot { it.id == taker.id }
        } else {
            if (comparedTakers.size >= 4) {
                requireContext().toast(getString(R.string.home_compare_limit))
                comparedTakers
            } else {
                comparedTakers + taker
            }
        }
        syncAdvancedFilterUi()
        if (comparedTakers.size >= 2) {
            showCompareDialog()
        }
    }

    private fun compareTrayText(): String =
        when {
            comparedTakers.isEmpty() -> getString(R.string.home_compare_empty)
            comparedTakers.size == 1 -> getString(R.string.home_compare_one, comparedTakers.first().fullName)
            else -> getString(R.string.home_compare_many, comparedTakers.size, comparedTakers.joinToString(", ") { it.fullName })
        }

    private fun showCompareDialog() {
        val message = comparedTakers.joinToString("\n\n") { taker ->
            val services = taker.offeredServices.take(3).joinToString(", ") { it.toServiceLabel(requireContext()) }
            buildString {
                append(taker.fullName)
                append("\n")
                append(listOfNotNull(
                    taker.trustLabel,
                    taker.distanceKm?.let { "${formatRadiusLabel(it)} away" },
                    if (taker.respondsFast) getString(R.string.home_filter_responds_fast) else null,
                    if (taker.avgRating > 0) "%.1f rating".format(taker.avgRating) else null,
                    services.ifBlank { null },
                ).joinToString(" | "))
                taker.searchExplanation?.takeIf { it.isNotBlank() }?.let {
                    append("\n")
                    append(it)
                }
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.home_compare_creators)
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .setNegativeButton(R.string.home_compare_clear) { _, _ ->
                comparedTakers = emptyList()
                syncAdvancedFilterUi()
            }
            .show()
    }

    private fun saveCurrentSearchAlert() {
        if (!session.isLoggedIn() || session.isGuest()) {
            requireContext().toast(getString(R.string.sign_in_to_book))
            openProfilePage()
            return
        }
        val parsed = parseSearchQuery(liveSearchQuery)
        val serviceTypes = selectedService?.let { listOf(it) } ?: parsed.serviceTypes.toList()
        viewModel.saveSearchAlert(
            SearchAlertRequest(
                queryText = liveSearchQuery,
                locationText = parsed.serverLocationQuery,
                serviceTypes = serviceTypes,
                serviceMatchMode = parsed.serviceMatchMode,
                radiusKm = searchRadiusKm.coerceAtMost(MAX_SEARCH_RADIUS_KM),
                filters = currentSearchFiltersMap(),
            )
        )
    }

    private fun currentSearchFiltersMap(): Map<String, String> =
        buildMap {
            selectedDate?.let { put("date", it) }
            put("availableOnly", isAvailableOnly.toString())
            trustFilter?.let { put("trustFilter", it) }
            put("respondsFastOnly", respondsFastOnly.toString())
            put("radiusKm", "%.1f".format(java.util.Locale.US, searchRadiusKm))
        }

    private fun recordSearchResult(resultCount: Int) {
        val parsed = parseSearchQuery(liveSearchQuery)
        if (parsed.raw.isBlank() && parsed.serverLocationQuery.isNullOrBlank() && parsed.serviceTypes.isEmpty()) return
        val eventKey = listOf(
            liveSearchQuery.trim().lowercase(),
            parsed.serverLocationQuery.orEmpty().lowercase(),
            selectedService.orEmpty(),
            parsed.serviceTypes.sorted().joinToString(","),
            parsed.serviceMatchMode,
            trustFilter.orEmpty(),
            respondsFastOnly.toString(),
            isAvailableOnly.toString(),
            selectedDate.orEmpty(),
            "%.1f".format(java.util.Locale.US, searchRadiusKm),
            resultCount.toString(),
        ).joinToString("|")
        val now = System.currentTimeMillis()
        if (eventKey == lastRecordedSearchKey && now - lastRecordedSearchAt < 30_000L) return
        lastRecordedSearchKey = eventKey
        lastRecordedSearchAt = now
        viewModel.recordSearchEvent(
            SearchEventRequest(
                eventType = "search",
                queryText = liveSearchQuery,
                locationText = parsed.serverLocationQuery,
                serviceTypes = (selectedService?.let { listOf(it) } ?: parsed.serviceTypes.toList()),
                serviceMatchMode = parsed.serviceMatchMode,
                requestedRadiusKm = searchRadiusKm,
                appliedRadiusKm = latestAppliedRadiusKm,
                resultCount = resultCount,
                filters = currentSearchFiltersMap(),
            )
        )
    }

    private fun showSearchRadiusPicker() {
        val labels = SEARCH_RADIUS_OPTIONS_KM.map(::formatRadiusLabel).toTypedArray()
        val checked = SEARCH_RADIUS_OPTIONS_KM
            .indexOfFirst { kotlin.math.abs(it - searchRadiusKm) < 0.1 }
            .coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.home_search_radius_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                searchRadiusKm = SEARCH_RADIUS_OPTIONS_KM[which].coerceAtMost(MAX_SEARCH_RADIUS_KM)
                syncRadiusFilterUi()
                dialog.dismiss()
                applyFilters(force = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun expandSearchRadiusManually() {
        val nextRadius = nextSearchRadius(searchRadiusKm) ?: return
        searchRadiusKm = nextRadius
        syncRadiusFilterUi()
        applyFilters(force = true)
    }

    private fun setupNotifications() {
        notificationAdapter = NotificationAdapter { onNotificationTapped(it) }

        notificationsVm.notificationsState.observe(viewLifecycleOwner) { result ->
            when (result) {
                is Result.Loading -> {
                    notificationsSheetBinding?.progressNotificationsSheet?.isVisible = true
                }
                is Result.Success -> {
                    currentNotifications = result.data.notifications
                    currentUnreadCount = result.data.unreadCount
                    lastNotificationsFetchAt = System.currentTimeMillis()
                    bindNotificationBadge(currentUnreadCount)
                    handleSystemNotificationsFor(currentNotifications)
                    bindNotificationsSheet(
                        loading = false,
                        notifications = currentNotifications,
                        unreadCount = currentUnreadCount,
                        emptyMessage = getString(R.string.notifications_none),
                    )
                }
                is Result.Error -> {
                    bindNotificationBadge(currentUnreadCount)
                    bindNotificationsSheet(
                        loading = false,
                        notifications = currentNotifications,
                        unreadCount = currentUnreadCount,
                        emptyMessage = result.message,
                    )
                }
            }
        }

        notificationsVm.markReadState.observe(viewLifecycleOwner) { result ->
            when (result) {
                is Result.Success -> {
                    if (!result.data.markAll && result.data.notificationId > 0) {
                        markNotificationReadLocally(result.data.notificationId)
                    }
                    fetchNotificationsThrottled(force = true)
                }
                is Result.Error -> {
                    requireContext().toast(result.message)
                    fetchNotificationsThrottled(force = true)
                }
                Result.Loading -> Unit
            }
        }
    }

    private fun shouldRefreshNotifications(): Boolean =
        System.currentTimeMillis() - lastNotificationsFetchAt > 30_000L

    private fun fetchNotificationsThrottled(force: Boolean = false) {
        if (!force && !shouldRefreshNotifications()) return
        lastNotificationsFetchAt = System.currentTimeMillis()
        notificationsVm.fetchNotifications(force = force)
    }

    private fun bindNotificationBadge(unreadCount: Int) {
        binding.tvNotificationBadge.isVisible = unreadCount > 0
        binding.tvNotificationBadge.text = when {
            unreadCount > 99 -> "99+"
            unreadCount > 0 -> unreadCount.toString()
            else -> ""
        }
    }

    private fun handleSystemNotificationsFor(notifications: List<NotificationItem>) {
        loadShownSystemNotificationIds()
        if (!systemNotificationInitialSyncDone) {
            val existingUnreadIds = notifications
                .asSequence()
                .filter { !it.isRead && it.id > 0 }
                .map { it.id }
                .toList()
            if (shownSystemNotificationIds.addAll(existingUnreadIds)) {
                saveShownSystemNotificationIds()
            }
            systemNotificationInitialSyncDone = true
            return
        }
        showSystemNotificationsFor(notifications)
    }

    private fun showSystemNotificationsFor(notifications: List<NotificationItem>) {
        val freshUnread = notifications
            .asSequence()
            .filter { !it.isRead && it.id > 0 && it.id !in shownSystemNotificationIds }
            .take(3)
            .toList()
        if (freshUnread.isEmpty()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        val manager = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_BOOKINGS,
                    getString(R.string.notifications_channel_bookings),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }

        freshUnread.forEach { notification ->
            val intent = Intent(requireContext(), requireActivity()::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                val eventId = notification.payload.intValue("event_id")
                val bookingId = notification.payload.intValue("booking_id")
                if (notification.type in eventNotificationTypes && (eventId > 0 || bookingId > 0)) {
                    putExtra("NAV_TO_BOOKINGS", true)
                    if (eventId > 0) putExtra(BookingsFragment.EXTRA_NAV_EVENT_ID, eventId)
                    if (bookingId > 0) putExtra(BookingsFragment.EXTRA_NAV_BOOKING_ID, bookingId)
                }
            }
            val pendingIntent = PendingIntent.getActivity(
                requireContext(),
                notification.id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val systemNotification = NotificationCompat.Builder(requireContext(), NOTIFICATION_CHANNEL_BOOKINGS)
                .setSmallIcon(R.drawable.ic_notifications)
                .setContentTitle(notification.title)
                .setContentText(notification.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notification.message))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            manager.notify(NOTIFICATION_ID_BASE + notification.id, systemNotification)
            shownSystemNotificationIds.add(notification.id)
        }
        saveShownSystemNotificationIds()
    }

    private fun loadShownSystemNotificationIds() {
        if (shownSystemNotificationIds.isNotEmpty()) return
        val stored = requireContext()
            .getSharedPreferences(NOTIFICATION_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SHOWN_NOTIFICATION_IDS, "")
            .orEmpty()
        stored
            .split(',')
            .mapNotNull { it.toIntOrNull() }
            .forEach { shownSystemNotificationIds.add(it) }
    }

    private fun saveShownSystemNotificationIds() {
        val compactIds = shownSystemNotificationIds
            .asSequence()
            .sortedDescending()
            .take(200)
            .toList()
        shownSystemNotificationIds.clear()
        shownSystemNotificationIds.addAll(compactIds)
        requireContext()
            .getSharedPreferences(NOTIFICATION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SHOWN_NOTIFICATION_IDS, compactIds.joinToString(","))
            .apply()
    }

    private fun cancelSystemNotification(notificationId: Int) {
        if (notificationId <= 0) return
        val manager = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID_BASE + notificationId)
        shownSystemNotificationIds.add(notificationId)
        saveShownSystemNotificationIds()
    }

    private fun cancelSystemNotifications(notifications: List<NotificationItem>) {
        val manager = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.forEach { notification ->
            if (notification.id > 0) {
                manager.cancel(NOTIFICATION_ID_BASE + notification.id)
                shownSystemNotificationIds.add(notification.id)
            }
        }
        saveShownSystemNotificationIds()
    }

    private fun bindNotificationsSheet(
        loading: Boolean,
        notifications: List<NotificationItem>,
        unreadCount: Int,
        emptyMessage: String,
    ) {
        val sheetBinding = notificationsSheetBinding ?: return
        sheetBinding.progressNotificationsSheet.isVisible = loading
        sheetBinding.tvNotificationUnread.isVisible = unreadCount > 0
        sheetBinding.btnMarkAllNotificationsRead.isVisible = unreadCount > 0
        sheetBinding.btnMarkAllNotificationsRead.isEnabled = !loading && unreadCount > 0
        if (unreadCount > 0) {
            sheetBinding.tvNotificationUnread.text = getString(R.string.notifications_unread_count, unreadCount)
        }
        notificationAdapter.submitList(notifications)
        sheetBinding.rvNotificationsSheet.isVisible = notifications.isNotEmpty()
        sheetBinding.tvEmptyNotificationsSheet.isVisible = !loading && notifications.isEmpty()
        sheetBinding.tvEmptyNotificationsSheet.text = emptyMessage
    }

    private fun openNotificationsSheet() {
        if (!session.isLoggedIn() || session.isGuest()) {
            requireContext().toast(getString(R.string.home_sign_in_for_notifications))
            openProfilePage()
            return
        }

        if (notificationsSheet == null) {
            val sheetBinding = BottomSheetNotificationsBinding.inflate(layoutInflater)
            notificationsSheetBinding = sheetBinding
            sheetBinding.rvNotificationsSheet.layoutManager = LinearLayoutManager(requireContext())
            sheetBinding.rvNotificationsSheet.adapter = notificationAdapter
            sheetBinding.btnMarkAllNotificationsRead.setOnClickListener {
                markAllNotificationsReadLocally()
                notificationsVm.markAllRead()
            }
            notificationsSheet = BottomSheetDialog(requireContext()).apply {
                setContentView(sheetBinding.root)
                setOnDismissListener {
                    notificationsSheet = null
                    notificationsSheetBinding = null
                }
            }
        }

        bindNotificationsSheet(
            loading = currentNotifications.isEmpty(),
            notifications = currentNotifications,
            unreadCount = currentUnreadCount,
            emptyMessage = getString(R.string.notifications_none),
        )
        notificationsSheet?.show()
        fetchNotificationsThrottled(force = true)
    }

    private fun onNotificationTapped(notification: NotificationItem) {
        showNotificationMessage(notification)
        if (!notification.isRead) {
            markNotificationReadLocally(notification.id)
            searchHandler.postDelayed({ notificationsVm.markRead(notification.id) }, 500L)
        }
    }

    private fun markAllNotificationsReadLocally() {
        if (currentUnreadCount <= 0) return
        cancelSystemNotifications(currentNotifications)
        currentNotifications = currentNotifications.map { it.copy(isRead = true) }
        currentUnreadCount = 0
        bindNotificationBadge(0)
        bindNotificationsSheet(
            loading = false,
            notifications = currentNotifications,
            unreadCount = 0,
            emptyMessage = getString(R.string.notifications_none),
        )
    }

    private fun markNotificationReadLocally(notificationId: Int) {
        if (notificationId <= 0 || currentNotifications.none { it.id == notificationId && !it.isRead }) return
        cancelSystemNotification(notificationId)
        currentNotifications = currentNotifications.map { notification ->
            if (notification.id == notificationId) notification.copy(isRead = true) else notification
        }
        currentUnreadCount = currentNotifications.count { !it.isRead }
        bindNotificationBadge(currentUnreadCount)
        bindNotificationsSheet(
            loading = false,
            notifications = currentNotifications,
            unreadCount = currentUnreadCount,
            emptyMessage = getString(R.string.notifications_none),
        )
    }

    private fun showNotificationMessage(notification: NotificationItem) {
        val eventId = notification.payload.intValue("event_id")
        val bookingId = notification.payload.intValue("booking_id")
        val canOpenEvent = notification.type in eventNotificationTypes && (eventId > 0 || bookingId > 0)
        val message = buildString {
            append(notification.message)
            appendLine()
            appendLine()
            append(notification.createdAt.replace('T', ' '))
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(notification.title)
            .setMessage(message)
            .setNegativeButton(R.string.close, null)
        if (canOpenEvent) {
            dialog.setPositiveButton(R.string.view_event) { _, _ ->
                notificationsSheet?.dismiss()
                openOrdersPage(eventId = eventId, bookingId = bookingId)
            }
        }
        dialog.show()
    }

    private fun openDetailFromNotification(notification: NotificationItem) {
        val payload = notification.payload
        val takerId = payload.intValue("taker_id")
        if (takerId <= 0) return
        val takerName = payload.stringValue("taker_name")
        val service = payload.stringValue("service_type")
        val city = payload.stringValue("city")
        val imageUrl = payload.stringValue("profile_image_url")
        val thumbUrl = payload.stringValue("profile_thumb_url")

        takerDetailLauncher.launch(Intent(requireContext(), TakerDetailActivity::class.java).apply {
            putExtra(TakerDetailActivity.EXTRA_ID, takerId)
            putExtra(TakerDetailActivity.EXTRA_NAME, takerName)
            putExtra(TakerDetailActivity.EXTRA_SVC, service)
            putStringArrayListExtra(
                TakerDetailActivity.EXTRA_SERVICES,
                arrayListOf(service).filter { it.isNotBlank() }.let(::ArrayList)
            )
            putExtra(TakerDetailActivity.EXTRA_CITY, city)
            putExtra(TakerDetailActivity.EXTRA_IMG, imageUrl)
            putExtra(TakerDetailActivity.EXTRA_THUMB, thumbUrl)
        })
    }

    private fun applyFilters(force: Boolean = false) {
        val parsed = parseSearchQuery(liveSearchQuery)
        val serviceFromSearch = parsed.backendService
        val effectiveService = selectedService ?: serviceFromSearch
        val effectiveServices = selectedService?.let { listOf(it) } ?: parsed.serviceTypes.toList()
        val effectiveLocation = parsed.serverLocationQuery
        val hasCoordinateSearch = deviceLatitude != null && deviceLongitude != null
        val activeSearchRadius = searchRadiusKm.takeIf {
            !effectiveLocation.isNullOrBlank() || hasCoordinateSearch
        }
        val filterKey = listOf(
            effectiveLocation.orEmpty().lowercase(),
            selectedDate.orEmpty(),
            effectiveService.orEmpty(),
            effectiveServices.sorted().joinToString(","),
            parsed.serviceMatchMode,
            trustFilter.orEmpty(),
            respondsFastOnly.toString(),
            isAvailableOnly.toString(),
            deviceLatitude?.let { "%.4f".format(java.util.Locale.US, it) }.orEmpty(),
            deviceLongitude?.let { "%.4f".format(java.util.Locale.US, it) }.orEmpty(),
            activeSearchRadius?.let { "%.1f".format(java.util.Locale.US, it) }.orEmpty(),
        ).joinToString("|")
        if (!force && filterKey == lastAppliedFilterKey) return
        lastAppliedFilterKey = filterKey
        selectedLocation = effectiveLocation
        takerAdapter.updateAvailabilityDate(selectedDate)
        takerAdapter.updateFavorites(favoriteTakerIds)
        viewModel.fetch(
            location = effectiveLocation,
            date_ = selectedDate,
            service = effectiveService,
            serviceTypes = effectiveServices,
            serviceMatchMode = parsed.serviceMatchMode,
            trustFilter = trustFilter,
            respondsFastOnly = respondsFastOnly,
            availOnly = isAvailableOnly,
            latitude = deviceLatitude,
            longitude = deviceLongitude,
            radiusKm = activeSearchRadius?.coerceAtMost(MAX_SEARCH_RADIUS_KM),
            force = force,
        )
    }

    private fun observeFavorites() {
        if (!session.isLoggedIn() || session.isGuest()) {
            hasFavoriteSnapshot = true
            favoriteTakerIds = emptySet()
            takerAdapter.updateFavorites(favoriteTakerIds)
            featuredAdapter.updateFavorites(favoriteTakerIds)
            return
        }
        favoriteRepo.getFavorites(session.getFavoriteActorId()).observe(viewLifecycleOwner) { list ->
            hasFavoriteSnapshot = true
            favoriteTakerIds = list.map { it.takerId }.toSet()
            applyFavoriteSnapshotToCurrentLists()
            takerAdapter.updateFavorites(favoriteTakerIds)
            featuredAdapter.updateFavorites(favoriteTakerIds)
            if (currentTakers.isNotEmpty()) {
                currentFeatured = buildFeaturedSource(currentTakers)
                renderVisibleLists(animate = false)
            }
        }
    }

    private fun observeState() {
        viewModel.state.observe(viewLifecycleOwner) { result ->
            binding.swipeRefresh.isRefreshing = false
            when (result) {
                is Result.Loading -> {
                    binding.layoutEmpty.hide()
                    if (currentTakers.isEmpty()) {
                        binding.shimmerLayout.startShimmer()
                        binding.shimmerLayout.show()
                        binding.rvTakers.hide()
                    }
                }
                is Result.Success -> {
                    binding.shimmerLayout.stopShimmer()
                    binding.shimmerLayout.hide()
                    binding.rvTakers.show()

                    val appendingPage = result.data.page > 1
                    latestSearchResultCount = result.data.total
                    latestAppliedRadiusKm = result.data.searchRadiusKm ?: searchRadiusKm
                    latestResultExplanation = result.data.resultExplanation
                    nearbyAlternatives = result.data.nearbyAlternatives
                    if (!appendingPage && result.data.searchRadiusKm != null && result.data.searchRadiusKm > searchRadiusKm) {
                        searchRadiusKm = result.data.searchRadiusKm.coerceAtMost(MAX_SEARCH_RADIUS_KM)
                        syncRadiusFilterUi()
                    }
                    currentTakers = withoutOwnTaker(result.data.takers)
                    if (hasFavoriteSnapshot) {
                        applyFavoriteSnapshotToCurrentLists()
                    }
                    syncServerFavoriteHints(result.data.takers + result.data.featured)
                    val featuredSource = (withoutOwnTaker(result.data.featured) + currentTakers)
                        .distinctBy { it.id }
                        .map { taker ->
                            if (hasFavoriteSnapshot) {
                                taker.copy(viewerHasFavorited = favoriteTakerIds.contains(taker.id))
                            } else {
                                taker
                            }
                        }
                    currentFeatured = buildFeaturedSource(featuredSource)
                    rebuildSearchSuggestionTrie()
                    if (!appendingPage && maybeAutoExpandRadius(currentTakers.size)) {
                        return@observe
                    }
                    if (!appendingPage) {
                        recordSearchResult(result.data.total)
                    }
                    if (appendingPage && visibleTakerCount > 0) {
                        visibleTakerCount = minOf(currentTakers.size, visibleTakerCount + TAKER_BATCH_STEP)
                    }
                    val hasNearbyCreators = currentFeatured.any { it.proximityLabel in setOf("Same pincode", "Nearby area", "Same city") }
                    binding.tvFeaturedTitle.text = if (selectedLocation.isNullOrBlank() && hasNearbyCreators) {
                        getString(R.string.home_top_near_you_title)
                    } else {
                        getString(R.string.home_top_creators_title)
                    }
                    renderVisibleLists(animate = true, resetPagination = !appendingPage)
                    updateSearchSuggestions(liveSearchQuery)
                    checkNetworkState()
                }
                is Result.Error -> {
                    binding.shimmerLayout.stopShimmer()
                    binding.shimmerLayout.hide()
                    if (currentTakers.isEmpty() && currentFeatured.isEmpty()) {
                        renderVisibleLists(animate = false, resetPagination = true)
                        binding.layoutEmpty.show()
                    } else {
                        binding.rvTakers.show()
                    }
                    checkNetworkState()
                    toast(result.message)
                }
            }
        }
    }

    private fun observeSearchExtras() {
        viewModel.trendingState.observe(viewLifecycleOwner) { result ->
            if (result is Result.Success) {
                trendingSearches = result.data.trending
                updateSearchSuggestions(liveSearchQuery)
            }
        }
        viewModel.searchAlertState.observe(viewLifecycleOwner) { result ->
            when (result) {
                is Result.Success -> requireContext().toast(getString(R.string.home_search_alert_saved))
                is Result.Error -> requireContext().toast(result.message)
                Result.Loading -> Unit
            }
        }
    }

    private fun observePlaceSuggestions() {
        pincodeVm.result.observe(viewLifecycleOwner) { postOffices ->
            postalPlaceSuggestions = buildPostalPlaceSuggestions(postOffices, remotePlaceSuggestionQuery)
            updateSearchSuggestions(liveSearchQuery)
        }
        placeVm.places.observe(viewLifecycleOwner) { places ->
            geoPlaceSuggestions = buildGeoPlaceSuggestions(places, remotePlaceSuggestionQuery)
            updateSearchSuggestions(liveSearchQuery)
        }
    }

    private fun renderVisibleLists(animate: Boolean = false, resetPagination: Boolean = false) {
        val parsed = parseSearchQuery(liveSearchQuery)
        syncRadiusFilterUi(parsed)
        val useServerExpandedResults = parsed.personQuery.isBlank() &&
            !selectedLocation.isNullOrBlank() &&
            parsed.serverLocationQuery.equals(selectedLocation, ignoreCase = true)
        val filteredTakers = if (useServerExpandedResults) {
            filterTakersByService(currentTakers, parsed.serviceTypes)
        } else {
            filterTakers(currentTakers, liveSearchQuery)
        }
        val featuredSource = if (useServerExpandedResults) {
            filterTakersByService(currentFeatured, parsed.serviceTypes)
        } else {
            filterTakers(currentFeatured, liveSearchQuery)
        }
        val filteredFeatured = if (featuredExpanded) featuredSource else featuredSource.take(6)
        renderSearchStatus(filteredTakers, parsed)
        renderNearbyAlternatives()
        bindBrowseHeader(parsed, filteredTakers.size)
        filteredTakersForDisplay = filteredTakers
        if (resetPagination || visibleTakerCount == 0 || visibleTakerCount > filteredTakers.size) {
            visibleTakerCount = minOf(filteredTakers.size, INITIAL_TAKER_BATCH)
        } else if (filteredTakers.size < visibleTakerCount) {
            visibleTakerCount = filteredTakers.size
        }
        takerAdapter.submitList(filteredTakers.take(visibleTakerCount))
        takerAdapter.updateAvailabilityDate(selectedDate)
        takerAdapter.updateFavorites(favoriteTakerIds)
        takerAdapter.updateCompareState(compareMode, comparedTakers.map { it.id }.toSet())
        featuredAdapter.submitList(filteredFeatured)
        featuredAdapter.updateFavorites(favoriteTakerIds)
        val hasFeaturedCards = filteredFeatured.isNotEmpty()
        binding.sectionFeatured.isVisible = hasFeaturedCards
        binding.rvFeatured.isVisible = hasFeaturedCards
        binding.tvViewAllFeatured.isVisible = hasFeaturedCards && featuredSource.size > 6
        binding.tvViewAllFeatured.text = getString(if (featuredExpanded) R.string.show_less else R.string.view_all)
        binding.layoutEmpty.isVisible = filteredTakers.isEmpty() &&
            binding.shimmerLayout.visibility != View.VISIBLE &&
            parsed.serverLocationQuery.isNullOrBlank()
        if (animate) {
            binding.rvFeatured.scheduleLayoutAnimation()
            binding.rvTakers.scheduleLayoutAnimation()
        }
        binding.scrollHomeContent.post { loadMoreTakersIfNeeded() }
    }

    private fun loadMoreTakers() {
        if (visibleTakerCount >= filteredTakersForDisplay.size) {
            viewModel.loadNextPage()
            return
        }
        visibleTakerCount = minOf(filteredTakersForDisplay.size, visibleTakerCount + TAKER_BATCH_STEP)
        takerAdapter.submitList(filteredTakersForDisplay.take(visibleTakerCount))
        takerAdapter.updateFavorites(favoriteTakerIds)
        binding.scrollHomeContent.post { loadMoreTakersIfNeeded() }
    }

    private fun loadMoreTakersIfNeeded() {
        val b = _binding ?: return
        val content = b.scrollHomeContent.getChildAt(0) ?: return
        val remaining = content.bottom - (b.scrollHomeContent.height + b.scrollHomeContent.scrollY)
        if (remaining < LOAD_MORE_THRESHOLD_PX && visibleTakerCount < filteredTakersForDisplay.size) {
            loadMoreTakers()
        }
    }

    private fun buildFeaturedSource(source: List<Taker>): List<Taker> {
        return source.sortedWith(
            compareByDescending<Taker> {
                if (isTakerFavorite(it)) 1 else 0
            }
                .thenByDescending { if (it.isTopTaker) 1 else 0 }
                .thenByDescending { it.rankingScore }
                .thenByDescending { it.favoriteCount }
                .thenByDescending { it.postReach }
                .thenByDescending { it.activePostCount }
                .thenByDescending { it.reviewCount }
                .thenByDescending { it.avgRating }
                .thenByDescending { it.postCount }
        )
    }

    private fun withoutOwnTaker(source: List<Taker>): List<Taker> {
        if (!session.isTaker()) return source
        val ownTakerId = session.getTakerActorId()
        return source.filterNot { it.id == ownTakerId }
    }

    private fun isTakerFavorite(taker: Taker): Boolean =
        favoriteTakerIds.contains(taker.id) || taker.viewerHasFavorited

    private fun refreshFavoriteSnapshot(forceRender: Boolean = false) {
        if (!session.isLoggedIn() || session.isGuest()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val ids = favoriteRepo.getFavoriteIdsOnce(session.getFavoriteActorId())
            if (forceRender || ids != favoriteTakerIds) {
                hasFavoriteSnapshot = true
                favoriteTakerIds = ids
                applyFavoriteSnapshotToCurrentLists()
                takerAdapter.updateFavorites(favoriteTakerIds)
                featuredAdapter.updateFavorites(favoriteTakerIds)
                if (currentTakers.isNotEmpty()) {
                    currentFeatured = buildFeaturedSource((currentFeatured + currentTakers).distinctBy { it.id })
                    renderVisibleLists(animate = false)
                }
            }
        }
    }

    private fun syncServerFavoriteHints(takers: List<Taker>) {
        if (!session.isLoggedIn() || session.isGuest()) return
        val serverFavorites = takers.filter { it.viewerHasFavorited }
        if (serverFavorites.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            favoriteRepo.syncServerFavorites(session.getFavoriteActorId(), serverFavorites)
        }
    }

    private fun applyFavoriteSnapshotToCurrentLists() {
        if (!hasFavoriteSnapshot) return
        fun Taker.withFavoriteSnapshot(): Taker =
            copy(viewerHasFavorited = favoriteTakerIds.contains(id))
        currentTakers = currentTakers.map { it.withFavoriteSnapshot() }
        currentFeatured = currentFeatured.map { it.withFavoriteSnapshot() }
    }

    private fun bindBrowseHeader(parsed: ParsedSearchQuery, resultCount: Int) {
        val serviceLabel = selectedService
            ?.toServiceLabel(requireContext())
            ?: parsed.serviceTypes.singleOrNull()?.toServiceLabel(requireContext())
        val location = parsed.serverLocationQuery?.trim().orEmpty()
        when {
            location.isNotBlank() -> {
                binding.tvBrowseTitle.text = getString(R.string.home_browse_search_title, location)
                binding.tvBrowseSubtitle.text = getString(
                    R.string.home_browse_subtitle_search,
                    resultCount,
                    formatRadiusLabel(searchRadiusKm),
                )
            }
            !serviceLabel.isNullOrBlank() -> {
                binding.tvBrowseTitle.text = getString(R.string.home_browse_service_title, serviceLabel)
                binding.tvBrowseSubtitle.text = getString(
                    R.string.home_browse_subtitle_service,
                    resultCount,
                    formatRadiusLabel(searchRadiusKm),
                )
            }
            else -> {
                binding.tvBrowseTitle.text = getString(R.string.home_browse_section_title)
                binding.tvBrowseSubtitle.text = getString(R.string.home_browse_subtitle_all)
            }
        }
    }

    private fun showInstantSearchFeedback(parsed: ParsedSearchQuery) {
        val location = parsed.serverLocationQuery?.trim().orEmpty()
        if (location.isBlank()) return
        binding.tvSearchStatus.text = getString(
            R.string.home_searching_radius,
            location,
            formatRadiusLabel(searchRadiusKm),
        )
        binding.tvSearchStatus.isVisible = true
    }

    private fun maybeAutoExpandRadius(resultCount: Int): Boolean {
        val parsed = parseSearchQuery(liveSearchQuery)
        val location = parsed.serverLocationQuery?.trim().orEmpty()
        val nextRadius = nextSearchRadius(searchRadiusKm)
        if (location.isBlank() || resultCount >= MIN_SEARCH_RESULTS_BEFORE_STOP || nextRadius == null) {
            return false
        }
        val previousRadius = searchRadiusKm
        searchRadiusKm = nextRadius
        syncRadiusFilterUi(parsed)
        binding.btnExpandSearchRadius.isVisible = false
        binding.tvSearchStatus.text = getString(
            R.string.home_auto_expanding_radius,
            resultCount,
            formatRadiusLabel(previousRadius),
            formatRadiusLabel(nextRadius),
        )
        binding.tvSearchStatus.isVisible = true
        applyFilters(force = true)
        return true
    }

    private fun renderSearchStatus(items: List<Taker>, parsed: ParsedSearchQuery) {
        val location = parsed.serverLocationQuery?.trim().orEmpty()
        if (location.isBlank()) {
            binding.tvSearchStatus.isVisible = false
            binding.btnExpandSearchRadius.isVisible = false
            binding.chipGroupNearbyAlternatives.isVisible = false
            return
        }
        val nextRadius = nextSearchRadius(searchRadiusKm)
        binding.btnExpandSearchRadius.isVisible = nextRadius != null
        binding.btnExpandSearchRadius.text = nextRadius?.let {
            getString(R.string.home_expand_radius_button, formatRadiusLabel(it))
        } ?: getString(R.string.home_expand_radius_button, formatRadiusLabel(MAX_SEARCH_RADIUS_KM))
        if (items.isEmpty()) {
            binding.tvSearchStatus.text = latestResultExplanation
                ?: getString(R.string.home_search_no_results_radius, location, formatRadiusLabel(searchRadiusKm))
            binding.tvSearchStatus.isVisible = binding.shimmerLayout.visibility != View.VISIBLE
            return
        }
        val variants = searchQueryVariants(location)
        val hasExact = items.any { taker ->
            val exactValues = listOf(taker.city, taker.state, taker.pincode)
            exactValues.any { value ->
                val normalized = value.trim().lowercase()
                variants.any { variant -> normalized == variant }
            }
        }
        binding.tvSearchStatus.text = latestResultExplanation ?: when {
            !hasExact -> getString(R.string.home_searching_nearby_fallback, location)
            items.size < MIN_SEARCH_RESULTS_BEFORE_STOP && nextRadius != null -> getString(R.string.home_expand_radius_hint)
            else -> getString(R.string.home_searching_radius, location, formatRadiusLabel(searchRadiusKm))
        }
        binding.tvSearchStatus.isVisible = true
    }

    private fun renderNearbyAlternatives() {
        binding.chipGroupNearbyAlternatives.removeAllViews()
        if (nearbyAlternatives.isEmpty()) {
            binding.chipGroupNearbyAlternatives.isVisible = false
            return
        }
        nearbyAlternatives.take(5).forEach { alternative ->
            val chip = Chip(requireContext()).apply {
                text = buildString {
                    append(alternative.title)
                    alternative.distanceKm?.let { append(" • ${formatRadiusLabel(it)}") }
                    if (alternative.creatorCount > 0) append(" • ${alternative.creatorCount}")
                }
                isCheckable = false
                setOnClickListener {
                    liveSearchQuery = alternative.query
                    selectedLocation = alternative.query
                    setSearchText(alternative.query)
                    saveRecentSearch(alternative.query)
                    applyFilters(force = true)
                }
            }
            binding.chipGroupNearbyAlternatives.addView(chip)
        }
        binding.chipGroupNearbyAlternatives.isVisible = true
    }

    private fun nextSearchRadius(currentRadius: Double): Double? =
        SEARCH_RADIUS_OPTIONS_KM.firstOrNull { it > currentRadius + 0.1 }

    private fun formatRadiusLabel(km: Double): String =
        when {
            km < 1.0 -> "1 km"
            km < 10.0 -> "${kotlin.math.ceil(km).toInt()} km"
            km < 100.0 -> "${(kotlin.math.ceil(km / 5.0) * 5).toInt()} km"
            else -> "${(kotlin.math.ceil(km / 25.0) * 25).toInt()} km"
        }

    private fun filterTakers(source: List<Taker>, query: String): List<Taker> {
        val parsed = parseSearchQuery(query)
        if (!parsed.serverLocationQuery.isNullOrBlank() && parsed.personQuery.isBlank()) {
            return filterTakersByService(source, parsed.serviceTypes)
        }
        val text = parsed.localFilterQuery
        val queryVariants = searchQueryVariants(text)
        val serviceFiltered = filterTakersByService(source, parsed.serviceTypes)
        if (queryVariants.isEmpty()) {
            return serviceFiltered
        }
        return serviceFiltered.filter { taker ->
            val searchable = listOf(
                taker.fullName,
                taker.city,
                taker.state,
                taker.pincode,
            ) + taker.offeredServices
            searchable.any { value ->
                val normalizedValue = value.lowercase()
                queryVariants.any { variant -> normalizedValue.contains(variant) }
            }
        }
    }

    private fun filterTakersByService(source: List<Taker>, serviceTypes: Set<String>): List<Taker> {
        if (serviceTypes.isEmpty() || selectedService != null) return source
        return source.filter { taker -> taker.offeredServices.any { it in serviceTypes } }
    }

    private fun renderSearchChips() {
        val parsed = parseSearchQuery(liveSearchQuery)
        val chipData = mutableListOf<Pair<String, () -> Unit>>()
        if (parsed.serviceTypes.isNotEmpty()) {
            chipData += serviceChipLabel(parsed.serviceTypes) to {
                updateSearchFromChip(removeServiceFromQuery(liveSearchQuery))
            }
        }
        val place = parsed.serverLocationQuery?.takeIf { it.isNotBlank() }
        if (place != null) {
            chipData += place to {
                updateSearchFromChip(removePlaceFromQuery(liveSearchQuery))
            }
        }

        binding.chipGroupSearchParts.removeAllViews()
        binding.chipGroupSearchParts.isVisible = chipData.isNotEmpty()
        chipData.forEach { (label, onClose) ->
            val chip = Chip(requireContext()).apply {
                text = label
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textDirection = View.TEXT_DIRECTION_LTR
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                isCloseIconVisible = true
                isCheckable = false
                setOnCloseIconClickListener { onClose() }
            }
            binding.chipGroupSearchParts.addView(chip)
        }
    }

    private fun serviceChipLabel(serviceTypes: Set<String>): String =
        when {
            serviceTypes.size == 1 -> serviceTypes.first().toServiceLabel(requireContext())
            serviceTypes == setOf("candid_videography", "traditional_videography") -> getString(R.string.service_candid_videography)
            serviceTypes == setOf("candid_photography", "traditional_photography") -> getString(R.string.service_candid_photography)
            serviceTypes == setOf("candid_photography", "candid_videography") -> getString(R.string.service_candid_photography)
            serviceTypes == setOf("traditional_photography", "traditional_videography") -> getString(R.string.service_traditional_photography)
            else -> getString(R.string.filter)
        }

    private fun updateSearchFromChip(query: String) {
        val next = query.trim().replace(Regex("\\s+"), " ")
        liveSearchQuery = next
        selectedLocation = parseSearchQuery(next).serverLocationQuery
        setSearchText(next)
        renderSearchChips()
        updateSearchSuggestions(next)
        applyFilters(force = true)
    }

    private fun removeServiceFromQuery(query: String): String {
        val parsed = parseSearchQuery(query)
        return parsed.serverLocationQuery?.takeIf { it.isNotBlank() } ?: cleanServiceWords(query)
    }

    private fun removePlaceFromQuery(query: String): String {
        val parsed = parseSearchQuery(query)
        if (parsed.serviceTypes.isNotEmpty()) return serviceSearchPhrase(parsed.serviceTypes)
        val prep = Regex("\\b(?:at|in|near|around|from)\\s+.+$", RegexOption.IGNORE_CASE)
        return query.replace(prep, "").trim()
    }

    private fun parseSearchQuery(query: String): ParsedSearchQuery {
        val clean = query.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return ParsedSearchQuery()

        val lower = clean.lowercase()
        val serviceTypes = detectServiceTypes(lower)
        val prepMatch = Regex("\\b(?:at|in|near|around|from)\\s+(.+)$", RegexOption.IGNORE_CASE).find(clean)
        val locationFromPrep = prepMatch?.groupValues?.getOrNull(1)
            ?.let(::cleanSearchRemainder)
            ?.let(::canonicalPlaceLookup)
            ?.takeIf { it.length >= 2 }
        val embeddedKnownPlace = knownPlaceFromText(clean)
        val beforePrep = prepMatch?.let { clean.substring(0, it.range.first) }
            ?.let(::cleanSearchRemainder)
            ?.let(::cleanServiceWords)
            .orEmpty()

        val remainder = cleanSearchRemainder(clean)
        val withoutServiceWords = cleanServiceWords(remainder)
        val locationQuery = when {
            !locationFromPrep.isNullOrBlank() -> locationFromPrep
            !embeddedKnownPlace.isNullOrBlank() -> embeddedKnownPlace
            serviceTypes.isNotEmpty() -> withoutServiceWords.takeIf { it.length >= 2 }
            else -> clean
        }
        val hasExplicitPlace = !locationFromPrep.isNullOrBlank() || !embeddedKnownPlace.isNullOrBlank()
        val personQuery = beforePrep.takeIf {
            it.length >= 2 && serviceTypes.isEmpty() && !hasExplicitPlace
        }
            ?: ""
        val localFilterQuery = listOfNotNull(
            personQuery.takeIf { it.isNotBlank() },
            locationQuery.takeIf { !it.isNullOrBlank() },
        ).joinToString(" ").ifBlank {
            if (serviceTypes.isNotEmpty()) "" else withoutServiceWords.ifBlank { clean }
        }
        val backendService = serviceTypes.singleOrNull()
        val serviceMatchMode = when {
            serviceTypes.size <= 1 -> "any"
            Regex("\\b(and|with|plus)\\b|\\+", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> "all"
            else -> "smart"
        }
        val serverLocation = locationQuery
            ?.takeIf { it.length >= 3 }
            ?: personQuery.takeIf { it.length >= 3 && serviceTypes.isEmpty() }

        return ParsedSearchQuery(
            raw = clean,
            serverLocationQuery = serverLocation,
            suggestionLookupQuery = (locationQuery ?: if (serviceTypes.isEmpty()) clean else "").trim(),
            localFilterQuery = localFilterQuery,
            personQuery = personQuery,
            serviceTypes = serviceTypes,
            backendService = backendService,
            serviceMatchMode = serviceMatchMode,
        )
    }

    private fun serviceSearchPhrase(serviceTypes: Set<String>): String =
        when {
            serviceTypes == setOf("led_wall") -> "led wall"
            serviceTypes == setOf("drone") -> "drone"
            serviceTypes == setOf("pre_wedding") -> "pre-wedding"
            serviceTypes == setOf("wedding_photography") -> "wedding"
            serviceTypes == setOf("birthday_photography") -> "birthday"
            serviceTypes == setOf("candid_videography", "traditional_videography") -> "video"
            serviceTypes == setOf("candid_photography", "traditional_photography") -> "photo"
            serviceTypes == setOf("candid_photography", "candid_videography") -> "candid"
            serviceTypes == setOf("traditional_photography", "traditional_videography") -> "traditional"
            serviceTypes.contains("led_wall") -> "led wall"
            serviceTypes.contains("drone") -> "drone"
            serviceTypes.contains("pre_wedding") -> "pre-wedding"
            serviceTypes.contains("wedding_photography") -> "wedding"
            serviceTypes.contains("birthday_photography") -> "birthday"
            serviceTypes.contains("live_streaming") -> "live streaming"
            serviceTypes.any { it.contains("video") } -> "video"
            serviceTypes.any { it.contains("photo") } -> "photo"
            else -> "service"
        }

    private fun detectServiceTypes(normalizedQuery: String): Set<String> {
        val result = linkedSetOf<String>()
        val hasCandid = normalizedQuery.contains("candid")
        val hasTraditional = normalizedQuery.contains("traditional")
        val hasVideo = normalizedQuery.contains("video") || normalizedQuery.contains("videograph")
        val hasPhoto = normalizedQuery.contains("photo") || normalizedQuery.contains("photograph")

        if (normalizedQuery.contains("drone")) result += "drone"
        if (normalizedQuery.contains("led") || normalizedQuery.contains("wall")) result += "led_wall"
        if (normalizedQuery.contains("pre wedding") || normalizedQuery.contains("prewedding")) result += "pre_wedding"
        if (normalizedQuery.contains("wedding")) result += "wedding_photography"
        if (normalizedQuery.contains("engagement")) result += "engagement_photography"
        if (normalizedQuery.contains("birthday")) result += "birthday_photography"
        if (normalizedQuery.contains("event")) result += "event_photography"
        if (normalizedQuery.contains("corporate")) result += "corporate_photography"
        if (normalizedQuery.contains("baby")) result += "baby_shoot"
        if (normalizedQuery.contains("maternity")) result += "maternity_shoot"
        if (normalizedQuery.contains("album")) result += "album_design"
        if (normalizedQuery.contains("edit")) result += "photo_editing"
        if (normalizedQuery.contains("live") || normalizedQuery.contains("stream")) result += "live_streaming"

        when {
            hasCandid && hasVideo -> result += "candid_videography"
            hasTraditional && hasVideo -> result += "traditional_videography"
            hasVideo -> result += listOf("candid_videography", "traditional_videography")
        }
        when {
            hasCandid && hasPhoto -> result += "candid_photography"
            hasTraditional && hasPhoto -> result += "traditional_photography"
            hasPhoto -> result += listOf("candid_photography", "traditional_photography")
        }
        if (hasCandid && !hasVideo && !hasPhoto) {
            result += listOf("candid_photography", "candid_videography")
        }
        if (hasTraditional && !hasVideo && !hasPhoto) {
            result += listOf("traditional_photography", "traditional_videography")
        }
        return result
    }

    private fun cleanSearchRemainder(text: String): String =
        text.trim()
            .replace(Regex("\\b(takers?|creators?|photographers?|videographers?|nearby|best|top|for|please|show|find|search)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun cleanServiceWords(text: String): String =
        text.trim()
            .replace(Regex("\\b(candid|traditional|wedding|prewedding|pre|engagement|birthday|event|corporate|baby|maternity|album|editing?|live|streaming|photography|photographer|photo|photos|videography|videographer|video|videos|drone|shots?|led|wall|other)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun rebuildSearchSuggestionTrie() {
        val nextTrie = SearchTrie<SearchSuggestion>()
        val labels = serviceLabels(requireContext())
        SERVICE_TYPES.forEachIndexed { index, service ->
            val label = labels.getOrNull(index) ?: service.toServiceLabel(requireContext())
            val suggestion = SearchSuggestion(
                title = label,
                subtitle = getString(R.string.home_service_suggestion_subtitle),
                typeLabel = "Service",
                query = label,
                displayQuery = label,
            )
            nextTrie.put(label, suggestion, rank = 120, aliases = serviceSuggestionAliases(service, label))
        }

        val source = (currentTakers + currentFeatured).distinctBy { it.id }
        addGroupedPlaceSuggestions(nextTrie, source)
        source
            .sortedWith(compareByDescending<Taker> { it.reviewCount }.thenByDescending { it.avgRating })
            .take(40)
            .forEach { taker ->
                nextTrie.put(
                    label = taker.fullName,
                    payload = SearchSuggestion(
                        title = taker.fullName,
                        subtitle = listOf(taker.city, taker.state).filter { it.isNotBlank() }.distinct().joinToString(", "),
                        typeLabel = "Person",
                        query = taker.fullName,
                    ),
                    rank = 35 + taker.reviewCount.coerceAtMost(30),
                )
            }
        searchSuggestionTrie = nextTrie
    }

    private fun addGroupedPlaceSuggestions(trie: SearchTrie<SearchSuggestion>, source: List<Taker>) {
        fun addGroup(
            label: String,
            subtitle: String,
            count: Int,
            rank: Int,
            aliases: Collection<String> = emptyList(),
        ) {
            val clean = label.trim()
            if (clean.length < 2) return
            trie.put(
                label = clean,
                payload = SearchSuggestion(
                    title = clean,
                    subtitle = if (count > 0) {
                        "$subtitle - $count creator${if (count == 1) "" else "s"}"
                    } else {
                        subtitle
                    },
                    typeLabel = if (clean.all { it.isDigit() }) "Pincode" else "Place",
                    query = clean,
                ),
                rank = rank + count.coerceAtMost(60),
                aliases = aliases,
            )
        }

        source.groupBy { it.city.trim() }
            .filterKeys { it.isNotBlank() }
            .forEach { (city, takers) ->
                val first = takers.first()
                addGroup(city, first.state, takers.size, rank = 90, aliases = placeAliases(city))
            }
        source.groupBy { it.pincode.trim() }
            .filterKeys { it.length >= 3 }
            .forEach { (pincode, takers) ->
                val first = takers.first()
                addGroup(pincode, "${first.city}, ${first.state}", takers.size, rank = 100)
            }
        popularPlaceNames().forEach { place ->
            addGroup(place, getString(R.string.home_search_this_place_subtitle), 0, rank = 125, aliases = placeAliases(place))
        }
    }

    private fun serviceSuggestionAliases(service: String, label: String): List<String> {
        val base = mutableListOf(
            service.replace('_', ' '),
            label,
            label.replace("Photography", "photo", ignoreCase = true),
            label.replace("Videography", "video", ignoreCase = true),
        )
        base += when (service) {
            "wedding_photography" -> listOf("wedding", "marriage", "wedding photo", "wedding photographer")
            "pre_wedding" -> listOf("pre wedding", "prewedding", "couple shoot")
            "candid_photography" -> listOf("candid", "candid photo")
            "candid_videography" -> listOf("candid video")
            "traditional_photography" -> listOf("traditional", "traditional photo")
            "traditional_videography" -> listOf("traditional video")
            "birthday_photography" -> listOf("birthday", "birthday photo")
            "event_photography" -> listOf("event", "function")
            "drone" -> listOf("drone", "drone shot", "aerial")
            "led_wall" -> listOf("led", "led wall", "screen")
            "live_streaming" -> listOf("live", "stream", "live stream")
            else -> emptyList()
        }
        return base.distinct()
    }

    private fun placeAliases(place: String): List<String> {
        val normalized = place.trim().lowercase()
        return knownPlaceAliases()
            .filterValues { it.equals(place, ignoreCase = true) }
            .keys
            .plus(normalized)
            .distinct()
    }

    private fun trieSuggestionsFor(query: String): List<SearchSuggestion> =
        searchSuggestionTrie.search(query, limit = 8)

    private fun updateSearchSuggestions(query: String) {
        val suggestions = buildSmartSearchSuggestions(query)
        searchSuggestionAdapter.submitList(suggestions)
        binding.cardSearchSuggestions.isVisible = binding.searchView.hasFocus() && suggestions.isNotEmpty()
    }

    private fun buildSmartSearchSuggestions(query: String): List<SearchSuggestion> {
        val parsed = parseSearchQuery(query)
        val suggestionQuery = parsed.suggestionLookupQuery.ifBlank {
            if (parsed.serviceTypes.isEmpty()) query else ""
        }
        val trieQuery = suggestionQuery.ifBlank { query }
        val queryVariants = searchQueryVariants(trieQuery)

        val suggestions = mutableListOf<SearchSuggestion>()
        val seen = linkedSetOf<String>()
        val seenVisibleLabels = linkedSetOf<String>()
        fun add(type: String, title: String, subtitle: String, queryText: String) {
            val cleanTitle = title.trim()
            val cleanQuery = queryText.trim()
            if (cleanTitle.isBlank() || cleanQuery.isBlank()) return
            val visibleKey = suggestionVisibleKey(type, cleanTitle, cleanQuery)
            if (!seenVisibleLabels.add(visibleKey)) return
            val key = "${type.lowercase()}|${cleanTitle.lowercase()}|${cleanQuery.lowercase()}"
            if (seen.add(key)) {
                suggestions += SearchSuggestion(
                    title = cleanTitle,
                    subtitle = subtitle.trim(),
                    typeLabel = type,
                    query = cleanQuery,
                )
            }
        }
        fun addSuggestion(item: SearchSuggestion) {
            val visibleKey = suggestionVisibleKey(item.typeLabel, item.title, item.query)
            if (!seenVisibleLabels.add(visibleKey)) return
            val key = "${item.typeLabel.lowercase()}|${item.title.lowercase()}|${item.query.lowercase()}|${item.action}"
            if (seen.add(key)) suggestions += item
        }

        if (query.isBlank()) {
            addSuggestion(
                SearchSuggestion(
                    title = getString(R.string.home_use_current_location),
                    subtitle = getString(R.string.home_current_location_hint),
                    typeLabel = "Nearby",
                    query = "",
                    action = ACTION_CURRENT_LOCATION,
                )
            )
            trendingSearches.take(4).forEach { trend ->
                add("Trending", trend.title, getString(R.string.home_trending_searches), trend.title)
            }
            recentSearches().forEach { recent ->
                add("Recent", recent, getString(R.string.home_recent_searches), recent)
            }
            return suggestions.take(8)
        }

        val correction = correctionSuggestion(parsed)
        if (correction != null) {
            addSuggestion(correction)
        }

        if (queryVariants.isEmpty()) return suggestions.take(8)

        trieSuggestionsFor(trieQuery)
            .forEach(::addSuggestion)

        trendingSearches
            .filter { trend ->
                val title = trend.title.lowercase()
                queryVariants.any { variant -> placeTextMatches(title, variant) }
            }
            .take(3)
            .forEach { trend ->
                add("Trending", trend.title, getString(R.string.home_trending_searches), trend.title)
            }

        nearbyAlternatives
            .filter { alternative ->
                val title = alternative.title.lowercase()
                queryVariants.any { variant -> placeTextMatches(title, variant) }
            }
            .take(3)
            .forEach { alternative ->
                add("Nearby", alternative.title, getString(R.string.home_nearby_alternative_subtitle), alternative.query)
            }

        remotePlaceSuggestions
            .filter { suggestion -> suggestionMatchesQuery(suggestion, queryVariants) }
            .take(4)
            .forEach(::addSuggestion)

        val source = filterTakersByService(
            (currentTakers + currentFeatured).distinctBy { it.id },
            parsed.serviceTypes,
        )
        if (parsed.serviceTypes.isNotEmpty() && parsed.serverLocationQuery.isNullOrBlank() && suggestionQuery.isBlank()) {
            return suggestions.take(10)
        }
        val digits = query.filter { it.isDigit() }

        if (digits.length >= 2) {
            source
                .filter { it.pincode.startsWith(digits) || it.pincode.contains(digits) }
                .groupBy { it.pincode }
                .entries
                .sortedWith(compareBy<Map.Entry<String, List<Taker>>> { if (it.key.startsWith(digits)) 0 else 1 }.thenBy { it.key })
                .take(3)
                .forEach { (pincode, takers) ->
                    val first = takers.first()
                    add("Pincode", pincode, "${first.city}, ${first.state} - ${takers.size} creator${if (takers.size == 1) "" else "s"}", pincode)
                }
        }

        val personVariants = searchQueryVariants(parsed.personQuery.ifBlank { query })
        if (parsed.personQuery.isNotBlank() || parsed.serviceTypes.isEmpty()) {
            source
                .filter { taker -> personVariants.any { taker.fullName.lowercase().contains(it) } }
                .sortedBy { it.fullName.lowercase() }
                .take(5)
                .forEach { taker ->
                    add("Person", taker.fullName, listOf(taker.city, taker.state).filter { it.isNotBlank() }.joinToString(", "), taker.fullName)
                }
        }

        val placeRows = source.filter { taker ->
            val placeValues = listOf(taker.city, taker.state, taker.pincode)
            placeValues.any { value ->
                val normalized = value.lowercase()
                queryVariants.any { placeTextMatches(normalized, it) }
            }
        }

        placeRows
            .groupBy { it.city.trim() }
            .filterKeys { it.isNotBlank() }
            .entries
            .sortedByDescending { it.value.size }
            .take(3)
            .forEach { (city, takers) ->
                val first = takers.first()
                add("Place", city, "${first.state} - ${takers.size} creator${if (takers.size == 1) "" else "s"}", city)
            }

        remotePlaceSuggestions
            .filter { suggestion -> suggestionMatchesQuery(suggestion, queryVariants) }
            .take(6)
            .forEach(::addSuggestion)

        explicitPlaceSuggestion(parsed, query)?.let(::addSuggestion)

        return suggestions.take(10)
    }

    private fun suggestionVisibleKey(type: String, title: String, query: String): String {
        val normalizedTitle = title
            .replace(Regex("^${Regex.escape(getString(R.string.home_search_this_place))}:\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .lowercase()
        val normalizedQuery = canonicalPlaceLookup(query).trim().lowercase()
        val normalizedType = when (type.lowercase()) {
            "place", "pincode", "fix" -> "place"
            "nearby" -> "nearby"
            "service" -> "service"
            "person" -> "person"
            else -> type.lowercase()
        }
        return "$normalizedType|${normalizedTitle.ifBlank { normalizedQuery }}"
    }

    private fun suggestionMatchesQuery(suggestion: SearchSuggestion, queryVariants: List<String>): Boolean {
        val values = listOf(suggestion.title, suggestion.subtitle, suggestion.query, suggestion.displayQuery)
        return values.any { value ->
            val normalized = value.lowercase()
            queryVariants.any { variant -> placeTextMatches(normalized, variant) }
        }
    }

    private fun explicitPlaceSuggestion(parsed: ParsedSearchQuery, query: String): SearchSuggestion? {
        val typedPlace = parsed.suggestionLookupQuery
            .ifBlank { if (parsed.serviceTypes.isEmpty()) query else "" }
            .trim()
            .replace(Regex("\\s+"), " ")
        if (typedPlace.length < 2) return null

        val canonical = canonicalPlaceLookup(typedPlace)
        val servicePrefix = parsed.serviceTypes.takeIf { it.isNotEmpty() }?.let {
            serviceSearchPhrase(it).replaceFirstChar { ch -> ch.uppercase() } + " near "
        }.orEmpty()
        val display = if (parsed.serviceTypes.isNotEmpty()) {
            "${serviceSearchPhrase(parsed.serviceTypes)} at $canonical"
        } else {
            canonical
        }
        return SearchSuggestion(
            title = if (servicePrefix.isBlank()) {
                "${getString(R.string.home_search_this_place)}: $canonical"
            } else {
                "$servicePrefix$canonical"
            },
            subtitle = getString(R.string.home_search_this_place_subtitle),
            typeLabel = "Place",
            query = canonical,
            displayQuery = display,
        )
    }

    private fun buildSearchSuggestions(query: String): List<SearchSuggestion> {
        val queryVariants = searchQueryVariants(query)
        if (queryVariants.isEmpty()) return emptyList()

        val source = (currentTakers + currentFeatured).distinctBy { it.id }
        if (source.isEmpty()) return emptyList()

        val suggestions = mutableListOf<SearchSuggestion>()
        val seen = linkedSetOf<String>()
        fun add(type: String, title: String, subtitle: String, queryText: String) {
            val cleanTitle = title.trim()
            val cleanQuery = queryText.trim()
            if (cleanTitle.isBlank() || cleanQuery.isBlank()) return
            val key = "${type.lowercase()}|${cleanTitle.lowercase()}|${cleanQuery.lowercase()}"
            if (seen.add(key)) {
                suggestions += SearchSuggestion(
                    title = cleanTitle,
                    subtitle = subtitle.trim(),
                    typeLabel = type,
                    query = cleanQuery,
                )
            }
        }
        fun addSuggestion(suggestion: SearchSuggestion) {
            add(suggestion.typeLabel, suggestion.title, suggestion.subtitle, suggestion.query)
        }

        val digits = query.filter { it.isDigit() }
        if (digits.length >= 2) {
            source
                .filter { it.pincode.startsWith(digits) || it.pincode.contains(digits) }
                .groupBy { it.pincode }
                .entries
                .sortedWith(compareBy<Map.Entry<String, List<Taker>>> { if (it.key.startsWith(digits)) 0 else 1 }.thenBy { it.key })
                .take(3)
                .forEach { (pincode, takers) ->
                    val first = takers.first()
                    add(
                        type = "Pincode",
                        title = pincode,
                        subtitle = "${first.city}, ${first.state} • ${takers.size} creator${if (takers.size == 1) "" else "s"}",
                        queryText = pincode,
                    )
                }
        }

        source
            .filter { taker -> queryVariants.any { taker.fullName.lowercase().contains(it) } }
            .sortedBy { it.fullName.lowercase() }
            .take(4)
            .forEach { taker ->
                add(
                    type = "Person",
                    title = taker.fullName,
                    subtitle = "${taker.city}, ${taker.state} • ${taker.offeredServices.firstOrNull().orEmpty().replace('_', ' ')}",
                    queryText = taker.fullName,
                )
            }

        val placeRows = source.filter { taker ->
            val placeValues = listOf(taker.city, taker.state, taker.pincode)
            placeValues.any { value ->
                val normalized = value.lowercase()
                queryVariants.any { normalized.contains(it) || it.contains(normalized) }
            }
        }
        placeRows
            .groupBy { it.city.trim() }
            .filterKeys { it.isNotBlank() }
            .entries
            .sortedByDescending { it.value.size }
            .take(4)
            .forEach { (place, takers) ->
                val first = takers.first()
                add(
                    type = "Place",
                    title = place,
                    subtitle = "${first.city}, ${first.state} • ${takers.size} creator${if (takers.size == 1) "" else "s"}",
                    queryText = place,
                )
            }

        placeRows
            .groupBy { it.city.trim() }
            .filterKeys { it.isNotBlank() }
            .entries
            .sortedByDescending { it.value.size }
            .take(2)
            .forEach { (city, takers) ->
                val first = takers.first()
                add(
                    type = "Place",
                    title = city,
                    subtitle = "${first.state} • ${takers.size} creator${if (takers.size == 1) "" else "s"}",
                    queryText = city,
                )
            }

        source
            .filter { taker ->
                val values = listOf(taker.fullName, taker.city, taker.state, taker.pincode)
                values.any { value ->
                    val normalized = value.lowercase()
                    queryVariants.any { normalized.contains(it) || it.contains(normalized) }
                }
            }
            .take(4)
            .forEach { taker ->
                val place = taker.city
                add(
                    type = "Person",
                    title = "${taker.fullName} in $place",
                    subtitle = "${taker.city}, ${taker.state} • ${taker.pincode}",
                    queryText = taker.fullName,
                )
            }

        remotePlaceSuggestions
            .filter { suggestion ->
                val values = listOf(suggestion.title, suggestion.subtitle, suggestion.query)
                values.any { value ->
                    val normalized = value.lowercase()
                    queryVariants.any { variant -> normalized.contains(variant) || variant.contains(normalized) }
                }
            }
            .take(5)
            .forEach(::addSuggestion)

        return suggestions.take(10)
    }

    private fun buildRemotePlaceSuggestions(postOffices: List<PostOffice>, query: String): List<SearchSuggestion> {
        val queryVariants = searchQueryVariants(query)
        if (queryVariants.isEmpty()) return emptyList()

        return postOffices
            .asSequence()
            .filter { office ->
                val values = listOf(office.name, office.district, office.state, office.pincode, office.block.orEmpty())
                values.any { value ->
                    val normalized = value.lowercase()
                    queryVariants.any { variant -> placeTextMatches(normalized, variant) }
                }
            }
            .distinctBy { "${it.name.lowercase()}|${it.district.lowercase()}|${it.pincode}" }
            .sortedWith(
                compareBy<PostOffice> { !it.name.lowercase().startsWith(query.trim().lowercase()) }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.pincode }
            )
            .take(8)
            .map { office ->
                SearchSuggestion(
                    typeLabel = if (query.trim().all { it.isDigit() }) "Pincode" else "Place",
                    title = office.name,
                    subtitle = "${office.district}, ${office.state} • ${office.pincode}",
                    query = office.name,
                )
            }
            .toList()
    }

    private fun buildPostalPlaceSuggestions(postOffices: List<PostOffice>, query: String): List<SearchSuggestion> {
        val queryVariants = searchQueryVariants(query)
        if (queryVariants.isEmpty()) return emptyList()
        val cleanQuery = query.trim()
        val numericQuery = cleanQuery.all { it.isDigit() }

        if (numericQuery) {
            return postOffices
                .asSequence()
                .filter { it.pincode.contains(cleanQuery) }
                .groupBy { it.pincode }
                .entries
                .sortedBy { it.key }
                .take(4)
                .map { (pincode, offices) ->
                    val first = offices.first()
                    SearchSuggestion(
                        typeLabel = "Pincode",
                        title = pincode,
                        subtitle = "${first.district}, ${first.state}",
                        query = pincode,
                    )
                }
        }

        val citySuggestions = postOffices
            .asSequence()
            .filter { office ->
                val values = listOf(office.district, office.state, office.block.orEmpty())
                values.any { value ->
                    val normalized = value.lowercase()
                    queryVariants.any { variant -> placeTextMatches(normalized, variant) }
                }
            }
            .groupBy { it.district.trim() }
            .filterKeys { it.isNotBlank() }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<PostOffice>>> { it.value.size }.thenBy { it.key })
            .take(2)
            .map { (district, offices) ->
                val first = offices.first()
                SearchSuggestion(
                    typeLabel = "Place",
                    title = district,
                    subtitle = first.state,
                    query = district,
                )
            }

        val subAreaSuggestions = postOffices
            .asSequence()
            .mapNotNull { office ->
                val block = office.block?.trim().orEmpty()
                if (block.isBlank() || block.equals("NA", ignoreCase = true) || block.equals(office.district, ignoreCase = true)) {
                    null
                } else {
                    office to block
                }
            }
            .filter { (office, block) ->
                val values = listOf(block, office.district, office.state)
                values.any { value ->
                    val normalized = value.lowercase()
                    queryVariants.any { variant -> placeTextMatches(normalized, variant) }
                }
            }
            .groupBy({ it.second }, { it.first })
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<PostOffice>>> { it.value.size }.thenBy { it.key })
            .take(3)
            .map { (block, offices) ->
                val first = offices.first()
                SearchSuggestion(
                    typeLabel = "Place",
                    title = block,
                    subtitle = "${first.district}, ${first.state}",
                    query = block,
                )
            }

        return (citySuggestions + subAreaSuggestions)
            .distinctBy { "${it.title.lowercase()}|${it.subtitle.lowercase()}" }
            .take(5)
    }

    private fun buildGeoPlaceSuggestions(places: List<NominatimPlace>, query: String): List<SearchSuggestion> {
        val queryVariants = searchQueryVariants(query)
        if (queryVariants.isEmpty()) return emptyList()
        val allowedTypes = setOf(
            "city",
            "town",
            "municipality",
            "city_district",
            "suburb",
            "borough",
            "county",
            "state_district",
            "administrative",
        )

        return places
            .asSequence()
            .filter { place -> place.type?.lowercase() in allowedTypes || place.placeClass == "boundary" }
            .mapNotNull { place ->
                val address = place.address
                val type = place.type?.lowercase().orEmpty()
                val title = when (type) {
                    "city", "town", "municipality" -> address?.city ?: address?.town ?: address?.municipality
                    "city_district", "suburb", "borough" -> address?.cityDistrict ?: address?.suburb
                    "county", "state_district", "administrative" -> address?.county ?: address?.stateDistrict ?: address?.city
                    else -> address?.city ?: address?.town ?: address?.cityDistrict ?: address?.suburb
                }?.trim().orEmpty().ifBlank {
                    place.displayName?.substringBefore(",")?.trim().orEmpty()
                }
                if (title.isBlank()) return@mapNotNull null

                val searchable = listOf(
                    title,
                    address?.city.orEmpty(),
                    address?.town.orEmpty(),
                    address?.municipality.orEmpty(),
                    address?.cityDistrict.orEmpty(),
                    address?.suburb.orEmpty(),
                    address?.county.orEmpty(),
                    address?.stateDistrict.orEmpty(),
                    address?.state.orEmpty(),
                    address?.postcode.orEmpty(),
                )
                val matches = searchable.any { value ->
                    val normalized = value.lowercase()
                    queryVariants.any { variant -> placeTextMatches(normalized, variant) }
                }
                if (!matches) return@mapNotNull null

                val state = address?.state?.trim().orEmpty()
                val postcode = address?.postcode?.trim().orEmpty()
                SearchSuggestion(
                    typeLabel = "Place",
                    title = title,
                    subtitle = listOf(state, postcode).filter { it.isNotBlank() }.joinToString(" - "),
                    query = title,
                ) to (place.importance ?: 0.0)
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { "${it.title.lowercase()}|${it.subtitle.lowercase()}" }
            .take(5)
            .toList()
    }

    private fun correctionSuggestion(parsed: ParsedSearchQuery): SearchSuggestion? {
        val typed = parsed.suggestionLookupQuery.trim()
        if (typed.length < 4) return null
        val canonical = canonicalPlaceLookup(typed)
        if (canonical.equals(typed, ignoreCase = true)) return null
        val display = if (parsed.serviceTypes.isNotEmpty()) {
            "${serviceSearchPhrase(parsed.serviceTypes)} at $canonical"
        } else {
            canonical
        }
        return SearchSuggestion(
            title = getString(R.string.home_did_you_mean, canonical),
            subtitle = display,
            typeLabel = "Fix",
            query = canonical,
            displayQuery = display,
        )
    }

    private fun placeTextMatches(value: String, query: String): Boolean {
        val cleanValue = value.trim().lowercase()
        val cleanQuery = query.trim().lowercase()
        if (cleanValue.isBlank() || cleanQuery.isBlank()) return false
        if (
            cleanValue.startsWith(cleanQuery) ||
            cleanQuery.startsWith(cleanValue) ||
            cleanValue.contains(cleanQuery) ||
            cleanQuery.contains(cleanValue)
        ) return true
        if (cleanQuery.length < 4) return false
        return cleanValue
            .split(Regex("[\\s,.-]+"))
            .filter { it.isNotBlank() }
            .any { token -> fuzzyWordMatches(token, cleanQuery) }
    }

    private fun oneEditAway(a: String, b: String): Boolean {
        return editDistanceAtMost(a, b, 1)
    }

    private fun fuzzyWordMatches(a: String, b: String): Boolean {
        if (a.length < 4 || b.length < 4) return false
        val allowedEdits = if (maxOf(a.length, b.length) >= 7) 2 else 1
        return editDistanceAtMost(a, b, allowedEdits)
    }

    private fun editDistanceAtMost(a: String, b: String, maxEdits: Int): Boolean {
        if (kotlin.math.abs(a.length - b.length) > maxEdits) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
            } else {
                edits++
                if (edits > maxEdits) return false
                when {
                    a.length > b.length -> i++
                    b.length > a.length -> j++
                    else -> {
                        i++
                        j++
                    }
                }
            }
        }
        if (i < a.length || j < b.length) edits++
        return edits <= maxEdits
    }

    private fun canonicalPlaceLookup(query: String): String {
        val normalized = query.trim().lowercase()
        val aliases = knownPlaceAliases()
        aliases[normalized]?.let { return it }
        aliases.entries.firstOrNull { (alias, _) ->
            alias.length >= 4 && normalized.length >= 4 && fuzzyWordMatches(alias, normalized)
        }?.value?.let { return it }
        return query.trim()
    }

    private fun knownPlaceFromText(query: String): String? {
        val tokens = query
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
        val aliases = knownPlaceAliases()
        tokens.forEach { token ->
            aliases[token]?.let { return it }
        }
        tokens.forEach { token ->
            aliases.entries.firstOrNull { (alias, _) ->
                alias.length >= 4 && token.length >= 4 && fuzzyWordMatches(alias, token)
            }?.value?.let { return it }
        }
        return null
    }

    private fun knownPlaceAliases(): Map<String, String> = mapOf(
        "eluru" to "Eluru",
        "elure" to "Eluru",
        "elluru" to "Eluru",
        "vijayawada" to "Vijayawada",
        "bezawada" to "Vijayawada",
        "guntur" to "Guntur",
        "mangalagiri" to "Mangalagiri",
        "mangalagiry" to "Mangalagiri",
        "mangalariri" to "Mangalagiri",
        "mangalgiri" to "Mangalagiri",
        "tadepalli" to "Tadepalli",
        "undavalli" to "Undavalli",
        "amaravati" to "Amaravati",
        "vizag" to "Visakhapatnam",
        "visakhapatnam" to "Visakhapatnam",
        "visakapatnam" to "Visakhapatnam",
        "vishakhapatnam" to "Visakhapatnam",
        "waltair" to "Visakhapatnam",
        "hyderabad" to "Hyderabad",
        "secunderabad" to "Secunderabad",
        "warangal" to "Warangal",
        "tirupati" to "Tirupati",
        "kakinada" to "Kakinada",
        "rajahmundry" to "Rajahmundry",
        "rajamahendravaram" to "Rajahmundry",
        "nellore" to "Nellore",
        "kurnool" to "Kurnool",
        "ananthapur" to "Anantapur",
        "anantapur" to "Anantapur",
        "kadapa" to "Kadapa",
        "cuddapah" to "Kadapa",
        "ongole" to "Ongole",
        "srikakulam" to "Srikakulam",
        "vizianagaram" to "Vizianagaram",
        "machilipatnam" to "Machilipatnam",
        "bhimavaram" to "Bhimavaram",
        "tenali" to "Tenali",
        "chennai" to "Chennai",
        "bangalore" to "Bengaluru",
        "bengaluru" to "Bengaluru",
        "mumbai" to "Mumbai",
        "delhi" to "Delhi",
        "pune" to "Pune",
        "kolkata" to "Kolkata",
        "kochi" to "Kochi",
    )

    private fun popularPlaceNames(): List<String> =
        knownPlaceAliases().values.distinct().sorted()

    private fun saveRecentSearch(query: String) {
        val clean = query.trim().replace(Regex("\\s+"), " ")
        if (clean.length < 2) return
        val next = (listOf(clean) + recentSearches().filterNot { it.equals(clean, ignoreCase = true) }).take(6)
        requireContext()
            .getSharedPreferences("home_search", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("recent", next.joinToString("\n"))
            .apply()
    }

    private fun recentSearches(): List<String> =
        requireContext()
            .getSharedPreferences("home_search", android.content.Context.MODE_PRIVATE)
            .getString("recent", "")
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(6)
            .toList()

    private fun searchQueryVariants(query: String): List<String> {
        val normalized = canonicalPlaceLookup(query).trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        val variants = linkedSetOf(normalized)
        val canonical = knownPlaceAliases()[normalized] ?: canonicalPlaceLookup(normalized)
        variants += knownPlaceAliases()
            .filterValues { it.equals(canonical, ignoreCase = true) }
            .keys
        return variants.toList()
    }

    private fun requestApproximateLocationFeed() {
        if (hasLocationPermission()) {
            requestCurrentLocation()
            return
        }
        toast(getString(R.string.home_location_permission_needed))
        locationPermissionsLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun hasLocationPermission(): Boolean {
        val context = context ?: return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun requestCurrentLocation() {
        val context = context ?: return
        if (!hasLocationPermission()) return
        val locationManager = ContextCompat.getSystemService(context, LocationManager::class.java) ?: return
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) -> LocationManager.PASSIVE_PROVIDER
            else -> null
        }

        val lastKnown = sequenceOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { providerName ->
                runCatching { locationManager.getLastKnownLocation(providerName) }.getOrNull()
            }
            .firstOrNull()
        if (lastKnown != null) {
            consumeDeviceLocation(lastKnown)
        }

        if (provider == null) {
            return
        }

        LocationManagerCompat.getCurrentLocation(
            locationManager,
            provider,
            null as android.os.CancellationSignal?,
            ContextCompat.getMainExecutor(context),
            Consumer<Location?> { location ->
                location?.let(::consumeDeviceLocation)
            },
        )
    }

    private fun consumeDeviceLocation(location: Location) {
        val newLat = location.latitude
        val newLon = location.longitude
        val isSame = deviceLatitude != null &&
            deviceLongitude != null &&
            kotlin.math.abs(deviceLatitude!! - newLat) < 0.001 &&
            kotlin.math.abs(deviceLongitude!! - newLon) < 0.001
        if (isSame) {
            return
        }
        deviceLatitude = newLat
        deviceLongitude = newLon
        applyFilters()
    }

    private fun openProfilePage() {
        selectTopLevelDestination(
            takerDestination = R.id.takerProfileFragment,
            clientDestination = R.id.profileFragment,
        )
    }

    private fun openOrdersPage(eventId: Int = 0, bookingId: Int = 0) {
        requireActivity().intent?.apply {
            if (eventId > 0) putExtra(BookingsFragment.EXTRA_NAV_EVENT_ID, eventId)
            if (bookingId > 0) putExtra(BookingsFragment.EXTRA_NAV_BOOKING_ID, bookingId)
        }
        selectTopLevelDestination(
            takerDestination = R.id.bookingsFragment,
            clientDestination = R.id.bookingsFragment,
        )
    }

    private fun selectTopLevelDestination(takerDestination: Int, clientDestination: Int) {
        val takerBottomNav = requireActivity().findViewById<BottomNavigationView>(R.id.bottomNavTaker)
        val clientBottomNav = requireActivity().findViewById<BottomNavigationView>(R.id.bottomNav)
        when {
            takerBottomNav != null -> {
                takerBottomNav.selectedItemId = takerDestination
                return
            }
            clientBottomNav != null -> {
                clientBottomNav.selectedItemId = clientDestination
                return
            }
        }

        val navController = findNavController()
        val destination = when {
            navController.graph.findNode(takerDestination) != null -> takerDestination
            navController.graph.findNode(clientDestination) != null -> clientDestination
            else -> return
        }
        if (navController.currentDestination?.id != destination) {
            runCatching { navController.navigate(destination) }
        }
    }

    private fun openDetail(taker: Taker) {
        val parsed = parseSearchQuery(liveSearchQuery)
        viewModel.recordSearchEvent(
            SearchEventRequest(
                eventType = "click",
                queryText = liveSearchQuery,
                locationText = parsed.serverLocationQuery,
                serviceTypes = (selectedService?.let { listOf(it) } ?: parsed.serviceTypes.toList()),
                serviceMatchMode = parsed.serviceMatchMode,
                requestedRadiusKm = searchRadiusKm,
                appliedRadiusKm = latestAppliedRadiusKm,
                resultCount = latestSearchResultCount,
                takerId = taker.id,
                filters = currentSearchFiltersMap(),
            )
        )
        val services = taker.offeredServices
        val defaultService = selectedService?.takeIf { it in services } ?: services.primaryServiceType()
        takerDetailLauncher.launch(Intent(requireContext(), TakerDetailActivity::class.java).apply {
            putExtra(TakerDetailActivity.EXTRA_ID, taker.id)
            putExtra(TakerDetailActivity.EXTRA_NAME, taker.fullName)
            putExtra(TakerDetailActivity.EXTRA_SVC, defaultService)
            putStringArrayListExtra(TakerDetailActivity.EXTRA_SERVICES, ArrayList(services))
            putExtra(TakerDetailActivity.EXTRA_CITY, listOf(taker.city, taker.state).filter { it.isNotBlank() }.joinToString(", "))
            putExtra(TakerDetailActivity.EXTRA_RATING, taker.avgRating)
            putExtra(TakerDetailActivity.EXTRA_IMG, taker.profileImageUrl)
            putExtra(TakerDetailActivity.EXTRA_THUMB, taker.profileThumbUrl)
        })
    }

    override fun onDestroyView() {
        notificationsSheet?.dismiss()
        notificationsSheet = null
        notificationsSheetBinding = null
        pendingSearchRunnable?.let(searchHandler::removeCallbacks)
        pendingSearchRunnable = null
        filteredTakersForDisplay = emptyList()
        visibleTakerCount = 0
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val INITIAL_TAKER_BATCH = 8
        private const val TAKER_BATCH_STEP = 8
        private const val LOAD_MORE_THRESHOLD_PX = 600
        private const val DEFAULT_SEARCH_RADIUS_KM = 25.0
        private const val MAX_SEARCH_RADIUS_KM = 100.0
        private const val MIN_SEARCH_RESULTS_BEFORE_STOP = 5
        private const val NOTIFICATION_CHANNEL_BOOKINGS = "booking_updates"
        private const val NOTIFICATION_ID_BASE = 50000
        private const val NOTIFICATION_PREFS = "pc_notification_state"
        private const val KEY_SHOWN_NOTIFICATION_IDS = "shown_notification_ids"
        private val SEARCH_RADIUS_OPTIONS_KM = doubleArrayOf(10.0, 25.0, 50.0, 75.0, MAX_SEARCH_RADIUS_KM)
        private val eventNotificationTypes = setOf(
            "booking_request",
            "booking_created",
            "booking_status",
            "booking_cancelled",
        )
    }
}

private fun Map<String, Any?>.intValue(key: String): Int =
    when (val value = this[key]) {
        is Int -> value
        is Long -> value.toInt()
        is Double -> value.toInt()
        is String -> value.toIntOrNull() ?: 0
        else -> 0
    }

private fun Map<String, Any?>.stringValue(key: String): String =
    (this[key] as? String)?.trim().orEmpty()

private data class ParsedSearchQuery(
    val raw: String = "",
    val serverLocationQuery: String? = null,
    val suggestionLookupQuery: String = "",
    val localFilterQuery: String = "",
    val personQuery: String = "",
    val serviceTypes: Set<String> = emptySet(),
    val backendService: String? = null,
    val serviceMatchMode: String = "smart",
)
