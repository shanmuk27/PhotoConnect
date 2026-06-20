package com.photoconnect.ui.adapters

import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.photoconnect.R
import com.photoconnect.databinding.ItemInlinePostImageBinding
import com.photoconnect.databinding.ItemTakerDetailPostBinding
import com.photoconnect.model.TakerPost
import com.photoconnect.model.TakerPostImage
import com.photoconnect.utils.toDisplayDate

class TakerDetailFeedAdapter(
    private val onImageLikeToggle: (post: TakerPost, image: TakerPostImage) -> Unit,
    private val onSaveToggle: (post: TakerPost) -> Unit,
    private val onOpenPost: (post: TakerPost) -> Unit,
) : RecyclerView.Adapter<TakerDetailFeedAdapter.PostVH>() {

    private val posts = mutableListOf<TakerPost>()
    private val pageStateByPostId = mutableMapOf<Int, Int>()
    private val expandedCaptionPostIds = mutableSetOf<Int>()

    fun submitList(items: List<TakerPost>) {
        posts.clear()
        posts.addAll(items)
        notifyDataSetChanged()
    }

    inner class PostVH(private val b: ItemTakerDetailPostBinding) : RecyclerView.ViewHolder(b.root) {
        private var pageCallback: ViewPager2.OnPageChangeCallback? = null

        fun bind(post: TakerPost) {
            val pagerAdapter = InlinePostImagePagerAdapter(
                onDoubleTap = { position ->
                    val image = post.images.getOrNull(position)
                    if (image != null) {
                        showLikeBurst()
                        onImageLikeToggle(post, image)
                    }
                },
                onSingleTap = { onOpenPost(post) }
            )
            b.viewPagerImages.adapter = pagerAdapter
            pagerAdapter.submitList(post.images)
            val savedPage = pageStateByPostId[post.id]?.coerceIn(0, (post.images.size - 1).coerceAtLeast(0)) ?: 0
            b.viewPagerImages.setCurrentItem(savedPage, false)

            b.tvPostTitle.text = b.root.context.getString(R.string.post_title_index, bindingAdapterPosition + 1)
            val created = post.createdAt?.take(10)?.toDisplayDate()
            val edited = post.updatedAt?.take(10)
                ?.takeIf { it.isNotBlank() && it != post.createdAt?.take(10) }
                ?.toDisplayDate()
            b.tvPostMeta.text = if (edited != null) {
                b.root.context.getString(R.string.post_edited_on, edited)
            } else {
                created ?: b.root.context.getString(R.string.recent_post)
            }
            b.tvCaption.text = post.caption?.trim().orEmpty()
            b.tvCaption.isVisible = !post.caption.isNullOrBlank()
            val isExpanded = expandedCaptionPostIds.contains(post.id)
            b.tvCaption.maxLines = if (isExpanded) Int.MAX_VALUE else 2
            b.tvCaptionMore.isVisible = !post.caption.isNullOrBlank() && (post.caption?.length ?: 0) > 60
            b.tvCaptionMore.text = if (isExpanded) b.root.context.getString(R.string.show_less) else b.root.context.getString(R.string.more_dots)
            b.tvLikeSummary.text = b.root.context.resources.getQuantityString(
                R.plurals.post_total_likes,
                post.likeCount,
                post.likeCount
            )
            b.btnSavePost.setImageResource(
                if (post.viewerHasSaved) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline
            )
            b.tvPostViews.text = b.root.context.getString(R.string.post_views_compact, post.viewCount)

            updatePagerMeta(post, 0)
            pageCallback?.let { b.viewPagerImages.unregisterOnPageChangeCallback(it) }
            pageCallback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    pageStateByPostId[post.id] = position
                    updatePagerMeta(post, position)
                }
            }
            b.viewPagerImages.registerOnPageChangeCallback(pageCallback!!)
            updatePagerMeta(post, savedPage)

            b.btnImageLike.setOnClickListener {
                val pos = b.viewPagerImages.currentItem
                val image = post.images.getOrNull(pos) ?: return@setOnClickListener
                if (!image.viewerHasLiked) showLikeBurst()
                onImageLikeToggle(post, image)
            }
            b.btnSavePost.setOnClickListener { onSaveToggle(post) }
            b.tvCaptionMore.setOnClickListener {
                if (expandedCaptionPostIds.contains(post.id)) {
                    expandedCaptionPostIds.remove(post.id)
                } else {
                    expandedCaptionPostIds.add(post.id)
                }
                notifyItemChanged(bindingAdapterPosition)
            }
            b.tvTapToOpen.setOnClickListener { onOpenPost(post) }
        }

        fun clear() {
            pageCallback?.let { b.viewPagerImages.unregisterOnPageChangeCallback(it) }
            pageCallback = null
        }

        private fun updatePagerMeta(post: TakerPost, position: Int) {
            val image = post.images.getOrNull(position)
            b.tvPageIndicator.text = "${position + 1}/${post.images.size.coerceAtLeast(1)}"
            b.tvPageIndicator.isVisible = post.images.isNotEmpty()
            b.tvImageLikes.text = (image?.likeCount ?: 0).toString()
            b.btnImageLike.setImageResource(
                if (image?.viewerHasLiked == true) R.drawable.ic_favorite_filled
                else R.drawable.ic_favorite_outline
            )
        }

        private fun showLikeBurst() {
            b.ivLikeBurst.apply {
                alpha = 0f
                scaleX = 0.6f
                scaleY = 0.6f
                visibility = View.VISIBLE
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180L)
                    .setInterpolator(OvershootInterpolator())
                    .withEndAction {
                        animate()
                            .alpha(0f)
                            .scaleX(1.15f)
                            .scaleY(1.15f)
                            .setDuration(180L)
                            .withEndAction { visibility = View.GONE }
                            .start()
                    }
                    .start()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PostVH =
        PostVH(ItemTakerDetailPostBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: PostVH, position: Int) = holder.bind(posts[position])

    override fun onViewRecycled(holder: PostVH) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    override fun getItemCount() = posts.size
}

private class InlinePostImagePagerAdapter(
    private val onDoubleTap: (position: Int) -> Unit,
    private val onSingleTap: () -> Unit,
) : RecyclerView.Adapter<InlinePostImagePagerAdapter.ImageVH>() {
    private val images = mutableListOf<TakerPostImage>()

    fun submitList(items: List<TakerPostImage>) {
        images.clear()
        images.addAll(items)
        notifyDataSetChanged()
    }

    inner class ImageVH(private val b: ItemInlinePostImageBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: TakerPostImage) {
            val detector = GestureDetectorCompat(
                b.root.context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        onSingleTap()
                        return true
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        onDoubleTap(bindingAdapterPosition)
                        return true
                    }
                }
            )
            val ctx = b.root.context
            val full = item.imageUrl
            val thumb = item.thumbUrl?.takeIf { it.isNotBlank() && it != full }
            val main = Glide.with(ctx)
                .load(full)
                .placeholder(R.drawable.ic_person_placeholder)
                .error(R.drawable.ic_person_placeholder)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .fitCenter()

            if (thumb != null) {
                val thumbReq = Glide.with(ctx)
                    .load(thumb)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .fitCenter()
                main.thumbnail(thumbReq).into(b.ivPostImage)
            } else {
                main.into(b.ivPostImage)
            }
            b.ivPostImage.scaleType = ImageView.ScaleType.CENTER_CROP
            b.root.setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
                false
            }
            b.root.setOnLongClickListener { true }
        }

        fun clear() {
            Glide.with(b.root.context).clear(b.ivPostImage)
            b.root.setOnTouchListener(null)
            b.root.setOnLongClickListener(null)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageVH =
        ImageVH(ItemInlinePostImageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ImageVH, position: Int) = holder.bind(images[position])

    override fun onViewRecycled(holder: ImageVH) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    override fun getItemCount() = images.size
}
