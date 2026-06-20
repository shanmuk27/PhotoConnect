package com.photoconnect.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.photoconnect.databinding.ItemCategoryChipBinding

class CategoryAdapter(
    private val serviceTypes: List<String>,
    private val labels: List<String>,
    private val icons: List<String>,
    private val onSelect: (String?) -> Unit,
) : RecyclerView.Adapter<CategoryAdapter.VH>() {
    private var selectedService: String? = null

    override fun getItemCount() = labels.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemCategoryChipBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (position == 0) {
            holder.bind("All", selectedService == null) { onSelect(null) }
        } else {
            val index = position - 1
            val serviceType = serviceTypes[index]
            holder.bind("${icons[index]} ${labels[index]}", selectedService == serviceType) { onSelect(serviceType) }
        }
    }

    fun setSelected(serviceType: String?) {
        selectedService = serviceType
        notifyDataSetChanged()
    }

    inner class VH(private val b: ItemCategoryChipBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(label: String, selected: Boolean, onClick: () -> Unit) {
            b.chip.text = label
            b.chip.isChecked = selected
            b.chip.setOnClickListener { onClick() }
        }
    }
}
