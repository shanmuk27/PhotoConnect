package com.photoconnect.ui.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.photoconnect.databinding.ActivityCreatePostBinding
import com.photoconnect.repository.Result
import com.photoconnect.ui.adapters.SelectedPostImageAdapter
import com.photoconnect.utils.SessionManager
import com.photoconnect.utils.toast
import com.photoconnect.viewmodel.TakerDetailViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CreatePostActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_URIS = "uris"
    }

    private lateinit var b: ActivityCreatePostBinding
    private val vm: TakerDetailViewModel by viewModels()
    private val selectedUris = mutableListOf<Uri>()
    private val imageAdapter = SelectedPostImageAdapter { index -> removeImage(index) }

    @Inject lateinit var session: SessionManager

    private val pickMoreImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val unique = (selectedUris + uris).distinctBy { it.toString() }
        selectedUris.clear()
        selectedUris.addAll(unique.take(8))
        if (unique.size > 8) {
            toast("Only 8 images are allowed in one post")
        }
        renderSelectedImages()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCreatePostBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        selectedUris.addAll(
            intent.getStringArrayListExtra(EXTRA_URIS).orEmpty().map(Uri::parse).take(8)
        )
        if (selectedUris.isEmpty()) {
            toast("Select images to create a post")
            finish()
            return
        }

        b.rvSelectedImages.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.rvSelectedImages.adapter = imageAdapter

        b.btnAddMoreImages.setOnClickListener { pickMoreImages.launch("image/*") }
        b.btnSharePost.setOnClickListener {
            if (selectedUris.isEmpty()) {
                toast("Select at least one image")
                return@setOnClickListener
            }
            vm.uploadTakerPost(
                takerId = session.getUserId(),
                caption = b.etCaption.text?.toString()?.trim(),
                imageUris = selectedUris.toList(),
                context = applicationContext,
            )
        }

        vm.createPostState.observe(this) { result ->
            when (result) {
                is Result.Loading -> {
                    b.progressBar.visibility = View.VISIBLE
                    b.btnSharePost.isEnabled = false
                    b.btnAddMoreImages.isEnabled = false
                }
                is Result.Success -> {
                    b.progressBar.visibility = View.GONE
                    setResult(Activity.RESULT_OK)
                    finish()
                }
                is Result.Error -> {
                    b.progressBar.visibility = View.GONE
                    b.btnSharePost.isEnabled = true
                    b.btnAddMoreImages.isEnabled = true
                    toast(result.message)
                }
            }
        }

        renderSelectedImages()
    }

    private fun removeImage(index: Int) {
        if (index !in selectedUris.indices) return
        selectedUris.removeAt(index)
        if (selectedUris.isEmpty()) {
            finish()
            return
        }
        renderSelectedImages()
    }

    private fun renderSelectedImages() {
        imageAdapter.submitList(selectedUris)
        b.tvSelectionCount.text = "${selectedUris.size} selected"
        b.btnSharePost.isEnabled = selectedUris.isNotEmpty()
    }
}
