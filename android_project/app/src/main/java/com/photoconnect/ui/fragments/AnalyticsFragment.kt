package com.photoconnect.ui.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.photoconnect.R
import com.photoconnect.databinding.FragmentAnalyticsBinding
import com.photoconnect.db.EventEntity
import com.photoconnect.ui.adapters.EventAdapter
import com.photoconnect.utils.SessionManager
import com.photoconnect.utils.toServiceLabel
import com.photoconnect.viewmodel.EventViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import kotlin.math.max

@AndroidEntryPoint
class AnalyticsFragment : Fragment() {
    private var _binding: FragmentAnalyticsBinding? = null
    private val binding get() = _binding!!
    private val eventVm: EventViewModel by viewModels()

    @Inject lateinit var session: SessionManager
    
    private var events = emptyList<EventEntity>()
    private var currentRangeMonths = 3 // 3, 6, 12, or 999 (all time)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        FragmentAnalyticsBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.toolbarAnalytics.setNavigationOnClickListener { findNavController().popBackStack() }
        
        binding.toggleTimeRange.check(binding.btnRange3Months.id)
        binding.toggleTimeRange.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentRangeMonths = when (checkedId) {
                binding.btnRange3Months.id -> 3
                binding.btnRange6Months.id -> 6
                binding.btnRangeYear.id -> 12
                else -> 999 // All time
            }
            renderAnalytics()
        }
        
        eventVm.cachedEvents().observe(viewLifecycleOwner) {
            events = it
            renderAnalytics()
        }
        
        if (events.isEmpty()) {
            eventVm.fetchEvents()
        }
    }
    
    private fun renderAnalytics() {
        if (events.isEmpty()) return
        
        // 1. Filter events by Time Range and Role
        val filteredEvents = filterEventsForRange()
        
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
        val upcomingEvents = filteredEvents.filter { it.eventDate >= todayStr }
        val pastEvents = filteredEvents.filter { it.eventDate < todayStr }
        
        // 2. Calculate Overall KPIs
        val totalRevenue = filteredEvents.sumOf { it.totalAmount }
        val totalEvents = filteredEvents.size
        val upcomingRevenue = upcomingEvents.sumOf { it.totalAmount }
        val realizedRevenue = pastEvents.sumOf { it.totalAmount }
        
        binding.tvTotalRevenue.text = EventAdapter.compactMoney(totalRevenue)
        binding.tvTotalEvents.text = totalEvents.toString()
        
        binding.tvUpcomingRevenue.text = EventAdapter.compactMoney(upcomingRevenue)
        binding.tvRealizedRevenue.text = EventAdapter.compactMoney(realizedRevenue)
        
        // 3. Render Trend Chart
        renderTrendChart(filteredEvents)
        
        // 4. Render Event Status Breakdown (Coming vs Passed Away)
        renderStatusBreakdown(upcomingEvents, pastEvents)
        
        // 5. Render Service Breakdown
        renderServiceBreakdown(filteredEvents)
        
        // 6. Render Outstanding Balances
        renderOutstandingBalances(filteredEvents)
    }
    
    private fun renderStatusBreakdown(upcomingEvents: List<EventEntity>, pastEvents: List<EventEntity>) {
        binding.layoutStatusBreakdown.removeAllViews()
        
        // Upcoming Row
        val upcomingRow = createBreakdownRow(
            getString(R.string.analytics_upcoming_events_count, upcomingEvents.size),
            upcomingEvents.sumOf { it.totalAmount },
        )
        binding.layoutStatusBreakdown.addView(upcomingRow)
        
        // Past Row
        val pastRow = createBreakdownRow(
            getString(R.string.analytics_past_events_count, pastEvents.size),
            pastEvents.sumOf { it.totalAmount },
        )
        binding.layoutStatusBreakdown.addView(pastRow)
    }

    private fun createBreakdownRow(label: String, amount: Double): LinearLayout {
        val row = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 16)
        }
        
        val name = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = label
            textSize = 14f
        }
        
        val amountView = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            text = EventAdapter.compactMoney(amount)
            val typeFace = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD)
            setTypeface(typeFace)
            textSize = 14f
        }
        
        row.addView(name)
        row.addView(amountView)
        return row
    }
    
    private fun filterEventsForRange(): List<EventEntity> {
        val roleEvents = if (session.isTaker()) {
            val takerId = session.getTakerActorId()
            events.filter { it.takerId == takerId }
        } else {
            val clientId = session.getClientProfileId()
            events.filter { it.clientId == clientId }
        }
        
        if (currentRangeMonths > 100) return roleEvents // All time
        
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -currentRangeMonths)
        val cutoffDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        
        return roleEvents.filter { it.eventDate >= cutoffDateStr }
    }
    
    private fun renderTrendChart(events: List<EventEntity>) {
        binding.layoutChartBars.removeAllViews()
        binding.layoutChartLabels.removeAllViews()
        
        if (events.isEmpty() || currentRangeMonths > 12) {
            binding.tvEmptyChart.isVisible = true
            binding.tvEmptyChart.text = if (events.isEmpty()) {
                getString(R.string.analytics_no_data)
            } else {
                getString(R.string.analytics_chart_unavailable_all_time)
            }
            return
        }
        binding.tvEmptyChart.isVisible = false
        
        // Group by YYYY-MM
        val monthlyRevenue = mutableMapOf<String, Double>()
        
        // Setup empty months
        val cal = Calendar.getInstance()
        val format = SimpleDateFormat("yyyy-MM", Locale.US)
        val monthDisplayFormat = SimpleDateFormat("MMM", Locale.US)
        
        val monthsToDisplay = mutableListOf<String>()
        val labels = mutableListOf<String>()
        
        for (i in (currentRangeMonths - 1) downTo 0) {
            val calClone = cal.clone() as Calendar
            calClone.add(Calendar.MONTH, -i)
            val key = format.format(calClone.time)
            monthlyRevenue[key] = 0.0
            monthsToDisplay.add(key)
            labels.add(monthDisplayFormat.format(calClone.time))
        }
        
        // Aggregate
        events.forEach {
            if (it.eventDate.length >= 7) {
                val key = it.eventDate.substring(0, 7)
                if (monthlyRevenue.containsKey(key)) {
                    monthlyRevenue[key] = (monthlyRevenue[key] ?: 0.0) + it.totalAmount
                }
            }
        }
        
        val maxRevenue = monthlyRevenue.values.maxOrNull() ?: 1.0
        val maxSafeRevenue = if (maxRevenue <= 0.0) 1.0 else maxRevenue
        
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
        val colorPrimary = typedValue.data
        
        monthsToDisplay.forEachIndexed { index, monthKey ->
            val revenue = monthlyRevenue[monthKey] ?: 0.0
            val weight = (revenue / maxSafeRevenue).toFloat().coerceAtLeast(0.05f)
            
            val barContainer = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.BOTTOM
                setPadding(8, 0, 8, 0)
            }
            
            val bar = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, weight)
                backgroundTintList = ColorStateList.valueOf(colorPrimary)
                setBackgroundResource(R.drawable.bg_metric_card_primary) // reuse shape
            }
            
            barContainer.addView(bar)
            binding.layoutChartBars.addView(barContainer)
            
            val label = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = labels[index]
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                textSize = 10f
            }
            binding.layoutChartLabels.addView(label)
        }
    }
    
    private fun renderServiceBreakdown(events: List<EventEntity>) {
        binding.layoutServiceBreakdown.removeAllViews()
        
        if (events.isEmpty()) {
            val empty = TextView(requireContext()).apply { text = getString(R.string.analytics_no_data_available) }
            binding.layoutServiceBreakdown.addView(empty)
            return
        }
        
        val breakdown = events.groupBy { it.serviceType ?: "other" }
            .mapValues { entry -> entry.value.sumOf { it.totalAmount } }
            .toList()
            .sortedByDescending { it.second }
            
        breakdown.forEach { (service, revenue) ->
            if (revenue <= 0.0) return@forEach
            
            val row = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 16)
            }
            
            val name = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = service.toServiceLabel(requireContext())
                textSize = 14f
            }
            
            val amount = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                text = EventAdapter.compactMoney(revenue)
                val typeFace = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD)
                setTypeface(typeFace)
                textSize = 14f
            }
            
            row.addView(name)
            row.addView(amount)
            binding.layoutServiceBreakdown.addView(row)
        }
    }
    
    private fun renderOutstandingBalances(events: List<EventEntity>) {
        binding.layoutOutstanding.removeAllViews()
        
        val outstanding = events.filter { it.totalAmount > it.paidAmount }
            .sortedByDescending { it.totalAmount - it.paidAmount }
            
        if (outstanding.isEmpty()) {
            val empty = TextView(requireContext()).apply { 
                text = getString(R.string.analytics_all_caught_up)
                setTextColor(Color.parseColor("#4CAF50"))
            }
            binding.layoutOutstanding.addView(empty)
            return
        }
        
        outstanding.forEach { event ->
            val balance = event.totalAmount - event.paidAmount
            
            val row = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 16)
            }
            
            val infoLayout = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                orientation = LinearLayout.VERTICAL
            }
            
            val clientName = TextView(requireContext()).apply {
                text = event.clientName ?: getString(R.string.analytics_unknown_client)
                textSize = 14f
                val typeFace = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD)
                setTypeface(typeFace)
            }
            
            val eventTitle = TextView(requireContext()).apply {
                text = "${event.title} (${event.eventDate})"
                textSize = 12f
            }
            
            infoLayout.addView(clientName)
            infoLayout.addView(eventTitle)
            
            val amount = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                text = EventAdapter.compactMoney(balance)
                val typeFace = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD)
                setTypeface(typeFace)
                setTextColor(Color.parseColor("#F44336"))
                textSize = 14f
            }
            
            row.addView(infoLayout)
            row.addView(amount)
            binding.layoutOutstanding.addView(row)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
