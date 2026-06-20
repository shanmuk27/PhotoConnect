package com.photoconnect.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.photoconnect.R
import com.photoconnect.databinding.ItemHeroImageBinding

data class HeroImage(
    val fullUrl: String?,
    val thumbUrl: String? = null,
)

class HeroImagePagerAdapter : RecyclerView.Adapter<HeroImagePagerAdapter.VH>() {
    private val items = mutableListOf<HeroImage>()

    fun submitList(list: List<HeroImage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemHeroImageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemHeroImageBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(img: HeroImage) {
            val ctx = b.root.context
            val full = img.fullUrl?.takeIf { it.isNotBlank() }
            val thumb = img.thumbUrl?.takeIf { it.isNotBlank() && it != full }

            val main = Glide.with(ctx)
                .load(full ?: thumb)
                .placeholder(R.drawable.ic_person_placeholder)
                .error(R.drawable.ic_person_placeholder)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade(200))

            if (full != null && thumb != null) {
                val thumbReq = Glide.with(ctx)
                    .load(thumb)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                main.thumbnail(thumbReq).into(b.ivHero)
            } else {
                main.into(b.ivHero)
            }
        }
    }
}

