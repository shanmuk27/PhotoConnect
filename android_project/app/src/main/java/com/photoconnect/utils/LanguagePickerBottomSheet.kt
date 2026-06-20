package com.photoconnect.utils

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.photoconnect.R

object LanguagePickerBottomSheet {
    fun show(activity: Activity, onLanguageChanged: (() -> Unit)? = null) {
        val languages = AppLocaleManager.supportedLanguages
        val labels = languages.map { AppLocaleManager.getPickerLabel(activity, it) }
        val currentTag = AppLocaleManager.getSavedLanguageTag(activity)
        val bottomSheet = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_language_picker, null)
        bottomSheet.setContentView(view)

        val rv = view.findViewById<RecyclerView>(R.id.rvLanguages)
        val searchView = view.findViewById<SearchView>(R.id.searchLanguage)

        view.forceLeftToRightTree()
        searchView.queryHint = activity.getString(R.string.language_search_hint)

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            var filteredLanguages = languages
            var filteredLabels = labels

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = LayoutInflater.from(parent.context).inflate(R.layout.item_language_picker, parent, false)
                row.forceLeftToRightTree()
                return object : RecyclerView.ViewHolder(row) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val lang = filteredLanguages[position]
                val tv = holder.itemView.findViewById<TextView>(R.id.tvLanguageName)
                val ivCheck = holder.itemView.findViewById<ImageView>(R.id.ivCheck)
                holder.itemView.forceLeftToRightTree()
                tv.text = filteredLabels[position]
                ivCheck.visibility = if (lang.tag == currentTag) View.VISIBLE else View.INVISIBLE
                holder.itemView.setOnClickListener {
                    bottomSheet.dismiss()
                    if (lang.tag != currentTag) {
                        AppLocaleManager.setLanguageTag(activity, lang.tag)
                        Toast.makeText(activity, activity.getString(R.string.settings_language_updated), Toast.LENGTH_SHORT).show()
                        onLanguageChanged?.invoke()
                    }
                }
            }

            override fun getItemCount() = filteredLanguages.size

            fun filter(query: String) {
                val normalized = query.trim()
                val filteredPairs = if (normalized.isBlank()) {
                    languages.zip(labels)
                } else {
                    languages.zip(labels).filter { (language, label) ->
                        label.contains(normalized, ignoreCase = true) ||
                            language.englishName.contains(normalized, ignoreCase = true) ||
                            language.nativeName.contains(normalized, ignoreCase = true)
                    }
                }
                filteredLanguages = filteredPairs.map { it.first }
                filteredLabels = filteredPairs.map { it.second }
                notifyDataSetChanged()
            }
        }

        rv.layoutManager = LinearLayoutManager(activity)
        rv.adapter = adapter
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filter(newText.orEmpty())
                return true
            }
        })

        bottomSheet.show()
    }
}
