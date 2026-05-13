package com.photoconnect.ui.adapters

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.photoconnect.utils.toDisplayDate
import com.photoconnect.utils.toServiceIcon
import com.photoconnect.utils.toServiceLabel
import com.photoconnect.utils.toServiceSummary

class TakerAdapter(private val onClick: (Taker) -> Unit) :
    ListAdapter<Taker, TakerAdapter.VH>(DIFF) {
    private var availabilityDate: String? = null
    private var favoriteTakerIds: Set<Int> = emptySet()

    fun updateAvailabilityDate(date: String?) {
        if (availabilityDate != date) {
            availabilityDate = date
            notifyDataSetChanged()
        }
    }

    fun updateFavorites(favoriteIds: Set<Int>) {
        if (favoriteTakerIds != favoriteIds) {
            favoriteTakerIds = favoriteIds
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTakerCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemTakerCardBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(t: Taker) {
            val ctx = b.root.context
            val hasDateFilter = availabilityDate != null
            val status = t.availabilityStatus ?: "Not Available"
            val isUnavailableForSelectedDate = hasDateFilter && status != "Available"
            b.tvName.text = t.fullName
            b.tvLocation.text = "${t.city}, ${t.state}"
            b.tvRating.text = if (t.avgRating > 0) "%.1f".format(t.avgRating) else "New"
            b.tvReviewSummary.text = buildString {
                if (t.reviewCount > 0) {
                    append("${t.reviewCount} ratings")
                }
                if (t.postCount > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${t.postCount} posts")
                }
                if (isEmpty()) {
                    append("New profile")
                }
            }
            b.tvServiceIcon.text = t.serviceType.toServiceIcon()

            b.cardFavoriteBadge.isVisible = favoriteTakerIds.contains(t.id) || t.viewerHasFavorited

            when {
                t.isTopTaker -> {
                    b.tvQualityBadge.visibility = View.VISIBLE
                    b.tvQualityBadge.text = ctx.getString(R.string.badge_top_near_you)
                }
                !t.proximityLabel.isNullOrBlank() -> {
                    b.tvQualityBadge.visibility = View.VISIBLE
                    b.tvQualityBadge.text = t.proximityLabel
                }
                t.isFeatured == 1 -> {
                    b.tvQualityBadge.visibility = View.VISIBLE
                    b.tvQualityBadge.text = ctx.getString(R.string.badge_featured)
                }
                t.avgRating >= 4.5f && t.reviewCount >= 5 -> {
                    b.tvQualityBadge.visibility = View.VISIBLE
                    b.tvQualityBadge.text = ctx.getString(R.string.badge_top_rated)
                }
                else -> b.tvQualityBadge.visibility = View.GONE
            }

            val services = t.offeredServices.filter { it.isNotBlank() }.distinct()
            b.chipGroupServices.removeAllViews()
            if (services.isEmpty()) {
                b.tvService.visibility = View.VISIBLE
                b.tvService.text = t.offeredServices.toServiceSummary()
            } else {
                b.tvService.visibility = View.GONE
                val maxChips = 3
                val chipH = ctx.resources.getDimensionPixelSize(R.dimen.category_chip_height)
                services.take(maxChips).forEach { key ->
                    val chip = Chip(ctx, null, com.google.android.material.R.attr.chipStyle).apply {
                        text = key.toServiceLabel()
                        isClickable = false
                        isCheckable = false
                        minimumHeight = chipH
                    }
                    b.chipGroupServices.addView(chip)
                }
                val extra = services.size - maxChips
                if (extra > 0) {
                    val more = Chip(ctx, null, com.google.android.material.R.attr.chipStyle).apply {
                        text = "+$extra"
                        isClickable = false
                        isCheckable = false
                        minimumHeight = chipH
                    }
                    b.chipGroupServices.addView(more)
                }
            }

            if (hasDateFilter) {
                b.tvAvailabilityBadge.text = when (status) {
                    "Available" -> "Available ${availabilityDate!!.toDisplayDate()}"
                    "Booked" -> "Booked ${availabilityDate!!.toDisplayDate()}"
                    else -> "Not available ${availabilityDate!!.toDisplayDate()}"
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
            if (isUnavailableForSelectedDate) {
                b.tvUnavailableRibbon.text = if (status == "Booked") "Booked" else "Unavailable"
                val matrix = ColorMatrix().apply { setSaturation(0.15f) }
                b.ivPortfolio.colorFilter = ColorMatrixColorFilter(matrix)
                b.ivPortfolio.alpha = 0.92f
            } else {
                b.ivPortfolio.colorFilter = null
                b.ivPortfolio.alpha = 1f
            }
            b.btnBookNowCard.isEnabled = !isUnavailableForSelectedDate
            b.btnBookNowCard.alpha = if (isUnavailableForSelectedDate) 0.55f else 1f
            b.btnBookNowCard.text = if (isUnavailableForSelectedDate) {
                ctx.getString(R.string.home_unavailable)
            } else {
                ctx.getString(R.string.book_now)
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

            b.root.setOnClickListener { onClick(t) }
            b.btnBookNowCard.setOnClickListener {
                if (!isUnavailableForSelectedDate) {
                    onClick(t)
                }
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Taker>() {
            override fun areItemsTheSame(o: Taker, n: Taker) = o.id == n.id
            override fun areContentsTheSame(o: Taker, n: Taker) = o == n
        }
    }
}
