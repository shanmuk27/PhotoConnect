package com.photoconnect.ui.activities

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.photoconnect.R
import com.photoconnect.repository.Result
import com.photoconnect.repository.TrustRepository
import com.photoconnect.utils.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class VerificationActivity : AppCompatActivity() {

    companion object {
        private const val DRAFT_PREFS = "verification_drafts"
        private const val KEY_PORTFOLIO = "portfolio"
        private const val KEY_MAPS = "maps"
        private const val KEY_GST = "gst"
        private const val KEY_AADHAAR_URI = "aadhaar_uri"
        private const val KEY_BUSINESS_URI = "business_uri"
    }

    @Inject lateinit var session: SessionManager
    @Inject lateinit var trustRepo: TrustRepository
    private val draftPrefs: SharedPreferences by lazy {
        getSharedPreferences(DRAFT_PREFS, MODE_PRIVATE)
    }

    private lateinit var contentContainer: LinearLayout
    private lateinit var btnSubmit: MaterialButton
    private lateinit var errorCard: View
    private lateinit var errorText: TextView

    private var role: String = "taker" // "taker" or "studio"
        private var needsPortfolio = false
    private var needsAadhaar = false
    private var needsBusiness = false
    private var needsOwnerAadhaar = false
    private var uploadedPostCount = -1

    // State Variables
    private var pendingAadhaarUri: Uri? = null
    private var pendingBusinessUri: Uri? = null
        private var portfolioLink: String = ""
    private var googleMapsLink: String = ""
    private var gstNumber: String = ""

    // UI References
    private var aadhaarLabel: TextView? = null
    private var aadhaarPreview: android.widget.ImageView? = null
    private var aadhaarUploadBtn: MaterialButton? = null
    private var aadhaarRemoveBtn: MaterialButton? = null

    private var businessLabel: TextView? = null
    private var businessPreview: android.widget.ImageView? = null
    private var businessUploadBtn: MaterialButton? = null
    private var businessRemoveBtn: MaterialButton? = null

    // Capture variables
    private var pendingCameraUri: Uri? = null
    private var isCapturingAadhaarBack = false
    private var pendingAadhaarFrontUri: Uri? = null
    private var isStudioAadhaar = false
    private var pendingShopLicenseUri: Uri? = null
    private var isCapturingSignatureBoard = false

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setContentView(R.layout.activity_verification)

        role = intent.getStringExtra("ROLE") ?: "taker"
        needsPortfolio = intent.getBooleanExtra("NEEDS_PORTFOLIO", false)
        needsAadhaar = intent.getBooleanExtra("NEEDS_AADHAAR", false)
        needsBusiness = intent.getBooleanExtra("NEEDS_BUSINESS", false)
        needsOwnerAadhaar = intent.getBooleanExtra("NEEDS_OWNER_AADHAAR", false)
        uploadedPostCount = intent.getIntExtra("UPLOADED_POST_COUNT", -1)
        val existingPortfolio = intent.getStringExtra("EXISTING_PORTFOLIO")
        restoreDraft()
        if (!existingPortfolio.isNullOrBlank()) portfolioLink = existingPortfolio

        contentContainer = findViewById(R.id.verificationContentContainer)
        btnSubmit = findViewById(R.id.btnSubmit)
        errorCard = findViewById(R.id.cardVerificationError)
        errorText = findViewById(R.id.tvVerificationError)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)

        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        btnSubmit.setOnClickListener {
            submitVerification()
        }

        buildUI()
        updateDocumentUI(true)
        updateDocumentUI(false)
        if (hasNoUploadedPostsForPortfolio()) {
            showVerificationError(getString(R.string.verification_no_posts_error))
        }
        updateSubmitState()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val isFullyFilled = isFormValid()
                val isPartiallyFilled = isFormPartiallyFilled()

                if (isFullyFilled) {
                    MaterialAlertDialogBuilder(this@VerificationActivity)
                        .setTitle(R.string.verification_submit_leave_title)
                        .setMessage(R.string.verification_submit_leave_body)
                        .setPositiveButton(R.string.verification_submit_button) { _, _ -> submitVerification() }
                        .setNegativeButton(R.string.leave) { _, _ -> finish() }
                        .show()
                } else if (isPartiallyFilled) {
                    MaterialAlertDialogBuilder(this@VerificationActivity)
                        .setTitle(R.string.verification_unsaved_changes_title)
                        .setMessage(R.string.verification_unsaved_changes_body)
                        .setPositiveButton(R.string.save_draft) { _, _ ->
                            persistDraft()
                            Toast.makeText(this@VerificationActivity, getString(R.string.draft_saved), Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        .setNegativeButton(R.string.discard) { _, _ ->
                            clearDrafts()
                            finish()
                        }
                        .setNeutralButton("Cancel", null)
                        .show()
                } else {
                    finish()
                }
            }
        })
    }

    private fun isFormValid(): Boolean {
        if (role == "taker") {
            if (hasNoUploadedPostsForPortfolio()) return false
            val linkFilled = portfolioLink.isNotBlank()
            val linkValid = if (needsPortfolio) linkFilled else true
            val aadhaarValid = if (needsAadhaar) pendingAadhaarUri != null else true
            return linkValid && aadhaarValid
        } else {
            val bizValid = if (needsBusiness) pendingBusinessUri != null else true
            val ownerAadhaarValid = if (needsOwnerAadhaar) pendingAadhaarUri != null else true
            return bizValid && ownerAadhaarValid
        }
    }

    private fun isFormPartiallyFilled(): Boolean {
        return portfolioLink.isNotBlank() || pendingAadhaarUri != null || pendingBusinessUri != null
    }

    private fun clearDrafts() {
        pendingAadhaarUri = null
        pendingBusinessUri = null
        portfolioLink = ""
        googleMapsLink = ""
        gstNumber = ""
        pendingAadhaarFrontUri = null
        pendingShopLicenseUri = null
        pendingCameraUri = null
        isCapturingAadhaarBack = false
        isCapturingSignatureBoard = false
        draftPrefs.edit().apply {
            remove(draftKey(KEY_PORTFOLIO))
            remove(draftKey(KEY_MAPS))
            remove(draftKey(KEY_GST))
            remove(draftKey(KEY_AADHAAR_URI))
            remove(draftKey(KEY_BUSINESS_URI))
            apply()
        }
    }

    private fun updateSubmitState() {
        btnSubmit.isEnabled = isFormValid()
    }

    private fun hasNoUploadedPostsForPortfolio(): Boolean =
        role == "taker" && needsPortfolio && uploadedPostCount == 0

    private fun showVerificationError(message: String) {
        errorText.text = message
        errorCard.visibility = View.VISIBLE
    }

    private fun buildUI() {
        if (role == "taker") {
            if (needsAadhaar) {
                addDocumentSection(
                    title = getString(R.string.verification_aadhaar_card_title),
                    isAadhaar = true
                )
            }

            if (needsPortfolio) {
                addLinkInputs()
            }
        } else {
            addMapsInput() // Maps link is always shown for Studio
            addGstInput()
            if (needsBusiness) {
                addDocumentSection(
                    title = getString(R.string.verification_business_document_title),
                    isAadhaar = false
                )
            }
            if (needsOwnerAadhaar) {
                addDocumentSection(
                    title = getString(R.string.verification_owner_aadhaar_title),
                    isAadhaar = true
                )
            }
        }
    }

    private fun addGstInput() {
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                gstNumber = s.toString().trim()
                persistDraft()
            }
        }

        val gstInput = TextInputEditText(this).apply {
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            addTextChangedListener(watcher)
            if (gstNumber.isNotBlank()) setText(gstNumber)
        }
        val gstLayout = TextInputLayout(this).apply {
            hint = getString(R.string.verification_gst_hint_optional)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(16f, 16f, 16f, 16f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(16) }
            addView(gstInput)
        }

        contentContainer.addView(gstLayout)
    }

    private fun addMapsInput() {
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                googleMapsLink = s.toString().trim()
                persistDraft()
            }
        }

        val mapsInput = TextInputEditText(this).apply {
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            addTextChangedListener(watcher)
            if (googleMapsLink.isNotBlank()) setText(googleMapsLink)
        }
        val mapsLayout = TextInputLayout(this).apply {
            hint = getString(R.string.verification_maps_link_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(16f, 16f, 16f, 16f)
            startIconDrawable = androidx.core.content.ContextCompat.getDrawable(this@VerificationActivity, R.drawable.ic_location)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) } // Reduced gap
            addView(mapsInput)
        }

        contentContainer.addView(mapsLayout)
    }

    private fun addLinkInputs() {
        val watcherPort = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                portfolioLink = s.toString().trim()
                updateSubmitState()
                persistDraft()
            }
        }

        val portfolioInput = TextInputEditText(this).apply {
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            addTextChangedListener(watcherPort)
            val existing = intent.getStringExtra("EXISTING_PORTFOLIO")
            if (!existing.isNullOrBlank()) {
                setText(existing)
                isEnabled = false
            } else if (portfolioLink.isNotBlank()) {
                setText(portfolioLink)
            }
        }
        val portfolioLayout = TextInputLayout(this).apply {
            hint = getString(R.string.verification_portfolio_hint)
            helperText = getString(R.string.verification_portfolio_helper_ui)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(16f, 16f, 16f, 16f)
            startIconDrawable = androidx.core.content.ContextCompat.getDrawable(this@VerificationActivity, R.drawable.ic_link)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
            addView(portfolioInput)
        }

        contentContainer.addView(portfolioLayout)
    }

    private fun addDocumentSection(title: String, isAadhaar: Boolean) {
        val uploadBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.action_upload)
            setOnClickListener {
                if (isAadhaar) {
                    isStudioAadhaar = role == "studio"
                    launchAadhaarCamera()
                } else {
                    launchBusinessCamera()
                }
            }
        }
        val removeBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.action_remove)
            isVisible = false
            setOnClickListener {
                if (isAadhaar) {
                    pendingAadhaarUri = null
                    updateDocumentUI(true)
                } else {
                    pendingBusinessUri = null
                    updateDocumentUI(false)
                }
                updateSubmitState()
                persistDraft()
            }
        }

        val label = TextView(this).apply {
            text = title
            setTextColor(MaterialColors.getColor(this@VerificationActivity, com.google.android.material.R.attr.colorOnSurfaceVariant, "Verify"))
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(4) }
            addView(uploadBtn)
            addView(removeBtn)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(16) }
            
            label.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            
            addView(label)
            addView(buttonContainer)
        }

        val preview = android.widget.ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(200)
            ).apply {
                topMargin = dpToPx(8)
                bottomMargin = dpToPx(16)
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            isVisible = false
        }

        contentContainer.addView(container)
        contentContainer.addView(preview)

        if (isAadhaar) {
            aadhaarLabel = label
            aadhaarUploadBtn = uploadBtn
            aadhaarRemoveBtn = removeBtn
            aadhaarPreview = preview
        } else {
            businessLabel = label
            businessUploadBtn = uploadBtn
            businessRemoveBtn = removeBtn
            businessPreview = preview
        }
    }

    private fun updateDocumentUI(isAadhaar: Boolean) {
        if (isAadhaar) {
            val uri = pendingAadhaarUri
            if (uri != null) {
                aadhaarLabel?.text = getString(R.string.verification_aadhaar_ready)
                aadhaarLabel?.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, "Verify"))
                aadhaarUploadBtn?.isVisible = false
                aadhaarRemoveBtn?.isVisible = true
                aadhaarPreview?.setImageURI(uri)
                aadhaarPreview?.isVisible = true
            } else {
                aadhaarLabel?.text = if (role == "taker") {
                    getString(R.string.verification_aadhaar_card_title)
                } else {
                    getString(R.string.verification_owner_aadhaar_title)
                }
                aadhaarLabel?.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, "Verify"))
                aadhaarUploadBtn?.isVisible = true
                aadhaarRemoveBtn?.isVisible = false
                aadhaarPreview?.isVisible = false
            }
        } else {
            val uri = pendingBusinessUri
            if (uri != null) {
                businessLabel?.text = getString(R.string.verification_business_document_ready)
                businessLabel?.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, "Verify"))
                businessUploadBtn?.isVisible = false
                businessRemoveBtn?.isVisible = true
                businessPreview?.setImageURI(uri)
                businessPreview?.isVisible = true
            } else {
                businessLabel?.text = getString(R.string.verification_business_document_title)
                businessLabel?.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, "Verify"))
                businessUploadBtn?.isVisible = true
                businessRemoveBtn?.isVisible = false
                businessPreview?.isVisible = false
            }
        }
    }

    private fun addAndDivider() {
        contentContainer.addView(TextView(this).apply {
            text = getString(R.string.and_word)
            gravity = android.view.Gravity.CENTER
            setTextColor(MaterialColors.getColor(this@VerificationActivity, com.google.android.material.R.attr.colorOnSurfaceVariant, "Verify"))
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dpToPx(8), 0, dpToPx(8))
        })
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun submitVerification() {
        val finalLink = portfolioLink
        if (hasNoUploadedPostsForPortfolio()) {
            showVerificationError(getString(R.string.verification_no_posts_error))
            updateSubmitState()
            return
        }

        btnSubmit.isEnabled = false
        errorCard.visibility = View.GONE

        lifecycleScope.launch {
            if (role == "taker") {
                val takerId = session.getTakerActorId()
                pendingAadhaarUri?.let { uri ->
                    val upload = trustRepo.uploadVerificationDocument(
                        targetRole = "taker",
                        targetId = takerId,
                        documentType = "aadhaar_front",
                        documentUri = uri,
                        context = this@VerificationActivity,
                    )
                    if (upload is Result.Error) {
                        showVerificationError(upload.message)
                        updateSubmitState()
                        return@launch
                    }
                }

                val submit = trustRepo.submitTakerVerification(
                    takerId = takerId,
                    socialUrl = finalLink,
                    portfolioUrl = finalLink,
                    aadhaarSubmitted = pendingAadhaarUri != null,
                )
                if (submit is Result.Error) {
                    showVerificationError(submit.message)
                    updateSubmitState()
                    return@launch
                }
            } else {
                if (gstNumber.isNotBlank() && !isValidGstin(gstNumber)) {
                    showVerificationError(getString(R.string.invalid_gst_number))
                    updateSubmitState()
                    return@launch
                }
                val clientId = session.getClientActorId()
                if (needsBusiness) pendingBusinessUri?.let { uri ->
                    val upload = trustRepo.uploadVerificationDocument(
                        targetRole = "studio",
                        targetId = clientId,
                        documentType = "shop_license",
                        documentUri = uri,
                        context = this@VerificationActivity,
                    )
                    if (upload is Result.Error) {
                        showVerificationError(upload.message)
                        updateSubmitState()
                        return@launch
                    }
                }
                if (needsOwnerAadhaar) pendingAadhaarUri?.let { uri ->
                    val upload = trustRepo.uploadVerificationDocument(
                        targetRole = "studio",
                        targetId = clientId,
                        documentType = "owner_aadhaar",
                        documentUri = uri,
                        context = this@VerificationActivity,
                    )
                    if (upload is Result.Error) {
                        showVerificationError(upload.message)
                        updateSubmitState()
                        return@launch
                    }
                }

                val submit = trustRepo.submitStudioVerification(
                    clientId = clientId,
                    verificationPath = "shop_license",
                    gstin = gstNumber,
                    googleMapsUrl = googleMapsLink,
                    ownerAadhaarSubmitted = needsOwnerAadhaar && pendingAadhaarUri != null,
                )
                if (submit is Result.Error) {
                    showVerificationError(submit.message)
                    updateSubmitState()
                    return@launch
                }
            }

            Toast.makeText(this@VerificationActivity, getString(R.string.verification_submitted_success), Toast.LENGTH_SHORT).show()
            clearDrafts()
            finish()
        }
    }

    private fun launchAadhaarCamera() {
        try {
            val dir = verificationMediaDirectory("verification_camera")
            val file = File.createTempFile("aadhaar_", ".jpg", dir)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            pendingCameraUri = uri
            captureAadhaar.launch(uri)
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(
                this,
                getString(R.string.verification_camera_launch_failed, e.message ?: ""),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    
    private fun launchBusinessCamera() {
        try {
            val dir = verificationMediaDirectory("verification_camera")
            val file = File.createTempFile("business_", ".jpg", dir)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            pendingCameraUri = uri
            captureBusiness.launch(uri)
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(
                this,
                getString(R.string.verification_camera_launch_failed, e.message ?: ""),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val pickBusinessDocument = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pendingBusinessUri = uri
            updateDocumentUI(false)
            updateSubmitState()
            persistDraft()
        }
    }

    
    private val captureBusiness = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val capturedUri = pendingCameraUri
        if (success && capturedUri != null) {
            cropBusiness.launch(
                CropImageContractOptions(
                    capturedUri,
                    CropImageOptions(
                        guidelines = CropImageView.Guidelines.ON,
                        fixAspectRatio = false,
                    ),
                )
            )
        } else {
            if (!isCapturingSignatureBoard) {
                pendingBusinessUri = null
                updateDocumentUI(false)
                updateSubmitState()
            }
        }
    }

    private val cropBusiness = registerForActivityResult(CropImageContract()) { result ->
        val croppedUri = result.uriContent
        if (result.isSuccessful && croppedUri != null) {
            if (!isCapturingSignatureBoard) {
                pendingShopLicenseUri = croppedUri
                isCapturingSignatureBoard = true
                Toast.makeText(this, getString(R.string.verification_shop_license_next), Toast.LENGTH_LONG).show()
                launchBusinessCamera()
            } else {
                isCapturingSignatureBoard = false
                val signatureUri = croppedUri
                val licenseUri = pendingShopLicenseUri
                if (licenseUri != null) {
                    lifecycleScope.launch {
                        businessLabel?.text = getString(R.string.verification_collaging)
                        val collagedUri = com.photoconnect.utils.AadhaarCollageUtils.createVerticalCollage(this@VerificationActivity, licenseUri, signatureUri)
                        if (collagedUri != null) {
                            pendingBusinessUri = collagedUri
                        } else {
                            Toast.makeText(this@VerificationActivity, getString(R.string.verification_collage_failed), Toast.LENGTH_SHORT).show()
                            pendingBusinessUri = null
                        }
                        updateDocumentUI(false)
                        updateSubmitState()
                        persistDraft()
                    }
                }
            }
        } else {
            isCapturingSignatureBoard = false
            if (!isCapturingSignatureBoard) {
                pendingBusinessUri = null
                updateDocumentUI(false)
                updateSubmitState()
                persistDraft()
            }
        }
    }

    private val captureAadhaar = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val capturedUri = pendingCameraUri
        if (success && capturedUri != null) {
            cropAadhaar.launch(
                CropImageContractOptions(
                    capturedUri,
                    CropImageOptions(
                        guidelines = CropImageView.Guidelines.ON,
                        fixAspectRatio = false,
                    ),
                )
            )
        } else {
            if (!isCapturingAadhaarBack) {
                pendingAadhaarUri = null
                updateDocumentUI(true)
                updateSubmitState()
            }
        }
    }

    private val cropAadhaar = registerForActivityResult(CropImageContract()) { result ->
        val croppedUri = result.uriContent
        if (result.isSuccessful && croppedUri != null) {
            if (!isCapturingAadhaarBack) {
                pendingAadhaarFrontUri = croppedUri
                isCapturingAadhaarBack = true
                Toast.makeText(this, getString(R.string.verification_aadhaar_next_back), Toast.LENGTH_LONG).show()
                launchAadhaarCamera()
            } else {
                isCapturingAadhaarBack = false
                val backUri = croppedUri
                val frontUri = pendingAadhaarFrontUri
                if (frontUri != null) {
                    lifecycleScope.launch {
                        aadhaarLabel?.text = getString(R.string.verification_collaging)
                        val collagedUri = com.photoconnect.utils.AadhaarCollageUtils.createCollage(this@VerificationActivity, frontUri, backUri)
                        if (collagedUri != null) {
                            pendingAadhaarUri = collagedUri
                        } else {
                            Toast.makeText(this@VerificationActivity, getString(R.string.verification_collage_failed), Toast.LENGTH_SHORT).show()
                            pendingAadhaarUri = null
                        }
                        updateDocumentUI(true)
                        updateSubmitState()
                        persistDraft()
                    }
                }
            }
        } else {
            isCapturingAadhaarBack = false
            if (!isCapturingAadhaarBack) {
                pendingAadhaarUri = null
                updateDocumentUI(true)
                updateSubmitState()
                persistDraft()
            }
        }
    }

    private fun restoreDraft() {
        portfolioLink = draftPrefs.getString(draftKey(KEY_PORTFOLIO), portfolioLink).orEmpty()
        googleMapsLink = draftPrefs.getString(draftKey(KEY_MAPS), googleMapsLink).orEmpty()
        gstNumber = draftPrefs.getString(draftKey(KEY_GST), gstNumber).orEmpty()
        pendingAadhaarUri = readDraftUri(KEY_AADHAAR_URI)
        pendingBusinessUri = readDraftUri(KEY_BUSINESS_URI)
    }

    private fun persistDraft() {
        draftPrefs.edit().apply {
            putString(draftKey(KEY_PORTFOLIO), portfolioLink)
            putString(draftKey(KEY_MAPS), googleMapsLink)
            putString(draftKey(KEY_GST), gstNumber)
            putString(draftKey(KEY_AADHAAR_URI), pendingAadhaarUri?.toString().orEmpty())
            putString(draftKey(KEY_BUSINESS_URI), pendingBusinessUri?.toString().orEmpty())
            apply()
        }
    }

    private fun readDraftUri(key: String): Uri? {
        val raw = draftPrefs.getString(draftKey(key), null)?.trim().orEmpty()
        if (raw.isBlank()) return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        return uri.takeIf(::isReadableUri)
    }

    private fun isReadableUri(uri: Uri): Boolean =
        runCatching {
            contentResolver.openInputStream(uri)?.close()
            true
        }.getOrDefault(false)

    private fun draftKey(name: String): String = "${role}_${draftActorId()}_$name"

    private fun draftActorId(): Int =
        if (role == "studio") session.getClientActorId() else session.getTakerActorId()

    private fun isValidGstin(value: String): Boolean =
        Regex("^[0-3][0-9][A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$", RegexOption.IGNORE_CASE)
            .matches(value.trim())

    private fun verificationMediaDirectory(name: String): File {
        val externalRoot = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val base = externalRoot ?: File(filesDir, "Pictures")
        return File(base, name).apply { mkdirs() }
    }
}
