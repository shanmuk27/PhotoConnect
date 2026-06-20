package com.photoconnect.ui.activities
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.photoconnect.R
import com.photoconnect.databinding.ActivityLoginBinding
import com.photoconnect.repository.Result
import com.photoconnect.utils.SessionManager
import com.photoconnect.utils.normalizeForLoginIdentity
import com.photoconnect.utils.hide
import com.photoconnect.utils.show
import com.photoconnect.utils.toast
import com.photoconnect.viewmodel.AuthViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {
    private lateinit var b: ActivityLoginBinding
    private val vm: AuthViewModel by viewModels()
    @Inject lateinit var session: SessionManager
    override fun onCreate(s: Bundle?) {
        super.onCreate(s); b = ActivityLoginBinding.inflate(layoutInflater); setContentView(b.root)
        b.btnLogin.setOnClickListener {
            val identity = (b.etPhone.text?.toString() ?: "").normalizeForLoginIdentity()
            val pass  = b.etPassword.text?.toString()?.trim() ?: ""
            if (identity.isEmpty() || pass.isEmpty()) { toast("Enter phone/email and password"); return@setOnClickListener }
            vm.loginAuto(identity, pass)
        }
        b.btnCreateAccount.setOnClickListener { startActivity(Intent(this, RegisterActivity::class.java)) }
        b.btnGuestBrowse.setOnClickListener {
            session.setGuest()
            startActivity(Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
        }
        vm.loginState.observe(this) { result ->
            when (result) {
                is Result.Loading -> { b.progressBar.show(); b.btnLogin.isEnabled = false }
                is Result.Success -> {
                    b.progressBar.hide(); b.btnLogin.isEnabled = true
                    val dest = if (session.isTaker()) TakerMainActivity::class.java else MainActivity::class.java
                    startActivity(Intent(this, dest).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
                }
                is Result.Error -> { b.progressBar.hide(); b.btnLogin.isEnabled = true; toast(result.message) }
            }
        }
    }
}
