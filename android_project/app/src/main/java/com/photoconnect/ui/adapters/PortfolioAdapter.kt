package com.photoconnect.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.photoconnect.R
import com.photoconnect.databinding.ItemPortfolioSampleBinding
import com.photoconnect.model.PortfolioSample

class PortfolioAdapter(
    private val onDelete: ((PortfolioSample) -> Unit)? = null,
) : RecyclerView.Adapter<PortfolioAdapter.VH>() {
    private val samples = mutableListOf<PortfolioSample>()

    fun submitList(items: List<PortfolioSample>) {
        samples.clear()
        samples.addAll(items)
        notifyDataSetChanged()
    }

    inner class VH(private val b: ItemPortfolioSampleBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(sample: PortfolioSample) {
            Glide.with(b.root.context)
                .load(sample.imageUrl)
                .placeholder(R.drawable.ic_person_placeholder)
                .thumbnail(0.25f)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .transition(DrawableTransitionOptions.withCrossFade())
                .centerCrop()
                .into(b.ivSample)

            b.tvCaption.text = sample.caption.orEmpty()
            b.tvCaption.visibility = if (sample.caption.isNullOrBlank()) View.GONE else View.VISIBLE

            b.btnDelete.visibility = if (onDelete == null) View.GONE else View.VISIBLE
            b.btnDelete.setOnClickListener {
                onDelete?.invoke(sample)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemPortfolioSampleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(samples[position])

    override fun getItemCount() = samples.size
}
