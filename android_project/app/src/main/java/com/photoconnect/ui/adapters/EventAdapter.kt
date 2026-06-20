package com.photoconnect.ui.adapters

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.photoconnect.R
import com.photoconnect.databinding.ItemEventBinding
import com.photoconnect.db.EventEntity
import com.photoconnect.utils.SessionManager
import com.photoconnect.utils.publicBookingReference
import com.photoconnect.utils.publicEventReference
import com.photoconnect.utils.toDisplayDate
import com.photoconnect.utils.toDayPartLabel
import com.photoconnect.utils.toServiceLabel
import java.text.NumberFormat
import java.util.Locale

class EventAdapter(
    private val actorRole: String,
    private val actionRoleForEvent: (EventEntity) -> String = { actorRole },
    private val onBookingAction: (EventEntity, String) -> Unit,
    private val onEdit: (EventEntity) -> Unit,
    private val onShare: (EventEntity) -> Unit,
    private val onCall: (EventEntity) -> Unit,
    private val onDelete: (EventEntity) -> Unit,
) : ListAdapter<EventEntity, EventAdapter.VH>(DIFF) {
    private var pendingBookingId: Int? = null
    private var highlightedEventId: Int? = null
    private var pendingHighlightedEventId: Int? = null
    private var pendingBookingIdUpdate: Int? = null
    private var isScrolling = false

    fun setPendingBooking(bookingId: Int?) {
        if (pendingBookingId != bookingId) {
            pendingBookingIdUpdate = bookingId
            if (!isScrolling) {
                applyPendingBookingUpdate()
            }
        }
    }

    fun setHighlightedEvent(eventId: Int?) {
        if (highlightedEventId != eventId) {
            pendingHighlightedEventId = eventId
            if (!isScrolling) {
                applyPendingHighlight()
            }
        }
    }

    fun setScrolling(scrolling: Boolean) {
        isScrolling = scrolling
        if (!scrolling) {
            // Apply pending updates once scrolling stops
            if (pendingHighlightedEventId != highlightedEventId) {
                applyPendingHighlight()
            }
            if (pendingBookingIdUpdate != pendingBookingId) {
                applyPendingBookingUpdate()
            }
        }
    }

    private fun applyPendingHighlight() {
        val oldEventId = highlightedEventId
        highlightedEventId = pendingHighlightedEventId
        notifyEventChanged(oldEventId)
        notifyEventChanged(highlightedEventId)
    }

    private fun applyPendingBookingUpdate() {
        val oldBookingId = pendingBookingId
        pendingBookingId = pendingBookingIdUpdate
        notifyBookingChanged(oldBookingId)
        notifyBookingChanged(pendingBookingId)
    }

    private fun notifyBookingChanged(bookingId: Int?) {
        if (bookingId == null) return
        val index = currentList.indexOfFirst { it.bookingId == bookingId }
        if (index >= 0) notifyItemChanged(index)
    }

    private fun notifyEventChanged(eventId: Int?) {
        if (eventId == null) return
        val index = currentList.indexOfFirst { it.id == eventId }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemEventBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemEventBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(event: EventEntity) {
            val ctx = b.root.context
            val balance = maxOf(0.0, event.totalAmount - event.paidAmount)
            (b.root as? MaterialCardView)?.let { card ->
                val isHighlighted = highlightedEventId == event.id
                card.strokeWidth = ((if (isHighlighted) 3 else 1) * ctx.resources.displayMetrics.density).toInt()
                card.strokeColor = MaterialColors.getColor(
                    card,
                    if (isHighlighted) com.google.android.material.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOutlineVariant,
                )
                card.cardElevation = (if (isHighlighted) 8f else 1f) * ctx.resources.displayMetrics.density
            }
            b.tvEventCode.text = if (event.bookingId != null) {
                ctx.getString(R.string.booking_number_format, publicBookingReference(event.bookingId))
            } else {
                ctx.getString(R.string.event_number_format, publicEventReference(event.id))
            }
            b.tvTitle.text = event.title
            b.tvDate.text = ctx.getString(
                R.string.event_date_day_part,
                event.eventDate.toDisplayDate(),
                event.dayPart.toDayPartLabel(ctx),
            )
            b.tvStatus.text = event.status.toStatusLabel(ctx)
            b.tvService.isVisible = !event.serviceType.isNullOrBlank()
            b.tvService.text = event.serviceType.orEmpty().toServiceLabel(ctx)
            b.rowLocation.isVisible = !event.location.isNullOrBlank()
            b.tvLocation.text = event.location.orEmpty()
            val eventActionRole = actionRoleForEvent(event)
            b.tvCounterparty.text = counterparty(ctx, event, eventActionRole)
            b.tvTotalAmount.text = ctx.getString(R.string.event_total_value, money(event.totalAmount))
            b.tvPaidAmount.text = ctx.getString(R.string.event_paid_value, money(event.paidAmount))
            b.tvBalanceAmount.text = ctx.getString(R.string.event_balance_value, money(balance))
            b.tvNotes.isVisible = !event.notes.isNullOrBlank()
            b.tvNotes.text = event.notes.orEmpty()
            b.btnDelete.isVisible = event.bookingId == null

            val (statusTextColor, statusFillColor) = when (event.status) {
                "Confirmed", "Upcoming" -> "#166534" to "#DCFCE7"
                "Cancelled" -> "#B91C1C" to "#FEE2E2"
                "Completed" -> "#1D4ED8" to "#DBEAFE"
                else -> "#92400E" to "#FEF3C7"
            }
            b.tvStatus.setTextColor(Color.parseColor(statusTextColor))
            b.tvStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor(statusFillColor))

            b.btnEdit.setOnClickListener { onEdit(event) }
            b.btnShare.setOnClickListener { onShare(event) }
            val callablePhone = callPhoneFor(event, eventActionRole)
            val canResolveTakerPhone = eventActionRole != SessionManager.ROLE_TAKER && (event.takerId ?: 0) > 0
            b.btnCall.isVisible = callablePhone.length == 10 || canResolveTakerPhone
            b.btnCall.setOnClickListener { onCall(event) }
            b.btnDelete.setOnClickListener { onDelete(event) }

            val actions = bookingActionsFor(event, eventActionRole)
            val isUpdating = pendingBookingId != null && pendingBookingId == event.bookingId
            bindAction(b.btnPrimaryAction, actions.first, event, isUpdating)
            bindAction(b.btnSecondaryAction, actions.second, event, isUpdating)
            b.groupBookingActions.isVisible = actions.first != null || actions.second != null
        }

        private fun bindAction(
            button: MaterialButton,
            action: EventAction?,
            event: EventEntity,
            isUpdating: Boolean,
        ) {
            button.isVisible = action != null
            if (action == null) return
            button.text = if (isUpdating) button.context.getString(R.string.updating) else button.context.getString(action.labelRes)
            button.isEnabled = !isUpdating
            button.setOnClickListener { onBookingAction(event, action.targetStatus) }
        }

        private fun bookingActionsFor(event: EventEntity, actionRole: String): Pair<EventAction?, EventAction?> {
            if (event.bookingId == null) return null to null
            return when (actionRole) {
                SessionManager.ROLE_TAKER -> when (event.status) {
                    "Pending" -> EventAction(R.string.confirm, "Confirmed") to EventAction(R.string.decline, "Cancelled")
                    "Confirmed" -> EventAction(R.string.mark_complete, "Completed") to EventAction(R.string.cancel, "Cancelled")
                    "Completed" -> EventAction(R.string.review_studio, ACTION_REVIEW_STUDIO) to null
                    else -> null to null
                }
                else -> when (event.status) {
                    "Pending", "Confirmed" -> EventAction(R.string.cancel, "Cancelled") to null
                    else -> null to null
                }
            }
        }

        private fun counterparty(ctx: Context, event: EventEntity, actionRole: String): String {
            return if (actionRole == SessionManager.ROLE_TAKER) {
                val name = event.clientName?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.booking_default_client)
                ctx.getString(R.string.booking_from_party, name)
            } else {
                val name = event.takerName?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.booking_default_taker)
                ctx.getString(R.string.booking_with_party, name)
            }
        }

        private fun callPhoneFor(event: EventEntity, actionRole: String): String {
            val clientPhone = event.clientPhone.orEmpty().filter(Char::isDigit)
            val takerPhone = event.takerPhone.orEmpty().filter(Char::isDigit)
            return if (actionRole == SessionManager.ROLE_TAKER) clientPhone else takerPhone
        }

        private fun String.toStatusLabel(context: Context): String =
            when (this) {
                "Upcoming" -> context.getString(R.string.event_status_upcoming)
                "Pending" -> context.getString(R.string.pending)
                "Confirmed" -> context.getString(R.string.confirmed)
                "Cancelled" -> context.getString(R.string.cancelled)
                "Completed" -> context.getString(R.string.completed)
                else -> this
            }
    }

    private data class EventAction(val labelRes: Int, val targetStatus: String)

    companion object {
        const val ACTION_REVIEW_STUDIO = "ReviewStudio"

        private val moneyFormat: NumberFormat = NumberFormat.getNumberInstance(Locale("en", "IN")).apply {
            maximumFractionDigits = 0
        }

        fun money(amount: Double): String = "Rs ${moneyFormat.format(amount)}"
        
        fun compactMoney(amount: Double): String {
            return when {
                amount >= 10_000_000 -> "Rs " + String.format(Locale.US, "%.1fCr", amount / 10_000_000).replace(".0Cr", "Cr")
                amount >= 100_000 -> "Rs " + String.format(Locale.US, "%.1fL", amount / 100_000).replace(".0L", "L")
                amount >= 1_000 -> "Rs " + String.format(Locale.US, "%.1fK", amount / 1_000).replace(".0K", "K")
                else -> money(amount)
            }
        }

        private val DIFF = object : DiffUtil.ItemCallback<EventEntity>() {
            override fun areItemsTheSame(oldItem: EventEntity, newItem: EventEntity) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: EventEntity, newItem: EventEntity) = oldItem == newItem
        }
    }
}
