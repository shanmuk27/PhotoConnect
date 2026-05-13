package com.photoconnect.ui.fragments

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.photoconnect.BuildConfig
import com.photoconnect.R
import com.photoconnect.databinding.FragmentProfileBinding
import com.photoconnect.db.toModel
import com.photoconnect.model.TakerPost
import com.photoconnect.repository.Result
import com.photoconnect.ui.activities.CreatePostActivity
import com.photoconnect.ui.activities.EditProfileActivity
import com.photoconnect.ui.activities.LoginActivity
import com.photoconnect.ui.activities.TakerPostViewerActivity
import com.photoconnect.ui.adapters.AccountPostAdapter
import com.photoconnect.utils.SessionManager
import com.photoconnect.utils.show
import com.photoconnect.utils.toServiceSummary
import com.photoconnect.viewmodel.TakerDetailViewModel
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ProfileFragment : Fragment() {
    private var _b: FragmentProfileBinding? = null
    private val b get() = _b!!
    private val vm: TakerDetailViewModel by viewModels()
    private val postJsonAdapter by lazy {
        Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build().adapter(TakerPost::class.java)
    }
    private val postAdapter by lazy {
        AccountPostAdapter { openPost(it) }
    }

    @Inject lateinit var session: SessionManager

    private val pickPostImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val intent = Intent(requireContext(), CreatePostActivity::class.java).apply {
            putStringArrayListExtra(
                CreatePostActivity.EXTRA_URIS,
                ArrayList(uris.take(8).map { it.toString() })
            )
        }
        createPostLauncher.launch(intent)
    }

    private val createPostLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            refreshAccount()
        }
    }

    private val viewPostLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            refreshAccount()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        FragmentProfileBinding.inflate(inflater, container, false).also { _b = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.rvPosts.layoutManager = GridLayoutManager(requireContext(), 3)
        b.rvPosts.adapter = postAdapter
        b.tvVersion.text = getString(R.string.version, BuildConfig.VERSION_NAME)

        if (session.isLoggedIn() && !session.isGuest()) {
            b.layoutLoggedIn.show()
            b.layoutGuest.isVisible = false
            b.tvName.text = session.getUserName()
            b.tvHandle.text = session.getUserPhone()
                .takeIf { it.isNotBlank() }
                ?.let { "@$it" }
                ?: session.getUserEmail()
            b.tvRole.text = session.getRoleLabel()
            b.btnSettings.isVisible = session.isTaker()
            b.layoutCreatorContent.isVisible = session.isTaker()

            b.btnSettings.setOnClickListener {
                startActivity(Intent(requireContext(), EditProfileActivity::class.java))
            }
            b.btnNewPost.setOnClickListener {
                pickPostImages.launch("image/*")
            }
            b.btnLogout.setOnClickListener {
                session.clearSession()
                startActivity(Intent(requireContext(), LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            }

            if (session.isTaker()) {
                vm.getTaker(session.getUserId()).observe(viewLifecycleOwner) { entity ->
                    val taker = entity?.toModel() ?: return@observe
                    b.tvName.text = taker.fullName
                    b.tvRole.text = taker.offeredServices.toServiceSummary()
                    val mainRequest = Glide.with(this)
                        .load(taker.profileImageUrl ?: taker.profileThumbUrl)
                        .placeholder(R.drawable.ic_person_placeholder)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .circleCrop()
                    val thumbRequest = taker.profileThumbUrl?.takeIf { it.isNotBlank() && it != taker.profileImageUrl }?.let {
                        Glide.with(this)
                            .load(it)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .circleCrop()
                    }
                    if (thumbRequest != null) {
                        mainRequest.thumbnail(thumbRequest).into(b.ivProfile)
                    } else {
                        mainRequest.into(b.ivProfile)
                    }
                }

                vm.takerPostsState.observe(viewLifecycleOwner) { result ->
                    when (result) {
                        is Result.Loading -> b.progressPosts.isVisible = true
                        is Result.Success -> {
                            b.progressPosts.isVisible = false
                            b.tvPostCount.text = result.data.summary.postCount.toString()
                            b.tvLikeCount.text = result.data.summary.totalLikes.toString()
                            b.tvViewCount.text = result.data.summary.totalViews.toString()
                            b.tvFavoriteCount.text = result.data.summary.favoriteCount.toString()
                            b.tvAvgRating.text = if (result.data.summary.avgRating > 0f) {
                                "%.1f".format(result.data.summary.avgRating)
                            } else {
                                getString(R.string.account_new_badge)
                            }
                            b.tvReviewCount.text = result.data.summary.reviewCount.toString()
                            postAdapter.submitList(result.data.posts)
                            b.rvPosts.isVisible = result.data.posts.isNotEmpty()
                            b.tvEmptyPosts.isVisible = result.data.posts.isEmpty()
                            if (result.data.posts.isEmpty()) {
                                b.tvEmptyPosts.text = getString(R.string.account_empty_posts)
                            }
                        }
                        is Result.Error -> {
                            b.progressPosts.isVisible = false
                            b.rvPosts.isVisible = false
                            b.tvEmptyPosts.isVisible = true
                            b.tvEmptyPosts.text = result.message
                        }
                    }
                }
            }

            refreshAccount()
        } else {
            b.layoutGuest.show()
            b.layoutLoggedIn.isVisible = false
            b.btnSettings.isVisible = false
            b.btnSignInProfile.setOnClickListener {
                startActivity(Intent(requireContext(), LoginActivity::class.java))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (session.isLoggedIn() && !session.isGuest()) {
            refreshAccount()
        }
    }

    private fun refreshAccount() {
        if (session.isTaker()) {
            vm.fetchTakerPosts(session.getUserId(), viewerRole = session.getRole(), viewerId = session.getUserId())
        }
    }

    private fun openPost(post: TakerPost) {
        val json = postJsonAdapter.toJson(post)
        viewPostLauncher.launch(
            Intent(requireContext(), TakerPostViewerActivity::class.java).apply {
                putExtra(TakerPostViewerActivity.EXTRA_POST_JSON, json)
                putExtra(TakerPostViewerActivity.EXTRA_IS_OWNER, true)
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
