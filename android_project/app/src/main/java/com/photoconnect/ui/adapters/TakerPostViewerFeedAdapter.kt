package com.photoconnect.ui.adapters

import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.photoconnect.R
import com.photoconnect.databinding.ItemTakerPostViewerFeedBinding
import com.photoconnect.model.TakerPost
import com.photoconnect.model.TakerPostImage
import com.photoconnect.utils.toDisplayDate

class TakerPostViewerFeedAdapter(
    private val onImageLikeToggle: (post: TakerPost, image: TakerPostImage) -> Unit,
    private val onSaveToggle: (post: TakerPost) -> Unit,
    private val onPostMenu: ((post: TakerPost, anchor: View) -> Unit)? = null,
) : RecyclerView.Adapter<TakerPostViewerFeedAdapter.PostVH>() {

    var showPostMenuActions: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    var onlyShowLikedImages: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    private val posts = mutableListOf<TakerPost>()
    private val pageStateByPostId = mutableMapOf<Int, Int>()
    private val expandedCaptionPostIds = mutableSetOf<Int>()
    private val initialLikedImageIdsByPostId = mutableMapOf<Int, Set<Int>>()
    private var deletingPostIds: Set<Int> = emptySet()

    fun submitList(items: List<TakerPost>) {
        posts.clear()
        posts.addAll(items)
        notifyDataSetChanged()
    }

    fun setDeletingPostIds(ids: Set<Int>) {
        if (deletingPostIds == ids) return
        val changed = deletingPostIds + ids
        deletingPostIds = ids
        posts.forEachIndexed { index, post ->
            if (post.id in changed) notifyItemChanged(index)
        }
    }

    inner class PostVH(private val b: ItemTakerPostViewerFeedBinding) : RecyclerView.ViewHolder(b.root) {
        private var pageCallback: ViewPager2.OnPageChangeCallback? = null

        fun bind(post: TakerPost) {
            val deleting = deletingPostIds.contains(post.id)
            val validIds = if (onlyShowLikedImages) {
                initialLikedImageIdsByPostId.getOrPut(post.id) {
                    post.images.filter { it.viewerHasLiked }.map { it.id }.toSet()
                }
            } else null

            val displayedImages = if (validIds != null) {
                post.images.filter { it.id in validIds }
            } else post.images

            val pagerAdapter = PostImagePagerAdapter {
                if (deleting) return@PostImagePagerAdapter
                val image = displayedImages.getOrNull(b.viewPagerImages.currentItem) ?: return@PostImagePagerAdapter
                onImageLikeToggle(post, image)
            }
            b.viewPagerImages.adapter = pagerAdapter
            pagerAdapter.submitList(
                displayedImages.mapNotNull { image ->
                    val full = image.imageUrl.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    PostImageItem(fullUrl = full, thumbUrl = image.thumbUrl)
                }
            )

            val savedPage = pageStateByPostId[post.id]?.coerceIn(0, (displayedImages.size - 1).coerceAtLeast(0)) ?: 0
            b.viewPagerImages.setCurrentItem(savedPage, false)
            pageCallback?.let { b.viewPagerImages.unregisterOnPageChangeCallback(it) }
            pageCallback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    pageStateByPostId[post.id] = position
                    updatePageMeta(displayedImages, position)
                }
            }
            b.viewPagerImages.registerOnPageChangeCallback(pageCallback!!)

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
            b.btnSavePost.setImageResource(
                if (post.viewerHasSaved) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline
            )
            b.tvPostViews.text = if (deleting) {
                b.root.context.getString(R.string.post_deleting)
            } else {
                b.root.context.getString(R.string.post_views_compact, post.viewCount)
            }
            b.btnPostMenu.isVisible = !deleting && showPostMenuActions && onPostMenu != null
            b.btnPostMenu.setOnClickListener(if (deleting) null else View.OnClickListener { onPostMenu?.invoke(post, it) })

            val expanded = expandedCaptionPostIds.contains(post.id)
            b.tvCaption.text = post.caption?.trim().orEmpty()
            b.tvCaption.isVisible = !post.caption.isNullOrBlank()
            b.tvCaption.maxLines = if (expanded) Int.MAX_VALUE else 2
            b.tvCaptionMore.isVisible = !post.caption.isNullOrBlank() && (post.caption?.length ?: 0) > 60
            b.tvCaptionMore.text = if (expanded) b.root.context.getString(R.string.show_less) else b.root.context.getString(R.string.more_dots)

            updatePageMeta(displayedImages, savedPage)

            b.btnImageLike.setOnClickListener {
                if (deleting) return@setOnClickListener
                val image = displayedImages.getOrNull(b.viewPagerImages.currentItem) ?: return@setOnClickListener
                onImageLikeToggle(post, image)
            }
            b.btnSavePost.setOnClickListener(if (deleting) null else View.OnClickListener { onSaveToggle(post) })
            b.tvCaptionMore.setOnClickListener {
                if (deleting) return@setOnClickListener
                if (expandedCaptionPostIds.contains(post.id)) {
                    expandedCaptionPostIds.remove(post.id)
                } else {
                    expandedCaptionPostIds.add(post.id)
                }
                notifyItemChanged(bindingAdapterPosition)
            }
            b.root.alpha = if (deleting) 0.42f else 1f
            b.btnImageLike.isEnabled = !deleting
            b.btnSavePost.isEnabled = !deleting
        }

        fun clear() {
            pageCallback?.let { b.viewPagerImages.unregisterOnPageChangeCallback(it) }
            pageCallback = null
            b.root.alpha = 1f
            b.btnImageLike.isEnabled = true
            b.btnSavePost.isEnabled = true
            b.btnPostMenu.setOnClickListener(null)
        }

        private fun updatePageMeta(displayedImages: List<TakerPostImage>, position: Int) {
            val image = displayedImages.getOrNull(position)
            b.tvPageIndicator.text = "${position + 1}/${displayedImages.size.coerceAtLeast(1)}"
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
