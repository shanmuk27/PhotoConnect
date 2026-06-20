package com.photoconnect.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.photoconnect.databinding.ItemReviewBinding
import com.photoconnect.db.ReviewEntity
import com.photoconnect.utils.toDisplayDate

class ReviewAdapter : ListAdapter<ReviewEntity, ReviewAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(p: ViewGroup, v: Int) =
        VH(ItemReviewBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun onBindViewHolder(h: VH, pos: Int) = h.bind(getItem(pos))

    inner class VH(private val b: ItemReviewBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(r: ReviewEntity) {
            b.tvClientName.text = r.clientName
            b.tvComment.text    = r.comment ?: ""
            b.tvDate.text       = r.createdAt.take(10).toDisplayDate()
            b.ratingBar.rating  = r.rating.toFloat()
        }
    }
    companion object { private val DIFF = object : DiffUtil.ItemCallback<ReviewEntity>() {
        override fun areItemsTheSame(o: ReviewEntity, n: ReviewEntity) = o.id == n.id
        override fun areContentsTheSame(o: ReviewEntity, n: ReviewEntity) = o == n
    }}
}
