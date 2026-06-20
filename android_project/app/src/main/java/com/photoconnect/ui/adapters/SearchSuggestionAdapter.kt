package com.photoconnect.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.photoconnect.databinding.ItemSearchSuggestionBinding
import com.photoconnect.utils.forceLeftToRightTree

data class SearchSuggestion(
    val title: String,
    val subtitle: String,
    val typeLabel: String,
    val query: String,
    val action: String = ACTION_SEARCH,
    val displayQuery: String = query,
)

const val ACTION_SEARCH = "search"
const val ACTION_CURRENT_LOCATION = "current_location"

class SearchSuggestionAdapter(
    private val onClick: (SearchSuggestion) -> Unit,
) : RecyclerView.Adapter<SearchSuggestionAdapter.VH>() {
    private val items = mutableListOf<SearchSuggestion>()

    fun submitList(suggestions: List<SearchSuggestion>) {
        val oldItems = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = suggestions.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = oldItems[oldItemPosition]
                val new = suggestions[newItemPosition]
                return old.typeLabel == new.typeLabel && old.title == new.title && old.query == new.query
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition] == suggestions[newItemPosition]
        })
        items.clear()
        items.addAll(suggestions)
        diff.dispatchUpdatesTo(this)
    }

    inner class VH(private val b: ItemSearchSuggestionBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.root.forceLeftToRightTree()
        }

        fun bind(item: SearchSuggestion) {
            b.tvSuggestionType.text = item.typeLabel
            b.tvSuggestionTitle.text = item.title
            b.tvSuggestionSubtitle.text = item.subtitle
            b.root.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSearchSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size
}
