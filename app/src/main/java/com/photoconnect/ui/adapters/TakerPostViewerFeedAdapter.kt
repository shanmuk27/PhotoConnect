package com.photoconnect.ui.adapters

import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import android.view.LayoutInflater
import android.view.ViewGroup
import com.photoconnect.R
import com.photoconnect.databinding.ItemTakerPostViewerFeedBinding
import com.photoconnect.model.TakerPost
import com.photoconnect.model.TakerPostImage
import com.photoconnect.utils.toDisplayDate

class TakerPostViewerFeedAdapter(
    private val onImageLikeToggle: (post: TakerPost, image: TakerPostImage) -> Unit,
    private val onSaveToggle: (post: TakerPost) -> Unit,
) : RecyclerView.Adapter<TakerPostViewerFeedAdapter.PostVH>() {

    private val posts = mutableListOf<TakerPost>()
    private val pageStateByPostId = mutableMapOf<Int, Int>()
    private val expandedCaptionPostIds = mutableSetOf<Int>()

    fun submitList(items: List<TakerPost>) {
        posts.clear()
        posts.addAll(items)
        notifyDataSetChanged()
    }

    inner class PostVH(private val b: ItemTakerPostViewerFeedBinding) : RecyclerView.ViewHolder(b.root) {
        private var pageCallback: ViewPager2.OnPageChangeCallback? = null

        fun bind(post: TakerPost) {
            val pagerAdapter = PostImagePagerAdapter {
                val image = post.images.getOrNull(b.viewPagerImages.currentItem) ?: return@PostImagePagerAdapter
                onImageLikeToggle(post, image)
            }
            b.viewPagerImages.adapter = pagerAdapter
            pagerAdapter.submitList(
                post.images.mapNotNull { image ->
                    val full = image.imageUrl.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    PostImageItem(fullUrl = full, thumbUrl = image.thumbUrl)
                }
            )

            val savedPage = pageStateByPostId[post.id]?.coerceIn(0, (post.images.size - 1).coerceAtLeast(0)) ?: 0
            b.viewPagerImages.setCurrentItem(savedPage, false)
            pageCallback?.let { b.viewPagerImages.unregisterOnPageChangeCallback(it) }
            pageCallback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    pageStateByPostId[post.id] = position
                    updatePageMeta(post, position)
                }
            }
            b.viewPagerImages.registerOnPageChangeCallback(pageCallback!!)

            b.tvPostTitle.text = b.root.context.getString(R.string.post_title_index, bindingAdapterPosition + 1)
            b.tvPostMeta.text = post.createdAt?.take(10)?.toDisplayDate()
                ?: b.root.context.getString(R.string.recent_post)
            b.btnSavePost.setImageResource(
                if (post.viewerHasSaved) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline
            )

            val expanded = expandedCaptionPostIds.contains(post.id)
            b.tvCaption.text = post.caption?.trim().orEmpty()
            b.tvCaption.isVisible = !post.caption.isNullOrBlank()
            b.tvCaption.maxLines = if (expanded) Int.MAX_VALUE else 2
            b.tvCaptionMore.isVisible = !post.caption.isNullOrBlank() && (post.caption?.length ?: 0) > 60
            b.tvCaptionMore.text = if (expanded) b.root.context.getString(R.string.show_less) else b.root.context.getString(R.string.more_dots)

            updatePageMeta(post, savedPage)

            b.btnImageLike.setOnClickListener {
                val image = post.images.getOrNull(b.viewPagerImages.currentItem) ?: return@setOnClickListener
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
        }

        fun clear() {
            pageCallback?.let { b.viewPagerImages.unregisterOnPageChangeCallback(it) }
            pageCallback = null
        }

        private fun updatePageMeta(post: TakerPost, position: Int) {
            val image = post.images.getOrNull(position)
            b.tvPageIndicator.text = "${position + 1}/${post.images.size.coerceAtLeast(1)}"
            b.tvImageLikes.text = (image?.likeCount ?: 0).toString()
            b.btnImageLike.setImageResource(
                if (image?.viewerHasLiked == true) R.drawable.ic_favorite_filled
                else R.drawable.ic_favorite_outline
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PostVH =
        PostVH(ItemTakerPostViewerFeedBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: PostVH, position: Int) = holder.bind(posts[position])

    override fun onViewRecycled(holder: PostVH) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    override fun getItemCount() = posts.size
}
