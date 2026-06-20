package com.photoconnect.ui.fragments

import android.os.Bundle
import android.view.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.photoconnect.R
import com.photoconnect.databinding.FragmentTakerDashboardBinding
import com.photoconnect.db.AvailabilityEntity
import com.photoconnect.db.BookingEntity
import com.photoconnect.model.AvailabilityEntry
import com.photoconnect.repository.Result
import com.photoconnect.utils.*
import com.photoconnect.viewmodel.AvailabilityViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TakerDashboardFragment : Fragment() {
    private var _b: FragmentTakerDashboardBinding? = null
    private val b get() = _b!!
    private val vm: AvailabilityViewModel by viewModels()
    @Inject lateinit var session: SessionManager
    private var takerBookings: List<BookingEntity> = emptyList()
    private var selectedBookedDate: String? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var lastRemoteCalendarRefreshAt = 0L

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        FragmentTakerDashboardBinding.inflate(i, c, false).also { _b = it }.root

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        b.tvWelcome.text = getString(R.string.hello_user, session.getUserName().split(" ").first())
        b.calendarView.showMonthHeader = false
        b.calendarView.onInvalidSelectionAttempt = { toast(it) }
        b.calendarView.onBookedDateSelected = { date ->
            selectedBookedDate = date
            renderBookedDateDetails()
        }
        b.btnCloseBookedDateDetails.setOnClickListener {
            selectedBookedDate = null
            b.cardBookedDateDetails.hide()
        }

        vm.cached().observe(viewLifecycleOwner) { entities ->
            val calendarState = entities.toCalendarState()
            b.calendarView.setAvailabilityMap(calendarState.first, calendarState.second)
            b.cardReminder.isVisible = entities.isEmpty()
        }
        vm.takerBookings.observe(viewLifecycleOwner) { bookings ->
            takerBookings = bookings
            renderBookedDateDetails()
        }

        updateMonthLabel()

        b.btnNextMonth.setOnClickListener { 
            b.calendarView.nextMonth()
            updateMonthLabel()
        }
        b.btnPrevMonth.setOnClickListener { 
            b.calendarView.prevMonth()
            updateMonthLabel()
        }

        b.calendarView.onMonthChanged = { _ ->
            updateMonthLabel()
            vm.fetch(b.calendarView.getCurrentMonthString())
        }

        b.btnCloseReminder.setOnClickListener { b.cardReminder.hide() }

        b.btnMarkAvailable.setOnClickListener {
            val dates = b.calendarView.getSelectedDates()
            if (dates.isEmpty()) { toast(getString(R.string.tap_dates_first)); return@setOnClickListener }
            chooseDayPart { dayPart ->
                updateAvailabilityIfOnline(dates.map { AvailabilityEntry(it, "Available", dayPart) })
            }
        }

        b.btnMarkUnavailable.setOnClickListener {
            val dates = b.calendarView.getSelectedDates()
            if (dates.isEmpty()) { toast(getString(R.string.tap_dates_first)); return@setOnClickListener }
            chooseDayPart { dayPart ->
                updateAvailabilityIfOnline(dates.map { AvailabilityEntry(it, "Not Available", dayPart) })
            }
        }

        b.btnMonthAvailable.setOnClickListener {
            val dates = b.calendarView.getEditableDatesInCurrentMonth()
            if (dates.isEmpty()) { toast(getString(R.string.no_editable_month_dates)); return@setOnClickListener }
            chooseDayPart { dayPart ->
                updateAvailabilityIfOnline(dates.map { AvailabilityEntry(it, "Available", dayPart) })
            }
        }

        b.btnMonthBusy.setOnClickListener {
            val dates = b.calendarView.getEditableDatesInCurrentMonth()
            if (dates.isEmpty()) { toast(getString(R.string.no_editable_month_dates)); return@setOnClickListener }
            chooseDayPart { dayPart ->
                updateAvailabilityIfOnline(dates.map { AvailabilityEntry(it, "Not Available", dayPart) })
            }
        }

        vm.update.observe(viewLifecycleOwner) { r ->
            when (r) {
                is Result.Loading -> {
                    setAvailabilityActionsEnabled(false)
                    b.progressBar.show()
                }
                is Result.Success -> {
                    setAvailabilityActionsEnabled(true)
                    b.progressBar.hide(); b.calendarView.clearSelection()
                    val skipped = r.data.skippedBookedCount
                    toast(if (skipped > 0) getString(R.string.availability_updated_booked_kept) else getString(R.string.availability_updated))
                }
                is Result.Error -> {
                    setAvailabilityActionsEnabled(true)
                    b.progressBar.hide(); toast(r.message)
                }
            }
        }

        vm.fetch(b.calendarView.getCurrentMonthString())
        vm.fetchBookings()
    }

    override fun onStart() {
        super.onStart()
        if (requireContext().isNetworkAvailable()) refreshRemoteCalendarData(force = false)
        registerNetworkResync()
    }

    override fun onStop() {
        unregisterNetworkResync()
        super.onStop()
    }

    private fun refreshRemoteCalendarData(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRemoteCalendarRefreshAt < REMOTE_REFRESH_THROTTLE_MS) return
        lastRemoteCalendarRefreshAt = now
        vm.fetch(b.calendarView.getCurrentMonthString(), force = force)
        vm.fetchBookings(force = force)
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
                    if (_b != null && isAdded) refreshRemoteCalendarData(force = false)
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

    private fun updateAvailabilityIfOnline(entries: List<AvailabilityEntry>) {
        if (!requireContext().isNetworkAvailable()) {
            toast(getString(R.string.calendar_read_only_offline))
            return
        }
        vm.updateDates(entries, b.calendarView.getCurrentMonthString())
    }

    private fun chooseDayPart(onPicked: (String) -> Unit) {
        if (!requireContext().isNetworkAvailable()) {
            toast(getString(R.string.calendar_read_only_offline))
            return
        }
        val labels = arrayOf(
            getString(R.string.day_part_full),
            getString(R.string.day_part_first_half),
            getString(R.string.day_part_second_half),
        )
        val values = arrayOf(DAY_PART_FULL, DAY_PART_FIRST_HALF, DAY_PART_SECOND_HALF)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.availability_day_part_title)
            .setItems(labels) { _, which -> onPicked(values[which]) }
            .show()
    }

    private fun setAvailabilityActionsEnabled(enabled: Boolean) {
        b.btnMarkAvailable.isEnabled = enabled
        b.btnMarkUnavailable.isEnabled = enabled
        b.btnMonthAvailable.isEnabled = enabled
        b.btnMonthBusy.isEnabled = enabled
    }

    private fun renderBookedDateDetails() {
        val date = selectedBookedDate
        if (date == null) {
            b.cardBookedDateDetails.hide()
            return
        }
        val bookings = takerBookings.filter {
            it.bookingDate == date && it.status in setOf("Confirmed", "Completed")
        }
        b.cardBookedDateDetails.show()
        b.tvBookedDateSubtitle.text = date.toDisplayDate()
        b.tvBookedDateDetails.text = if (bookings.isEmpty()) {
            getString(R.string.booked_date_details_empty)
        } else {
            bookings.joinToString("\n\n") { booking ->
                getString(
                    R.string.booked_date_details_line,
                    booking.serviceType.toServiceLabel(requireContext()),
                    booking.bookingDate.toDisplayDate(),
                    booking.dayPart.toDayPartLabel(requireContext()),
                    booking.clientName?.takeIf { it.isNotBlank() } ?: getString(R.string.booking_default_client),
                    booking.status,
                )
            }
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
                DAY_PART_FULL in notAvailable || notAvailable.containsAll(setOf(DAY_PART_FIRST_HALF, DAY_PART_SECOND_HALF)) ->
                    "Not Available" to DAY_PART_FULL
                DAY_PART_FIRST_HALF in notAvailable -> "Not Available" to DAY_PART_FIRST_HALF
                DAY_PART_SECOND_HALF in notAvailable -> "Not Available" to DAY_PART_SECOND_HALF
                else -> "Not Available" to DAY_PART_FULL
            }
            statusByDate[date] = statusPart.first
            partByDate[date] = statusPart.second
        }
        return statusByDate to partByDate
    }

    private fun updateMonthLabel() {
        val current = b.calendarView.getCurrentMonthString()
        b.tvCurrentMonth.text = try {
            val parts = current.split("-")
            val year = parts[0]
            val month = parts[1].toInt()
            val monthName = java.text.DateFormatSymbols().months[month - 1]
            "$monthName $year"
        } catch (e: Exception) { current }
    }

    override fun onDestroyView() {
        unregisterNetworkResync()
        super.onDestroyView()
        _b = null
    }

    companion object {
        private const val REMOTE_REFRESH_THROTTLE_MS = 30_000L
    }
}
