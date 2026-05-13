package com.photoconnect.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.photoconnect.R
import com.photoconnect.databinding.ItemFeaturedTakerBinding
import com.photoconnect.model.Taker
import com.photoconnect.utils.toServiceSummary

class FeaturedTakerAdapter(private val onClick: (Taker) -> Unit) : ListAdapter<Taker, FeaturedTakerAdapter.VH>(DIFF) {
    override fun onCreateViewHolder(p: ViewGroup, v: Int) = VH(ItemFeaturedTakerBinding.inflate(LayoutInflater.from(p.context), p, false))
    override fun onBindViewHolder(h: VH, p: Int) = h.bind(getItem(p))
    inner class VH(private val b: ItemFeaturedTakerBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(t: Taker) {
            b.tvFeaturedName.text = t.fullName
            b.tvFeaturedService.text = t.offeredServices.toServiceSummary()
            b.tvFeaturedRating.text = if (t.avgRating > 0f) "%.1f".format(t.avgRating) else "New"

            val ctx = b.root.context
            val thumbUrl = t.profileThumbUrl
            val fullUrl = t.profileImageUrl
            if (!thumbUrl.isNullOrBlank() && !fullUrl.isNullOrBlank()) {
                val thumbRequest = Glide.with(ctx)
                    .load(thumbUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                Glide.with(ctx)
                    .load(fullUrl)
                    .placeholder(R.drawable.ic_person_placeholder)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .thumbnail(thumbRequest)
                    .into(b.ivFeatured)
            } else {
                Glide.with(ctx).load(fullUrl)
                    .placeholder(R.drawable.ic_person_placeholder).centerCrop()
                    .transition(DrawableTransitionOptions.withCrossFade()).into(b.ivFeatured)
            }

            b.root.setOnClickListener { onClick(t) }
        }
    }
    companion object { val DIFF = object : DiffUtil.ItemCallback<Taker>() {
        override fun areItemsTheSame(a: Taker, b: Taker) = a.id == b.id
        override fun areContentsTheSame(a: Taker, b: Taker) = a == b
    }}
}
