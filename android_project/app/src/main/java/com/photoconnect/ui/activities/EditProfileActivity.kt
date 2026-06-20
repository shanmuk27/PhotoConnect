package com.photoconnect.ui.activities

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.signature.ObjectKey
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.color.MaterialColors
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.photoconnect.R
import com.photoconnect.databinding.ActivityEditProfileBinding
import com.photoconnect.model.PostOffice
import com.photoconnect.model.ProfileImageData
import com.photoconnect.model.Taker
import com.photoconnect.model.UpdateClientProfileRequest
import com.photoconnect.model.UpdateTakerRequest
import com.photoconnect.model.OtpVerificationData
import com.photoconnect.repository.Result
import com.photoconnect.ui.adapters.LocationSuggestionAdapter
import com.photoconnect.utils.SERVICE_TYPES
import com.photoconnect.utils.SessionManager
import com.photoconnect.utils.serviceLabels
import com.photoconnect.utils.toCustomServiceSlug
import com.photoconnect.utils.toServiceSummary
import com.photoconnect.utils.toast
import com.photoconnect.viewmodel.TakerDetailViewModel
import com.photoconnect.viewmodel.PincodeViewModel
import com.photoconnect.viewmodel.AuthViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class EditProfileActivity : AppCompatActivity() {
    private lateinit var b: ActivityEditProfileBinding
    private val detailVm: TakerDetailViewModel by viewModels()
    private val pincodeVm: PincodeViewModel by viewModels()
    private val authVm: AuthViewModel by viewModels()

    @Inject lateinit var session: SessionManager

    private var currentTaker: Taker? = null
    private val selectedServiceTypes = linkedSetOf<String>()
    private var pendingImageUri: Uri? = null
    private var selectedScope: String = "public"
    private var scopeDialog: AlertDialog? = null
    private var isAvatarUploading = false
    private var deleteInFlight = false
    private var applyingAddressLocation = false
    private var addressAutoApplyLocation = false
    private var activeAddressPincode: EditText? = null
    private var activeAddressArea: MaterialAutoCompleteTextView? = null
    private var activeAddressCity: MaterialAutoCompleteTextView? = null
    private var activeAddressState: EditText? = null
    private var pendingClientProfileUpdate: UpdateClientProfileRequest? = null
    private var pendingTakerProfileUpdate: UpdateTakerRequest? = null
    private val addressSearchHandler = Handler(Looper.getMainLooper())
    private var pendingAddressSearch: Runnable? = null

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
            if (session.isClient()) {
                detailVm.uploadClientProfileImage(session.getClientActorId(), croppedUri, applicationContext)
            } else {
                showScopeDialog(croppedUri)
            }
        } else {
            cleanupTempUri(croppedUri)
            pendingImageUri = null
            if (session.isClient()) {
                renderAvatar(session.getProfileImageUrl(), session.getProfileThumbUrl(), bypassCache = false)
            } else {
                currentTaker?.let(::loadAvatarFromTaker)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val onSurface = MaterialColors.getColor(b.toolbar, com.google.android.material.R.attr.colorOnSurface)
        b.toolbar.navigationIcon?.setTint(onSurface)
        b.toolbar.setTitleTextColor(onSurface)
        b.toolbar.setNavigationOnClickListener { finish() }

        setupUi()
        observeData()
    }

    private fun setupUi() {
        b.cardAvatar.setOnClickListener {
            if (!isAvatarUploading) pickAvatarImage.launch("image/*")
        }

        if (session.isClient()) {
            b.rowNameEmail.setOnClickListener { showClientNameEmailDialog() }
            b.rowPhone.setOnClickListener { showClientPhoneDialog() }
            b.rowServices.visibility = View.GONE
            b.rowLanguages.visibility = View.GONE
            b.rowAddress.visibility = View.GONE
            b.rowSocial.visibility = View.GONE
            b.dividerServicesLanguages.visibility = View.GONE
            b.dividerLanguagesAddress.visibility = View.GONE
            b.dividerAddressSocial.visibility = View.GONE
            b.dividerAccountBottom.visibility = View.GONE
            b.btnDeleteAccount.visibility = View.GONE
        } else {
            b.rowNameEmail.setOnClickListener { showEditNameEmailDialog() }
            b.rowPhone.setOnClickListener { showEditPhoneDialog() }
            b.rowServices.setOnClickListener { showEditServicesDialog() }
            b.rowLanguages.setOnClickListener { showEditLanguagesDialog() }
            b.rowAddress.setOnClickListener { showEditAddressDialog() }
            b.rowSocial.setOnClickListener { showEditSocialDialog() }
        }

        b.btnDeleteAccount.setOnClickListener {
            if (deleteInFlight) return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_account_title)
                .setMessage(R.string.delete_account_message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    detailVm.deleteTakerAccount(session.getTakerActorId())
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun observeData() {
        if (session.isClient()) {
            renderClientSettings()
        } else {
            detailVm.takerState.observe(this) { result ->
            if (result !is Result.Success) return@observe
            val taker = result.data
            currentTaker = taker

            selectedServiceTypes.clear()
            selectedServiceTypes.addAll(taker.offeredServices)

            b.tvNameEmailValue.text = "${taker.fullName} / ${taker.email}"
            b.tvPhoneValue.text = taker.phone?.ifBlank { null } ?: session.getUserPhone().ifBlank { getString(R.string.not_found) }
            b.tvServicesValue.text = getString(
                R.string.edit_profile_services_years,
                selectedServiceTypes.toServiceSummary(this),
                taker.yearsExperience,
            )
            b.tvLanguagesValue.text = taker.languages.takeIf { !it.isNullOrBlank() } ?: getString(R.string.edit_profile_none)
            b.tvAddressValue.text = listOf(taker.city, taker.state, taker.pincode)
                .filter { it.isNotBlank() }
                .joinToString(", ")
            
            b.tvSocialValue.text = socialSummaryLabels(
                taker.portfolioUrl,
                taker.socialLinkAdditional1,
                taker.socialLinkAdditional2,
                taker.instagramUrl,
                taker.youtubeUrl,
            )

            if (!isAvatarUploading && pendingImageUri == null) {
                loadAvatarFromTaker(taker)
            }
        }
            detailVm.fetchTakerProfile(session.getTakerActorId(), force = true)
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
                    cleanupTempUri(pendingImageUri)
                    pendingImageUri = null
                    if (session.isClient()) {
                        session.setSessionProfileImage(result.data.url, result.data.thumbUrl)
                    }
                    renderUploadedAvatar(result.data)
                    toast(getString(R.string.profile_photo_updated))
                }
                is Result.Error -> {
                    isAvatarUploading = false
                    b.uploadProgressBar.visibility = View.GONE
                    b.cardAvatar.isEnabled = true
                    scopeDialog?.dismiss()
                    cleanupTempUri(pendingImageUri)
                    pendingImageUri = null
                    toast(getString(R.string.profile_photo_upload_failed, result.message))
                    if (session.isClient()) {
                        renderAvatar(session.getProfileImageUrl(), session.getProfileThumbUrl(), bypassCache = false)
                    } else {
                        currentTaker?.let(::loadAvatarFromTaker)
                    }
                }
            }
        }

        detailVm.deleteAccountState.observe(this) { result ->
            when (result) {
                is Result.Loading -> {
                    deleteInFlight = true
                    b.loadingOverlay.visibility = View.VISIBLE
                }
                is Result.Success -> {
                    deleteInFlight = false
                    b.loadingOverlay.visibility = View.GONE
                    session.clearSession()
                    toast(getString(R.string.account_deleted))
                    startActivity(android.content.Intent(this, LoginActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }
                is Result.Error -> {
                    deleteInFlight = false
                    b.loadingOverlay.visibility = View.GONE
                    toast(result.message)
                }
            }
        }

        detailVm.updateState.observe(this) { result ->
            when (result) {
                Result.Loading -> Unit
                is Result.Success -> {
                    val clientUpdate = pendingClientProfileUpdate
                    val takerUpdate = pendingTakerProfileUpdate
                    if (clientUpdate == null && takerUpdate == null) return@observe
                    clientUpdate?.let {
                        saveClientProfileToSession(it)
                        pendingClientProfileUpdate = null
                        renderClientSettings()
                    }
                    takerUpdate?.let {
                        saveTakerProfileToSession(it)
                        pendingTakerProfileUpdate = null
                    }
                    toast(getString(R.string.saved_changes))
                }
                is Result.Error -> {
                    if (pendingClientProfileUpdate == null && pendingTakerProfileUpdate == null) return@observe
                    pendingClientProfileUpdate = null
                    pendingTakerProfileUpdate = null
                    toast(result.message)
                }
            }
        }

        pincodeVm.result.observe(this) { offices ->
            val cityField = activeAddressCity ?: return@observe
            val pincodeField = activeAddressPincode ?: return@observe
            if (offices.isNotEmpty()) {
                bindAddressLocationSuggestions(offices)
                if (addressAutoApplyLocation) {
                    applyAddressPostOffice(pickBestPostOffice(offices))
                } else {
                    cityField.showDropDown()
                }
                pincodeField.error = null
            }
        }
    }

    private fun renderClientSettings() {
        b.tvNameEmailValue.text = listOf(session.getUserName(), session.getUserEmail())
            .filter { it.isNotBlank() }
            .joinToString(" / ")
            .ifBlank { getString(R.string.client_account_default_name) }
        b.tvPhoneValue.text = session.getUserPhone().ifBlank { getString(R.string.not_found) }
        if (!isAvatarUploading && pendingImageUri == null) {
            renderAvatar(session.getProfileImageUrl(), session.getProfileThumbUrl(), bypassCache = false)
        }
    }

    private fun showClientNameEmailDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_name_email, null)
        val etName = view.findViewById<EditText>(R.id.etFullName)
        val etEmail = view.findViewById<EditText>(R.id.etEmail)
        etName.setText(session.getUserName())
        etEmail.setText(session.getUserEmail())

        showModernBottomSheet(getString(R.string.edit_profile_name_email), view) {
            val name = etName.text.toString().trim()
            val email = etEmail.text.toString().trim()
            if (name.isBlank()) {
                toast(getString(R.string.name_required))
                return@showModernBottomSheet false
            }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                toast(getString(R.string.error_email))
                return@showModernBottomSheet false
            }

            val request = UpdateClientProfileRequest(
                clientId = session.getClientActorId(),
                name = name,
                email = email,
                phone = session.getUserPhone().filter(Char::isDigit).takeLast(10),
            )
            if (!email.equals(session.getUserEmail(), ignoreCase = true)) {
                showVerifyIdentityOtpDialog(
                    title = getString(R.string.verify_email),
                    identity = email,
                    isEmail = true,
                    purpose = "update_email",
                ) { token ->
                    submitClientProfileUpdate(request.copy(emailVerificationToken = token))
                }
            } else {
                submitClientProfileUpdate(request)
            }
            true
        }
    }

    private fun showClientPhoneDialog() {
        val currentPhone = session.getUserPhone().filter(Char::isDigit).takeLast(10)
        val phoneInput = createSingleFieldView(R.string.phone_number, currentPhone, InputType.TYPE_CLASS_PHONE)
        showModernBottomSheet(getString(R.string.phone_number), phoneInput.first) {
            val phone = phoneInput.second.text?.toString()?.filter(Char::isDigit).orEmpty()
            if (phone.length != 10) {
                toast(getString(R.string.error_phone))
                return@showModernBottomSheet false
            }
            if (phone == currentPhone) return@showModernBottomSheet true

            val request = UpdateClientProfileRequest(
                clientId = session.getClientActorId(),
                name = session.getUserName(),
                email = session.getUserEmail(),
                phone = phone,
            )
            showVerifyIdentityOtpDialog(
                title = getString(R.string.verify_phone),
                identity = phone,
                isEmail = false,
                purpose = "update_phone",
            ) { token ->
                submitClientProfileUpdate(request.copy(phoneVerificationToken = token))
            }
            true
        }
    }

    private fun submitClientProfileUpdate(request: UpdateClientProfileRequest) {
        pendingClientProfileUpdate = request
        detailVm.updateClientProfile(request)
    }

    private fun saveClientProfileToSession(req: UpdateClientProfileRequest) {
        session.saveSession(
            role = session.getRole(),
            userId = session.getUserId(),
            name = req.name,
            phone = req.phone,
            email = req.email,
            takerProfileId = session.getTakerProfileId(),
            clientProfileId = session.getClientProfileId(),
            profileImageUrl = session.getProfileImageUrl(),
            profileThumbUrl = session.getProfileThumbUrl(),
            accessToken = session.getAccessToken(),
            refreshToken = session.getRefreshToken(),
        )
    }

    private fun createSingleFieldView(hintRes: Int, value: String, inputType: Int): Pair<LinearLayout, TextInputEditText> {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 0)
        }
        val input = TextInputEditText(this).apply {
            setText(value)
            this.inputType = inputType
            setSingleLine(true)
            if (inputType == InputType.TYPE_CLASS_PHONE) {
                filters = arrayOf(android.text.InputFilter.LengthFilter(10))
            }
        }
        val layout = TextInputLayout(this).apply {
            hint = getString(hintRes)
            addView(input)
        }
        container.addView(layout)
        return container to input
    }

    private fun socialSummaryLabels(vararg urls: String?): String {
        val labels = urls.mapNotNull { url ->
            val clean = url?.trim().orEmpty()
            if (clean.isBlank()) return@mapNotNull null
            when {
                clean.contains("instagram.", ignoreCase = true) -> "Instagram"
                clean.contains("youtube.", ignoreCase = true) || clean.contains("youtu.be", ignoreCase = true) -> "YouTube"
                else -> getString(R.string.website)
            }
        }.distinct()
        return labels.joinToString(", ").ifBlank { getString(R.string.edit_profile_none) }
    }

    private fun dispatchUpdate(req: UpdateTakerRequest) {
        pendingTakerProfileUpdate = req
        detailVm.updateProfile(req)
    }

    private fun saveTakerProfileToSession(req: UpdateTakerRequest) {
        session.saveSession(
            role = session.getRole(),
            userId = session.getUserId(),
            name = req.fullName,
            phone = req.phone ?: session.getUserPhone(),
            email = req.email,
            takerProfileId = session.getTakerProfileId(),
            clientProfileId = session.getClientProfileId(),
            accessToken = session.getAccessToken(),
            refreshToken = session.getRefreshToken()
        )
    }

    private fun showModernBottomSheet(
        title: String,
        contentView: View,
        onDismiss: (() -> Unit)? = null,
        onSave: () -> Boolean,
    ) {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val wrapper = layoutInflater.inflate(R.layout.bottom_sheet_edit_wrapper, null)
        
        wrapper.findViewById<android.widget.TextView>(R.id.tvSheetTitle).text = title
        wrapper.findViewById<android.widget.FrameLayout>(R.id.sheetContentContainer).addView(contentView)
        
        wrapper.findViewById<android.view.View>(R.id.btnSheetSave).setOnClickListener {
            if (onSave()) {
                bottomSheet.dismiss()
            }
        }
        
        bottomSheet.setContentView(wrapper)
        bottomSheet.setOnDismissListener { onDismiss?.invoke() }
        bottomSheet.show()
        bottomSheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun showEditNameEmailDialog() {
        val taker = currentTaker ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_name_email, null)
        val etName = view.findViewById<EditText>(R.id.etFullName)
        val etEmail = view.findViewById<EditText>(R.id.etEmail)
        
        etName.setText(taker.fullName)
        etEmail.setText(taker.email)
        
        showModernBottomSheet(getString(R.string.edit_profile_name_email), view) {
            val newName = etName.text.toString().trim()
            if (newName.isBlank()) {
                toast(getString(R.string.name_required))
                return@showModernBottomSheet false
            }
            val newEmail = etEmail.text.toString().trim()
            
            val req = createUpdateRequest().copy(
                fullName = newName,
                email = newEmail
            )
            
            if (!newEmail.equals(taker.email, ignoreCase = true)) {
                if (newEmail.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(newEmail).matches()) {
                    toast(getString(R.string.error_email))
                    return@showModernBottomSheet false
                }
                showVerifyIdentityOtpDialog(
                    title = getString(R.string.verify_email),
                    identity = newEmail,
                    isEmail = true,
                    purpose = "update_email",
                ) { token ->
                    dispatchUpdate(req.copy(emailVerificationToken = token))
                }
                return@showModernBottomSheet true
            }
            
            dispatchUpdate(req)
            true
        }
    }

    private fun showEditPhoneDialog() {
        val currentPhone = (currentTaker?.phone ?: session.getUserPhone()).filter(Char::isDigit).takeLast(10)
        val phoneInput = createSingleFieldView(R.string.phone_number, currentPhone, InputType.TYPE_CLASS_PHONE)
        showModernBottomSheet(getString(R.string.phone_number), phoneInput.first) {
            val phone = phoneInput.second.text?.toString()?.filter(Char::isDigit).orEmpty()
            if (phone.length != 10) {
                toast(getString(R.string.error_phone))
                return@showModernBottomSheet false
            }
            if (phone == currentPhone) return@showModernBottomSheet true

            val req = createUpdateRequest().copy(phone = phone)
            showVerifyIdentityOtpDialog(
                title = getString(R.string.verify_phone),
                identity = phone,
                isEmail = false,
                purpose = "update_phone",
            ) { token ->
                dispatchUpdate(req.copy(phoneVerificationToken = token))
            }
            true
        }
    }

    private fun showVerifyIdentityOtpDialog(
        title: String,
        identity: String,
        isEmail: Boolean,
        purpose: String,
        onVerified: (String) -> Unit,
    ) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 4, 24, 0)
        }
        val status = TextView(this).apply {
            text = if (isEmail) {
                getString(R.string.edit_profile_sending_code_email, identity)
            } else {
                getString(R.string.edit_profile_sending_code_phone, identity)
            }
            setTextColor(MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurfaceVariant))
            textSize = 14f
        }
        val otpInput = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            setSingleLine(true)
        }
        val otpLayout = TextInputLayout(this).apply {
            hint = getString(R.string.enter_otp_code)
            addView(otpInput)
        }
        val progress = android.widget.ProgressBar(this).apply {
            visibility = View.GONE
        }
        content.addView(status)
        content.addView(otpLayout)
        content.addView(progress)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save_changes, null)
            .create()

        var verifiedToken = ""
        var sendStarted = false
        var verifyStarted = false
        lateinit var saveButton: android.widget.Button

        val sendObserver = androidx.lifecycle.Observer<Result<*>> { result ->
            if (!sendStarted) return@Observer
            when (result) {
                Result.Loading -> {
                    progress.visibility = View.VISIBLE
                    status.text = getString(R.string.edit_profile_otp_sending)
                }
                is Result.Success -> {
                    progress.visibility = View.GONE
                    status.text = if (isEmail) {
                        getString(R.string.edit_profile_otp_sent_email, identity)
                    } else {
                        getString(R.string.edit_profile_otp_sent_phone, identity)
                    }
                }
                is Result.Error -> {
                    progress.visibility = View.GONE
                    status.text = result.message
                }
            }
        }
        val verifyObserver = androidx.lifecycle.Observer<Result<OtpVerificationData>> { result ->
            if (!verifyStarted) return@Observer
            when (result) {
                Result.Loading -> {
                    progress.visibility = View.VISIBLE
                    status.text = getString(R.string.edit_profile_otp_verifying)
                }
                is Result.Success -> {
                    progress.visibility = View.GONE
                    verifiedToken = result.data.verificationToken.orEmpty()
                    if (result.data.verified && verifiedToken.isNotBlank()) {
                        otpLayout.error = null
                        status.text = getString(R.string.edit_profile_otp_verified)
                        saveButton.isEnabled = true
                    } else {
                        status.text = getString(R.string.invalid_otp)
                        otpLayout.error = getString(R.string.invalid_otp)
                        saveButton.isEnabled = false
                    }
                }
                is Result.Error -> {
                    progress.visibility = View.GONE
                    status.text = result.message
                    otpLayout.error = result.message
                    saveButton.isEnabled = false
                }
            }
        }

        dialog.setOnShowListener {
            saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.isEnabled = false
            saveButton.setOnClickListener {
                if (verifiedToken.isBlank()) return@setOnClickListener
                onVerified(verifiedToken)
                dialog.dismiss()
            }
            otpInput.addTextChangedListener { text ->
                val otp = text?.toString()?.trim().orEmpty()
                verifiedToken = ""
                saveButton.isEnabled = false
                if (otp.length == 6) {
                    verifyStarted = true
                    if (isEmail) {
                        authVm.verifyEmailOtp(identity, otp)
                    } else {
                        authVm.verifyOtp(identity, otp)
                    }
                }
            }
            if (isEmail) {
                authVm.sendEmailOtpState.observe(this, sendObserver)
                authVm.verifyEmailOtpState.observe(this, verifyObserver)
                sendStarted = true
                authVm.sendEmailOtp(identity, purpose)
            } else {
                authVm.sendOtpState.observe(this, sendObserver)
                authVm.verifyOtpState.observe(this, verifyObserver)
                sendStarted = true
                authVm.sendOtp(identity, purpose)
            }
        }
        dialog.setOnDismissListener {
            if (isEmail) {
                authVm.sendEmailOtpState.removeObserver(sendObserver)
                authVm.verifyEmailOtpState.removeObserver(verifyObserver)
            } else {
                authVm.sendOtpState.removeObserver(sendObserver)
                authVm.verifyOtpState.removeObserver(verifyObserver)
            }
        }
        dialog.show()
    }

    private fun showEditServicesDialog() {
        val taker = currentTaker ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_services, null)
        val actvService = view.findViewById<EditText>(R.id.actvServiceType)
        val etExp = view.findViewById<EditText>(R.id.etExperience)
        
        actvService.setText(selectedServiceTypes.toServiceSummary(this))
        etExp.setText(taker.yearsExperience.toString())
        
        val tempSelectedServices = linkedSetOf<String>().apply { addAll(selectedServiceTypes) }

        actvService.setOnClickListener {
            val checked = SERVICE_TYPES.map { it in tempSelectedServices }.toBooleanArray()
            val labels = serviceLabels(this)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.select_services)
                .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                    val service = SERVICE_TYPES[which]
                    if (isChecked) tempSelectedServices.add(service) else tempSelectedServices.remove(service)
                }
                .setPositiveButton(R.string.done) { _, _ ->
                    if ("other" in tempSelectedServices) {
                        val input = EditText(this).apply {
                            hint = getString(R.string.service_other_hint)
                            setSingleLine(true)
                        }
                        MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.add_custom_service)
                            .setView(input)
                            .setPositiveButton(R.string.done) { _, _ ->
                                input.text?.toString()?.toCustomServiceSlug()?.let { tempSelectedServices.add(it) }
                                actvService.setText(tempSelectedServices.toServiceSummary(this))
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    } else {
                        actvService.setText(tempSelectedServices.toServiceSummary(this))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        
        showModernBottomSheet(getString(R.string.edit_profile_services), view) {
            if (tempSelectedServices.isEmpty()) {
                toast(getString(R.string.select_at_least_one_service))
                return@showModernBottomSheet false
            }
            val req = createUpdateRequest().copy(
                serviceTypes = tempSelectedServices.toList(),
                yearsExperience = etExp.text.toString().toIntOrNull() ?: 0
            )
            dispatchUpdate(req)
            true
        }
    }

    private fun showEditLanguagesDialog() {
        val taker = currentTaker ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_languages, null)
        val etLang = view.findViewById<EditText>(R.id.etLanguages)
        
        etLang.setText(taker.languages)
        
        showModernBottomSheet(getString(R.string.edit_profile_languages), view) {
            val req = createUpdateRequest().copy(
                languages = etLang.text.toString().trim().ifEmpty { null }
            )
            dispatchUpdate(req)
            true
        }
    }

    private fun showEditAddressDialog() {
        val taker = currentTaker ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_address, null)
        val etPincode = view.findViewById<EditText>(R.id.etPincode)
        val etArea = view.findViewById<MaterialAutoCompleteTextView>(R.id.etArea)
        val etCity = view.findViewById<MaterialAutoCompleteTextView>(R.id.etCity)
        val etState = view.findViewById<EditText>(R.id.etState)
        
        etPincode.setText(taker.pincode)
        etArea.setText(taker.city, false)
        etCity.setText(taker.city, false)
        etState.setText(taker.state)
        activeAddressPincode = etPincode
        activeAddressArea = etArea
        activeAddressCity = etCity
        activeAddressState = etState
        etPincode.addTextChangedListener { text ->
            val pin = text?.toString()?.trim().orEmpty()
            etPincode.error = null
            if (!applyingAddressLocation && pin.length == 6) {
                addressAutoApplyLocation = true
                pincodeVm.lookup(pin)
            }
        }
        etCity.addTextChangedListener { text ->
            val city = text?.toString()?.trim().orEmpty()
            if (!applyingAddressLocation && city.length >= 3) {
                addressAutoApplyLocation = false
                scheduleAddressCityLookup(city)
            }
        }
        etCity.setOnItemClickListener { parent, _, position, _ ->
            (parent.getItemAtPosition(position) as? PostOffice)?.let(::applyAddressPostOffice)
        }
        
        showModernBottomSheet(
            getString(R.string.edit_profile_address),
            view,
            onDismiss = {
                activeAddressPincode = null
                activeAddressArea = null
                activeAddressCity = null
                activeAddressState = null
            },
        ) {
            val pincode = etPincode.text.toString().trim()
            val city = etCity.text.toString().trim()
            val state = etState.text.toString().trim()
            if (pincode.length != 6) {
                toast(getString(R.string.register_pincode_invalid))
                return@showModernBottomSheet false
            }
            if (city.isBlank()) {
                toast(getString(R.string.register_city_missing))
                return@showModernBottomSheet false
            }
            if (state.isBlank()) {
                toast(getString(R.string.register_state_missing))
                return@showModernBottomSheet false
            }
            val req = createUpdateRequest().copy(
                pincode = pincode,
                area = city,
                city = city,
                state = state,
            )
            dispatchUpdate(req)
            true
        }
    }

    private fun bindAddressLocationSuggestions(offices: List<PostOffice>) {
        activeAddressCity?.setAdapter(
            LocationSuggestionAdapter(this, offices.cleanLocationSuggestions())
        )
    }

    private fun scheduleAddressCityLookup(query: String) {
        pendingAddressSearch?.let(addressSearchHandler::removeCallbacks)
        val clean = query.trim()
        if (clean.length < 3) return
        pendingAddressSearch = Runnable {
            pincodeVm.searchPlace(clean)
        }.also { addressSearchHandler.postDelayed(it, 280L) }
    }

    private fun pickBestPostOffice(offices: List<PostOffice>): PostOffice =
        offices.firstOrNull { it.district.isNotBlank() }
            ?: offices.firstOrNull { it.branchType?.contains("Head", ignoreCase = true) == true }
            ?: offices.first()

    private fun applyAddressPostOffice(po: PostOffice) {
        applyingAddressLocation = true
        val cityName = po.bestCityName()
        activeAddressArea?.setText(cityName, false)
        activeAddressCity?.setText(cityName, false)
        activeAddressState?.setText(po.state)
        if (po.pincode.isNotBlank()) {
            activeAddressPincode?.setText(po.pincode)
        }
        applyingAddressLocation = false
    }

    private fun List<PostOffice>.cleanLocationSuggestions(): List<PostOffice> =
        distinctBy { listOf(it.bestCityName(), it.state, it.pincode).joinToString("|").lowercase() }
            .take(6)

    private fun showEditSocialDialog() {
        val taker = currentTaker ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_social, null)
        val etPort = view.findViewById<EditText>(R.id.etPortfolioUrl)
        val container = view.findViewById<LinearLayout>(R.id.layoutSocialLinks)
        val addButton = view.findViewById<MaterialButton>(R.id.btnAddSocialLink)
        val extraFields = mutableListOf<EditText>()

        fun syncAddButton() {
            addButton.visibility = if (etPort.text?.isNotBlank() == true || extraFields.isNotEmpty()) View.VISIBLE else View.GONE
            addButton.isEnabled = extraFields.size < 2
        }

        fun addExtraField(initialValue: String? = null) {
            if (extraFields.size >= 2) return
            container.visibility = View.VISIBLE
            val hint = if (extraFields.isEmpty()) R.string.additional_link_hint else R.string.additional_link_2_hint
            val inputLayout = TextInputLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() }
                this.hint = getString(hint)
                endIconMode = TextInputLayout.END_ICON_CUSTOM
                setEndIconDrawable(android.R.drawable.ic_menu_close_clear_cancel)
            }
            val editText = TextInputEditText(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                inputType = InputType.TYPE_TEXT_VARIATION_URI
                setText(initialValue.orEmpty())
            }
            inputLayout.addView(editText)
            inputLayout.setEndIconOnClickListener {
                container.removeView(inputLayout)
                extraFields.remove(editText)
                if (extraFields.isEmpty()) container.visibility = View.GONE
                syncAddButton()
            }
            container.addView(inputLayout)
            extraFields.add(editText)
            syncAddButton()
        }

        etPort.setText(taker.portfolioUrl)
        listOf(
            taker.socialLinkAdditional1,
            taker.socialLinkAdditional2,
            taker.instagramUrl,
            taker.youtubeUrl,
        )
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .take(2)
            .forEach { addExtraField(it) }
        etPort.addTextChangedListener { syncAddButton() }
        addButton.setOnClickListener { addExtraField() }
        syncAddButton()

        showModernBottomSheet(getString(R.string.edit_profile_social), view) {
            val links = buildList {
                etPort.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                extraFields.mapNotNullTo(this) { it.text?.toString()?.trim()?.takeIf(String::isNotBlank) }
            }.distinct()
            val req = createUpdateRequest().copy(
                portfolioUrl = links.getOrNull(0),
                socialLinkAdditional1 = links.getOrNull(1),
                socialLinkAdditional2 = links.getOrNull(2),
                instagramUrl = null,
                youtubeUrl = null,
            )
            dispatchUpdate(req)
            true
        }
    }

    private fun createUpdateRequest(): UpdateTakerRequest {
        val taker = currentTaker!!
        return UpdateTakerRequest(
            takerId = taker.id,
            fullName = taker.fullName,
            email = taker.email,
            phone = taker.phone ?: session.getUserPhone(),
            pincode = taker.pincode,
            area = taker.city,
            city = taker.city,
            state = taker.state,
            serviceTypes = taker.offeredServices,
            yearsExperience = taker.yearsExperience,
            languages = taker.languages,
            portfolioUrl = taker.portfolioUrl,
            instagramUrl = taker.instagramUrl,
            youtubeUrl = taker.youtubeUrl,
            socialLinkAdditional1 = taker.socialLinkAdditional1,
            socialLinkAdditional2 = taker.socialLinkAdditional2,
        )
    }

    private fun showScopeDialog(uri: Uri) {
        scopeDialog?.dismiss()

        val scopes = arrayOf(
            getString(R.string.photo_scope_public),
            getString(R.string.photo_scope_profile_only),
        )
        val scopeKeys = arrayOf("public", "profile-only")
        var chosen = if (currentTaker?.profileImageScope == "profile-only") 1 else 0
        var uploadStarted = false

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.photo_scope_title)
            .setSingleChoiceItems(scopes, chosen) { _, which -> chosen = which }
            .setPositiveButton(R.string.upload, null)
            .setNegativeButton(R.string.cancel) { _, _ ->
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
                detailVm.uploadProfileImage(session.getTakerActorId(), selectedScope, uri, applicationContext)
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
        pendingAddressSearch?.let(addressSearchHandler::removeCallbacks)
        scopeDialog?.dismiss()
        cleanupTempUri(pendingImageUri)
        pendingImageUri = null
        super.onDestroy()
    }
}
