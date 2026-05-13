package com.photoconnect.ui.adapters

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.photoconnect.databinding.ItemSelectedPostImageBinding

class SelectedPostImageAdapter(
    private val onRemove: (Int) -> Unit,
) : RecyclerView.Adapter<SelectedPostImageAdapter.VH>() {
    private val items = mutableListOf<Uri>()

    fun submitList(uris: List<Uri>) {
        items.clear()
        items.addAll(uris)
        notifyDataSetChanged()
    }

    inner class VH(private val b: ItemSelectedPostImageBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(uri: Uri) {
            Glide.with(b.root.context)
                .load(uri)
                .centerCrop()
                .into(b.ivPreview)
            b.btnRemove.setOnClickListener { onRemove(bindingAdapterPosition) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSelectedPostImageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size
}
