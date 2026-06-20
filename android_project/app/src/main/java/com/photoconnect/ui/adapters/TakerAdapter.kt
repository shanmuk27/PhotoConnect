package com.photoconnect.ui.adapters

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.material.chip.Chip
import com.photoconnect.R
import com.photoconnect.databinding.ItemTakerCardBinding
import com.photoconnect.model.Taker
import com.photoconnect.utils.forceLeftToRightTree
import com.photoconnect.utils.toDisplayDate
import com.photoconnect.utils.toServiceIcon
import com.photoconnect.utils.toServiceLabel
import com.photoconnect.utils.toServiceSummary

class TakerAdapter(
    private val onClick: (Taker) -> Unit,
    private val onSocialClick: (String) -> Unit = {},
    private val onCompareToggle: (Taker) -> Unit = {},
) :
    ListAdapter<Taker, TakerAdapter.VH>(DIFF) {
    private var availabilityDate: String? = null
    private var favoriteTakerIds: Set<Int> = emptySet()
    private var hasFavoriteSnapshot: Boolean = false
    private var compareMode: Boolean = false
    private var comparedTakerIds: Set<Int> = emptySet()

    fun updateAvailabilityDate(date: String?) {
        if (availabilityDate != date) {
            availabilityDate = date
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
        }
    }

    fun updateFavorites(favoriteIds: Set<Int>) {
        val hadFavoriteSnapshot = hasFavoriteSnapshot
        hasFavoriteSnapshot = true
        if (!hadFavoriteSnapshot) {
            favoriteTakerIds = favoriteIds
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
            return
        }
        if (favoriteTakerIds != favoriteIds) {
            val oldFavoriteIds = favoriteTakerIds
            favoriteTakerIds = favoriteIds
            currentList.forEachIndexed { index, taker ->
                if (oldFavoriteIds.contains(taker.id) != favoriteIds.contains(taker.id)) {
                    notifyItemChanged(index)
                }
            }
        }
    }

    fun updateCompareState(enabled: Boolean, selectedIds: Set<Int>) {
        if (compareMode != enabled || comparedTakerIds != selectedIds) {
            compareMode = enabled
            comparedTakerIds = selectedIds
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTakerCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemTakerCardBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.root.forceLeftToRightTree()
        }

        fun bind(t: Taker) {
            val ctx = b.root.context
            val hasDateFilter = availabilityDate != null
            val status = t.availabilityStatus ?: "Not Available"
            val isUnavailableForSelectedDate = hasDateFilter && status != "Available"
            val isFavorite = favoriteTakerIds.contains(t.id) || t.viewerHasFavorited
            val isCompared = comparedTakerIds.contains(t.id)
            b.tvName.text = t.fullName
            b.tvLocation.text = listOfNotNull(
                t.city.takeIf { it.isNotBlank() },
                t.state.takeIf { it.isNotBlank() },
                t.proximityLabel?.takeIf { it.isNotBlank() },
            ).distinct().joinToString(" - ").ifBlank { t.pincode }
            b.tvRating.text = if (t.avgRating > 0) "%.1f".format(t.avgRating) else ctx.getString(R.string.account_new_badge)
            val services = t.offeredServices.filter { it.isNotBlank() }.distinct()
            val primaryService = services.firstOrNull().orEmpty()
            b.tvServiceIcon.text = primaryService.toServiceIcon()
            b.tvTrustBadge.text = trustBadgeText(t, ctx)
            val (trustText, trustBg) = when (t.trustStage.lowercase()) {
                "pro_verified" -> "#312E81" to "#E0E7FF"
                "trusted" -> "#064E3B" to "#D1FAE5"
                "verified" -> "#042F2E" to "#CCFBF1"
                else -> "#7F1D1D" to "#FEE2E2"
            }
            b.tvTrustBadge.setTextColor(Color.parseColor(trustText))
            b.tvTrustBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor(trustBg))
            b.tvQualityBadge.isVisible = compareMode || t.respondsFast || t.isTopTaker
            b.tvQualityBadge.text = when {
                compareMode && isCompared -> ctx.getString(R.string.home_compare_selected)
                compareMode -> ctx.getString(R.string.home_compare_add)
                t.respondsFast -> ctx.getString(R.string.home_filter_responds_fast)
                else -> ctx.getString(R.string.badge_featured)
            }
            b.tvService.visibility = View.VISIBLE
            b.tvService.text = if (services.isEmpty()) {
                t.offeredServices.toServiceSummary(ctx)
            } else {
                if (services.size == 1) {
                    services.first().toServiceLabel(ctx)
                } else {
                    "${services.first().toServiceLabel(ctx)} +${services.size - 1}"
                }
            }
            b.tvSearchReason.isVisible = !t.searchExplanation.isNullOrBlank()
            b.tvSearchReason.text = t.searchExplanation.orEmpty()
            if (hasDateFilter) {
                val displayDate = availabilityDate!!.toDisplayDate()
                b.tvAvailabilityBadge.text = when (status) {
                    "Available" -> ctx.getString(R.string.available_on_date, displayDate)
                    "Booked" -> ctx.getString(R.string.booked_on_date, displayDate)
                    else -> ctx.getString(R.string.not_available_on_date, displayDate)
                }
                val (badgeTextColor, badgeFillColor) = when (status) {
                    "Available" -> "#F0FDF4" to "#CC166534"
                    "Booked" -> "#FEF2F2" to "#CC991B1B"
                    else -> "#F8FAFC" to "#CC334155"
                }
                b.tvAvailabilityBadge.setTextColor(Color.parseColor(badgeTextColor))
                b.tvAvailabilityBadge.backgroundTintList =
                    ColorStateList.valueOf(Color.parseColor(badgeFillColor))
                b.tvAvailabilityBadge.visibility = View.VISIBLE
            } else {
                b.tvAvailabilityBadge.visibility = View.GONE
            }

            b.layoutUnavailableOverlay.visibility = if (isUnavailableForSelectedDate) View.VISIBLE else View.GONE
            b.cardFavoriteBadge.isVisible = isFavorite
            if (isUnavailableForSelectedDate) {
                b.tvUnavailableRibbon.text = if (status == "Booked") ctx.getString(R.string.booked) else ctx.getString(R.string.home_unavailable)
                val matrix = ColorMatrix().apply { setSaturation(0.15f) }
                b.ivPortfolio.colorFilter = ColorMatrixColorFilter(matrix)
                b.ivPortfolio.alpha = 0.92f
            } else {
                b.ivPortfolio.colorFilter = null
                b.ivPortfolio.alpha = 1f
            }

            // Progressive image loading: show thumb first, then upgrade to full
            val thumbUrl = t.profileThumbUrl
            val fullUrl  = t.profileImageUrl
            if (!fullUrl.isNullOrBlank() && !thumbUrl.isNullOrBlank()) {
                val thumbRequest = Glide.with(ctx)
                    .load(thumbUrl)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .centerCrop()
                val fullRequest = Glide.with(ctx)
                    .load(fullUrl)
                    .placeholder(R.drawable.ic_person_placeholder)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .centerCrop()
                    .transition(DrawableTransitionOptions.withCrossFade(200))
                fullRequest.thumbnail(thumbRequest).into(b.ivPortfolio)
            } else {
                Glide.with(ctx)
                    .load(fullUrl ?: t.profileImageUrl)
                    .placeholder(R.drawable.ic_person_placeholder)
                    .centerCrop()
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(b.ivPortfolio)
            }

            b.root.setOnClickListener {
                if (compareMode) onCompareToggle(t) else onClick(t)
            }
        }

        private fun trustBadgeText(t: Taker, ctx: Context): String =
            when (t.trustStage.lowercase()) {
                "pro_verified" -> ctx.getString(R.string.trust_pro_verified)
                "trusted" -> ctx.getString(R.string.trust_trusted)
                "verified" -> ctx.getString(R.string.trust_verified)
                else -> ctx.getString(R.string.trust_unverified)
            }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Taker>() {
            override fun areItemsTheSame(o: Taker, n: Taker) = o.id == n.id
            override fun areContentsTheSame(o: Taker, n: Taker) = o == n
        }
    }
}
