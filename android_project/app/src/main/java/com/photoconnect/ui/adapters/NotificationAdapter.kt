package com.photoconnect.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.photoconnect.R
import com.photoconnect.databinding.ItemNotificationBinding
import com.photoconnect.model.NotificationItem

class NotificationAdapter(
    private val onClick: (NotificationItem) -> Unit,
) : RecyclerView.Adapter<NotificationAdapter.VH>() {
    private val items = mutableListOf<NotificationItem>()

    fun submitList(notifications: List<NotificationItem>) {
        val oldItems = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = notifications.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition].id == notifications[newItemPosition].id

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition] == notifications[newItemPosition]
        })
        items.clear()
        items.addAll(notifications)
        diff.dispatchUpdatesTo(this)
    }

    inner class VH(
        private val binding: ItemNotificationBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: NotificationItem) {
            binding.tvNotificationTitle.text = item.title
            binding.tvNotificationMessage.text = item.message
            binding.tvNotificationMeta.text = item.createdAt.replace('T', ' ')
            binding.viewUnreadDot.isVisible = !item.isRead
            binding.root.alpha = if (item.isRead) 0.78f else 1f
            binding.root.setOnClickListener { onClick(item) }
            binding.root.contentDescription = binding.root.context.getString(
                if (item.isRead) R.string.notification_read else R.string.notification_unread,
                item.title,
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size
}
