package com.photoconnect.ui.fragments

import android.os.Bundle
import android.view.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.photoconnect.databinding.FragmentTakerDashboardBinding
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

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        FragmentTakerDashboardBinding.inflate(i, c, false).also { _b = it }.root

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        b.tvWelcome.text = "Hello, ${session.getUserName().split(" ").first()}"

        vm.cached().observe(viewLifecycleOwner) { entities ->
            b.calendarView.setAvailabilityMap(entities.associate { it.date to it.status })
            b.cardReminder.isVisible = entities.isEmpty()
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
            if (dates.isEmpty()) { toast("Tap dates on the calendar first"); return@setOnClickListener }
            vm.updateDates(dates.map { AvailabilityEntry(it, "Available") })
        }

        b.btnMarkUnavailable.setOnClickListener {
            val dates = b.calendarView.getSelectedDates()
            if (dates.isEmpty()) { toast("Tap dates on the calendar first"); return@setOnClickListener }
            vm.updateDates(dates.map { AvailabilityEntry(it, "Not Available") })
        }

        vm.update.observe(viewLifecycleOwner) { r ->
            when (r) {
                is Result.Loading -> b.progressBar.show()
                is Result.Success -> {
                    b.progressBar.hide(); b.calendarView.clearSelection()
                    val skipped = r.data.skippedBookedCount
                    toast(if (skipped > 0) "Updated. Booked dates were kept booked." else "Availability updated!")
                    vm.fetch(b.calendarView.getCurrentMonthString())
                }
                is Result.Error -> { b.progressBar.hide(); toast(r.message) }
            }
        }

        vm.fetch(b.calendarView.getCurrentMonthString())
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

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
