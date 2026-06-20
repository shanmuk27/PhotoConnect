package com.photoconnect.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.photoconnect.R
import com.photoconnect.databinding.FragmentBookingsBinding
import com.photoconnect.db.BookingEntity
import com.photoconnect.repository.Result
import com.photoconnect.ui.activities.LoginActivity
import com.photoconnect.ui.adapters.BookingAdapter
import com.photoconnect.utils.hide
import com.photoconnect.utils.show
import com.photoconnect.utils.toast
import com.photoconnect.viewmodel.BookingViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.photoconnect.utils.SessionManager

@AndroidEntryPoint
class BookingsFragment : Fragment() {
    private var _binding: FragmentBookingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: BookingViewModel by viewModels()

    @Inject lateinit var session: SessionManager

    private lateinit var adapter: BookingAdapter
    private var placedBookings = emptyList<BookingEntity>()
    private var receivedBookings = emptyList<BookingEntity>()
    private var showingReceived = false
    private var pendingActionBookingId: Int? = null

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

        adapter = BookingAdapter { booking, targetStatus ->
            pendingActionBookingId = booking.id
            adapter.setPendingAction(booking.id)
            viewModel.updateBookingStatus(booking.id, targetStatus, asTaker = showingReceived)
        }

        binding.rvBookings.layoutManager = LinearLayoutManager(requireContext())
        binding.rvBookings.adapter = adapter
        binding.rvBookings.layoutAnimation =
            AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_staggered_fade)

        binding.tvHeading.text = if (session.isTaker()) "Orders & Requests" else "My Bookings"
        binding.tvSubheading.text = if (session.isTaker()) {
            "Start with incoming requests, then switch to the bookings you placed."
        } else {
            "Track your upcoming bookings and service requests."
        }
        binding.toggleOrderScope.isVisible = session.isTaker()
        binding.tabReceivedOrders.isVisible = session.isTaker()
        binding.toggleOrderScope.check(
            if (showingReceived) binding.tabReceivedOrders.id else binding.tabPlacedOrders.id
        )
        binding.toggleOrderScope.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            showingReceived = checkedId == binding.tabReceivedOrders.id
            renderBookings()
        }
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.fetchPlacedBookings()
            if (session.isTaker()) viewModel.fetchReceivedBookings()
        }

        viewModel.cachedPlacedBookings().observe(viewLifecycleOwner) { bookings ->
            placedBookings = bookings
            renderBookings()
        }
        viewModel.cachedReceivedBookings().observe(viewLifecycleOwner) { bookings ->
            receivedBookings = bookings
            renderBookings()
        }
        viewModel.placedState.observe(viewLifecycleOwner) { result ->
            binding.swipeRefresh.isRefreshing = false
            when (result) {
                is Result.Loading -> binding.progressBar.show()
                else -> binding.progressBar.hide()
            }
        }
        viewModel.receivedState.observe(viewLifecycleOwner) { result ->
            binding.swipeRefresh.isRefreshing = false
            when (result) {
                is Result.Loading -> binding.progressBar.show()
                else -> binding.progressBar.hide()
            }
        }
        viewModel.updateState.observe(viewLifecycleOwner) { result ->
            when (result) {
                is Result.Loading -> binding.progressBar.show()
                is Result.Success -> {
                    pendingActionBookingId = null
                    adapter.setPendingAction(null)
                    binding.progressBar.hide()
                    toast("Booking updated to ${result.data.status}")
                    viewModel.fetchPlacedBookings()
                    if (session.isTaker()) viewModel.fetchReceivedBookings()
                }
                is Result.Error -> {
                    pendingActionBookingId = null
                    adapter.setPendingAction(null)
                    binding.progressBar.hide()
                    toast(result.message)
                }
            }
        }

        viewModel.fetchPlacedBookings()
        if (session.isTaker()) viewModel.fetchReceivedBookings()
    }

    private fun sortForDisplay(list: List<BookingEntity>): List<BookingEntity> {
        val statusRank = mapOf(
            "Pending" to 0,
            "Confirmed" to 1,
            "Completed" to 2,
            "Cancelled" to 3,
        )
        return list.sortedWith(
            compareBy<BookingEntity>({ statusRank[it.status] ?: 9 }, { it.bookingDate }, { it.id })
        )
    }

    private fun renderBookings() {
        val raw = if (showingReceived) receivedBookings else placedBookings
        val items = sortForDisplay(raw)
        adapter.setMode(if (showingReceived) BookingAdapter.Mode.RECEIVED else BookingAdapter.Mode.PLACED)
        adapter.setPendingAction(pendingActionBookingId)
        adapter.submitList(items)
        binding.rvBookings.scheduleLayoutAnimation()
        binding.tvEmpty.isVisible = items.isEmpty()
        binding.tvEmpty.text = if (showingReceived) "No booking requests yet" else "No bookings yet"
        binding.tvSummaryCount.text = items.size.toString()
        binding.tvSummaryLabel.text = if (showingReceived) "Requests" else "Placed"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
