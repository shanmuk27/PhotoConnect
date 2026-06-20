package com.photoconnect.ui.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import com.photoconnect.R
import com.photoconnect.model.PostOffice

class LocationSuggestionAdapter(
    private val context: Context,
    private val items: List<PostOffice>,
) : BaseAdapter(), Filterable {

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): PostOffice = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        bind(position, convertView, parent)

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        bind(position, convertView, parent)

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults =
            FilterResults().apply {
                values = items
                count = items.size
            }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            notifyDataSetChanged()
        }

        override fun convertResultToString(resultValue: Any?): CharSequence =
            (resultValue as? PostOffice)?.displayTitle().orEmpty()
    }

    private fun bind(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_location_suggestion, parent, false)
        val item = getItem(position)
        view.findViewById<TextView>(R.id.tvLocationTitle).text = item.displayTitle()
        view.findViewById<TextView>(R.id.tvLocationSubtitle).text = item.displaySubtitle()
        return view
    }

    private fun PostOffice.displayTitle(): String = bestCityName()

    private fun PostOffice.displaySubtitle(): String =
        listOf(
            state,
            pincode.takeIf { it.isNotBlank() }?.let { "PIN $it" }.orEmpty(),
        ).filter { it.isNotBlank() }.joinToString(" - ")
}
