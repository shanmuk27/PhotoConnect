package com.photoconnect.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.photoconnect.R
import com.photoconnect.databinding.ItemAccountPostBinding
import com.photoconnect.model.TakerPost

class AccountPostAdapter(
    private val onClick: (TakerPost) -> Unit,
) : RecyclerView.Adapter<AccountPostAdapter.VH>() {
    private val posts = mutableListOf<TakerPost>()

    fun submitList(items: List<TakerPost>) {
        posts.clear()
        posts.addAll(items)
        notifyDataSetChanged()
    }

    inner class VH(private val b: ItemAccountPostBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(post: TakerPost) {
            val ctx = b.root.context
            val full = post.coverImageUrl
            val thumb = post.coverThumbUrl
            if (!full.isNullOrBlank() && !thumb.isNullOrBlank() && thumb != full) {
                val thumbReq = Glide.with(ctx)
                    .load(thumb)
                    .centerCrop()
                Glide.with(ctx)
                    .load(full)
                    .placeholder(R.drawable.ic_person_placeholder)
                    .error(R.drawable.ic_person_placeholder)
                    .centerCrop()
                    .thumbnail(thumbReq)
                    .into(b.ivCover)
            } else {
                Glide.with(ctx)
                    .load(full ?: thumb)
                    .placeholder(R.drawable.ic_person_placeholder)
                    .error(R.drawable.ic_person_placeholder)
                    .centerCrop()
                    .into(b.ivCover)
            }

            b.tvLikes.text = post.likeCount.toString()
            b.tvViews.text = post.viewCount.toString()
            b.tvImageCount.text = post.images.size.toString()
            b.groupMulti.visibility = if (post.images.size > 1) View.VISIBLE else View.GONE
            b.root.setOnClickListener { onClick(post) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAccountPostBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(posts[position])

    override fun getItemCount() = posts.size
}
