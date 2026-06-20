package com.photoconnect.ui.adapters

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.photoconnect.R
import com.photoconnect.databinding.LayoutProfilePagerPageBinding
import com.photoconnect.db.ReviewEntity
import com.photoconnect.model.TakerPost
import kotlin.math.abs

class ProfilePagerAdapter(
    private val onPostClick: (TakerPost) -> Unit,
    private val onPostMenu: (TakerPost, View) -> Unit,
    private val canShowPostMenu: (TakerPost) -> Boolean = { true },
    private val onPageScroll: (isAtTop: Boolean, dy: Int) -> Unit = { _, _ -> },
    private val onLoadMore: (ProfilePage) -> Unit = {},
    private val onImageLikeToggle: (TakerPost, com.photoconnect.model.TakerPostImage) -> Unit = { _, _ -> },
    private val onSaveToggle: (TakerPost) -> Unit = { _ -> },
) : RecyclerView.Adapter<ProfilePagerAdapter.PageVH>() {

    private val pages = ProfilePage.values()
    private val postsAdapter = AccountPostAdapter(onMenu = onPostMenu, canShowMenu = canShowPostMenu, onClick = onPostClick)
    private val savedAdapter = AccountPostAdapter(onMenu = onPostMenu, canShowMenu = canShowPostMenu, onClick = onPostClick)
    private val likedAdapter = AccountPostAdapter(onMenu = onPostMenu, canShowMenu = canShowPostMenu, onClick = onPostClick)
    private val reviewsAdapter = ReviewAdapter()

    private var postsLoading = false
    private var savedLoading = false
    private var likedLoading = false
    private var reviewsLoading = false
    private var postsAppending = false
    private var savedAppending = false
    private var likedAppending = false
    private var reviewsAppending = false
    private var postsError: String? = null
    private var savedError: String? = null
    private var likedError: String? = null
    private var reviewsError: String? = null
    private var posts: List<TakerPost> = emptyList()
    private var savedPosts: List<TakerPost> = emptyList()
    private var likedPosts: List<TakerPost> = emptyList()
    private var reviews: List<ReviewEntity> = emptyList()
    private var hostRecyclerView: RecyclerView? = null
    private val postViewPool = RecyclerView.RecycledViewPool()
    private val reviewViewPool = RecyclerView.RecycledViewPool()

    init {
        setHasStableIds(true)
        postViewPool.setMaxRecycledViews(AccountPostAdapter.VIEW_TYPE, 24)
        reviewViewPool.setMaxRecycledViews(ReviewAdapter.VIEW_TYPE, 12)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        hostRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        if (hostRecyclerView === recyclerView) {
            hostRecyclerView = null
        }
    }

    private fun notifyPageChanged(page: ProfilePage) {
        val position = page.ordinal
        val host = hostRecyclerView
        if (host == null || (!host.isComputingLayout && host.scrollState == RecyclerView.SCROLL_STATE_IDLE)) {
            notifyItemChanged(position)
            return
        }
        host.post { notifyItemChanged(position) }
    }

    fun submitPosts(items: List<TakerPost>) {
        posts = items
        postsError = null
        postsLoading = false
        postsAppending = false
        postsAdapter.submitList(posts)
        notifyPageChanged(ProfilePage.POSTS)
    }

    fun submitCollections(saved: List<TakerPost>, liked: List<TakerPost>) {
        submitSavedPosts(saved)
        submitLikedPosts(liked)
    }

    fun submitSavedPosts(items: List<TakerPost>) {
        savedPosts = items
        savedError = null
        savedLoading = false
        savedAppending = false
        savedAdapter.submitList(savedPosts)
        notifyPageChanged(ProfilePage.SAVED)
    }

    fun submitLikedPosts(items: List<TakerPost>) {
        likedPosts = items
        likedError = null
        likedLoading = false
        likedAppending = false
        likedAdapter.submitList(likedPosts)
        notifyPageChanged(ProfilePage.LIKED)
    }

    fun submitReviews(items: List<ReviewEntity>) {
        reviews = items
        reviewsError = null
        reviewsLoading = false
        reviewsAppending = false
        reviewsAdapter.submitList(items)
        notifyPageChanged(ProfilePage.REVIEWS)
    }

    fun setDeletingPostIds(ids: Set<Int>) {
        postsAdapter.setDeletingPostIds(ids)
        savedAdapter.setDeletingPostIds(ids)
    }

    fun setPostsLoading(loading: Boolean, append: Boolean = false) {
        postsLoading = loading
        postsAppending = loading && append
        if (loading) postsError = null
        notifyPageChanged(ProfilePage.POSTS)
    }

    fun setSavedLoading(loading: Boolean, append: Boolean = false) {
        savedLoading = loading
        savedAppending = loading && append
        if (loading) savedError = null
        notifyPageChanged(ProfilePage.SAVED)
    }

    fun setLikedLoading(loading: Boolean, append: Boolean = false) {
        likedLoading = loading
        likedAppending = loading && append
        if (loading) likedError = null
        notifyPageChanged(ProfilePage.LIKED)
    }

    fun setReviewsLoading(loading: Boolean, append: Boolean = false) {
        reviewsLoading = loading
        reviewsAppending = loading && append
        if (loading) reviewsError = null
        notifyPageChanged(ProfilePage.REVIEWS)
    }

    fun setPostsError(message: String) {
        postsError = message
        postsLoading = false
        postsAppending = false
        notifyPageChanged(ProfilePage.POSTS)
    }

    fun setSavedError(message: String) {
        savedError = message
        savedLoading = false
        savedAppending = false
        notifyPageChanged(ProfilePage.SAVED)
    }

    fun setLikedError(message: String) {
        likedError = message
        likedLoading = false
        likedAppending = false
        notifyPageChanged(ProfilePage.LIKED)
    }

    fun setReviewsError(message: String) {
        reviewsError = message
        reviewsLoading = false
        reviewsAppending = false
        notifyPageChanged(ProfilePage.REVIEWS)
    }

    fun savedCount(): Int = savedPosts.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
        val binding = LayoutProfilePagerPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PageVH(binding)
    }

    override fun onBindViewHolder(holder: PageVH, position: Int) {
        holder.bind(pages[position])
    }

    override fun onViewRecycled(holder: PageVH) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = pages.size

    override fun getItemId(position: Int): Long = pages[position].ordinal.toLong()

    fun titleRes(position: Int): Int = pages[position].titleRes

    inner class PageVH(private val b: LayoutProfilePagerPageBinding) : RecyclerView.ViewHolder(b.root) {
        private var scrollListener: RecyclerView.OnScrollListener? = null
        private var touchStartY = 0f

        fun bind(page: ProfilePage) {
            val context = b.root.context
            val loading = when (page) {
                ProfilePage.POSTS -> postsLoading
                ProfilePage.SAVED -> savedLoading
                ProfilePage.LIKED -> likedLoading
                ProfilePage.REVIEWS -> reviewsLoading
            }
            val appending = when (page) {
                ProfilePage.POSTS -> postsAppending
                ProfilePage.SAVED -> savedAppending
                ProfilePage.LIKED -> likedAppending
                ProfilePage.REVIEWS -> reviewsAppending
            }
            val error = when (page) {
                ProfilePage.POSTS -> postsError
                ProfilePage.SAVED -> savedError
                ProfilePage.LIKED -> likedError
                ProfilePage.REVIEWS -> reviewsError
            }
            val itemCount = when (page) {
                ProfilePage.POSTS -> posts.size
                ProfilePage.SAVED -> savedPosts.size
                ProfilePage.LIKED -> likedPosts.size
                ProfilePage.REVIEWS -> reviews.size
            }

            configureRecycler(page)
            val targetAdapter = when (page) {
                ProfilePage.POSTS -> postsAdapter
                ProfilePage.SAVED -> savedAdapter
                ProfilePage.LIKED -> likedAdapter
                ProfilePage.REVIEWS -> reviewsAdapter
            }
            if (b.rvPageItems.adapter != targetAdapter) {
                b.rvPageItems.adapter = targetAdapter
            }
            scrollListener?.let { b.rvPageItems.removeOnScrollListener(it) }
            scrollListener = object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    onPageScroll(!recyclerView.canScrollVertically(-1), dy)
                    if (dy >= 0 && shouldLoadMore(recyclerView, page)) {
                        recyclerView.post { onLoadMore(page) }
                    }
                }
            }
            b.rvPageItems.addOnScrollListener(scrollListener!!)

            b.rvPageItems.isNestedScrollingEnabled = true
            b.rvPageItems.itemAnimator = null
            b.rvPageItems.isVisible = itemCount > 0
            b.layoutSkeletonPage.isVisible = loading && itemCount == 0 && page != ProfilePage.REVIEWS
            b.emptyPageCard.isVisible = if (page == ProfilePage.REVIEWS) itemCount == 0 else (!loading && itemCount == 0)
            b.progressLoadMore.isVisible = appending && itemCount > 0
            b.emptyPageCard.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> touchStartY = event.rawY
                    MotionEvent.ACTION_MOVE -> {
                        val dy = touchStartY - event.rawY
                        if (abs(dy) > 14f) {
                            onPageScroll(true, dy.toInt())
                            touchStartY = event.rawY
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchStartY = 0f
                }
                false
            }
            b.tvEmptyGlyph.setImageResource(page.drawableRes)
            b.tvEmptyTitle.text = context.getString(page.emptyTitleRes)
            b.tvEmptyBody.text = error ?: context.getString(page.emptyBodyRes)
        }

        fun recycle() {
            scrollListener?.let { b.rvPageItems.removeOnScrollListener(it) }
            scrollListener = null
            b.emptyPageCard.setOnTouchListener(null)
        }

        private fun configureRecycler(page: ProfilePage) {
            val context = b.root.context
            val currentManager = b.rvPageItems.layoutManager
            if (page == ProfilePage.REVIEWS) {
                if (currentManager !is LinearLayoutManager || currentManager is GridLayoutManager) {
                    b.rvPageItems.layoutManager = LinearLayoutManager(context)
                }
                b.rvPageItems.setHasFixedSize(false)
                b.rvPageItems.setItemViewCacheSize(8)
                b.rvPageItems.setRecycledViewPool(reviewViewPool)
            } else {
                if (currentManager !is GridLayoutManager || currentManager.spanCount != 3) {
                    b.rvPageItems.layoutManager = GridLayoutManager(context, 3)
                }
                b.rvPageItems.setHasFixedSize(true)
                b.rvPageItems.setItemViewCacheSize(18)
                b.rvPageItems.setRecycledViewPool(postViewPool)
            }
            b.rvPageItems.itemAnimator = null
            b.rvPageItems.overScrollMode = View.OVER_SCROLL_NEVER
        }

        private fun shouldLoadMore(recyclerView: RecyclerView, page: ProfilePage): Boolean {
            val count = recyclerView.adapter?.itemCount ?: return false
            if (count == 0) return false
            val lastVisible = when (val manager = recyclerView.layoutManager) {
                is GridLayoutManager -> manager.findLastVisibleItemPosition()
                is LinearLayoutManager -> manager.findLastVisibleItemPosition()
                else -> RecyclerView.NO_POSITION
            }
            val threshold = if (page == ProfilePage.REVIEWS) 4 else 6
            return lastVisible != RecyclerView.NO_POSITION && lastVisible >= count - threshold
        }
    }

    enum class ProfilePage(
        val titleRes: Int,
        val emptyTitleRes: Int,
        val emptyBodyRes: Int,
        val drawableRes: Int,
    ) {
        POSTS(
            R.string.account_posts,
            R.string.account_empty_posts,
            R.string.account_empty_posts_body,
            R.drawable.ic_camera_24,
        ),
        SAVED(
            R.string.account_saved,
            R.string.account_empty_saved,
            R.string.account_empty_saved_body,
            R.drawable.ic_bookmark_outline,
        ),
        LIKED(
            R.string.account_liked,
            R.string.account_empty_liked,
            R.string.account_empty_liked_body,
            R.drawable.ic_favorite_outline,
        ),
        REVIEWS(
            R.string.account_reviews,
            R.string.account_empty_reviews,
            R.string.account_empty_reviews_body,
            R.drawable.ic_star,
        ),
    }
}
