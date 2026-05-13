package com.photoconnect.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.photoconnect.R
import com.photoconnect.databinding.ActivityRegisterBinding
import com.photoconnect.model.PostOffice
import com.photoconnect.model.RegisterClientRequest
import com.photoconnect.model.RegisterTakerRequest
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
    private var locationLookup = emptyMap<String, PostOffice>()
    private var awaitingIdentityCheck = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener {
            if (currentStep > 1) moveBack() else finish()
        }

        setupSteps()
        setupListeners()
        setupObservers()
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
            if (!applyingLocation && pin.length == 6) {
                autoApplyLocation = true
                pincodeVm.lookup(pin)
            }
        }

        b.etCity.addTextChangedListener { text ->
            if (!applyingLocation && (text?.toString()?.trim()?.length ?: 0) >= 3) {
                autoApplyLocation = false
                pincodeVm.searchPlace(text.toString())
            }
        }
        b.etArea.addTextChangedListener { text ->
            if (!applyingLocation && (text?.toString()?.trim()?.length ?: 0) >= 3 && b.etPincode.text?.length != 6) {
                autoApplyLocation = false
                pincodeVm.searchPlace(text.toString())
            }
        }

        b.etArea.setOnItemClickListener { parent, _, position, _ ->
            locationLookup[parent.getItemAtPosition(position).toString()]?.let { applyPostOffice(it) }
        }
        b.etCity.setOnItemClickListener { parent, _, position, _ ->
            locationLookup[parent.getItemAtPosition(position).toString()]?.let { applyPostOffice(it) }
        }
    }

    private fun setupObservers() {
        pincodeVm.result.observe(this) { offices ->
            if (offices.isNotEmpty()) {
                bindLocationSuggestions(offices)
                if (autoApplyLocation) {
                    val selected = pickBestPostOffice(offices)
                    applyPostOffice(selected)
                } else {
                    b.etCity.showDropDown()
                }
                b.etPincode.error = null
            } else if ((b.etPincode.text?.length ?: 0) == 6) {
                b.etPincode.error = "Not found"
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
                    b.etPhone.error = null
                    b.etEmail.error = null
                    val d = r.data
                    if (d.phoneRegistered) {
                        b.etPhone.error = getString(R.string.identity_phone_in_use)
                        toast(getString(R.string.identity_phone_in_use))
                        return@observe
                    }
                    val em = b.etEmail.text?.toString()?.trim().orEmpty()
                    if (em.isNotEmpty() && d.emailRegistered) {
                        b.etEmail.error = getString(R.string.identity_email_in_use)
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
    }

    private fun bindLocationSuggestions(offices: List<PostOffice>) {
        val labels = offices.associateBy { it.toSuggestionLabel() }
        locationLookup = labels
        val adapter = ArrayAdapter(this, R.layout.item_dropdown, labels.keys.toList())
        b.etArea.setAdapter(adapter)
        b.etCity.setAdapter(adapter)
    }

    private fun pickBestPostOffice(offices: List<PostOffice>): PostOffice =
        offices.firstOrNull { it.branchType?.contains("Head", ignoreCase = true) == true }
            ?: offices.firstOrNull { !it.block.isNullOrBlank() }
            ?: offices.first()

    private fun applyPostOffice(po: PostOffice) {
        applyingLocation = true
        val cityName = po.block?.takeIf { it.isNotBlank() } ?: po.name.substringBefore("(").trim()
        b.etArea.setText(po.name, false)
        b.etCity.setText(cityName, false)
        b.etState.setText(po.state)
        b.etPincode.setText(po.pincode)
        applyingLocation = false
    }

    private fun PostOffice.toSuggestionLabel(): String =
        "$name, ${block?.takeIf { it.isNotBlank() } ?: district} - $pincode"

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

    private fun updateStepUI() {
        val maxSteps = if (isTakerMode) 5 else 3
        b.stepProgress.progress = (currentStep * 100) / maxSteps

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
                if (name.isEmpty()) { toast("Enter your name"); return false }
                if (!phone.isValidPhone()) { toast("Enter a valid 10-digit phone"); return false }
                if (email.isNotEmpty() && !email.isValidEmail()) { toast("Enter a valid email"); return false }
            }
            3 -> {
                val pass = b.etPassword.text?.toString()?.trim() ?: ""
                val pass2 = b.etConfirmPassword.text?.toString()?.trim() ?: ""
                if (pass.length < 6) { toast("Password must be 6+ characters"); return false }
                if (pass != pass2) { toast("Passwords do not match"); return false }
            }
            4 -> {
                val pincode = b.etPincode.text?.toString()?.trim() ?: ""
                val area = b.etArea.text?.toString()?.trim() ?: ""
                val city = b.etCity.text?.toString()?.trim() ?: ""
                val state = b.etState.text?.toString()?.trim() ?: ""
                if (pincode.length != 6) { toast("Enter a valid 6-digit pincode"); return false }
                if (area.isEmpty()) { toast("Area / locality is required"); return false }
                if (city.isEmpty()) { toast("City is required"); return false }
                if (state.isEmpty()) { toast("State is required"); return false }
            }
            5 -> {
                if (selectedServiceTypes.isEmpty()) { toast("Select at least one service"); return false }
            }
        }
        return true
    }

    private fun attemptRegister() {
        val name  = b.etFullName.text?.toString()?.trim() ?: ""
        val phone = b.etPhone.text?.toString()?.trim() ?: ""
        val email = b.etEmail.text?.toString()?.trim() ?: ""
        val pass  = b.etPassword.text?.toString()?.trim() ?: ""

        if (isTakerMode) {
            val pincode = b.etPincode.text?.toString()?.trim() ?: ""
            val area    = b.etArea.text?.toString()?.trim() ?: ""
            val city    = b.etCity.text?.toString()?.trim() ?: ""
            val state   = b.etState.text?.toString()?.trim() ?: ""

            authVm.registerTaker(RegisterTakerRequest(name, phone, email, pass,
                pincode, area.ifEmpty { city }, city, state,
                selectedServiceTypes.toList(),
                b.etYearsExp.text?.toString()?.trim()?.toIntOrNull() ?: 0,
                b.etLanguagesReg.text?.toString()?.trim()?.ifEmpty { null }, 
                null, null,
                b.etPortfolioUrl.text?.toString()?.trim()?.ifEmpty { null },
            ))
        } else {
            authVm.registerClient(RegisterClientRequest(name, phone, email.ifEmpty { null }, pass))
        }
    }

    private fun handleResult(r: Result<*>) {
        when (r) {
            is Result.Loading -> { b.progressBar.show(); b.btnNext.isEnabled = false }
            is Result.Success -> {
                b.progressBar.hide()
                toast("Registered! Please sign in.")
                startActivity(Intent(this, LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
                finish()
            }
            is Result.Error -> { b.progressBar.hide(); b.btnNext.isEnabled = true; toast(r.message) }
        }
    }
}
