package com.photoconnect.ui.activities
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.photoconnect.BuildConfig
import com.photoconnect.R
import com.photoconnect.databinding.ActivityLoginBinding
import com.photoconnect.databinding.DialogLoginSupportBinding
import com.photoconnect.debug.ErrorConsoleRecorder
import com.photoconnect.model.SubmitHelpTicketRequest
import com.photoconnect.network.PhotoConnectApiService
import com.photoconnect.model.OtpVerificationData
import com.photoconnect.repository.Result
import com.photoconnect.utils.SessionManager
import com.photoconnect.utils.LanguagePickerBottomSheet
import com.photoconnect.utils.isValidEmail
import com.photoconnect.utils.isValidPhone
import com.photoconnect.utils.normalizeForLoginIdentity
import com.photoconnect.utils.hide
import com.photoconnect.utils.show
import com.photoconnect.utils.toast
import com.photoconnect.viewmodel.AuthViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {
    private lateinit var b: ActivityLoginBinding
    private val vm: AuthViewModel by viewModels()
    @Inject lateinit var session: SessionManager
    @Inject lateinit var apiService: PhotoConnectApiService
    private var forgotDialog: AlertDialog? = null
    private var forgotIdentity = ""
    private var forgotUsesEmail = false
    private var forgotStatus: TextView? = null
    private var forgotIdentityLayout: TextInputLayout? = null
    private var forgotIdentityField: TextInputEditText? = null
    private var forgotOtpField: TextInputEditText? = null
    private var forgotOtpLayout: TextInputLayout? = null
    private var forgotNewPasswordLayout: TextInputLayout? = null
    private var forgotConfirmPasswordLayout: TextInputLayout? = null
    private var forgotResetFields: View? = null
    private var forgotSendButton: MaterialButton? = null
    private var forgotResetButton: MaterialButton? = null
    private var forgotProgress: View? = null
    private var forgotOtpVerified = false
    private var forgotOtpVerifying = false
    private var forgotLastVerifiedOtp = ""
    private var forgotPhoneCooldownTimer: CountDownTimer? = null
    private var forgotPhoneCooldownActive = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s); b = ActivityLoginBinding.inflate(layoutInflater); setContentView(b.root)
        b.btnLanguage.setOnClickListener {
            LanguagePickerBottomSheet.show(this) { recreate() }
        }
        b.btnLogin.setOnClickListener {
            val identity = (b.etPhone.text?.toString() ?: "").normalizeForLoginIdentity()
            val pass  = b.etPassword.text?.toString()?.trim() ?: ""
            b.tilLoginIdentity.error = null
            b.tilLoginPassword.error = null
            if (identity.isEmpty()) {
                b.tilLoginIdentity.error = getString(R.string.login_identity_required)
                return@setOnClickListener
            }
            if (pass.isEmpty()) {
                b.tilLoginPassword.error = getString(R.string.login_password_required)
                return@setOnClickListener
            }
            vm.loginAuto(identity, pass)
        }
        b.btnCreateAccount.setOnClickListener { startActivity(Intent(this, RegisterActivity::class.java)) }
        b.btnGuestBrowse.setOnClickListener {
            session.setGuest()
            startActivity(Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
        }
        b.tvForgotPassword.setOnClickListener { showForgotPasswordDialog() }
        b.btnLoginSupport.setOnClickListener { showLoginSupportDialog() }
        
        b.btnGoogleLogin.setOnClickListener { startGoogleSignIn() }

        vm.loginState.observe(this) { result ->
            when (result) {
                is Result.Loading -> { b.progressBar.show(); b.btnLogin.isEnabled = false; b.btnGoogleLogin.isEnabled = false }
                is Result.Success -> {
                    b.progressBar.hide(); b.btnLogin.isEnabled = true; b.btnGoogleLogin.isEnabled = true
                    
                    val data = result.data
                    fun profileString(profile: Map<String, Any?>?, key: String): String? =
                        profile?.get(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                    val firstTaker = data.profiles.takers.firstOrNull()
                    val firstClient = data.profiles.clients.firstOrNull()
                    val displayName = data.user.name?.takeIf { it.isNotBlank() }
                        ?: profileString(firstTaker, "full_name")
                        ?: profileString(firstClient, "name")
                        ?: ""
                    val profileImageUrl = profileString(firstTaker ?: firstClient, "profile_image_url")
                    val profileThumbUrl = profileString(firstTaker ?: firstClient, "profile_thumb_url")
                    session.saveSession(
                        role = "auto", // the user will need to select if they have multiple, but for now just auto
                        userId = data.user.id,
                        name = displayName,
                        phone = data.user.phone ?: "",
                        email = data.user.email ?: "",
                        takerProfileId = firstTaker?.get("id")?.toString()?.toDoubleOrNull()?.toInt() ?: 0,
                        clientProfileId = firstClient?.get("id")?.toString()?.toDoubleOrNull()?.toInt() ?: 0,
                        profileImageUrl = profileImageUrl,
                        profileThumbUrl = profileThumbUrl,
                        accessToken = data.accessToken,
                        refreshToken = data.refreshToken,
                    )
                    
                    if (data.requiresMoreInfo) {
                        // Registration flow: they need to provide additional details
                        val intent = Intent(this, RegisterActivity::class.java).apply {
                            putExtra("is_google_signup", true)
                        }
                        startActivity(intent)
                    } else {
                        // User has profiles. Let's auto route them
                        if (data.profiles.takers.isNotEmpty()) {
                            session.setActiveRole("taker")
                        } else if (data.profiles.clients.isNotEmpty()) {
                            session.setActiveRole("client")
                        } else {
                            session.setActiveRole("client")
                        }
                        
                        if (session.isTaker()) {
                            com.photoconnect.workers.PostUploadWorker.resumePendingUploads(applicationContext)
                        }
                        val dest = if (session.isTaker()) TakerMainActivity::class.java else MainActivity::class.java
                        startActivity(Intent(this, dest).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
                    }
                }
                is Result.Error -> {
                    b.progressBar.hide()
                    b.btnLogin.isEnabled = true
                    b.btnGoogleLogin.isEnabled = true
                    when (result.field) {
                        "email", "phone", "identity" -> b.tilLoginIdentity.error = result.message
                        "password" -> b.tilLoginPassword.error = result.message
                        else -> toast(result.message)
                    }
                }
            }
        }
        setupForgotPasswordObservers()
    }

    private val googleSignInLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                account?.idToken?.let { idToken ->
                    vm.googleLogin(idToken)
                } ?: run {
                    toast(getString(R.string.google_sign_in_token_missing))
                }
            } catch (e: Exception) {
                toast(getString(R.string.google_sign_in_failed, e.message ?: getString(R.string.error_unknown)))
            }
        }
    }

    private fun startGoogleSignIn() {
        val serverClientId = getString(R.string.google_server_client_id).trim()
        if (serverClientId.isBlank() || serverClientId == "YOUR_SERVER_CLIENT_ID") {
            toast(getString(R.string.google_sign_in_not_configured))
            return
        }
        val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(serverClientId)
            .requestEmail()
            .build()
        val googleSignInClient = com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(this, gso)
        googleSignInClient.signOut().addOnCompleteListener {
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }
    }

    private fun showForgotPasswordDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_forgot_password, null)
        val etIdentity = view.findViewById<TextInputEditText>(R.id.etResetIdentity)
        val tilIdentity = view.findViewById<TextInputLayout>(R.id.tilResetIdentity)
        val etOtp = view.findViewById<TextInputEditText>(R.id.etResetOtp)
        val tilOtp = view.findViewById<TextInputLayout>(R.id.tilResetOtp)
        val etNewPassword = view.findViewById<TextInputEditText>(R.id.etResetNewPassword)
        val tilNewPassword = view.findViewById<TextInputLayout>(R.id.tilResetNewPassword)
        val etConfirmPassword = view.findViewById<TextInputEditText>(R.id.etResetConfirmPassword)
        val tilConfirmPassword = view.findViewById<TextInputLayout>(R.id.tilResetConfirmPassword)
        forgotStatus = view.findViewById(R.id.tvResetStatus)
        forgotIdentityField = etIdentity
        forgotIdentityLayout = tilIdentity
        forgotOtpField = etOtp
        forgotOtpLayout = tilOtp
        forgotNewPasswordLayout = tilNewPassword
        forgotConfirmPasswordLayout = tilConfirmPassword
        forgotResetFields = view.findViewById(R.id.layoutResetFields)
        forgotSendButton = view.findViewById(R.id.btnSendResetOtp)
        forgotResetButton = view.findViewById(R.id.btnResetPassword)
        forgotProgress = view.findViewById(R.id.progressResetPassword)

        etIdentity.setText(b.etPhone.text?.toString()?.trim().orEmpty())
        forgotResetFields?.visibility = View.GONE
        forgotResetButton?.isEnabled = false
        forgotStatus?.text = getString(R.string.forgot_password_body)

        etOtp.addTextChangedListener { text ->
            val otp = text?.toString()?.trim().orEmpty()
            if (otp != forgotLastVerifiedOtp) {
                forgotOtpVerified = false
                forgotResetButton?.isEnabled = false
            }
            if (otp.length == 6 && forgotIdentity.isNotBlank() && !forgotOtpVerified && !forgotOtpVerifying) {
                forgotOtpVerifying = true
                setForgotLoading(true, getString(R.string.reset_password_verifying_code))
                if (forgotUsesEmail) {
                    vm.verifyEmailOtp(forgotIdentity, otp)
                } else {
                    vm.verifyOtp(forgotIdentity, otp)
                }
            }
        }

        forgotSendButton?.setOnClickListener {
            val rawIdentity = etIdentity.text?.toString()?.trim().orEmpty()
            val identity = rawIdentity.normalizeForLoginIdentity()
            stopForgotPhoneCooldown()
            if (rawIdentity.contains("@")) {
                if (!rawIdentity.isValidEmail()) {
                    forgotIdentityLayout?.error = getString(R.string.error_email)
                    return@setOnClickListener
                }
                forgotIdentity = rawIdentity
                forgotUsesEmail = true
                vm.sendEmailOtp(rawIdentity, purpose = "reset")
            } else {
                if (!identity.isValidPhone()) {
                    forgotIdentityLayout?.error = getString(R.string.error_phone)
                    return@setOnClickListener
                }
                forgotIdentity = identity
                forgotUsesEmail = false
                vm.sendOtp(identity, purpose = "reset")
            }
            forgotIdentityLayout?.error = null
            etIdentity.isEnabled = false
            forgotOtpLayout?.isEnabled = true
            etOtp.isEnabled = true
            forgotOtpLayout?.error = null
            forgotOtpVerified = false
            forgotLastVerifiedOtp = ""
        }

        forgotResetButton?.setOnClickListener {
            val otp = etOtp.text?.toString()?.trim().orEmpty()
            val password = etNewPassword.text?.toString()?.trim().orEmpty()
            val confirm = etConfirmPassword.text?.toString()?.trim().orEmpty()
            
            forgotOtpLayout?.error = null
            forgotNewPasswordLayout?.error = null
            forgotConfirmPasswordLayout?.error = null
            
            if (otp.length != 6) {
                forgotOtpLayout?.error = getString(R.string.reset_otp_hint)
                return@setOnClickListener
            }
            if (!forgotOtpVerified) {
                forgotOtpLayout?.error = getString(R.string.reset_password_verify_code_first)
                return@setOnClickListener
            }
            if (password.length < 6) {
                forgotNewPasswordLayout?.error = getString(R.string.error_password_short)
                return@setOnClickListener
            }
            if (password != confirm) {
                forgotConfirmPasswordLayout?.error = getString(R.string.error_passwords_no_match)
                return@setOnClickListener
            }
            vm.resetPassword(forgotIdentity, otp, password)
        }

            forgotDialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    stopForgotPhoneCooldown()
                    forgotDialog = null
                    forgotStatus = null
                    forgotIdentityField = null
                    forgotIdentityLayout = null
                    forgotOtpField = null
                    forgotOtpLayout = null
                    forgotNewPasswordLayout = null
                    forgotConfirmPasswordLayout = null
                    forgotResetFields = null
                    forgotSendButton = null
                    forgotResetButton = null
                    forgotProgress = null
                }
                dialog.show()
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
    }

    private fun showLoginSupportDialog() {
        val supportBinding = DialogLoginSupportBinding.inflate(layoutInflater)
        supportBinding.etSupportContact.setText(b.etPhone.text?.toString()?.trim().orEmpty())
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.login_support_title)
            .setView(supportBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.help_support_send, null)
            .create()

        fun setLoading(loading: Boolean) {
            supportBinding.progressSupport.visibility = if (loading) View.VISIBLE else View.GONE
            supportBinding.etSupportContact.isEnabled = !loading
            supportBinding.etSupportProblem.isEnabled = !loading
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !loading
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !loading
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rawContact = supportBinding.etSupportContact.text?.toString()?.trim().orEmpty()
                val normalizedContact = if (rawContact.contains("@")) rawContact else rawContact.normalizeForLoginIdentity()
                val problem = supportBinding.etSupportProblem.text?.toString()?.trim().orEmpty()
                supportBinding.tilSupportContact.error = null
                supportBinding.tilSupportProblem.error = null

                if (normalizedContact.isBlank()) {
                    supportBinding.tilSupportContact.error = getString(R.string.login_support_contact_required)
                    return@setOnClickListener
                }
                val validContact = if (normalizedContact.contains("@")) {
                    normalizedContact.isValidEmail()
                } else {
                    normalizedContact.isValidPhone()
                }
                if (!validContact) {
                    supportBinding.tilSupportContact.error = getString(R.string.login_support_contact_invalid)
                    return@setOnClickListener
                }
                if (problem.isBlank()) {
                    supportBinding.tilSupportProblem.error = getString(R.string.login_support_problem_required)
                    return@setOnClickListener
                }

                setLoading(true)
                lifecycleScope.launch {
                    try {
                        val logs = withContext(Dispatchers.IO) { buildLoginSupportLogs(normalizedContact) }
                        val response = apiService.submitHelpTicket(
                            SubmitHelpTicketRequest(
                                phone = normalizedContact,
                                problem = problem,
                                logs = logs,
                            )
                        )
                        val body = response.body()
                        if (response.isSuccessful && body?.success == true) {
                            toast(getString(R.string.login_support_sent))
                            dialog.dismiss()
                        } else {
                            setLoading(false)
                            toast(body?.message ?: getString(R.string.login_support_failed))
                        }
                    } catch (e: Exception) {
                        ErrorConsoleRecorder.e("LoginSupport", "Failed to submit login support ticket", e)
                        setLoading(false)
                        toast(getString(R.string.login_support_failed))
                    }
                }
            }
        }
        dialog.show()
    }

    private fun buildLoginSupportLogs(contact: String): String {
        val latestLogs = sanitizeSupportLogs(ErrorConsoleRecorder.readAll(this).takeLast(8000))
        return buildString {
            appendLine("Source: login support")
            appendLine("Contact: $contact")
            appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
            appendLine()
            append(latestLogs)
        }
    }

    private fun sanitizeSupportLogs(raw: String): String =
        raw
            .replace(Regex("Bearer\\s+[A-Za-z0-9._-]+"), "Bearer [redacted]")
            .replace(Regex("(?i)(password|otp|token|authorization)\\s*[:=]\\s*[^\\s,}]+")) { match ->
                "${match.groupValues[1]}=[redacted]"
            }

    private fun setupForgotPasswordObservers() {
        vm.sendOtpState.observe(this) { result ->
            if (forgotDialog?.isShowing == true && !forgotUsesEmail) handleForgotOtpResult(result)
        }
        vm.sendEmailOtpState.observe(this) { result ->
            if (forgotDialog?.isShowing == true && forgotUsesEmail) handleForgotOtpResult(result)
        }
        vm.verifyOtpState.observe(this) { result ->
            if (forgotDialog?.isShowing == true && !forgotUsesEmail) handleForgotVerifyResult(result)
        }
        vm.verifyEmailOtpState.observe(this) { result ->
            if (forgotDialog?.isShowing == true && forgotUsesEmail) handleForgotVerifyResult(result)
        }
        vm.resetPasswordState.observe(this) { result ->
            if (forgotDialog?.isShowing != true) return@observe
            when (result) {
                is Result.Loading -> setForgotLoading(true, getString(R.string.reset_password_updating))
                is Result.Success -> {
                    setForgotLoading(false, getString(R.string.reset_password_success))
                    forgotDialog?.dismiss()
                    b.etPassword.setText("")
                    toast(getString(R.string.reset_password_success))
                }
                is Result.Error -> setForgotLoading(false, result.message)
            }
        }
    }

    private fun handleForgotOtpResult(result: Result<*>) {
        when (result) {
            is Result.Loading -> setForgotLoading(true, getString(R.string.reset_password_sending_code))
            is Result.Success -> {
                stopForgotPhoneCooldown()
                setForgotLoading(false, getString(R.string.reset_code_sent))
                forgotIdentityField?.isEnabled = false
                forgotResetFields?.visibility = View.VISIBLE
                forgotOtpLayout?.isEnabled = true
                forgotOtpField?.isEnabled = true
                forgotResetButton?.isEnabled = false
            }
            is Result.Error -> {
                forgotIdentityField?.isEnabled = true
                setForgotLoading(false, result.message)
                val retryAfter = result.retryAfterSeconds
                if (!forgotUsesEmail && result.code == 429 && retryAfter != null && retryAfter > 0) {
                    startForgotPhoneCooldown(retryAfter)
                }
            }
        }
    }

    private fun startForgotPhoneCooldown(seconds: Int) {
        forgotPhoneCooldownTimer?.cancel()
        forgotPhoneCooldownActive = true
        forgotSendButton?.isEnabled = false
        forgotPhoneCooldownTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val remaining = ((millisUntilFinished + 999L) / 1000L).toInt().coerceAtLeast(1)
                forgotStatus?.text = getString(R.string.otp_cooldown_timer, remaining / 60, remaining % 60)
                forgotSendButton?.isEnabled = false
            }

            override fun onFinish() {
                forgotPhoneCooldownActive = false
                forgotSendButton?.isEnabled = forgotProgress?.visibility != View.VISIBLE
                forgotStatus?.text = getString(R.string.forgot_password_body)
            }
        }.start()
    }

    private fun stopForgotPhoneCooldown() {
        forgotPhoneCooldownTimer?.cancel()
        forgotPhoneCooldownTimer = null
        forgotPhoneCooldownActive = false
    }

    private fun handleForgotVerifyResult(result: Result<OtpVerificationData>) {
        forgotOtpVerifying = result is Result.Loading
        when (result) {
            is Result.Loading -> setForgotLoading(true, getString(R.string.reset_password_verifying_code))
            is Result.Success -> {
                forgotOtpVerified = result.data.verified && !result.data.verificationToken.isNullOrBlank()
                if (!forgotOtpVerified) {
                    forgotLastVerifiedOtp = ""
                    forgotOtpField?.isEnabled = true
                    forgotOtpLayout?.isEnabled = true
                    forgotOtpLayout?.error = getString(R.string.invalid_otp)
                    setForgotLoading(false, getString(R.string.invalid_otp))
                    return
                }
                forgotLastVerifiedOtp = forgotOtpField?.text?.toString()?.trim().orEmpty()
                forgotOtpLayout?.error = null
                forgotOtpField?.clearFocus()
                forgotOtpField?.isEnabled = false
                forgotOtpLayout?.isEnabled = false
                setForgotLoading(false, getString(R.string.reset_password_code_verified))
            }
            is Result.Error -> {
                forgotOtpVerified = false
                forgotLastVerifiedOtp = ""
                forgotOtpField?.isEnabled = true
                forgotOtpLayout?.isEnabled = true
                forgotOtpLayout?.error = result.message
                setForgotLoading(false, result.message)
            }
        }
    }

    private fun setForgotLoading(loading: Boolean, message: String) {
        forgotProgress?.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading && forgotIdentity.isNotBlank()) {
            forgotIdentityField?.isEnabled = false
        }
        forgotSendButton?.isEnabled = !loading && !forgotPhoneCooldownActive
        forgotResetButton?.isEnabled = !loading && forgotResetFields?.visibility == View.VISIBLE && forgotOtpVerified
        forgotStatus?.text = message
    }
}
