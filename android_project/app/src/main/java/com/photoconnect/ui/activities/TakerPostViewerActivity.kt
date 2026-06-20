package com.photoconnect.ui.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.photoconnect.R
import com.photoconnect.databinding.ActivityTakerPostViewerBinding
import com.photoconnect.model.TakerPost
import com.photoconnect.repository.Result
import com.photoconnect.utils.PostDownloadWatermarker
import com.photoconnect.ui.adapters.TakerPostViewerFeedAdapter
import com.photoconnect.utils.SessionManager
import com.photoconnect.utils.toast
import com.photoconnect.viewmodel.TakerDetailViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TakerPostViewerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_POST_JSON = "post_json"
        const val EXTRA_POSTS_JSON = "posts_json"
        const val EXTRA_POST_INDEX = "post_index"
        const val EXTRA_TAKER_ID = "taker_id"
        const val EXTRA_POST_ID = "post_id"
        const val EXTRA_IS_OWNER = "is_owner"
        const val EXTRA_DELETED_POST_ID = "deleted_post_id"
        const val EXTRA_ONLY_LIKED_IMAGES = "only_liked_images"
        private const val MENU_EDIT_POST = 1
        private const val MENU_DOWNLOAD_POST = 2
        private const val MENU_DELETE_POST = 3
    }

    private lateinit var b: ActivityTakerPostViewerBinding
    private val vm: TakerDetailViewModel by viewModels()

    @Inject lateinit var session: SessionManager

    private val moshi by lazy { Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build() }
    private val postAdapterJson by lazy { moshi.adapter(TakerPost::class.java) }
    private val postListAdapterJson by lazy {
        val type = Types.newParameterizedType(List::class.java, TakerPost::class.java)
        moshi.adapter<List<TakerPost>>(type)
    }

    private var currentPosts: List<TakerPost> = emptyList()
    private var isOwner = false
    private var shouldRefreshParent = false
    private val viewedPostIds = mutableSetOf<Int>()
    private var takerId = 0
    private var focusedPostId = 0
    private var focusedPostIndex = 0
    private var editingPostId = 0
    private var deletedPostId = 0
    private var pendingDeletedPostId = 0

    private val editPostLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            shouldRefreshParent = true
            fetchPosts()
        }
    }

    private val viewerAdapter = TakerPostViewerFeedAdapter(
        onImageLikeToggle = { post, image ->
            if (!session.isLoggedIn() || session.isGuest()) {
                toast(getString(R.string.sign_in_like_images))
            } else if (isOwner) {
                toast(getString(R.string.cannot_like_own_image))
            } else {
                vm.toggleTakerPostImageLike(image.id, session.getRole(), session.getActiveActorId(), !image.viewerHasLiked)
            }
        },
        onSaveToggle = { post ->
            if (!session.isLoggedIn() || session.isGuest()) {
                toast(getString(R.string.sign_in_save_posts))
            } else if (isOwner) {
                toast(getString(R.string.cannot_save_own_post))
            } else {
                vm.toggleTakerPostSave(post.id, session.getRole(), session.getActiveActorId(), !post.viewerHasSaved)
            }
        },
        onPostMenu = { post, anchor ->
            if (isOwner) showPostMenu(post, anchor)
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        b = ActivityTakerPostViewerBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        isOwner = intent.getBooleanExtra(EXTRA_IS_OWNER, false)
        viewerAdapter.showPostMenuActions = isOwner
        viewerAdapter.onlyShowLikedImages = intent.getBooleanExtra(EXTRA_ONLY_LIKED_IMAGES, false)
        takerId = intent.getIntExtra(EXTRA_TAKER_ID, 0)
        focusedPostId = intent.getIntExtra(EXTRA_POST_ID, 0)
        focusedPostIndex = intent.getIntExtra(EXTRA_POST_INDEX, 0).coerceAtLeast(0)
        currentPosts = parsePostsFromIntent()
        if (currentPosts.isEmpty() && takerId <= 0) {
            toast(getString(R.string.could_not_open_posts))
            finish()
            return
        }

        b.rvPosts.layoutManager = LinearLayoutManager(this)
        b.rvPosts.adapter = viewerAdapter
        if (currentPosts.isNotEmpty()) {
            viewerAdapter.submitList(currentPosts)
        }

        observeStates()
        fetchPosts()

        if (currentPosts.isNotEmpty()) {
            b.rvPosts.post {
                scrollToFocusedPost()
                recordVisiblePostView()
            }
        }
        b.rvPosts.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                recordVisiblePostView()
                preloadNextVisiblePost()
            }
        })
    }

    private fun parsePostsFromIntent(): List<TakerPost> {
        val postsJson = intent.getStringExtra(EXTRA_POSTS_JSON).orEmpty()
        val list = if (postsJson.isNotBlank()) {
            runCatching { postListAdapterJson.fromJson(postsJson).orEmpty() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        if (list.isNotEmpty()) return list

        val postJson = intent.getStringExtra(EXTRA_POST_JSON).orEmpty()
        val single = if (postJson.isNotBlank()) {
            runCatching { postAdapterJson.fromJson(postJson) }.getOrNull()
        } else {
            null
        }
        return listOfNotNull(single)
    }

    private fun observeStates() {
        vm.takerPostsState.observe(this) { result ->
            when (result) {
                is Result.Loading -> b.progressBar.visibility = android.view.View.VISIBLE
                is Result.Error -> {
                    b.progressBar.visibility = android.view.View.GONE
                    if (currentPosts.isEmpty()) {
                        toast(result.message)
                    }
                }
                is Result.Success -> {
                    b.progressBar.visibility = android.view.View.GONE
                    val hiddenDeletedId = deletedPostId
                    currentPosts = if (hiddenDeletedId > 0) {
                        result.data.posts.filterNot { it.id == hiddenDeletedId }
                    } else {
                        result.data.posts
                    }
                    viewerAdapter.submitList(currentPosts)
                    b.rvPosts.post {
                        scrollToFocusedPost()
                        recordVisiblePostView()
                    }
                }
            }
        }

        vm.togglePostImageLikeState.observe(this) { result ->
            when (result) {
                is Result.Loading -> Unit
                is Result.Error -> toast(result.message)
                is Result.Success -> {
                    currentPosts = currentPosts.map { post ->
                        if (post.id != result.data.postId) return@map post
                        post.copy(
                            likeCount = result.data.postLikeCount,
                            viewerHasLiked = post.images.any {
                                if (it.id == result.data.imageId) result.data.viewerHasLiked else it.viewerHasLiked
                            },
                            images = post.images.map { image ->
                                if (image.id == result.data.imageId) {
                                    image.copy(
                                        likeCount = result.data.imageLikeCount,
                                        viewerHasLiked = result.data.viewerHasLiked,
                                    )
                                } else image
                            }
                        )
                    }
                    shouldRefreshParent = true
                    viewerAdapter.submitList(currentPosts)
                }
            }
        }

        vm.togglePostSaveState.observe(this) { result ->
            when (result) {
                is Result.Loading -> Unit
                is Result.Error -> toast(result.message)
                is Result.Success -> {
                    currentPosts = currentPosts.map { post ->
                        if (post.id == result.data.postId) {
                            post.copy(viewerHasSaved = result.data.viewerHasSaved)
                        } else post
                    }
                    shouldRefreshParent = true
                    viewerAdapter.submitList(currentPosts)
                    toast(
                        if (result.data.viewerHasSaved) {
                            getString(R.string.post_saved_toast)
                        } else {
                            getString(R.string.post_removed_saved_toast)
                        }
                    )
                }
            }
        }

        vm.recordPostViewState.observe(this) { result ->
            when (result) {
                is Result.Loading -> Unit
                is Result.Error -> Unit
                is Result.Success -> {
                    currentPosts = currentPosts.map { post ->
                        if (post.id == result.data.postId) post.copy(viewCount = result.data.viewCount) else post
                    }
                    viewerAdapter.submitList(currentPosts)
                }
            }
        }

        vm.updatePostState.observe(this) { result ->
            when (result) {
                is Result.Loading -> Unit
                is Result.Error -> {
                    editingPostId = 0
                    toast(result.message)
                }
                is Result.Success -> {
                    toast(getString(R.string.post_updated))
                    editingPostId = 0
                    fetchPosts()
                    shouldRefreshParent = true
                }
            }
        }

        vm.deletePostState.observe(this) { result ->
            when (result) {
                is Result.Loading -> Unit
                is Result.Error -> {
                    clearPendingDeletedPost()
                    toast(result.message)
                }
                is Result.Success -> {
                    toast(getString(R.string.post_deleted))
                    val id = pendingDeletedPostId.takeIf { it > 0 } ?: focusedPostId
                    deletedPostId = id
                    currentPosts = currentPosts.filterNot { it.id == id }
                    viewerAdapter.submitList(currentPosts)
                    viewerAdapter.setDeletingPostIds(emptySet())
                    shouldRefreshParent = true
                    pendingDeletedPostId = 0
                    if (currentPosts.isEmpty()) finish()
                }
            }
        }
    }

    private fun showPostMenu(post: TakerPost, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_EDIT_POST, 0, R.string.edit)
            menu.add(0, MENU_DOWNLOAD_POST, 1, R.string.download)
            menu.add(0, MENU_DELETE_POST, 2, R.string.delete_post)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_EDIT_POST -> {
                        openEditPost(post)
                        true
                    }
                    MENU_DOWNLOAD_POST -> {
                        downloadPostImages(post)
                        true
                    }
                    MENU_DELETE_POST -> {
                        confirmDeletePost(post)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun openEditPost(post: TakerPost) {
        editPostLauncher.launch(CreatePostActivity.editIntent(this, post))
    }

    private fun downloadPostImages(post: TakerPost) {
        PostDownloadWatermarker.savePostImages(this, post)
    }

    private fun confirmDeletePost(post: TakerPost) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_post)
            .setMessage(R.string.delete_post_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                pendingDeletedPostId = post.id
                viewerAdapter.setDeletingPostIds(setOf(post.id))
                vm.deleteTakerPost(session.getTakerActorId(), post.id)
            }
            .show()
    }

    private fun clearPendingDeletedPost() {
        viewerAdapter.setDeletingPostIds(emptySet())
        pendingDeletedPostId = 0
    }

    private fun recordVisiblePostView() {
        if (!session.isLoggedIn() || session.isGuest() || isOwner) return
        val layoutManager = b.rvPosts.layoutManager as? LinearLayoutManager ?: return
        val pos = layoutManager.findFirstCompletelyVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION }
            ?: layoutManager.findFirstVisibleItemPosition()
        val post = currentPosts.getOrNull(pos) ?: return
        if (!viewedPostIds.add(post.id)) return
        vm.recordTakerPostView(post.id, session.getRole(), session.getActiveActorId())
    }

    private fun preloadNextVisiblePost() {
        val layoutManager = b.rvPosts.layoutManager as? LinearLayoutManager ?: return
        val nextIndex = (layoutManager.findFirstVisibleItemPosition() + 1)
            .takeIf { it >= 0 }
            ?: return
        currentPosts.getOrNull(nextIndex)
            ?.images
            ?.take(2)
            ?.mapNotNull { it.thumbUrl?.takeIf(String::isNotBlank) ?: it.imageUrl.takeIf(String::isNotBlank) }
            ?.distinct()
            ?.forEach { url ->
                Glide.with(this)
                    .load(url)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .preload()
            }
    }

    private fun fetchPosts() {
        if (takerId <= 0) return
        val viewerRole = if (session.isLoggedIn() && !session.isGuest()) session.getRole() else null
        val viewerId = if (session.isLoggedIn() && !session.isGuest()) session.getActiveActorId() else null
        vm.fetchTakerPosts(takerId, viewerRole, viewerId)
    }

    private fun scrollToFocusedPost() {
        if (currentPosts.isEmpty()) return
        val targetIndex = currentPosts.indexOfFirst { it.id == focusedPostId }
            .takeIf { it >= 0 }
            ?: focusedPostIndex.coerceIn(0, currentPosts.lastIndex)
        (b.rvPosts.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(targetIndex, 0)
    }

    override fun finish() {
        if (shouldRefreshParent) {
            val intent = Intent().apply {
                val json = postListAdapterJson.toJson(currentPosts)
                putExtra(EXTRA_POSTS_JSON, json)
                if (deletedPostId > 0) {
                    putExtra(EXTRA_DELETED_POST_ID, deletedPostId)
                }
            }
            setResult(Activity.RESULT_OK, intent)
        }
        super.finish()
    }
}
