package com.photoconnect.ui.activities

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.photoconnect.R
import com.photoconnect.databinding.ActivityRegisterBinding
import com.photoconnect.model.PostOffice
import com.photoconnect.model.RegisterClientRequest
import com.photoconnect.model.RegisterTakerRequest
import com.photoconnect.network.IdData
import com.photoconnect.repository.Result
import com.photoconnect.utils.*
import com.photoconnect.viewmodel.AuthViewModel
import com.photoconnect.viewmodel.PincodeViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class RegisterActivity : AppCompatActivity() {
    private lateinit var b: ActivityRegisterBinding
    private val authVm: AuthViewModel by viewModels()
    private val pincodeVm: PincodeViewModel by viewModels()

    private var currentStep = 1
    private var isTakerMode = false
    private val selectedServiceTypes = linkedSetOf<String>()
    private var applyingLocation = false
    private var autoApplyLocation = false
    private var lockedPincode = ""
    private var resolvedPincode = ""
    private var isGoogleSignup = false
    private var awaitingIdentityCheck = false
    private var isPhoneVerified = false
    private var verifiedPhone = ""
    private var phoneVerificationToken = ""
    private var isEmailVerified = false
    private var verifiedEmail = ""
    private var emailVerificationToken = ""
    private var isPhoneOtpPending = false
    private var isEmailOtpPending = false
    private var phoneOtpTimer: android.os.CountDownTimer? = null
    private var emailOtpTimer: android.os.CountDownTimer? = null
    private var phoneOtpExpiresInSeconds = 180
    private val locationSearchHandler = Handler(Looper.getMainLooper())
    private var pendingLocationSearch: Runnable? = null
    private var servicePickerDialog: BottomSheetDialog? = null
    private val sessionManager by lazy { SessionManager(this) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        isGoogleSignup = intent.getBooleanExtra("is_google_signup", false)
        b = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val onSurface = MaterialColors.getColor(b.toolbar, com.google.android.material.R.attr.colorOnSurface)
        b.toolbar.navigationIcon?.setTint(onSurface)
        b.toolbar.setTitleTextColor(onSurface)
        b.toolbar.setSubtitleTextColor(onSurface)
        b.toolbar.setNavigationOnClickListener {
            if (currentStep > 1) moveBack() else finish()
        }

        setupSteps()
        setupListeners()
        setupObservers()
        
        if (isGoogleSignup) {
            b.tilPassword.visibility = View.GONE
            b.tilConfirmPassword.visibility = View.GONE
            
            // Auto fill from session
            b.etFullName.setText(sessionManager.getUserName())
            b.etEmail.setText(sessionManager.getUserEmail())
            if (sessionManager.getUserEmail().isNotBlank()) {
                isEmailVerified = true
                verifiedEmail = sessionManager.getUserEmail()
                b.etEmail.isEnabled = false
                b.btnVerifyEmail.visibility = View.GONE
                b.btnVerifyEmail.isEnabled = false
            }
            if (sessionManager.getUserPhone().isNotBlank()) {
                isPhoneVerified = true
                verifiedPhone = sessionManager.getUserPhone()
                b.etPhone.setText(sessionManager.getUserPhone())
                b.etPhone.isEnabled = false
                b.btnVerifyPhone.visibility = View.GONE
                b.btnVerifyPhone.isEnabled = false
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_auth_language, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.actionLanguage -> {
                LanguagePickerBottomSheet.show(this) { recreate() }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupSteps() {
        b.actvServiceType.setOnClickListener { showServicePicker() }
        b.actvServiceType.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showServicePicker()
        }
        updateStepUI()
    }

    private fun setupListeners() {
        b.toggleGroupRole.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                isTakerMode = id == R.id.tabRegTaker
            }
        }

        b.btnNext.setOnClickListener { moveNext() }
        b.btnBack.setOnClickListener { moveBack() }
        b.tvSignIn.setOnClickListener { finish() }

        b.etPincode.addTextChangedListener { text ->
            val pin = text?.toString()?.trim() ?: ""
            b.tilPincode.error = null
            if (!applyingLocation) {
                if (pin != lockedPincode) {
                    unlockLocationFields(clearValues = true)
                }
                if (pin.length == 6) {
                    autoApplyLocation = true
                    pincodeVm.lookup(pin)
                } else {
                    autoApplyLocation = false
                    hideLocationSuggestions()
                }
            }
        }

        b.etCity.addTextChangedListener { text ->
            val queryLength = text?.toString()?.trim()?.length ?: 0
            if (!applyingLocation && b.etCity.isEnabled && queryLength >= 3) {
                autoApplyLocation = false
                scheduleCityLookup(text.toString())
            } else if (!applyingLocation && queryLength < 3) {
                hideLocationSuggestions()
            }
        }

        b.btnVerifyPhone.setOnClickListener { handlePhoneVerifyClick() }

        b.btnVerifyEmail.setOnClickListener { handleEmailVerifyClick() }
        b.btnAddLink.setOnClickListener { addExtraLinkField() }
        
        b.etPhoneOtp.addTextChangedListener { text ->
            if (text?.length == 6) {
                val phone = b.etPhone.text?.toString()?.trim() ?: ""
                b.tilPhoneOtp.error = null
                authVm.verifyOtp(phone, text.toString())
            }
        }

        b.etEmailOtp.addTextChangedListener { text ->
            if (text?.length == 6) {
                val email = b.etEmail.text?.toString()?.trim() ?: ""
                b.tilEmailOtp.error = null
                authVm.verifyEmailOtp(email, text.toString())
            }
        }

        b.etPhone.addTextChangedListener {
            val current = it?.toString()?.trim().orEmpty()
            if (isPhoneVerified && current != verifiedPhone) {
                isPhoneVerified = false
                verifiedPhone = ""
                phoneVerificationToken = ""
                b.etPhone.isEnabled = true
                b.btnVerifyPhone.isEnabled = true
                b.btnVerifyPhone.text = getString(R.string.verify)
            }
        }

        b.etEmail.addTextChangedListener {
            val current = it?.toString()?.trim().orEmpty()
            if (isEmailVerified && !current.equals(verifiedEmail, ignoreCase = true)) {
                isEmailVerified = false
                verifiedEmail = ""
                emailVerificationToken = ""
                b.etEmail.isEnabled = true
                b.btnVerifyEmail.isEnabled = true
                b.btnVerifyEmail.text = getString(R.string.verify)
            }
        }
    }

    private fun handlePhoneVerifyClick() {
        if (isPhoneOtpPending && !isPhoneVerified) {
            resetPhoneVerificationForReentry()
            return
        }
        val phone = b.etPhone.text?.toString()?.trim() ?: ""
        if (!phone.isValidPhone()) {
            b.tilPhone.error = getString(R.string.error_phone)
            return
        }
        b.tilPhone.error = null
        authVm.sendOtp(phone)
    }

    private fun handleEmailVerifyClick() {
        if (isEmailOtpPending && !isEmailVerified) {
            resetEmailVerificationForReentry()
            return
        }
        val email = b.etEmail.text?.toString()?.trim() ?: ""
        if (!email.isValidEmail()) {
            b.tilEmail.error = getString(R.string.error_email)
            return
        }
        b.tilEmail.error = null
        authVm.sendEmailOtp(email)
    }

    private fun resetPhoneVerificationForReentry() {
        phoneOtpTimer?.cancel()
        isPhoneOtpPending = false
        isPhoneVerified = false
        verifiedPhone = ""
        phoneVerificationToken = ""
        b.etPhone.isEnabled = true
        b.etPhone.requestFocus()
        b.etPhoneOtp.setText("")
        b.tilPhoneOtp.error = null
        b.tilPhoneOtp.visibility = View.GONE
        b.tvPhoneOtpTimer.visibility = View.GONE
        b.btnVerifyPhone.isEnabled = true
        b.btnVerifyPhone.text = getString(R.string.verify)
    }

    private fun resetEmailVerificationForReentry() {
        emailOtpTimer?.cancel()
        isEmailOtpPending = false
        isEmailVerified = false
        verifiedEmail = ""
        emailVerificationToken = ""
        b.etEmail.isEnabled = true
        b.etEmail.requestFocus()
        b.etEmailOtp.setText("")
        b.tilEmailOtp.error = null
        b.tilEmailOtp.visibility = View.GONE
        b.tvEmailOtpTimer.visibility = View.GONE
        b.btnVerifyEmail.isEnabled = true
        b.btnVerifyEmail.text = getString(R.string.verify)
    }

    private fun scheduleCityLookup(query: String) {
        pendingLocationSearch?.let(locationSearchHandler::removeCallbacks)
        val clean = query.trim()
        if (clean.length < 3) return
        pendingLocationSearch = Runnable {
            pincodeVm.searchPlace(clean)
        }.also { locationSearchHandler.postDelayed(it, 280L) }
    }

    private fun setupObservers() {
        pincodeVm.loading.observe(this) { loading ->
            b.progressLocationLookup.visibility = if (loading) View.VISIBLE else View.GONE
        }

        pincodeVm.result.observe(this) { offices ->
            if (autoApplyLocation) {
                val enteredPin = b.etPincode.text?.toString()?.trim().orEmpty()
                val matchingOffices = offices.filter { it.pincode == enteredPin }
                if (enteredPin.length == 6 && matchingOffices.isNotEmpty()) {
                    val selected = pickBestPostOffice(matchingOffices)
                    applyPostOffice(selected, lockToPincode = true)
                    b.tilPincode.error = null
                } else if (enteredPin.length == 6) {
                    unlockLocationFields(clearValues = true)
                    b.tilPincode.error = getString(R.string.location_lookup_failed)
                    hideLocationSuggestions()
                }
                return@observe
            }

            if (offices.isNotEmpty()) {
                bindLocationSuggestions(offices)
                b.tilPincode.error = null
            } else {
                hideLocationSuggestions()
            }
        }

        authVm.regTakerState.observe(this) { handleResult(it) }
        authVm.regClientState.observe(this) { handleResult(it) }

        authVm.identityState.observe(this) { r ->
            if (!awaitingIdentityCheck) return@observe
            when (r) {
                is Result.Loading -> {
                    b.progressBar.show()
                    b.btnNext.isEnabled = false
                }
                is Result.Success -> {
                    awaitingIdentityCheck = false
                    b.progressBar.hide()
                    b.btnNext.isEnabled = true
                    b.tilPhone.error = null
                    b.tilEmail.error = null
                    val d = r.data
                    if (d.phoneRegistered) {
                        b.tilPhone.error = getString(R.string.identity_phone_in_use)
                        toast(getString(R.string.identity_phone_in_use))
                        return@observe
                    }
                    val em = b.etEmail.text?.toString()?.trim().orEmpty()
                    if (em.isNotEmpty() && d.emailRegistered) {
                        b.tilEmail.error = getString(R.string.identity_email_in_use)
                        toast(getString(R.string.identity_email_in_use))
                        return@observe
                    }
                    currentStep++
                    updateStepUI()
                }
                is Result.Error -> {
                    awaitingIdentityCheck = false
                    b.progressBar.hide()
                    b.btnNext.isEnabled = true
                    toast(r.message)
                }
            }
        }

        authVm.sendOtpState.observe(this) { r ->
            when (r) {
                is Result.Loading -> {
                    b.btnVerifyPhone.isEnabled = false
                    b.progressBar.show()
                }
                is Result.Success -> {
                    b.progressBar.hide()
                    toast(getString(R.string.phone_otp_sent))
                    isPhoneOtpPending = true
                    isPhoneVerified = false
                    verifiedPhone = ""
                    phoneVerificationToken = ""
                    b.etPhone.isEnabled = false
                    b.btnVerifyPhone.isEnabled = true
                    b.btnVerifyPhone.text = getString(R.string.reenter)
                    b.tilPhoneOtp.visibility = View.VISIBLE
                    b.tvPhoneOtpTimer.visibility = View.VISIBLE
                    b.etPhoneOtp.setText("")
                    b.tilPhoneOtp.error = null
                    phoneOtpExpiresInSeconds = r.data.expiresIn?.coerceAtLeast(30) ?: 180
                    startPhoneOtpTimer(phoneOtpExpiresInSeconds)
                }
                is Result.Error -> {
                    b.progressBar.hide()
                    val retryAfter = r.retryAfterSeconds
                    if (r.code == 429 && retryAfter != null && retryAfter > 0) {
                        startPhoneOtpCooldownTimer(retryAfter)
                    } else {
                        b.btnVerifyPhone.isEnabled = true
                    }
                    toast(r.message)
                }
            }
        }

        authVm.verifyOtpState.observe(this) { r ->
            when (r) {
                is Result.Loading -> b.progressBar.show()
                is Result.Success -> {
                    b.progressBar.hide()
                    val token = r.data.verificationToken.orEmpty()
                    if (!r.data.verified || token.isBlank()) {
                        isPhoneVerified = false
                        verifiedPhone = ""
                        phoneVerificationToken = ""
                        b.tilPhoneOtp.error = getString(R.string.invalid_otp)
                        return@observe
                    }
                    isPhoneVerified = true
                    verifiedPhone = b.etPhone.text?.toString()?.trim().orEmpty()
                    phoneVerificationToken = token
                    b.btnVerifyPhone.text = getString(R.string.verified)
                    b.btnVerifyPhone.isEnabled = false
                    b.etPhone.isEnabled = false
                    isPhoneOtpPending = false
                    b.tilPhoneOtp.visibility = View.GONE
                    b.tvPhoneOtpTimer.visibility = View.GONE
                    phoneOtpTimer?.cancel()
                    toast(getString(R.string.phone_verified_success))
                }
                is Result.Error -> {
                    b.progressBar.hide()
                    b.tilPhoneOtp.error = r.message
                }
            }
        }

        authVm.sendEmailOtpState.observe(this) { r ->
            when (r) {
                is Result.Loading -> {
                    b.btnVerifyEmail.isEnabled = false
                    b.progressBar.show()
                }
                is Result.Success -> {
                    b.progressBar.hide()
                    toast(getString(R.string.email_otp_sent))
                    isEmailOtpPending = true
                    isEmailVerified = false
                    verifiedEmail = ""
                    emailVerificationToken = ""
                    b.etEmail.isEnabled = false
                    b.btnVerifyEmail.isEnabled = true
                    b.btnVerifyEmail.text = getString(R.string.reenter)
                    b.tilEmailOtp.visibility = View.VISIBLE
                    b.tvEmailOtpTimer.visibility = View.VISIBLE
                    b.etEmailOtp.setText("")
                    b.tilEmailOtp.error = null
                    startEmailOtpTimer()
                }
                is Result.Error -> {
                    b.progressBar.hide()
                    b.btnVerifyEmail.isEnabled = true
                    toast(r.message)
                }
            }
        }

        authVm.verifyEmailOtpState.observe(this) { r ->
            when (r) {
                is Result.Loading -> b.progressBar.show()
                is Result.Success -> {
                    b.progressBar.hide()
                    val token = r.data.verificationToken.orEmpty()
                    if (!r.data.verified || token.isBlank()) {
                        isEmailVerified = false
                        verifiedEmail = ""
                        emailVerificationToken = ""
                        b.tilEmailOtp.error = getString(R.string.invalid_otp)
                        return@observe
                    }
                    isEmailVerified = true
                    verifiedEmail = b.etEmail.text?.toString()?.trim().orEmpty()
                    emailVerificationToken = token
                    b.btnVerifyEmail.text = getString(R.string.verified)
                    b.btnVerifyEmail.isEnabled = false
                    b.etEmail.isEnabled = false
                    isEmailOtpPending = false
                    b.tilEmailOtp.visibility = View.GONE
                    b.tvEmailOtpTimer.visibility = View.GONE
                    emailOtpTimer?.cancel()
                    toast(getString(R.string.email_verified_success))
                }
                is Result.Error -> {
                    b.progressBar.hide()
                    b.tilEmailOtp.error = r.message
                }
            }
        }
    }

    private fun startPhoneOtpTimer(durationSeconds: Int = phoneOtpExpiresInSeconds) {
        phoneOtpTimer?.cancel()
        phoneOtpTimer = object : android.os.CountDownTimer(durationSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                val min = seconds / 60
                val sec = seconds % 60
                b.tvPhoneOtpTimer.text = getString(R.string.otp_timer, min, sec)
            }
            override fun onFinish() {
                if (!isPhoneVerified) {
                    isPhoneOtpPending = false
                    b.etPhone.isEnabled = true
                    b.btnVerifyPhone.isEnabled = true
                    b.btnVerifyPhone.text = getString(R.string.verify)
                    b.tvPhoneOtpTimer.text = getString(R.string.otp_expired)
                }
            }
        }.start()
    }

    private fun startPhoneOtpCooldownTimer(durationSeconds: Int) {
        phoneOtpTimer?.cancel()
        isPhoneOtpPending = false
        b.etPhone.isEnabled = true
        b.btnVerifyPhone.isEnabled = false
        b.btnVerifyPhone.text = getString(R.string.verify)
        b.tilPhoneOtp.visibility = View.GONE
        b.tvPhoneOtpTimer.visibility = View.VISIBLE
        phoneOtpTimer = object : android.os.CountDownTimer(durationSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).coerceAtLeast(1)
                val min = seconds / 60
                val sec = seconds % 60
                b.tvPhoneOtpTimer.text = getString(R.string.otp_cooldown_timer, min, sec)
            }

            override fun onFinish() {
                if (!isPhoneVerified) {
                    b.btnVerifyPhone.isEnabled = true
                    b.tvPhoneOtpTimer.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun startEmailOtpTimer() {
        emailOtpTimer?.cancel()
        emailOtpTimer = object : android.os.CountDownTimer(180000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                val min = seconds / 60
                val sec = seconds % 60
                b.tvEmailOtpTimer.text = getString(R.string.otp_timer, min, sec)
            }
            override fun onFinish() {
                if (!isEmailVerified) {
                    isEmailOtpPending = false
                    b.etEmail.isEnabled = true
                    b.btnVerifyEmail.isEnabled = true
                    b.btnVerifyEmail.text = getString(R.string.verify)
                    b.tvEmailOtpTimer.text = getString(R.string.otp_expired)
                }
            }
        }.start()
    }

    private fun bindLocationSuggestions(offices: List<PostOffice>) {
        val suggestions = offices.cleanLocationSuggestions()
        b.chipGroupLocationSuggestions.removeAllViews()
        suggestions.forEach { office ->
            b.chipGroupLocationSuggestions.addView(Chip(this).apply {
                text = office.locationChipText()
                isCheckable = false
                isClickable = true
                chipBackgroundColor = ColorStateList.valueOf(
                    MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorSurfaceVariant)
                )
                setTextColor(MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurfaceVariant))
                setOnClickListener {
                    applyPostOffice(office, lockToPincode = office.pincode.isNotBlank())
                    hideLocationSuggestions()
                }
            })
        }
        b.chipGroupLocationSuggestions.visibility = if (suggestions.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun pickBestPostOffice(offices: List<PostOffice>): PostOffice =
        offices.firstOrNull { !it.district.isNullOrBlank() }
            ?: offices.firstOrNull { it.branchType?.contains("Head", ignoreCase = true) == true }
            ?: offices.first()

    private fun applyPostOffice(po: PostOffice, lockToPincode: Boolean = false) {
        applyingLocation = true
        val cityName = po.bestCityName()
        b.etArea.setText(cityName, false)
        b.etCity.setText(cityName, false)
        b.etState.setText(po.state)
        if (po.pincode.isNotBlank()) {
            b.etPincode.setText(po.pincode)
        }
        applyingLocation = false
        if (lockToPincode && po.pincode.isNotBlank()) {
            lockLocationFields(po.pincode)
        }
    }

    private fun List<PostOffice>.cleanLocationSuggestions(): List<PostOffice> =
        distinctBy { listOf(it.bestCityName(), it.state, it.pincode).joinToString("|").lowercase() }
            .take(6)

    private fun PostOffice.locationChipText(): String {
        val title = bestCityName()
        return listOf(
            title,
            state,
            pincode.takeIf { it.isNotBlank() }?.let { "PIN $it" }.orEmpty(),
        ).filter { it.isNotBlank() }.joinToString(" - ")
    }

    private fun hideLocationSuggestions() {
        b.chipGroupLocationSuggestions.removeAllViews()
        b.chipGroupLocationSuggestions.visibility = View.GONE
    }

    private fun unlockLocationFields(clearValues: Boolean) {
        lockedPincode = ""
        resolvedPincode = ""
        b.etArea.isEnabled = true
        b.etCity.isEnabled = true
        b.etState.isEnabled = true
        if (clearValues && (b.etArea.text?.isNotBlank() == true || b.etCity.text?.isNotBlank() == true || b.etState.text?.isNotBlank() == true)) {
            applyingLocation = true
            b.etArea.setText("", false)
            b.etCity.setText("", false)
            b.etState.setText("")
            applyingLocation = false
        }
        hideLocationSuggestions()
    }

    private fun lockLocationFields(pincode: String) {
        lockedPincode = pincode
        resolvedPincode = pincode
    }

    private fun showServicePicker() {
        if (servicePickerDialog?.isShowing == true) return
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_service_picker, null)
        val search = view.findViewById<TextInputEditText>(R.id.etServiceSearch)
        val chips = view.findViewById<ChipGroup>(R.id.chipGroupServiceChoices)
        val btnReset = view.findViewById<View>(R.id.btnResetServices)
        val btnDone = view.findViewById<View>(R.id.btnDoneServices)
        val labels = serviceLabels(this)
        val draftSelection = linkedSetOf<String>().apply { addAll(selectedServiceTypes) }

        fun renderChoices(query: String = "") {
            val cleanQuery = query.trim()
            chips.removeAllViews()
            labels.forEachIndexed { index, label ->
                val service = SERVICE_TYPES[index]
                if (cleanQuery.isNotBlank() && !label.contains(cleanQuery, ignoreCase = true)) return@forEachIndexed
                chips.addView(Chip(this).apply {
                    text = label
                    isCheckable = true
                    isChecked = service in draftSelection
                    chipBackgroundColor = serviceChipBackground()
                    setTextColor(serviceChipTextColor())
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) draftSelection.add(service) else draftSelection.remove(service)
                    }
                })
            }
        }

        search.addTextChangedListener { renderChoices(it?.toString().orEmpty()) }
        btnReset.setOnClickListener {
            draftSelection.clear()
            renderChoices(search.text?.toString().orEmpty())
        }
        btnDone.setOnClickListener {
            selectedServiceTypes.clear()
            selectedServiceTypes.addAll(draftSelection)
            dialog.dismiss()
            if ("other" in selectedServiceTypes) showCustomServiceDialog() else renderSelectedServices()
        }

        renderChoices()
        dialog.setContentView(view)
        dialog.setOnDismissListener { servicePickerDialog = null }
        servicePickerDialog = dialog
        dialog.show()
    }

    private fun serviceChipBackground(): ColorStateList =
        ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(
                MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorPrimaryContainer),
                MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorSurfaceVariant),
            )
        )

    private fun serviceChipTextColor(): ColorStateList =
        ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(
                MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnPrimaryContainer),
                MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurfaceVariant),
            )
        )

    private fun showCustomServiceDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.service_other_hint)
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_custom_service)
            .setView(input)
            .setPositiveButton(R.string.done) { _, _ ->
                input.text?.toString()?.toCustomServiceSlug()?.let { selectedServiceTypes.add(it) }
                renderSelectedServices()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> renderSelectedServices() }
            .show()
    }

    private fun renderSelectedServices() {
        b.actvServiceType.setText(selectedServiceTypes.toServiceSummary(this), false)
    }

    private fun updateStepUI() {
        val maxSteps = if (isTakerMode) 5 else 3
        b.stepProgress.progress = (currentStep * 100) / maxSteps
        b.tvStepCounter.text = getString(R.string.register_step_counter, currentStep, maxSteps)

        // Hide all steps first
        b.stepRole.hide(); b.stepBasic.hide(); b.stepSecurity.hide()
        b.stepLocation.hide(); b.stepService.hide()

        b.btnBack.visibility = if (currentStep > 1) View.VISIBLE else View.INVISIBLE
        b.btnNext.text =
            if (currentStep == maxSteps) getString(R.string.register) else getString(R.string.next)

        when (currentStep) {
            1 -> {
                b.tvStepTitle.text = getString(R.string.register_step_role_title)
                b.tvStepDescription.text = getString(R.string.register_step_role_desc)
                b.stepRole.show()
            }
            2 -> {
                b.tvStepTitle.text = getString(R.string.register_step_contact_title)
                b.tvStepDescription.text = getString(R.string.register_step_contact_desc)
                b.stepBasic.show()
            }
            3 -> {
                b.tvStepTitle.text = getString(R.string.register_step_security_title)
                b.tvStepDescription.text = getString(R.string.register_step_security_desc)
                b.stepSecurity.show()
            }
            4 -> {
                b.tvStepTitle.text = getString(R.string.register_step_location_title)
                b.tvStepDescription.text = getString(R.string.register_step_location_desc)
                b.stepLocation.show()
            }
            5 -> {
                b.tvStepTitle.text = getString(R.string.register_step_service_title)
                b.tvStepDescription.text = getString(R.string.register_step_service_desc)
                b.stepService.show()
            }
        }
    }

    private fun moveNext() {
        val maxSteps = if (isTakerMode) 5 else 3
        if (currentStep == 2) {
            if (!validateCurrentStep()) return
            if (!isPhoneVerified) {
                toast(getString(R.string.verify_phone_first))
                return
            }
            if (!isEmailVerified) {
                toast(getString(R.string.verify_email_first))
                return
            }
            if (isGoogleSignup) {
                currentStep++
                updateStepUI()
                return
            }
            awaitingIdentityCheck = true
            val phone = b.etPhone.text?.toString()?.trim() ?: ""
            val email = b.etEmail.text?.toString()?.trim().orEmpty()
            authVm.checkIdentityForRegistration(phone, email.ifBlank { null })
            return
        }
        if (!validateCurrentStep()) return
        if (currentStep < maxSteps) {
            currentStep++
            updateStepUI()
        } else {
            attemptRegister()
        }
    }

    private fun moveBack() {
        if (currentStep > 1) {
            currentStep--
            updateStepUI()
        }
    }

    private fun validateCurrentStep(): Boolean {
        when (currentStep) {
            2 -> {
                val name = b.etFullName.text?.toString()?.trim() ?: ""
                val phone = b.etPhone.text?.toString()?.trim() ?: ""
                val email = b.etEmail.text?.toString()?.trim() ?: ""
                if (name.isEmpty()) { toast(getString(R.string.name_required)); return false }
                if (!phone.isValidPhone()) { toast(getString(R.string.error_phone)); return false }
                if (email.isEmpty()) { toast(getString(R.string.email_required)); return false }
                if (!email.isValidEmail()) { toast(getString(R.string.error_email)); return false }
            }
            3 -> {
                if (!isGoogleSignup) {
                    val pass = b.etPassword.text?.toString()?.trim() ?: ""
                    val pass2 = b.etConfirmPassword.text?.toString()?.trim() ?: ""
                    b.tilPassword.error = null
                    b.tilConfirmPassword.error = null
                    if (pass.length < 6) { b.tilPassword.error = getString(R.string.error_password_short); return false }
                    if (pass != pass2) { b.tilConfirmPassword.error = getString(R.string.error_passwords_no_match); return false }
                }
            }
            4 -> {
                val pincode = b.etPincode.text?.toString()?.trim() ?: ""
                val city = b.etCity.text?.toString()?.trim() ?: ""
                val state = b.etState.text?.toString()?.trim() ?: ""
                if (pincode.length != 6) { toast(getString(R.string.register_pincode_invalid)); return false }
                if (city.isEmpty()) { toast(getString(R.string.register_city_missing)); return false }
                if (state.isEmpty()) { toast(getString(R.string.register_state_missing)); return false }
                if (resolvedPincode != pincode) { toast(getString(R.string.location_match_required)); return false }
            }
            5 -> {
                if (selectedServiceTypes.isEmpty()) { toast(getString(R.string.select_at_least_one_service)); return false }
            }
        }
        return true
    }

    private fun attemptRegister() {
        val name  = b.etFullName.text?.toString()?.trim() ?: ""
        val phone = b.etPhone.text?.toString()?.trim() ?: ""
        val email = b.etEmail.text?.toString()?.trim() ?: ""
        val pass  = b.etPassword.text?.toString()?.trim() ?: ""
        val phoneToken = phoneVerificationToken
            .takeIf { isPhoneVerified && verifiedPhone == phone && it.isNotBlank() }
        val emailToken = emailVerificationToken
            .takeIf { isEmailVerified && verifiedEmail.equals(email, ignoreCase = true) && it.isNotBlank() }

        if (!isGoogleSignup && phoneToken == null) {
            toast(getString(R.string.verify_phone_first))
            return
        }
        if (!isGoogleSignup && emailToken == null) {
            toast(getString(R.string.verify_email_first))
            return
        }

        if (isTakerMode) {
            val pincode = b.etPincode.text?.toString()?.trim() ?: ""
            val city    = b.etCity.text?.toString()?.trim() ?: ""
            val state   = b.etState.text?.toString()?.trim() ?: ""

            val allLinks = getAllLinks()
            val portfolioLink = allLinks.getOrNull(0)
            val additionalLink1 = allLinks.getOrNull(1)
            val additionalLink2 = allLinks.getOrNull(2)

            authVm.registerTaker(RegisterTakerRequest(
                fullName = name,
                phone = phone,
                email = email,
                password = pass,
                pincode = pincode,
                area = city,
                city = city,
                state = state,
                serviceTypes = selectedServiceTypes.toList(),
                yearsExperience = b.etYearsExp.text?.toString()?.trim()?.toIntOrNull() ?: 0,
                languages = b.etLanguagesReg.text?.toString()?.trim()?.ifEmpty { null },
                portfolioUrl = portfolioLink,
                socialLinkAdditional1 = additionalLink1,
                socialLinkAdditional2 = additionalLink2,
                phoneVerificationToken = phoneToken,
                emailVerificationToken = emailToken,
            ))
        } else {
            authVm.registerClient(RegisterClientRequest(
                name,
                phone,
                email,
                pass,
                null,
                null,
                phoneToken,
                emailToken,
            ))
        }
    }

    private fun handleResult(r: Result<*>) {
        when (r) {
            is Result.Loading -> { b.progressBar.show(); b.btnNext.isEnabled = false }
            is Result.Success -> {
                b.progressBar.hide()
                if (isGoogleSignup) {
                    completeGoogleProfile(r.data as? IdData)
                    return
                }
                toast(getString(R.string.register_success_sign_in))
                startActivity(Intent(this, LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
                finish()
            }
            is Result.Error -> { b.progressBar.hide(); b.btnNext.isEnabled = true; toast(r.message) }
        }
    }

    private fun completeGoogleProfile(data: IdData?) {
        val userId = data?.userId?.takeIf { it > 0 } ?: sessionManager.getUserId()
        val name = b.etFullName.text?.toString()?.trim().orEmpty()
        val phone = b.etPhone.text?.toString()?.trim().orEmpty()
        val email = b.etEmail.text?.toString()?.trim().orEmpty()
        val role = if (isTakerMode) SessionManager.ROLE_TAKER else SessionManager.ROLE_CLIENT
        val takerProfileId = if (isTakerMode) data?.id ?: 0 else sessionManager.getTakerProfileId()
        val clientProfileId = if (isTakerMode) data?.clientId ?: sessionManager.getClientProfileId() else data?.id ?: 0

        sessionManager.saveSession(
            role = role,
            userId = userId,
            name = name,
            phone = phone,
            email = email,
            takerProfileId = takerProfileId,
            clientProfileId = clientProfileId,
            profileImageUrl = sessionManager.getProfileImageUrl(),
            profileThumbUrl = sessionManager.getProfileThumbUrl(),
            accessToken = sessionManager.getAccessToken(),
            refreshToken = sessionManager.getRefreshToken(),
        )
        toast(getString(R.string.register_success_sign_in))
        val dest = if (role == SessionManager.ROLE_TAKER) TakerMainActivity::class.java else MainActivity::class.java
        startActivity(Intent(this, dest).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun getAllLinks(): List<String> {
        val links = mutableListOf<String>()
        b.etPortfolioUrl.text?.toString()?.trim()?.let { if (it.isNotEmpty()) links.add(it) }
        val container = b.layoutExtraLinks
        for (i in 0 until container.childCount) {
            val et = container.getChildAt(i)?.findViewById<EditText>(android.R.id.text1)
            et?.text?.toString()?.trim()?.let { if (it.isNotEmpty()) links.add(it) }
        }
        return links
    }

    private fun addExtraLinkField() {
        val container = b.layoutExtraLinks
        if (container.childCount >= 2) {
            toast(getString(R.string.register_max_links))
            return
        }
        container.visibility = View.VISIBLE
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }
        }
        val et = EditText(this).apply {
            id = android.R.id.text1
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            hint = "https://..."
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            textSize = 14f
        }
        val btnRemove = android.widget.ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_delete)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { container.removeView(row) }
        }
        row.addView(et)
        row.addView(btnRemove)
        container.addView(row)
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingLocationSearch?.let(locationSearchHandler::removeCallbacks)
        phoneOtpTimer?.cancel()
        emailOtpTimer?.cancel()
    }
}
