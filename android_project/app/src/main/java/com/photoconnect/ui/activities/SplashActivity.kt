package com.photoconnect.ui.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.photoconnect.debug.DebugAgentLog
import com.photoconnect.utils.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@SuppressLint("CustomSplashScreen")
@AndroidEntryPoint
class SplashActivity : AppCompatActivity() {
    
    @Inject lateinit var session: SessionManager
    
    override fun onCreate(s: Bundle?) {
        installSplashScreen()
        super.onCreate(s)
        session.applyLocale()
        session.applyTheme()
        
        val dest = when {
            session.isTaker() -> TakerMainActivity::class.java
            session.isClient() || session.isGuest() -> MainActivity::class.java
            else -> LoginActivity::class.java
        }
        // #region agent log
        DebugAgentLog.log(
            hypothesisId = "H3",
            location = "SplashActivity.kt:onCreate",
            message = "splash_navigate",
            data = mapOf(
                "dest" to dest.simpleName,
                "isTaker" to session.isTaker(),
                "isGuest" to session.isGuest(),
                "isLoggedIn" to session.isLoggedIn(),
            ),
        )
        // #endregion
        
        val nextIntent = Intent(this, dest).apply {
            // BUG FIX: Carry over any deep link data, actions, or push notification extras
            // so they don't get destroyed when we clear the task.
            intent.extras?.let { putExtras(it) }
            action = intent.action
            data = intent.data
            
            // Clear the backstack so the user can't press 'back' to return to the splash screen
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        startActivity(nextIntent)
        finish()
    }
}
