package com.photoconnect.ui.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.signature.ObjectKey
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.photoconnect.R
import com.photoconnect.databinding.ActivityEditProfileBinding
import com.photoconnect.db.toModel
import com.photoconnect.model.ProfileImageData
import com.photoconnect.model.Taker
import com.photoconnect.model.UpdateTakerRequest
import com.photoconnect.repository.Result
import com.photoconnect.utils.SERVICE_LABELS
import com.photoconnect.utils.SERVICE_TYPES
import com.photoconnect.utils.SessionManager
import com.photoconnect.utils.toServiceSummary
import com.photoconnect.utils.toast
import com.photoconnect.viewmodel.TakerDetailViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class EditProfileActivity : AppCompatActivity() {
    private lateinit var b: ActivityEditProfileBinding
    private val detailVm: TakerDetailViewModel by viewModels()

    @Inject lateinit var session: SessionManager

    private var currentTaker: Taker? = null
    private val selectedServiceTypes = linkedSetOf<String>()
    private var pendingImageUri: Uri? = null
    private var selectedScope: String = "public"
    private var scopeDialog: AlertDialog? = null
    private var hasBoundForm = false
    private var isAvatarUploading = false

    private val pickAvatarImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        cropAvatarImage.launch(
            CropImageContractOptions(
                uri,
                CropImageOptions(
                    guidelines = CropImageView.Guidelines.ON,
                    fixAspectRatio = true,
                    aspectRatioX = 1,
                    aspectRatioY = 1,
                ),
            )
        )
    }

    private val cropAvatarImage = registerForActivityResult(CropImageContract()) { result ->
        val croppedUri = result.uriContent
        if (result.isSuccessful && croppedUri != null) {
            pendingImageUri = croppedUri
            renderAvatar(croppedUri.toString(), null, bypassCache = true)
            showScopeDialog(croppedUri)
        } else {
            cleanupTempUri(croppedUri)
            pendingImageUri = null
            currentTaker?.let(::loadAvatarFromTaker)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        setupUi()
        observeData()
    }

    private fun setupUi() {
        bindThemeSelection()

        b.actvServiceType.setOnClickListener { showServicePicker() }
        b.actvServiceType.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showServicePicker()
        }

        b.cardAvatar.setOnClickListener { pickAvatarImage.launch("image/*") }
        b.btnOpenErrorLogs.setOnClickListener {
            startActivity(Intent(this, ErrorConsoleActivity::class.java))
        }
        b.btnSaveChanges.setOnClickListener {
            val taker = currentTaker ?: return@setOnClickListener
            if (b.etFullName.text.isNullOrBlank()) {
                toast("Name is required")
                return@setOnClickListener
            }
            if (selectedServiceTypes.isEmpty()) {
                toast("Select at least one service")
                return@setOnClickListener
            }

            val req = UpdateTakerRequest(
                takerId = taker.id,
                fullName = b.etFullName.text.toString().trim(),
                email = b.etEmail.text.toString().trim(),
                pincode = b.etPincode.text.toString().trim(),
                area = b.etArea.text.toString().trim(),
                city = taker.city,
                state = taker.state,
                serviceTypes = selectedServiceTypes.toList(),
                yearsExperience = b.etExperience.text.toString().toIntOrNull() ?: 0,
                languages = b.etLanguages.text.toString().trim().ifEmpty { null },
                portfolioUrl = b.etPortfolioUrl.text.toString().trim().ifEmpty { null },
                instagramUrl = b.etInstagramUrl.text.toString().trim().ifEmpty { null },
            )
            detailVm.updateProfile(req)
        }

        b.btnDeleteAccount.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Delete account?")
                .setMessage("This will disable your taker account and sign you out.")
                .setPositiveButton("Delete") { _, _ ->
                    detailVm.deleteTakerAccount(session.getUserId())
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun bindThemeSelection() {
        val checkedId = when (session.getDarkMode()) {
            SessionManager.THEME_LIGHT -> b.btnThemeLight.id
            SessionManager.THEME_DARK -> b.btnThemeDark.id
            else -> b.btnThemeSystem.id
        }
        b.themeToggleGroup.check(checkedId)
        b.themeToggleGroup.addOnButtonCheckedListener { _, checkedButtonId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedButtonId) {
                b.btnThemeLight.id -> SessionManager.THEME_LIGHT
                b.btnThemeDark.id -> SessionManager.THEME_DARK
                else -> SessionManager.THEME_SYSTEM
            }
            if (mode != session.getDarkMode()) {
                session.setDarkMode(mode)
            }
        }
    }

    private fun showScopeDialog(uri: Uri) {
        scopeDialog?.dismiss()

        val scopes = arrayOf(
            "Public - visible to all clients",
            "Profile-only - visible only to you",
        )
        val scopeKeys = arrayOf("public", "profile-only")
        var chosen = if (currentTaker?.profileImageScope == "profile-only") 1 else 0
        var uploadStarted = false

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Who can see this photo?")
            .setSingleChoiceItems(scopes, chosen) { _, which -> chosen = which }
            .setPositiveButton("Upload", null)
            .setNegativeButton("Cancel") { _, _ ->
                cleanupTempUri(pendingImageUri)
                pendingImageUri = null
                currentTaker?.let(::loadAvatarFromTaker)
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                uploadStarted = true
                selectedScope = scopeKeys[chosen]
                dialog.dismiss()
                detailVm.uploadProfileImage(session.getUserId(), selectedScope, uri, applicationContext)
            }
        }
        dialog.setOnDismissListener {
            if (!uploadStarted && pendingImageUri == uri) {
                cleanupTempUri(pendingImageUri)
                pendingImageUri = null
                currentTaker?.let(::loadAvatarFromTaker)
            }
            if (scopeDialog === dialog) {
                scopeDialog = null
            }
        }

        scopeDialog = dialog
        dialog.show()
    }

    private fun observeData() {
        detailVm.getTaker(session.getUserId()).observe(this) { entity ->
            val taker = entity?.toModel() ?: return@observe
            currentTaker = taker

            if (!hasBoundForm) {
                b.etFullName.setText(taker.fullName)
                b.etEmail.setText(taker.email)
                b.etPincode.setText(taker.pincode)
                b.etArea.setText(taker.area)
                b.etExperience.setText(taker.yearsExperience.toString())
                b.etLanguages.setText(taker.languages)
                b.etPortfolioUrl.setText(taker.portfolioUrl)
                b.etInstagramUrl.setText(taker.instagramUrl)
                selectedServiceTypes.clear()
                selectedServiceTypes.addAll(taker.offeredServices)
                renderSelectedServices()
                hasBoundForm = true
            }

            if (!isAvatarUploading && pendingImageUri == null) {
                loadAvatarFromTaker(taker)
            }
        }

        detailVm.uploadImageState.observe(this) { result ->
            when (result) {
                is Result.Loading -> {
                    isAvatarUploading = true
                    b.uploadProgressBar.visibility = View.VISIBLE
                    b.cardAvatar.isEnabled = false
                }
                is Result.Success -> {
                    isAvatarUploading = false
                    b.uploadProgressBar.visibility = View.GONE
                    b.cardAvatar.isEnabled = true
                    scopeDialog?.dismiss()
                    currentTaker = currentTaker?.copy(
                        profileImageUrl = result.data.url,
                        profileThumbUrl = result.data.thumbUrl,
                        profileImageScope = result.data.scope,
                    )
                    cleanupTempUri(pendingImageUri)
                    pendingImageUri = null
                    renderUploadedAvatar(result.data)
                    toast("Profile photo updated!")
                }
                is Result.Error -> {
                    isAvatarUploading = false
                    b.uploadProgressBar.visibility = View.GONE
                    b.cardAvatar.isEnabled = true
                    scopeDialog?.dismiss()
                    cleanupTempUri(pendingImageUri)
                    pendingImageUri = null
                    toast("Upload failed: ${result.message}")
                    currentTaker?.let(::loadAvatarFromTaker)
                }
            }
        }

        detailVm.updateState.observe(this) { result ->
            when (result) {
                is Result.Loading -> {
                    b.progressBar.visibility = View.VISIBLE
                    b.btnSaveChanges.isEnabled = false
                }
                is Result.Success -> {
                    b.progressBar.visibility = View.GONE
                    b.btnSaveChanges.isEnabled = true
                    toast("Settings updated")
                    finish()
                }
                is Result.Error -> {
                    b.progressBar.visibility = View.GONE
                    b.btnSaveChanges.isEnabled = true
                    toast(result.message)
                }
            }
        }

        detailVm.deleteAccountState.observe(this) { result ->
            when (result) {
                is Result.Loading -> {
                    b.progressBar.visibility = View.VISIBLE
                    b.btnDeleteAccount.isEnabled = false
                    b.btnSaveChanges.isEnabled = false
                }
                is Result.Success -> {
                    b.progressBar.visibility = View.GONE
                    session.clearSession()
                    toast("Account deleted")
                    startActivity(android.content.Intent(this, LoginActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }
                is Result.Error -> {
                    b.progressBar.visibility = View.GONE
                    b.btnDeleteAccount.isEnabled = true
                    b.btnSaveChanges.isEnabled = true
                    toast(result.message)
                }
            }
        }
    }

    private fun loadAvatarFromTaker(taker: Taker) {
        renderAvatar(taker.profileImageUrl, taker.profileThumbUrl, bypassCache = false)
    }

    private fun renderAvatar(fullUrl: String?, thumbUrl: String?, bypassCache: Boolean) {
        if (fullUrl.isNullOrBlank()) {
            Glide.with(this)
                .load(R.drawable.ic_person_placeholder)
                .circleCrop()
                .into(b.ivProfile)
            return
        }

        val signature = ObjectKey(listOfNotNull(fullUrl, thumbUrl).joinToString("|"))
        val diskStrategy = if (bypassCache) DiskCacheStrategy.NONE else DiskCacheStrategy.ALL

        val thumbnailRequest = thumbUrl?.takeIf { it.isNotBlank() }?.let {
            Glide.with(this)
                .load(it)
                .signature(signature)
                .diskCacheStrategy(diskStrategy)
                .skipMemoryCache(bypassCache)
                .circleCrop()
        }

        val mainRequest = Glide.with(this)
            .load(fullUrl)
            .signature(signature)
            .diskCacheStrategy(diskStrategy)
            .skipMemoryCache(bypassCache)
            .circleCrop()
            .transition(DrawableTransitionOptions.withCrossFade(300))

        if (thumbnailRequest != null) {
            mainRequest.thumbnail(thumbnailRequest).into(b.ivProfile)
        } else {
            mainRequest.into(b.ivProfile)
        }
    }

    private fun renderUploadedAvatar(data: ProfileImageData) {
        renderAvatar(data.url, data.thumbUrl, bypassCache = true)
    }

    private fun showServicePicker() {
        val checked = SERVICE_TYPES.map { it in selectedServiceTypes }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Select services")
            .setMultiChoiceItems(SERVICE_LABELS.toTypedArray(), checked) { _, which, isChecked ->
                val service = SERVICE_TYPES[which]
                if (isChecked) selectedServiceTypes.add(service) else selectedServiceTypes.remove(service)
            }
            .setPositiveButton("Done") { _, _ -> renderSelectedServices() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderSelectedServices() {
        b.actvServiceType.setText(selectedServiceTypes.toServiceSummary(), false)
    }

    private fun cleanupTempUri(uri: Uri?) {
        if (uri == null) return
        try {
            if (uri.scheme == "file") {
                uri.path?.let { File(it).delete() }
            } else {
                contentResolver.delete(uri, null, null)
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        scopeDialog?.dismiss()
        cleanupTempUri(pendingImageUri)
        pendingImageUri = null
        super.onDestroy()
    }
}
