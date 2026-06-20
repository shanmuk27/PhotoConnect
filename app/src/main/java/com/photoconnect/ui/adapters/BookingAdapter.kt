package com.photoconnect.ui.adapters

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.photoconnect.databinding.ItemBookingBinding
import com.photoconnect.db.BookingEntity
import com.photoconnect.utils.toDisplayDate
import com.photoconnect.utils.toServiceLabel
import java.util.Locale

class BookingAdapter(
    private val onActionClick: (BookingEntity, String) -> Unit = { _, _ -> },
) : ListAdapter<BookingEntity, BookingAdapter.VH>(DIFF) {
    enum class Mode { PLACED, RECEIVED }

    private var mode: Mode = Mode.PLACED
    private var pendingActionBookingId: Int? = null

    fun setMode(value: Mode) {
        if (mode != value) {
            mode = value
            notifyDataSetChanged()
        }
    }

    fun setPendingAction(bookingId: Int?) {
        if (pendingActionBookingId != bookingId) {
            pendingActionBookingId = bookingId
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemBookingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val binding: ItemBookingBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(booking: BookingEntity) {
            binding.tvOrderId.text = String.format(Locale.US, "BOOKING #%05d", booking.id)
            binding.tvService.text = booking.serviceType.toServiceLabel()
            binding.tvCounterparty.text = when (mode) {
                Mode.PLACED -> {
                    val name = booking.takerName?.takeIf { it.isNotBlank() } ?: "Taker"
                    binding.root.context.getString(com.photoconnect.R.string.booking_with_party, name)
                }
                Mode.RECEIVED -> {
                    val name = booking.clientName?.takeIf { it.isNotBlank() } ?: "Client"
                    binding.root.context.getString(com.photoconnect.R.string.booking_from_party, name)
                }
            }
            binding.tvDate.text = booking.bookingDate.toDisplayDate()
            binding.tvLocation.text = when {
                !booking.eventLocation.isNullOrBlank() -> booking.eventLocation
                mode == Mode.RECEIVED -> "Location to be confirmed by client"
                else -> "Location pending"
            }
            binding.tvNotes.isVisible = !booking.notes.isNullOrBlank()
            binding.tvNotes.text = booking.notes.orEmpty()
            binding.tvStatus.text = booking.status

            val (statusTextColor, statusFillColor) = when (booking.status) {
                "Confirmed" -> "#166534" to "#DCFCE7"
                "Cancelled" -> "#B91C1C" to "#FEE2E2"
                "Completed" -> "#1D4ED8" to "#DBEAFE"
                else -> "#92400E" to "#FEF3C7"
            }
            binding.tvStatus.setTextColor(Color.parseColor(statusTextColor))
            binding.tvStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor(statusFillColor))

            val actions = actionsFor(booking)
            val isUpdating = pendingActionBookingId == booking.id
            bindAction(binding.btnPrimaryAction, actions.first, booking, isUpdating)
            bindAction(binding.btnSecondaryAction, actions.second, booking, isUpdating)
            binding.groupActions.isVisible = actions.first != null || actions.second != null
        }

        private fun bindAction(
            button: MaterialButton,
            action: BookingAction?,
            booking: BookingEntity,
            isUpdating: Boolean,
        ) {
            button.isVisible = action != null
            if (action == null) return
            button.text = if (isUpdating) "Updating..." else action.label
            button.isEnabled = !isUpdating
            button.setOnClickListener { onActionClick(booking, action.targetStatus) }
        }

        private fun actionsFor(booking: BookingEntity): Pair<BookingAction?, BookingAction?> =
            when (mode) {
                Mode.PLACED -> when (booking.status) {
                    "Pending", "Confirmed" -> BookingAction("Cancel", "Cancelled") to null
                    else -> null to null
                }
                Mode.RECEIVED -> when (booking.status) {
                    "Pending" -> BookingAction("Confirm", "Confirmed") to BookingAction("Decline", "Cancelled")
                    "Confirmed" -> BookingAction("Mark Complete", "Completed") to BookingAction("Cancel", "Cancelled")
                    else -> null to null
                }
            }
    }

    private data class BookingAction(val label: String, val targetStatus: String)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BookingEntity>() {
            override fun areItemsTheSame(oldItem: BookingEntity, newItem: BookingEntity) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: BookingEntity, newItem: BookingEntity) = oldItem == newItem
        }
    }
}
