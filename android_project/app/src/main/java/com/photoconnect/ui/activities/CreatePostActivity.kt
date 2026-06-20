package com.photoconnect.ui.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.photoconnect.R
import com.photoconnect.databinding.ActivityCreatePostBinding
import com.photoconnect.model.TakerPost
import com.photoconnect.repository.Result
import com.photoconnect.ui.adapters.EditablePostImage
import com.photoconnect.ui.adapters.SelectedPostImageAdapter
import com.photoconnect.utils.SessionManager
import com.photoconnect.utils.toast
import com.photoconnect.viewmodel.TakerDetailViewModel
import com.photoconnect.workers.PostUploadWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CreatePostActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_EDIT_POST_ID = "edit_post_id"
        const val EXTRA_EDIT_CAPTION = "edit_caption"
        const val EXTRA_EXISTING_IMAGE_IDS = "existing_image_ids"
        const val EXTRA_EXISTING_IMAGE_URLS = "existing_image_urls"
        const val EXTRA_SKIPPED_IMAGE_COUNT = "skipped_image_count"

        fun editIntent(context: Context, post: TakerPost): Intent =
            Intent(context, CreatePostActivity::class.java).apply {
                putExtra(EXTRA_EDIT_POST_ID, post.id)
                putExtra(EXTRA_EDIT_CAPTION, post.caption.orEmpty())
                putIntegerArrayListExtra(
                    EXTRA_EXISTING_IMAGE_IDS,
                    ArrayList(post.images.map { it.id }),
                )
                putStringArrayListExtra(
                    EXTRA_EXISTING_IMAGE_URLS,
                    ArrayList(post.images.map { image ->
                        image.imageUrl.takeIf { it.isNotBlank() } ?: image.thumbUrl.orEmpty()
                    }),
                )
            }
    }

    private lateinit var b: ActivityCreatePostBinding
    private val vm: TakerDetailViewModel by viewModels()
    private val selectedImages = mutableListOf<EditablePostImage>()
    private val imageAdapter = SelectedPostImageAdapter { index -> removeImage(index) }
    private var editPostId = 0
    private var isSubmitting = false

    @Inject lateinit var session: SessionManager

    private val pickMoreImages = registerForActivityResult(PickMultipleVisualMedia(PostUploadWorker.MAX_IMAGES)) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        if (selectedImages.size >= PostUploadWorker.MAX_IMAGES) {
            toast(getString(R.string.only_8_images_allowed))
            return@registerForActivityResult
        }
        uris.forEach(::persistReadPermission)
        var skippedCount = 0
        val validUris = uris.filter { uri ->
            if (com.photoconnect.utils.ExifUtils.isOriginalCameraPhoto(this, uri)) {
                true
            } else {
                skippedCount++
                false
            }
        }

        if (skippedCount > 0) showPhotoSourceError(skippedCount) else hidePhotoSourceError()

        if (validUris.isEmpty()) return@registerForActivityResult

        val currentKeys = selectedImages.map { it.uri.toString() }.toMutableSet()
        val newImages = validUris
            .filter { currentKeys.add(it.toString()) }
            .map { EditablePostImage(it) }
        val combined = selectedImages + newImages
        selectedImages.clear()
        selectedImages.addAll(combined.take(PostUploadWorker.MAX_IMAGES))
        if (combined.size > PostUploadWorker.MAX_IMAGES) {
            toast(getString(R.string.only_8_images_allowed))
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

        editPostId = intent.getIntExtra(EXTRA_EDIT_POST_ID, 0)
        val isEditMode = editPostId > 0
        if (!isEditMode && isShareIntent(intent)) {
            if (!session.isLoggedIn() || session.isGuest()) {
                toast(getString(R.string.share_post_sign_in_required))
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                return
            }
            if (!session.isTaker()) {
                toast(getString(R.string.share_post_taker_only))
                finish()
                return
            }
            val shared = consumeShareIntentUris(intent)
            if (shared.validUris.isEmpty()) {
                toast(getString(R.string.select_images_to_create_post))
                finish()
                return
            }
            if (shared.skippedCount > 0) showPhotoSourceError(shared.skippedCount)
            intent.putStringArrayListExtra(EXTRA_URIS, ArrayList(shared.validUris.map { it.toString() }))
            intent.putExtra(EXTRA_SKIPPED_IMAGE_COUNT, shared.skippedCount)
            intent.action = null
        }

        if (isEditMode) {
            supportActionBar?.title = getString(R.string.edit_post)
            b.btnSharePost.text = getString(R.string.save_changes)
            b.etCaption.setText(intent.getStringExtra(EXTRA_EDIT_CAPTION).orEmpty())
            val ids = intent.getIntegerArrayListExtra(EXTRA_EXISTING_IMAGE_IDS).orEmpty()
            val urls = intent.getStringArrayListExtra(EXTRA_EXISTING_IMAGE_URLS).orEmpty()
            selectedImages.addAll(
                urls.mapIndexedNotNull { index, url ->
                    url.takeIf { it.isNotBlank() }?.let {
                        EditablePostImage(Uri.parse(it), ids.getOrNull(index))
                    }
                }.take(PostUploadWorker.MAX_IMAGES)
            )
        } else {
            val skippedCount = intent.getIntExtra(EXTRA_SKIPPED_IMAGE_COUNT, 0)
            val initialUris = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty().map(Uri::parse)
            if (skippedCount > 0) showPhotoSourceError(skippedCount)
            if (initialUris.size > PostUploadWorker.MAX_IMAGES) {
                toast(getString(R.string.only_8_images_allowed))
            }
            selectedImages.addAll(initialUris.take(PostUploadWorker.MAX_IMAGES).map { EditablePostImage(it) })
        }
        if (selectedImages.isEmpty()) {
            toast(getString(R.string.select_images_to_create_post))
            finish()
            return
        }

        b.rvSelectedImages.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.rvSelectedImages.adapter = imageAdapter

        b.btnAddMoreImages.setOnClickListener {
            pickMoreImages.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        }
        b.btnSharePost.setOnClickListener {
            if (isSubmitting) return@setOnClickListener
            if (selectedImages.isEmpty()) {
                toast(getString(R.string.select_at_least_one_image))
                return@setOnClickListener
            }
            if (selectedImages.size > PostUploadWorker.MAX_IMAGES) {
                toast(getString(R.string.only_8_images_allowed))
                return@setOnClickListener
            }
            setSubmitting(true)
            val caption = b.etCaption.text?.toString()?.trim()
            if (isEditMode) {
                vm.updateTakerPost(
                    takerId = session.getTakerActorId(),
                    postId = editPostId,
                    caption = caption,
                    keepImageIds = selectedImages.mapNotNull { it.existingImageId },
                    newImageUris = selectedImages.filter { it.existingImageId == null }.map { it.uri },
                    context = applicationContext,
                )
                return@setOnClickListener
            }
            lifecycleScope.launch {
                runCatching {
                    PostUploadWorker.enqueue(
                        context = applicationContext,
                        takerId = session.getTakerActorId(),
                        caption = caption,
                        imageUris = selectedImages.map { it.uri },
                    )
                }.onSuccess {
                    toast(getString(R.string.post_upload_queued))
                    setResult(Activity.RESULT_OK)
                    finish()
                }.onFailure {
                    setSubmitting(false)
                    toast(it.message ?: getString(R.string.post_upload_queue_failed))
                }
            }
        }

        vm.createPostState.observe(this) { result ->
            when (result) {
                is Result.Loading -> {
                    setSubmitting(true)
                }
                is Result.Success -> {
                    setResult(Activity.RESULT_OK)
                    finish()
                }
                is Result.Error -> {
                    setSubmitting(false)
                    toast(result.message)
                }
            }
        }

        vm.updatePostState.observe(this) { result ->
            when (result) {
                is Result.Loading -> setSubmitting(true)
                is Result.Success -> {
                    toast(getString(R.string.post_updated))
                    setResult(Activity.RESULT_OK)
                    finish()
                }
                is Result.Error -> {
                    setSubmitting(false)
                    toast(result.message)
                }
            }
        }

        renderSelectedImages()
    }

    private fun removeImage(index: Int) {
        if (index !in selectedImages.indices) return
        if (selectedImages.size == 1) {
            toast(getString(R.string.select_at_least_one_image))
            return
        }
        selectedImages.removeAt(index)
        renderSelectedImages()
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun renderSelectedImages() {
        imageAdapter.submitList(selectedImages)
        b.tvSelectionCount.text = getString(R.string.selected_count, selectedImages.size)
        b.btnSharePost.isEnabled = !isSubmitting && selectedImages.isNotEmpty()
        b.btnAddMoreImages.isEnabled = !isSubmitting && selectedImages.size < PostUploadWorker.MAX_IMAGES
    }

    private fun showPhotoSourceError(skippedCount: Int) {
        b.tvPhotoSourceError.text = getString(R.string.photo_source_error_message, skippedCount)
        b.cardPhotoSourceError.visibility = View.VISIBLE
    }

    private fun hidePhotoSourceError() {
        b.cardPhotoSourceError.visibility = View.GONE
    }

    private fun setSubmitting(submitting: Boolean) {
        isSubmitting = submitting
        b.progressBar.visibility = if (submitting) View.VISIBLE else View.GONE
        b.btnSharePost.isClickable = !submitting
        b.btnSharePost.isEnabled = !submitting && selectedImages.isNotEmpty()
        renderSelectedImages()
    }

    private fun isShareIntent(intent: Intent): Boolean =
        intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE

    private data class SharedImageSelection(
        val validUris: List<Uri>,
        val skippedCount: Int,
    )

    private fun consumeShareIntentUris(intent: Intent): SharedImageSelection {
        val rawUris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                listOfNotNull(stream)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                }
            }
            else -> emptyList()
        }
        var skippedCount = 0
        val verified = rawUris
            .distinctBy { it.toString() }
            .mapNotNull { uri ->
                persistReadPermission(uri)
                if (com.photoconnect.utils.ExifUtils.isOriginalCameraPhoto(this, uri)) {
                    uri
                } else {
                    skippedCount++
                    null
                }
            }
        if (verified.size > PostUploadWorker.MAX_IMAGES) {
            skippedCount += verified.size - PostUploadWorker.MAX_IMAGES
        }
        return SharedImageSelection(
            validUris = verified.take(PostUploadWorker.MAX_IMAGES),
            skippedCount = skippedCount,
        )
    }
}
