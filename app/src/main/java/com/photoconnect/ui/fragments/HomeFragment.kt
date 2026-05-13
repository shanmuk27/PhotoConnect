package com.photoconnect.ui.fragments

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.util.Consumer
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.photoconnect.R
import com.photoconnect.databinding.BottomSheetNotificationsBinding
import com.photoconnect.databinding.FragmentHomeBinding
import com.photoconnect.db.toModel
import com.photoconnect.model.NotificationItem
import com.photoconnect.model.Taker
import com.photoconnect.repository.FavoriteRepository
import com.photoconnect.repository.Result
import com.photoconnect.ui.activities.TakerDetailActivity
import com.photoconnect.ui.adapters.CategoryAdapter
import com.photoconnect.ui.adapters.FeaturedTakerAdapter
import com.photoconnect.ui.adapters.NotificationAdapter
import com.photoconnect.ui.adapters.TakerAdapter
import com.photoconnect.utils.SERVICE_ICONS
import com.photoconnect.utils.SERVICE_LABELS
import com.photoconnect.utils.SERVICE_TYPES
import com.photoconnect.utils.SessionManager
import com.photoconnect.utils.hide
import com.photoconnect.utils.primaryServiceType
import com.photoconnect.utils.show
import com.photoconnect.utils.toDisplayDate
import com.photoconnect.utils.toast
import com.photoconnect.viewmodel.HomeViewModel
import com.photoconnect.viewmodel.NotificationViewModel
import com.photoconnect.viewmodel.TakerDetailViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()
    private val notificationsVm: NotificationViewModel by viewModels()
    private val detailVm: TakerDetailViewModel by viewModels()

    @Inject lateinit var session: SessionManager
    @Inject lateinit var favoriteRepo: FavoriteRepository

    private lateinit var featuredAdapter: FeaturedTakerAdapter
    private lateinit var takerAdapter: TakerAdapter
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var takerGridLayoutManager: GridLayoutManager
    private lateinit var notificationAdapter: NotificationAdapter

    private var selectedLocation: String? = null
    private var liveSearchQuery: String = ""
    private var selectedService: String? = null
    private var selectedDate: String? = null
    private var isAvailableOnly: Boolean = false
    private var favoriteTakerIds: Set<Int> = emptySet()
    private var currentTakers: List<Taker> = emptyList()
    private var currentFeatured: List<Taker> = emptyList()
    private var currentNotifications: List<NotificationItem> = emptyList()
    private var currentUnreadCount: Int = 0
    private var deviceLatitude: Double? = null
    private var deviceLongitude: Double? = null
    private var requestedLocationOnce = false
    private var notificationsSheet: BottomSheetDialog? = null
    private var notificationsSheetBinding: BottomSheetNotificationsBinding? = null

    private val locationPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.any { it.value }) {
            requestCurrentLocation()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        FragmentHomeBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        setupCategories()
        setupFeatured()
        setupGrid()
        setupSearch()
        setupFilters()
        setupNotifications()
        observeFavorites()
        observeState()
        requestLocationFeed()
    }

    override fun onResume() {
        super.onResume()
        if (session.isLoggedIn() && !session.isGuest()) {
            notificationsVm.fetchNotifications()
        }
        if (deviceLatitude == null || deviceLongitude == null) {
            requestLocationFeed()
        }
    }

    private fun setupUI() {
        binding.tvGreeting.text = when {
            session.isLoggedIn() && !session.isGuest() -> "Hello, ${session.getUserName().split(" ").firstOrNull().orEmpty()}"
            else -> "Hello, Guest"
        }
        binding.tvFeaturedTitle.text = getString(R.string.home_top_creators_title)

        renderHeaderProfile(null, null)
        binding.cardHeaderProfile.setOnClickListener { openProfilePage() }
        binding.cardHeaderProfile.contentDescription = getString(R.string.home_profile_button)

        binding.btnNotifications.setOnClickListener { openNotificationsSheet() }

        if (session.isTaker()) {
            detailVm.getTaker(session.getUserId()).observe(viewLifecycleOwner) { entity ->
                val taker = entity?.toModel() ?: return@observe
                renderHeaderProfile(taker.profileImageUrl, taker.profileThumbUrl)
            }
        }
    }

    private fun renderHeaderProfile(fullUrl: String?, thumbUrl: String?) {
        val request = Glide.with(this)
            .load(fullUrl ?: thumbUrl ?: R.drawable.ic_person_placeholder)
            .circleCrop()
            .placeholder(R.drawable.ic_person_placeholder)
        if (!thumbUrl.isNullOrBlank() && thumbUrl != fullUrl) {
            request.thumbnail(Glide.with(this).load(thumbUrl).circleCrop())
        }
        request.into(binding.ivHeaderProfile)
    }

    private fun setupCategories() {
        categoryAdapter = CategoryAdapter(SERVICE_TYPES, SERVICE_LABELS, SERVICE_ICONS) { service ->
            selectedService = if (selectedService == service) null else service
            categoryAdapter.setSelected(selectedService)
            applyFilters()
        }
        binding.rvCategories.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = categoryAdapter
        }
    }

    private fun setupFeatured() {
        featuredAdapter = FeaturedTakerAdapter { openDetail(it) }
        binding.rvFeatured.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = featuredAdapter
            layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.layout_staggered_fade)
        }
    }

    private fun setupGrid() {
        takerAdapter = TakerAdapter { openDetail(it) }
        takerGridLayoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvTakers.apply {
            layoutManager = takerGridLayoutManager
            adapter = takerAdapter
            layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.layout_staggered_fade)
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if ((right - left) != (oldRight - oldLeft)) {
                    updateGridColumns(right - left)
                }
            }
        }
        binding.rvTakers.post { updateGridColumns(binding.rvTakers.width) }
        binding.swipeRefresh.setOnRefreshListener { applyFilters() }
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
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                liveSearchQuery = query?.trim().orEmpty()
                selectedLocation = liveSearchQuery.ifBlank { null }
                applyFilters()
                binding.searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(query: String?): Boolean {
                liveSearchQuery = query?.trim().orEmpty()
                renderVisibleLists()
                if (liveSearchQuery.isBlank() && selectedLocation != null) {
                    selectedLocation = null
                    applyFilters()
                }
                return true
            }
        })
    }

    private fun setupFilters() {
        binding.btnDateFilter.setOnClickListener {
            val calendar = java.util.Calendar.getInstance()
            DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    selectedDate = "%04d-%02d-%02d".format(year, month + 1, day)
                    syncDateFilterUi()
                    applyFilters()
                },
                calendar.get(java.util.Calendar.YEAR),
                calendar.get(java.util.Calendar.MONTH),
                calendar.get(java.util.Calendar.DAY_OF_MONTH)
            ).show()
        }
        binding.btnClearDateFilter.setOnClickListener { clearDateFilter() }
        binding.switchAvailableOnly.setOnCheckedChangeListener { _, isChecked ->
            isAvailableOnly = isChecked
            applyFilters()
        }
        syncDateFilterUi()
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
                    bindNotificationBadge(currentUnreadCount)
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
                        notifications = emptyList(),
                        unreadCount = 0,
                        emptyMessage = result.message,
                    )
                }
            }
        }

        notificationsVm.markReadState.observe(viewLifecycleOwner) { result ->
            when (result) {
                is Result.Success -> notificationsVm.fetchNotifications()
                is Result.Error -> requireContext().toast(result.message)
                Result.Loading -> Unit
            }
        }
    }

    private fun bindNotificationBadge(unreadCount: Int) {
        binding.tvNotificationBadge.isVisible = unreadCount > 0
        binding.tvNotificationBadge.text = when {
            unreadCount > 99 -> "99+"
            unreadCount > 0 -> unreadCount.toString()
            else -> ""
        }
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
        notificationsVm.fetchNotifications()
    }

    private fun onNotificationTapped(notification: NotificationItem) {
        if (!notification.isRead) {
            notificationsVm.markRead(notification.id)
        }

        when (notification.type) {
            "booking_request", "booking_created", "booking_status", "booking_cancelled" -> {
                notificationsSheet?.dismiss()
                findNavController().navigate(R.id.bookingsFragment)
            }
            "favorite_taker_post" -> {
                notificationsSheet?.dismiss()
                openDetailFromNotification(notification)
            }
        }
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

        startActivity(Intent(requireContext(), TakerDetailActivity::class.java).apply {
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

    private fun applyFilters() {
        takerAdapter.updateAvailabilityDate(selectedDate)
        takerAdapter.updateFavorites(favoriteTakerIds)
        viewModel.fetch(
            location = selectedLocation,
            date_ = selectedDate,
            service = selectedService,
            availOnly = isAvailableOnly,
            latitude = deviceLatitude,
            longitude = deviceLongitude,
        )
    }

    private fun observeFavorites() {
        if (!session.isLoggedIn() || session.isGuest()) {
            favoriteTakerIds = emptySet()
            takerAdapter.updateFavorites(favoriteTakerIds)
            return
        }
        favoriteRepo.getFavorites(session.getUserId()).observe(viewLifecycleOwner) { list ->
            favoriteTakerIds = list.map { it.takerId }.toSet()
            takerAdapter.updateFavorites(favoriteTakerIds)
        }
    }

    private fun observeState() {
        viewModel.state.observe(viewLifecycleOwner) { result ->
            binding.swipeRefresh.isRefreshing = false
            when (result) {
                is Result.Loading -> {
                    binding.shimmerLayout.startShimmer()
                    binding.shimmerLayout.show()
                    binding.rvTakers.hide()
                    binding.layoutEmpty.hide()
                }
                is Result.Success -> {
                    binding.shimmerLayout.stopShimmer()
                    binding.shimmerLayout.hide()
                    binding.rvTakers.show()

                    currentTakers = result.data.takers.filterNot { session.isTaker() && it.id == session.getUserId() }
                    currentFeatured = result.data.featured.filterNot { session.isTaker() && it.id == session.getUserId() }
                    binding.tvFeaturedTitle.text = if (selectedLocation.isNullOrBlank() && deviceLatitude != null && deviceLongitude != null) {
                        getString(R.string.home_top_near_you_title)
                    } else {
                        getString(R.string.home_top_creators_title)
                    }
                    renderVisibleLists()
                    binding.layoutEmpty.isVisible = currentTakers.isEmpty()
                }
                is Result.Error -> {
                    binding.shimmerLayout.stopShimmer()
                    binding.shimmerLayout.hide()
                    currentTakers = emptyList()
                    currentFeatured = emptyList()
                    renderVisibleLists()
                    binding.layoutEmpty.show()
                    toast(result.message)
                }
            }
        }
    }

    private fun renderVisibleLists() {
        val filteredTakers = filterTakers(currentTakers, liveSearchQuery)
        val filteredFeatured = filterTakers(currentFeatured, liveSearchQuery)
        takerAdapter.submitList(filteredTakers)
        takerAdapter.updateAvailabilityDate(selectedDate)
        takerAdapter.updateFavorites(favoriteTakerIds)
        featuredAdapter.submitList(filteredFeatured)
        binding.sectionFeatured.isVisible = filteredFeatured.isNotEmpty()
        binding.layoutEmpty.isVisible = filteredTakers.isEmpty() && binding.shimmerLayout.visibility != View.VISIBLE
        binding.rvFeatured.scheduleLayoutAnimation()
        binding.rvTakers.scheduleLayoutAnimation()
    }

    private fun filterTakers(source: List<Taker>, query: String): List<Taker> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) {
            return source
        }
        return source.filter { taker ->
            taker.fullName.lowercase().contains(normalizedQuery) ||
                taker.area.lowercase().contains(normalizedQuery) ||
                taker.city.lowercase().contains(normalizedQuery) ||
                taker.state.lowercase().contains(normalizedQuery) ||
                taker.pincode.lowercase().contains(normalizedQuery) ||
                taker.offeredServices.any { it.lowercase().contains(normalizedQuery) }
        }
    }

    private fun requestLocationFeed() {
        if (hasLocationPermission()) {
            requestCurrentLocation()
            return
        }
        if (!requestedLocationOnce) {
            requestedLocationOnce = true
            locationPermissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        val context = context ?: return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCurrentLocation() {
        val context = context ?: return
        val locationManager = ContextCompat.getSystemService(context, LocationManager::class.java) ?: return
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }

        val lastKnown = sequenceOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
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
            Consumer<Location> { location ->
            if (location != null) {
                consumeDeviceLocation(location)
            }
        })
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
        val target = if (session.isTaker()) R.id.takerProfileFragment else R.id.profileFragment
        if (findNavController().currentDestination?.id != target) {
            findNavController().navigate(target)
        }
    }

    private fun openDetail(taker: Taker) {
        val services = taker.offeredServices
        val defaultService = selectedService?.takeIf { it in services } ?: services.primaryServiceType()
        startActivity(Intent(requireContext(), TakerDetailActivity::class.java).apply {
            putExtra(TakerDetailActivity.EXTRA_ID, taker.id)
            putExtra(TakerDetailActivity.EXTRA_NAME, taker.fullName)
            putExtra(TakerDetailActivity.EXTRA_SVC, defaultService)
            putStringArrayListExtra(TakerDetailActivity.EXTRA_SERVICES, ArrayList(services))
            putExtra(TakerDetailActivity.EXTRA_CITY, "${taker.area}, ${taker.city}")
            putExtra(TakerDetailActivity.EXTRA_RATING, taker.avgRating)
            putExtra(TakerDetailActivity.EXTRA_IMG, taker.profileImageUrl)
            putExtra(TakerDetailActivity.EXTRA_THUMB, taker.profileThumbUrl)
        })
    }

    override fun onDestroyView() {
        notificationsSheet?.dismiss()
        notificationsSheet = null
        notificationsSheetBinding = null
        _binding = null
        super.onDestroyView()
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
