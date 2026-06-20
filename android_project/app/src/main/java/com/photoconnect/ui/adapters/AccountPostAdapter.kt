package com.photoconnect.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.photoconnect.R
import com.photoconnect.databinding.ItemAccountPostBinding
import com.photoconnect.model.TakerPost

class AccountPostAdapter(
    private val compactHorizontal: Boolean = false,
    private val onMenu: ((TakerPost, View) -> Unit)? = null,
    private val canShowMenu: (TakerPost) -> Boolean = { true },
    private val onClick: (TakerPost) -> Unit,
) : RecyclerView.Adapter<AccountPostAdapter.VH>() {
    private val posts = mutableListOf<TakerPost>()
    private var deletingPostIds: Set<Int> = emptySet()

    init {
        setHasStableIds(true)
    }

    fun submitList(items: List<TakerPost>) {
        val oldItems = posts.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = items.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition].id == items[newItemPosition].id

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition] == items[newItemPosition]
        })
        posts.clear()
        posts.addAll(items)
        diff.dispatchUpdatesTo(this)
    }

    fun setDeletingPostIds(ids: Set<Int>) {
        if (deletingPostIds == ids) return
        val changed = deletingPostIds + ids
        deletingPostIds = ids
        posts.forEachIndexed { index, post ->
            if (post.id in changed) notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAccountPostBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        if (compactHorizontal) {
            val width = (156 * parent.resources.displayMetrics.density).toInt()
            binding.root.layoutParams = RecyclerView.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (10 * parent.resources.displayMetrics.density).toInt()
            }
        }
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(posts[position])

    override fun getItemViewType(position: Int): Int = VIEW_TYPE

    override fun getItemId(position: Int): Long {
        val id = posts.getOrNull(position)?.id?.toLong() ?: return RecyclerView.NO_ID
        return if (id == RecyclerView.NO_ID) Long.MIN_VALUE else id
    }

    override fun onViewRecycled(holder: VH) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    override fun getItemCount() = posts.size

    inner class VH(private val b: ItemAccountPostBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(post: TakerPost) {
            val ctx = b.root.context
            val deleting = deletingPostIds.contains(post.id)
            val full = post.coverImageUrl
            val thumb = post.coverThumbUrl
            Glide.with(ctx)
                .load(thumb ?: full)
                .placeholder(R.drawable.ic_person_placeholder)
                .error(R.drawable.ic_person_placeholder)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .into(b.ivCover)

            if (deleting) {
                b.tvLikes.text = post.likeCount.toString()
                b.tvViews.text = ctx.getString(R.string.post_deleting)
            } else if (post.isPendingLocalUpload()) {
                b.tvLikes.text = "0"
                b.tvViews.text = if (post.viewCount < 0) {
                    ctx.getString(R.string.upload_failed)
                } else {
                    "Sync ${post.viewCount.coerceIn(1, 100)}%"
                }
            } else {
                b.tvLikes.text = post.likeCount.toString()
                b.tvViews.text = post.viewCount.toString()
            }
            b.tvImageCount.text = post.images.size.toString()
            b.groupMulti.visibility = if (post.images.size > 1) View.VISIBLE else View.GONE
            b.btnPostMenu.visibility = if (!deleting && onMenu != null && canShowMenu(post)) View.VISIBLE else View.GONE
            b.btnPostMenu.setOnClickListener(if (deleting) null else View.OnClickListener { onMenu?.invoke(post, it) })
            b.root.alpha = if (deleting) 0.42f else 1f
            b.root.isEnabled = !deleting
            b.root.setOnClickListener(if (deleting) null else View.OnClickListener { onClick(post) })
        }

        fun clear() {
            Glide.with(b.root.context).clear(b.ivCover)
            b.root.alpha = 1f
            b.root.isEnabled = true
            b.root.setOnClickListener(null)
            b.btnPostMenu.setOnClickListener(null)
        }
    }

    private fun TakerPost.isPendingLocalUpload(): Boolean =
        id < 0 && images.any { it.imageUrl.startsWith("file:", ignoreCase = true) }

    companion object {
        const val VIEW_TYPE = 1001
    }
}
