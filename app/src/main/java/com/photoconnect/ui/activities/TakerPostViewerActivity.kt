package com.photoconnect.ui.activities

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.photoconnect.databinding.ActivityTakerPostViewerBinding
import com.photoconnect.model.TakerPost
import com.photoconnect.repository.Result
import com.photoconnect.ui.adapters.TakerPostViewerFeedAdapter
import com.photoconnect.utils.SessionManager
import com.photoconnect.utils.toast
import com.photoconnect.viewmodel.TakerDetailViewModel
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

    private val viewerAdapter = TakerPostViewerFeedAdapter(
        onImageLikeToggle = { post, image ->
            if (!session.isLoggedIn() || session.isGuest()) {
                toast("Sign in to like images")
            } else if (isOwner) {
                toast("You cannot like your own image")
            } else {
                vm.toggleTakerPostImageLike(image.id, session.getRole(), session.getUserId(), !image.viewerHasLiked)
            }
        },
        onSaveToggle = { post ->
            if (!session.isLoggedIn() || session.isGuest()) {
                toast("Sign in to save posts")
            } else {
                vm.toggleTakerPostSave(post.id, session.getRole(), session.getUserId(), !post.viewerHasSaved)
            }
        }
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
        takerId = intent.getIntExtra(EXTRA_TAKER_ID, 0)
        focusedPostId = intent.getIntExtra(EXTRA_POST_ID, 0)
        focusedPostIndex = intent.getIntExtra(EXTRA_POST_INDEX, 0).coerceAtLeast(0)
        currentPosts = parsePostsFromIntent()
        if (currentPosts.isEmpty() && takerId <= 0) {
            toast("Could not open posts")
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
            }
        })
    }

    private fun parsePostsFromIntent(): List<TakerPost> {
        val postsJson = intent.getStringExtra(EXTRA_POSTS_JSON).orEmpty()
        val list = postListAdapterJson.fromJson(postsJson).orEmpty()
        if (list.isNotEmpty()) return list

        val single = postAdapterJson.fromJson(intent.getStringExtra(EXTRA_POST_JSON).orEmpty())
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
                    currentPosts = result.data.posts
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
                    toast(if (result.data.viewerHasSaved) "Post saved" else "Post removed from saved")
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
    }

    private fun recordVisiblePostView() {
        if (!session.isLoggedIn() || session.isGuest() || isOwner) return
        val layoutManager = b.rvPosts.layoutManager as? LinearLayoutManager ?: return
        val pos = layoutManager.findFirstCompletelyVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION }
            ?: layoutManager.findFirstVisibleItemPosition()
        val post = currentPosts.getOrNull(pos) ?: return
        if (!viewedPostIds.add(post.id)) return
        vm.recordTakerPostView(post.id, session.getRole(), session.getUserId())
    }

    private fun fetchPosts() {
        if (takerId <= 0) return
        val viewerRole = if (session.isLoggedIn() && !session.isGuest()) session.getRole() else null
        val viewerId = if (session.isLoggedIn() && !session.isGuest()) session.getUserId() else null
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
            setResult(Activity.RESULT_OK)
        }
        super.finish()
    }
}
