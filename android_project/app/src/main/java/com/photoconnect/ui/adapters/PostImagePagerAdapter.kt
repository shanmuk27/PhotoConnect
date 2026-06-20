package com.photoconnect.ui.adapters

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.GestureDetectorCompat
import androidx.recyclerview.widget.RecyclerView
import android.view.GestureDetector
import com.photoconnect.R
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.photoconnect.databinding.ItemPostViewerImageBinding
import com.photoconnect.ui.views.ZoomableImageView

data class PostImageItem(
    val fullUrl: String,
    val thumbUrl: String? = null,
)

class PostImagePagerAdapter(
    private val onDoubleTap: () -> Unit,
) : RecyclerView.Adapter<PostImagePagerAdapter.VH>() {
    private val images = mutableListOf<PostImageItem>()

    fun submitList(urls: List<PostImageItem>) {
        images.clear()
        images.addAll(urls)
        notifyDataSetChanged()
    }

    inner class VH(private val b: ItemPostViewerImageBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: PostImageItem) {
            val detector = GestureDetectorCompat(
                b.root.context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        onDoubleTap()
                        return true
                    }
                }
            )
            val ctx = b.root.context
            val full = item.fullUrl
            val thumb = item.thumbUrl?.takeIf { it.isNotBlank() && it != full }
            val main = Glide.with(ctx)
                .load(full)
                .placeholder(R.drawable.ic_person_placeholder)
                .error(R.drawable.ic_person_placeholder)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()

            if (thumb != null) {
                val thumbReq = Glide.with(ctx)
                    .load(thumb)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                main.thumbnail(thumbReq).into(b.ivPostImage)
            } else {
                main.into(b.ivPostImage)
            }
            (b.ivPostImage as? ZoomableImageView)?.resetZoom()
            b.ivPostImage.setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
                false
            }
            b.ivPostImage.setOnLongClickListener { true }

            // Small perf win: prefetch next image to make swipes feel instant.
            val next = images.getOrNull(bindingAdapterPosition + 1)?.fullUrl
            if (!next.isNullOrBlank()) {
                Glide.with(ctx).load(next).diskCacheStrategy(DiskCacheStrategy.ALL).preload()
            }
        }

        fun clear() {
            Glide.with(b.root.context).clear(b.ivPostImage)
            (b.ivPostImage as? ZoomableImageView)?.resetZoom()
            b.ivPostImage.setOnTouchListener(null)
            b.ivPostImage.setOnLongClickListener(null)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemPostViewerImageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(images[position])

    override fun onViewRecycled(holder: VH) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    override fun getItemCount() = images.size
}
