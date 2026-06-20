package com.photoconnect.ui.fragments

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.photoconnect.R
import com.photoconnect.databinding.BottomSheetEventBinding
import com.photoconnect.databinding.FragmentBookingsBinding
import com.photoconnect.db.EventEntity
import com.photoconnect.model.UpsertEventRequest
import com.photoconnect.repository.Result
import com.photoconnect.repository.TakerRepository
import com.photoconnect.ui.activities.LoginActivity
import com.photoconnect.ui.adapters.EventAdapter
import com.photoconnect.utils.SERVICE_TYPES
import com.photoconnect.utils.SessionManager
import com.photoconnect.utils.hide
import com.photoconnect.utils.isNetworkAvailable
import com.photoconnect.utils.serviceLabels
import com.photoconnect.utils.show
import com.photoconnect.utils.toDisplayDate
import com.photoconnect.utils.toDayPartLabel
import com.photoconnect.utils.toServiceTypeInput
import com.photoconnect.utils.toServiceLabel
import com.photoconnect.utils.toServiceTypeOrNull
import com.photoconnect.utils.toast
import com.photoconnect.viewmodel.BookingViewModel
import com.photoconnect.viewmodel.EventViewModel
import com.photoconnect.viewmodel.NotificationViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.net.URLEncoder
import java.util.Calendar
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BookingsFragment : Fragment() {
    private var _binding: FragmentBookingsBinding? = null
    private val binding get() = _binding!!
    private val bookingVm: BookingViewModel by viewModels()
    private val eventVm: EventViewModel by viewModels()
    private val notificationsVm: NotificationViewModel by viewModels()

    @Inject lateinit var session: SessionManager
    @Inject lateinit var takerRepo: TakerRepository

    private lateinit var adapter: EventAdapter
    private var events = emptyList<EventEntity>()
    private var showingReceived = false
    private var activeFilter = EventFilter.ALL
    private var selectedMonth: String? = null
    private var searchQuery: String = ""
    private var pendingActionBookingId: Int? = null
    private var hasAnimatedEventList = false
    private var targetEventId: Int? = null
    private var targetBookingId: Int? = null
    private var addEventExtended = true
    private var heroExpandedHeight = 0
    private var heroCollapsedHeight = 0
    private val markedNotificationIds = mutableSetOf<Int>()
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var lastRemoteEventRefreshAt = 0L
    private var pendingSaveTargetReceivedScope: Boolean? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        FragmentBookingsBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!session.isLoggedIn() || session.isGuest()) {
            binding.layoutGuest.show()
            binding.contentGroup.hide()
            binding.btnSignInBookings.setOnClickListener {
                startActivity(Intent(requireContext(), LoginActivity::class.java))
            }
            return
        }

        binding.layoutGuest.hide()
        binding.contentGroup.show()
        showingReceived = session.isTaker()
        readNavigationTarget()

        adapter = EventAdapter(
            actorRole = session.getRole(),
            actionRoleForEvent = { event -> actionRoleFor(event) },
            onBookingAction = { event, targetStatus ->
                confirmBookingAction(event, targetStatus)
            },
            onEdit = { showEventSheet(it) },
            onShare = { shareEventStatement(it) },
            onCall = { callEventPhone(it) },
            onDelete = { confirmDeleteEvent(it) },
        )

        binding.rvBookings.layoutManager = LinearLayoutManager(requireContext())
        binding.rvBookings.adapter = adapter
        binding.rvBookings.layoutAnimation =
            AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_staggered_fade)

        bindHeader()
        bindFilters()
        bindObservers()
        bindHeroCollapse()
        bindAddEventFabCollapse()

        binding.btnAddEvent.setOnClickListener { chooseNewEventTarget() }
        binding.swipeRefresh.setOnRefreshListener { refreshRemoteEventData(force = true) }
        eventVm.fetchEvents()
        setupEventsNotificationReadSync()
    }

    override fun onStart() {
        super.onStart()
        if (!session.isLoggedIn() || session.isGuest()) return
        if (requireContext().isNetworkAvailable()) refreshRemoteEventData(force = false)
        registerNetworkResync()
    }

    override fun onStop() {
        unregisterNetworkResync()
        super.onStop()
    }

    private fun refreshRemoteEventData(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRemoteEventRefreshAt < REMOTE_REFRESH_THROTTLE_MS) return
        lastRemoteEventRefreshAt = now
        eventVm.fetchEvents(force = force)
        notificationsVm.fetchNotifications(force = force)
    }

    private fun registerNetworkResync() {
        if (networkCallback != null) return
        val cm = requireContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                activity?.runOnUiThread {
                    if (_binding != null && isAdded) refreshRemoteEventData(force = false)
                }
            }
        }
        cm.registerNetworkCallback(request, networkCallback!!)
    }

    private fun unregisterNetworkResync() {
        val callback = networkCallback ?: return
        networkCallback = null
        val cm = context?.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        try {
            cm?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    private fun bindHeroCollapse() {
        // Measure the actual content height dynamically instead of forcing 238dp
        binding.cardEventsHero.post {
            heroExpandedHeight = binding.cardEventsHero.height.coerceAtLeast(dp(180f))
            heroCollapsedHeight = dp(86f)
        }
        binding.appBarBookings.addOnOffsetChangedListener { appBar, verticalOffset ->
            val range = appBar.totalScrollRange.takeIf { it > 0 } ?: return@addOnOffsetChangedListener
            val fraction = (abs(verticalOffset).toFloat() / range.toFloat()).coerceIn(0f, 1f)
            applyHeroCollapse(fraction, resources.displayMetrics.density)
            updateAddEventFab(shouldExtend = fraction < 0.18f && binding.rvBookings.computeVerticalScrollOffset() < dp(8f))
        }
    }

    private fun applyHeroCollapse(fraction: Float, density: Float) {
        val detailAlpha = (1f - fraction * 1.45f).coerceIn(0f, 1f)
        val padding = lerp(18f, 9f, fraction) * density
        val heroTopMargin = lerp(14f, 2f, fraction) * density
        binding.tvSubheading.alpha = detailAlpha
        binding.tvSubheading.isVisible = fraction < 0.68f
        binding.layoutBudgetSummary.alpha = (1f - fraction * 1.15f).coerceIn(0f, 1f)
        binding.layoutBudgetSummary.isVisible = fraction < 0.78f
        binding.toggleOrderScope.alpha = (1f - fraction * 0.75f).coerceIn(0.35f, 1f)
        binding.cardEventsHero.scaleX = 1f - (fraction * 0.03f)
        binding.cardEventsHero.scaleY = 1f
        binding.cardEventsHero.translationY = -fraction * 4f * density
        binding.layoutEventsHeroContent.setPadding(
            padding.toInt(),
            padding.toInt(),
            padding.toInt(),
            padding.toInt(),
        )
        binding.tvHeading.setTextSize(TypedValue.COMPLEX_UNIT_SP, lerp(21f, 15f, fraction))
        binding.tvSummaryCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, lerp(30f, 20f, fraction))
        binding.tvSummaryLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, lerp(12f, 9f, fraction))
        (binding.layoutHeroSummary.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            val next = heroTopMargin.toInt()
            if (params.topMargin != next) {
                params.topMargin = next
                binding.layoutHeroSummary.layoutParams = params
            }
        }
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + ((end - start) * fraction.coerceIn(0f, 1f))

    private fun bindAddEventFabCollapse() {
        binding.rvBookings.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val offset = recyclerView.computeVerticalScrollOffset()
                val nearTop = offset < dp(8f)
                val fraction = (offset.toFloat() / dp(140f).coerceAtLeast(1)).coerceIn(0f, 1f)
                applyHeroCollapse(fraction, resources.displayMetrics.density)
                updateAddEventFab(shouldExtend = nearTop)
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                // Update adapter scroll state to prevent flickering during scroll
                adapter.setScrolling(newState != RecyclerView.SCROLL_STATE_IDLE)
            }
        })
    }

    private fun updateAddEventFab(shouldExtend: Boolean) {
        if (addEventExtended == shouldExtend) return
        addEventExtended = shouldExtend
        if (shouldExtend) binding.btnAddEvent.extend() else binding.btnAddEvent.shrink()
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun bindHeader() {
        binding.tvHeading.text = if (session.isTaker()) getString(R.string.bookings_heading_taker) else getString(R.string.bookings_heading_client)
        binding.tvSubheading.text = if (session.isTaker()) {
            getString(R.string.bookings_subheading_taker)
        } else {
            getString(R.string.bookings_subheading_client)
        }
        binding.toggleOrderScope.isVisible = session.isTaker()
        binding.tabReceivedOrders.isVisible = session.isTaker()
        binding.toggleOrderScope.check(
            if (showingReceived) binding.tabReceivedOrders.id else binding.tabPlacedOrders.id
        )
        binding.toggleOrderScope.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            showingReceived = checkedId == binding.tabReceivedOrders.id
            renderEvents()
        }
    }

    private fun bindFilters() {
        binding.toggleEventFilter.check(binding.filterAllEvents.id)
        binding.toggleEventFilter.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            activeFilter = when (checkedId) {
                binding.filterUpcomingEvents.id -> EventFilter.UPCOMING
                binding.filterPendingEvents.id -> EventFilter.PENDING
                binding.filterCompletedEvents.id -> EventFilter.COMPLETED
                binding.filterCancelledEvents.id -> EventFilter.CANCELLED
                else -> EventFilter.ALL
            }
            renderEvents()
        }
        binding.btnMonthFilter.setOnClickListener { showMonthFilterDialog() }
        binding.btnAdvancedAnalytics.setOnClickListener {
            openAdvancedAnalytics()
        }
        
        binding.etSearchEvents.addTextChangedListener { text ->
            searchQuery = text?.toString()?.trim().orEmpty()
            renderEvents()
        }
        
        binding.etSearchEvents.setOnClickListener {
            binding.appBarBookings.setExpanded(false, true)
        }
        
        binding.etSearchEvents.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.appBarBookings.setExpanded(false, true)
            }
        }
    }
    
    private fun showMonthFilterDialog() {
        val scoped = scopedEvents()
        val months = scoped.mapNotNull { event ->
            if (event.eventDate.length >= 7) event.eventDate.substring(0, 7) else null
        }.distinct().sortedDescending()
        
        val options = mutableListOf(getString(R.string.events_all_time))
        options.addAll(months.map { it.toMonthDisplay() })
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.events_filter_by_month)
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    selectedMonth = null
                    binding.btnMonthFilter.text = getString(R.string.events_all_time)
                } else {
                    selectedMonth = months[which - 1]
                    binding.btnMonthFilter.text = options[which]
                }
                renderEvents()
            }
            .show()
    }
    
    private fun String.toMonthDisplay(): String {
        val parts = this.split("-")
        if (parts.size != 2) return this
        val year = parts[0]
        val monthIdx = parts[1].toIntOrNull() ?: return this
        val monthNames = java.text.DateFormatSymbols.getInstance(resources.configuration.locales[0]).shortMonths
        val monthName = monthNames.getOrElse(monthIdx - 1) { parts[1] }.ifBlank { parts[1] }
        return "$monthName $year"
    }

    private fun openAdvancedAnalytics() {
        val navController = findNavController()
        val destinationId = R.id.analyticsFragment
        if (navController.currentDestination?.id == destinationId) return
        if (navController.graph.findNode(destinationId) == null) {
            toast(getString(R.string.events_advanced_analytics_unavailable))
            return
        }
        runCatching { navController.navigate(destinationId) }
            .onFailure { toast(getString(R.string.events_advanced_analytics_unavailable)) }
    }

    private fun bindObservers() {
        eventVm.cachedEvents().observe(viewLifecycleOwner) {
            events = it
            renderEvents()
        }
        eventVm.eventsState.observe(viewLifecycleOwner) { result ->
            binding.swipeRefresh.isRefreshing = false
            when (result) {
                is Result.Loading -> {
                    if (events.isEmpty()) binding.progressBar.show() else binding.progressBar.hide()
                }
                is Result.Success -> binding.progressBar.hide()
                is Result.Error -> {
                    binding.progressBar.hide()
                    if (events.isEmpty()) toast(result.message)
                }
            }
        }
        eventVm.saveState.observe(viewLifecycleOwner) { result ->
            when (result) {
                is Result.Loading -> binding.progressBar.show()
                is Result.Success -> {
                    binding.progressBar.hide()
                    val targetReceived = pendingSaveTargetReceivedScope
                    if (targetReceived != null && session.isTaker()) {
                        showingReceived = targetReceived
                        binding.toggleOrderScope.check(
                            if (showingReceived) binding.tabReceivedOrders.id else binding.tabPlacedOrders.id
                        )
                        renderEvents()
                    }
                    pendingSaveTargetReceivedScope = null
                    toast(getString(R.string.event_saved))
                }
                is Result.Error -> {
                    binding.progressBar.hide()
                    pendingSaveTargetReceivedScope = null
                    toast(result.message)
                }
            }
        }
        eventVm.deleteState.observe(viewLifecycleOwner) { result ->
            when (result) {
                is Result.Loading -> binding.progressBar.show()
                is Result.Success -> {
                    binding.progressBar.hide()
                    toast(getString(R.string.event_deleted))
                }
                is Result.Error -> {
                    binding.progressBar.hide()
                    toast(result.message)
                }
            }
        }
        bookingVm.updateState.observe(viewLifecycleOwner) { result ->
            when (result) {
                is Result.Loading -> binding.progressBar.show()
                is Result.Success -> {
                    pendingActionBookingId = null
                    adapter.setPendingBooking(null)
                    binding.progressBar.hide()
                    eventVm.fetchEvents(force = true)
                    toast(getString(R.string.booking_updated_to, result.data.status))
                }
                is Result.Error -> {
                    pendingActionBookingId = null
                    adapter.setPendingBooking(null)
                    binding.progressBar.hide()
                    toast(result.message)
                }
            }
        }
        bookingVm.studioReviewState.observe(viewLifecycleOwner) { result ->
            when (result) {
                is Result.Loading -> binding.progressBar.show()
                is Result.Success -> {
                    binding.progressBar.hide()
                    toast(getString(R.string.studio_review_submitted))
                }
                is Result.Error -> {
                    binding.progressBar.hide()
                    toast(result.message)
                }
            }
        }
    }

    private fun scopedEvents(): List<EventEntity> {
        val baseList = if (!session.isTaker()) events else {
            val takerId = session.getTakerActorId()
            val clientProfileId = session.getClientProfileId()
            if (showingReceived) {
                events.filter {
                    it.takerId == takerId &&
                        isClientBookingEvent(it, takerId, clientProfileId)
                }
            } else {
                events.filter {
                    !isClientBookingEvent(it, takerId, clientProfileId) &&
                        (
                            (it.createdByRole == SessionManager.ROLE_TAKER && it.createdById == takerId) ||
                                (clientProfileId != 0 && it.clientId == clientProfileId)
                        )
                }
            }
        }
        
        val cal = Calendar.getInstance()
        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
        
        return baseList.map { event ->
            if (event.status == "Upcoming" || event.status == "Confirmed" || event.status == "Pending") {
                val isPastDate = event.eventDate < todayStr
                val isToday = event.eventDate == todayStr
                val shouldComplete = isPastDate || (isToday && when(event.dayPart) {
                    "first_half" -> currentHour >= 14
                    "second_half" -> currentHour >= 22
                    else -> currentHour >= 22
                })
                
                if (shouldComplete) {
                    event.copy(status = "Completed")
                } else {
                    event
                }
            } else {
                event
            }
        }
    }

    private fun actionRoleFor(event: EventEntity): String {
        if (!session.isTaker()) return SessionManager.ROLE_CLIENT
        val isReceivedBooking = event.takerId == session.getTakerActorId() && event.bookingId != null
        return if (isReceivedBooking) SessionManager.ROLE_TAKER else SessionManager.ROLE_CLIENT
    }

    private fun confirmBookingAction(event: EventEntity, targetStatus: String) {
        val bookingId = event.bookingId ?: return
        if (targetStatus == EventAdapter.ACTION_REVIEW_STUDIO) {
            showStudioReviewDialog(event)
            return
        }
        val actionLabel = statusActionLabel(targetStatus)
        val conflictingUnconfirmed = if (targetStatus == "Confirmed") unconfirmedSlotConflicts(event) else emptyList()
        val message = buildString {
            append(getString(R.string.event_action_confirm_message, actionLabel.lowercase()))
            if (conflictingUnconfirmed.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine(getString(R.string.event_confirm_conflicts_warning))
                conflictingUnconfirmed.take(5).forEach { conflict ->
                    val bookedBy = conflict.clientName
                        ?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.booking_default_client)
                    appendLine("- $bookedBy: ${conflict.title} (${conflict.eventDate.toDisplayDate()}, ${conflict.dayPart.toDayPartLabel(requireContext())}, ${eventStatusLabel(conflict.status)})")
                }
                if (conflictingUnconfirmed.size > 5) {
                    appendLine(getString(R.string.event_conflicts_more_count, conflictingUnconfirmed.size - 5))
                }
            }
        }
        val positiveLabel = if (targetStatus == "Cancelled") {
            getString(R.string.cancel_event_action)
        } else {
            actionLabel
        }
        val negativeLabel = if (targetStatus == "Cancelled") R.string.keep_event else R.string.cancel
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.event_action_confirm_title)
            .setMessage(message)
            .setPositiveButton(positiveLabel) { _, _ ->
                pendingActionBookingId = bookingId
                adapter.setPendingBooking(bookingId)
                bookingVm.updateBookingStatus(
                    bookingId,
                    targetStatus,
                    asTaker = actionRoleFor(event) == SessionManager.ROLE_TAKER,
                )
            }
            .setNegativeButton(negativeLabel, null)
            .show()
    }

    private fun unconfirmedSlotConflicts(event: EventEntity): List<EventEntity> {
        val bookingId = event.bookingId ?: return emptyList()
        val takerId = event.takerId ?: return emptyList()
        return events
            .asSequence()
            .filter { it.bookingId != null && it.bookingId != bookingId }
            .filter { it.takerId == takerId && it.eventDate == event.eventDate }
            .filter { it.status in setOf("Pending", "Upcoming") }
            .filter { dayPartsConflict(event.dayPart, it.dayPart) }
            .sortedWith(compareBy<EventEntity> { it.eventDate }.thenBy { it.title })
            .toList()
    }

    private fun dayPartsConflict(left: String, right: String): Boolean =
        left == "full_day" || right == "full_day" || left == right

    private fun showStudioReviewDialog(event: EventEntity) {
        val clientId = event.clientId ?: return
        val content = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
        }
        val ratingBar = android.widget.RatingBar(requireContext()).apply {
            numStars = 5
            stepSize = 1f
            rating = 5f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
        }
        val commentInput = com.google.android.material.textfield.TextInputEditText(requireContext())
        val commentLayout = com.google.android.material.textfield.TextInputLayout(requireContext()).apply {
            hint = getString(R.string.review_comment_hint)
            addView(commentInput)
        }
        content.addView(ratingBar)
        content.addView(commentLayout)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.review_studio_named, event.clientName ?: getString(R.string.booking_default_client)))
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.submit) { _, _ ->
                val rating = ratingBar.rating.toInt()
                if (rating <= 0) {
                    toast(getString(R.string.give_rating))
                } else {
                    bookingVm.submitStudioReview(
                        clientId = clientId,
                        rating = rating,
                        comment = commentInput.text?.toString()?.trim()?.ifEmpty { null },
                    )
                }
            }
            .show()
    }

    private fun statusActionLabel(status: String): String =
        when (status) {
            "Confirmed" -> getString(R.string.confirm)
            "Cancelled" -> getString(R.string.cancel)
            "Completed" -> getString(R.string.mark_complete)
            else -> status
        }

    private fun renderEvents() {
        if (alignScopeToNavigationTarget()) return
        var scoped = scopedEvents()
        
        if (selectedMonth != null) {
            scoped = scoped.filter { it.eventDate.startsWith(selectedMonth!!) }
        }
        
        if (searchQuery.isNotEmpty()) {
            val query = searchQuery.lowercase()
            scoped = scoped.filter {
                (it.title.lowercase().contains(query)) ||
                (it.clientName?.lowercase()?.contains(query) == true) ||
                (it.clientPhone?.lowercase()?.contains(query) == true) ||
                (it.takerName?.lowercase()?.contains(query) == true) ||
                (it.notes?.lowercase()?.contains(query) == true) ||
                (it.location?.lowercase()?.contains(query) == true) ||
                (it.serviceType?.lowercase()?.contains(query) == true) ||
                (it.status.lowercase().contains(query)) ||
                (it.eventDate.lowercase().contains(query)) ||
                (it.totalAmount.toString().contains(query))
            }
        }

        val visible = scoped.filter { event ->
            when (activeFilter) {
                EventFilter.ALL -> true
                EventFilter.UPCOMING -> event.status in setOf("Upcoming", "Confirmed")
                EventFilter.PENDING -> event.status == "Pending"
                EventFilter.COMPLETED -> event.status == "Completed"
                EventFilter.CANCELLED -> event.status == "Cancelled"
            }
        }
        adapter.submitList(visible) { scrollToNavigationTarget(visible) }
        if (!hasAnimatedEventList && visible.isNotEmpty()) {
            binding.rvBookings.scheduleLayoutAnimation()
            hasAnimatedEventList = true
        }
        binding.layoutEmptyState.isVisible = visible.isEmpty()
        binding.tvEmpty.text = getString(R.string.bookings_empty)
        binding.tvSummaryCount.text = scoped.size.toString()
        binding.tvSummaryLabel.text = when {
            session.isTaker() && showingReceived -> getString(R.string.bookings_scope_received)
            session.isTaker() -> getString(R.string.bookings_scope_placed)
            else -> getString(R.string.bookings_count_label)
        }
        
        val total = scoped.sumOf { it.totalAmount }
        val paid = scoped.sumOf { it.paidAmount }
        val balance = scoped.sumOf { maxOf(0.0, it.totalAmount - it.paidAmount) }
        
        binding.tvTotalBudget.text = "${getString(R.string.events_total_budget)}\n${EventAdapter.compactMoney(total)}"
        binding.tvPaidBudget.text = "${getString(R.string.events_paid_budget)}\n${EventAdapter.compactMoney(paid)}"
        binding.tvBalanceBudget.text = "${getString(R.string.events_balance_budget)}\n${EventAdapter.compactMoney(balance)}"
        
        // Analytics Update
        val avg = if (scoped.isNotEmpty()) total / scoped.size else 0.0
        binding.tvAvgIncome.text = getString(R.string.events_avg_income_per_event, EventAdapter.compactMoney(avg))
        
        val paidPercentage = if (total > 0.0) {
            (paid / total * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }
        binding.tvPaidPercentage.text = "$paidPercentage%"
        binding.progressPaidBudget.progress = paidPercentage
    }

    private fun readNavigationTarget() {
        val intent = requireActivity().intent ?: return
        targetEventId = intent.getIntExtra(EXTRA_NAV_EVENT_ID, 0).takeIf { it > 0 }
        targetBookingId = intent.getIntExtra(EXTRA_NAV_BOOKING_ID, 0).takeIf { it > 0 }
    }

    private fun alignScopeToNavigationTarget(): Boolean {
        if (!session.isTaker()) return false
        val target = events.firstOrNull { event ->
            targetEventId?.let { event.id == it } == true ||
                targetBookingId?.let { event.bookingId == it } == true
        } ?: return false
        val shouldShowReceived = target.takerId == session.getTakerActorId() &&
            isClientBookingEvent(target, session.getTakerActorId())
        if (showingReceived == shouldShowReceived) return false
        showingReceived = shouldShowReceived
        binding.toggleOrderScope.check(
            if (showingReceived) binding.tabReceivedOrders.id else binding.tabPlacedOrders.id
        )
        return true
    }

    private fun scrollToNavigationTarget(visible: List<EventEntity>) {
        val index = visible.indexOfFirst { event ->
            targetEventId?.let { event.id == it } == true ||
                targetBookingId?.let { event.bookingId == it } == true
        }
        if (index < 0) return
        val target = visible[index]
        adapter.setHighlightedEvent(target.id)
        binding.rvBookings.post {
            (binding.rvBookings.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(index, (16 * resources.displayMetrics.density).toInt())
            binding.rvBookings.postDelayed({ adapter.setHighlightedEvent(null) }, 3200L)
        }
        requireActivity().intent?.removeExtra(EXTRA_NAV_EVENT_ID)
        requireActivity().intent?.removeExtra(EXTRA_NAV_BOOKING_ID)
        targetEventId = null
        targetBookingId = null
    }

    private fun chooseNewEventTarget() {
        if (!session.isTaker()) {
            showEventSheet(null)
            return
        }
        val labels = arrayOf(
            getString(R.string.event_target_my_booking),
            getString(R.string.event_target_client_booking),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.event_target_picker_title)
            .setItems(labels) { _, which -> showEventSheet(null, targetClientBooking = which == 1) }
            .show()
    }

    private fun showEventSheet(existing: EventEntity?, targetClientBooking: Boolean? = null) {
        val sheet = BottomSheetEventBinding.inflate(layoutInflater)
        val isCancelledEvent = existing?.status == "Cancelled"
        val clientBookingTarget = targetClientBooking
            ?: existing?.let { event -> isClientBookingEvent(event, session.getTakerActorId()) }
            ?: false
        val labels = serviceLabels(requireContext())
        sheet.actvServiceType.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels))
        sheet.tvSheetTitle.text = getString(if (existing == null) R.string.events_add else R.string.edit)
        var selectedDate = existing?.eventDate.orEmpty()
        fun selectedServiceType(): String? {
            val selected = sheet.actvServiceType.text?.toString().orEmpty()
            return if (selected.toServiceTypeOrNull(requireContext()) == "other") {
                sheet.etCustomService.text?.toString()?.toServiceTypeInput(requireContext())
            } else {
                selected.toServiceTypeInput(requireContext())
            }
        }
        fun refreshCustomServiceVisibility() {
            val selected = sheet.actvServiceType.text?.toString().orEmpty().toServiceTypeOrNull(requireContext())
            sheet.tilCustomService.isVisible = selected == "other"
        }
        fun applyPredictedAmount() {
            if (existing != null) return
            if (!sheet.etTotalAmount.text.isNullOrBlank()) return
            val service = selectedServiceType() ?: return
            val phone = sheet.etClientPhone.text?.toString()?.filter(Char::isDigit).orEmpty()
            val clientName = sheet.etClientName.text?.toString()?.trim().orEmpty()
            val match = events
                .filter { it.totalAmount > 0.0 && it.serviceType == service }
                .filter {
                    val oldPhone = it.clientPhone.orEmpty().filter(Char::isDigit).takeLast(10)
                    (phone.length == 10 && oldPhone == phone) ||
                        (clientName.length >= 3 && it.clientName.equals(clientName, ignoreCase = true))
                }
                .maxByOrNull { it.updatedAt ?: it.eventDate }
            if (match != null) sheet.etTotalAmount.setText(match.totalAmount.toString().trimEnd('0').trimEnd('.'))
        }
        fun renderDate() {
            sheet.btnPickEventDate.text = selectedDate.takeIf { it.isNotBlank() }?.toDisplayDate()
                ?: getString(R.string.pick_event_date)
        }
        sheet.etEventTitle.setText(existing?.title.orEmpty())
        sheet.etEventLocation.setText(existing?.location.orEmpty())
        sheet.etClientName.setText(existing?.clientName.orEmpty())
        sheet.etClientPhone.setText(existing?.clientPhone.orEmpty())
        sheet.etTakerName.setText(existing?.takerName.orEmpty())
        sheet.etTotalAmount.setText(existing?.totalAmount?.takeIf { it > 0.0 }?.toString().orEmpty())
        sheet.etPaidAmount.setText(existing?.paidAmount?.takeIf { it > 0.0 }?.toString().orEmpty())
        sheet.etTotalAmount.isEnabled = !isCancelledEvent
        sheet.etPaidAmount.isEnabled = !isCancelledEvent
        sheet.etEventNotes.setText(existing?.notes.orEmpty())
        existing?.serviceType?.let {
            if (it in SERVICE_TYPES) {
                sheet.actvServiceType.setText(it.toServiceLabel(requireContext()), false)
            } else {
                sheet.actvServiceType.setText("other".toServiceLabel(requireContext()), false)
                sheet.etCustomService.setText(it.toServiceLabel(requireContext()))
            }
        }
        sheet.tilClientName.isVisible = session.isTaker() && clientBookingTarget
        sheet.tilClientPhone.isVisible = session.isTaker() && clientBookingTarget
        sheet.tilTakerName.isVisible = !session.isTaker() || (session.isTaker() && !clientBookingTarget)
        refreshCustomServiceVisibility()
        sheet.actvServiceType.setOnItemClickListener { _, _, _, _ ->
            refreshCustomServiceVisibility()
            applyPredictedAmount()
        }
        sheet.etCustomService.addTextChangedListener { applyPredictedAmount() }
        sheet.etClientPhone.addTextChangedListener { applyPredictedAmount() }
        sheet.etClientName.addTextChangedListener { applyPredictedAmount() }
        renderDate()
        sheet.btnPickEventDate.setOnClickListener {
            val cal = Calendar.getInstance()
            val parts = selectedDate.split("-")
            if (parts.size == 3) {
                cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
            }
            DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    selectedDate = "%04d-%02d-%02d".format(year, month + 1, day)
                    renderDate()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH),
            ).show()
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(sheet.root)
            .setPositiveButton(R.string.save_changes, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val title = sheet.etEventTitle.text?.toString()?.trim().orEmpty()
            if (title.isBlank()) {
                sheet.etEventTitle.error = getString(R.string.event_title_required)
                return@setOnClickListener
            }
            if (selectedDate.isBlank()) {
                toast(getString(R.string.event_date_required))
                return@setOnClickListener
            }
            val clientName = if (clientBookingTarget) sheet.etClientName.text?.toString()?.trim().orEmpty() else ""
            val clientPhone = if (clientBookingTarget) sheet.etClientPhone.text?.toString()?.filter(Char::isDigit).orEmpty() else ""
            if (session.isTaker() && clientBookingTarget && clientName.isBlank() && clientPhone.isBlank()) {
                sheet.etClientName.error = getString(R.string.event_client_required_for_client_booking)
                toast(getString(R.string.event_client_required_for_client_booking))
                return@setOnClickListener
            }
            if (clientPhone.isNotBlank() && clientPhone.length != 10) {
                sheet.etClientPhone.error = getString(R.string.error_phone)
                toast(getString(R.string.error_phone))
                return@setOnClickListener
            }
            val total = if (isCancelledEvent) {
                existing?.totalAmount ?: 0.0
            } else {
                sheet.etTotalAmount.text?.toString()?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
            }
            val paid = if (isCancelledEvent) {
                existing?.paidAmount ?: 0.0
            } else {
                (sheet.etPaidAmount.text?.toString()?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0).coerceAtMost(total)
            }
            pendingSaveTargetReceivedScope = if (session.isTaker() && existing == null) {
                clientBookingTarget
            } else {
                null
            }
            eventVm.saveEvent(
                UpsertEventRequest(
                    eventId = existing?.id,
                    clientId = existing?.clientId,
                    takerId = existing?.takerId ?: if (session.isTaker()) session.getTakerActorId() else null,
                    title = title,
                    eventDate = selectedDate,
                    dayPart = existing?.dayPart ?: "full_day",
                    serviceType = selectedServiceType(),
                    location = sheet.etEventLocation.text?.toString()?.trim()?.ifBlank { null },
                    clientName = clientName.ifBlank { null },
                    clientPhone = clientPhone.ifBlank { null },
                    takerName = sheet.etTakerName.text?.toString()?.trim()?.ifBlank { null },
                    totalAmount = total,
                    paidAmount = paid,
                    notes = sheet.etEventNotes.text?.toString()?.trim()?.ifBlank { null },
                    status = existing?.status ?: "Upcoming",
                )
            )
            dialog.dismiss()
        }
    }

    private fun isClientBookingEvent(event: EventEntity, takerId: Int, clientProfileId: Int = session.getClientProfileId()): Boolean =
        (event.bookingId != null && event.takerId == takerId && (clientProfileId <= 0 || event.clientId != clientProfileId)) ||
            (
                event.takerId == takerId &&
                    event.createdByRole == SessionManager.ROLE_TAKER &&
                    event.createdById == takerId &&
                    (
                        event.clientName?.isNotBlank() == true ||
                            event.clientPhone?.isNotBlank() == true
                    )
            )

    private fun shareEventStatement(event: EventEntity) {
        val dueEvents = dueEventsForClient(event)
        val creatorEvents = eventsForCreator(event)
        val actions = buildList<Pair<String, () -> Unit>> {
            add(getString(R.string.share_this_event) to { shareText(buildEventStatement(event), event.clientPhone) })
            if (dueEvents.isNotEmpty()) {
                add(getString(R.string.share_client_due_list) to { shareText(buildClientDueStatement(event, dueEvents), event.clientPhone) })
            }
            if (creatorEvents.isNotEmpty()) {
                add(getString(R.string.share_creator_event_list) to { shareText(buildCreatorEventStatement(event, creatorEvents), event.clientPhone) })
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.share_statement)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second.invoke() }
            .show()
    }

    private fun callEventPhone(event: EventEntity) {
        val actionRole = actionRoleFor(event)
        val phone = if (actionRole == SessionManager.ROLE_TAKER) {
            event.clientPhone.orEmpty().filter(Char::isDigit)
        } else {
            event.takerPhone.orEmpty().filter(Char::isDigit)
        }
        if (phone.length == 10) {
            dialPhone(phone)
            return
        }
        val takerId = event.takerId ?: 0
        if (actionRole != SessionManager.ROLE_TAKER && takerId > 0) {
            lifecycleScope.launch {
                when (val result = takerRepo.fetchTakerProfile(takerId)) {
                    is Result.Success -> {
                        val resolvedPhone = result.data.phone.orEmpty().filter(Char::isDigit)
                        if (resolvedPhone.length == 10) {
                            dialPhone(resolvedPhone)
                        } else {
                            toast(getString(R.string.error_phone))
                        }
                    }
                    is Result.Error -> toast(result.message)
                    Result.Loading -> Unit
                }
            }
            return
        }
        toast(getString(R.string.error_phone))
    }

    private fun dialPhone(phone: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.could_not_start_call))
        }
    }

    private fun buildEventStatement(event: EventEntity): String {
        val balance = maxOf(0.0, event.totalAmount - event.paidAmount)
        return buildString {
            appendLine(getString(R.string.event_statement_title))
            appendLine()
            appendLine("${event.title} - ${event.eventDate.toDisplayDate()}")
            appendLine("Status: ${eventStatusLabel(event.status)}")
            appendLine("Time: ${event.dayPart.toDayPartLabel(requireContext())}")
            event.clientName?.takeIf { it.isNotBlank() }?.let { appendLine("Client: $it") }
            event.takerName?.takeIf { it.isNotBlank() }?.let { appendLine("Creator: $it") }
            event.serviceType?.takeIf { it.isNotBlank() }?.let { appendLine("Service: ${it.toServiceLabel(requireContext())}") }
            event.location?.takeIf { it.isNotBlank() }?.let { appendLine("Location: $it") }
            appendLine("Total: ${EventAdapter.money(event.totalAmount)}")
            appendLine("Paid: ${EventAdapter.money(event.paidAmount)}")
            appendLine("Balance: ${EventAdapter.money(balance)}")
            event.notes?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("Notes: $it")
            }
        }
    }

    private fun buildClientDueStatement(anchor: EventEntity, dueEvents: List<EventEntity>): String {
        val client = anchor.clientName?.takeIf { it.isNotBlank() } ?: getString(R.string.booking_default_client)
        val total = dueEvents.sumOf { it.totalAmount }
        val paid = dueEvents.sumOf { it.paidAmount }
        val balance = dueEvents.sumOf { maxOf(0.0, it.totalAmount - it.paidAmount) }
        return buildString {
            appendLine(getString(R.string.client_due_statement_title))
            appendLine()
            appendLine("Client: $client")
            appendLine("Items: ${dueEvents.size}")
            appendLine("Total: ${EventAdapter.money(total)}")
            appendLine("Paid: ${EventAdapter.money(paid)}")
            appendLine("Balance: ${EventAdapter.money(balance)}")
            appendLine()
            dueEvents.forEachIndexed { index, event ->
                appendLine("${index + 1}. ${event.title} - ${event.eventDate.toDisplayDate()}")
                appendLine("   Status: ${eventStatusLabel(event.status)}")
                appendLine("   Time: ${event.dayPart.toDayPartLabel(requireContext())}")
                appendLine("   Due: ${EventAdapter.money(maxOf(0.0, event.totalAmount - event.paidAmount))}")
            }
        }
    }

    private fun buildCreatorEventStatement(anchor: EventEntity, creatorEvents: List<EventEntity>): String {
        val creator = anchor.takerName?.takeIf { it.isNotBlank() } ?: getString(R.string.booking_default_taker)
        val total = creatorEvents.sumOf { it.totalAmount }
        val paid = creatorEvents.sumOf { it.paidAmount }
        val balance = creatorEvents.sumOf { maxOf(0.0, it.totalAmount - it.paidAmount) }
        return buildString {
            appendLine(getString(R.string.creator_event_statement_title))
            appendLine()
            appendLine("Creator: $creator")
            appendLine("Events: ${creatorEvents.size}")
            appendLine("Total: ${EventAdapter.money(total)}")
            appendLine("Paid: ${EventAdapter.money(paid)}")
            appendLine("Balance: ${EventAdapter.money(balance)}")
            appendLine()
            creatorEvents.forEachIndexed { index, event ->
                appendLine("${index + 1}. ${event.title} - ${event.eventDate.toDisplayDate()}")
                appendLine("   Status: ${eventStatusLabel(event.status)}")
                appendLine("   Time: ${event.dayPart.toDayPartLabel(requireContext())}")
                event.clientName?.takeIf { it.isNotBlank() }?.let { appendLine("   Client: $it") }
                event.serviceType?.takeIf { it.isNotBlank() }?.let { appendLine("   Service: ${it.toServiceLabel(requireContext())}") }
                appendLine("   Total: ${EventAdapter.money(event.totalAmount)}")
                appendLine("   Paid: ${EventAdapter.money(event.paidAmount)}")
                appendLine("   Balance: ${EventAdapter.money(maxOf(0.0, event.totalAmount - event.paidAmount))}")
            }
        }
    }

    private fun dueEventsForClient(anchor: EventEntity): List<EventEntity> =
        events
            .asSequence()
            .filter { it.status != "Cancelled" }
            .filter { sameClient(anchor, it) }
            .filter { maxOf(0.0, it.totalAmount - it.paidAmount) > 0.0 }
            .sortedWith(compareBy<EventEntity> { it.eventDate }.thenBy { it.title })
            .toList()

    private fun sameClient(left: EventEntity, right: EventEntity): Boolean {
        val leftId = left.clientId
        val rightId = right.clientId
        if (leftId != null && rightId != null && leftId > 0 && rightId > 0) return leftId == rightId

        val leftPhone = left.clientPhone.orEmpty().filter(Char::isDigit).takeLast(10)
        val rightPhone = right.clientPhone.orEmpty().filter(Char::isDigit).takeLast(10)
        if (leftPhone.length == 10 && rightPhone.length == 10) return leftPhone == rightPhone

        val leftName = left.clientName.orEmpty().trim()
        val rightName = right.clientName.orEmpty().trim()
        return leftName.length >= 3 && leftName.equals(rightName, ignoreCase = true)
    }

    private fun eventsForCreator(anchor: EventEntity): List<EventEntity> =
        events
            .asSequence()
            .filter { sameCreator(anchor, it) }
            .sortedWith(compareBy<EventEntity> { it.eventDate }.thenBy { it.title })
            .toList()

    private fun sameCreator(left: EventEntity, right: EventEntity): Boolean {
        val leftId = left.takerId
        val rightId = right.takerId
        if (leftId != null && rightId != null && leftId > 0 && rightId > 0) return leftId == rightId

        val leftName = left.takerName.orEmpty().trim()
        val rightName = right.takerName.orEmpty().trim()
        return leftName.length >= 3 && leftName.equals(rightName, ignoreCase = true)
    }

    private fun eventStatusLabel(status: String): String =
        when (status) {
            "Upcoming" -> getString(R.string.event_status_upcoming)
            "Pending" -> getString(R.string.pending)
            "Confirmed" -> getString(R.string.confirmed)
            "Cancelled" -> getString(R.string.cancelled)
            "Completed" -> getString(R.string.completed)
            else -> status
        }

    private fun shareText(text: String, phoneNumber: String?) {
        val phone = phoneNumber.orEmpty().filter(Char::isDigit).takeLast(10)
        val whatsapp = if (phone.length == 10) {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/91$phone?text=${URLEncoder.encode(text, "UTF-8")}"))
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                setPackage("com.whatsapp")
            }
        }
        try {
            startActivity(whatsapp)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }, getString(R.string.share_statement)))
        }
    }

    private fun confirmDeleteEvent(event: EventEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.event_delete_title)
            .setMessage(R.string.event_delete_message)
            .setPositiveButton(R.string.delete) { _, _ -> eventVm.deleteEvent(event.id) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupEventsNotificationReadSync() {
        notificationsVm.notificationsState.observe(viewLifecycleOwner) { result ->
            if (result !is Result.Success) return@observe
            result.data.notifications
                .filter { !it.isRead && it.type in eventNotificationTypes && markedNotificationIds.add(it.id) }
                .forEach { notificationsVm.markRead(it.id) }
        }
        notificationsVm.fetchNotifications()
    }

    override fun onDestroyView() {
        unregisterNetworkResync()
        super.onDestroyView()
        _binding = null
    }

    private enum class EventFilter { ALL, UPCOMING, PENDING, COMPLETED, CANCELLED }

    companion object {
        private const val REMOTE_REFRESH_THROTTLE_MS = 30_000L
        private val eventNotificationTypes = setOf(
            "booking_request",
            "booking_created",
            "booking_status",
            "booking_cancelled",
        )
        const val EXTRA_NAV_EVENT_ID = "NAV_EVENT_ID"
        const val EXTRA_NAV_BOOKING_ID = "NAV_BOOKING_ID"
    }
}
