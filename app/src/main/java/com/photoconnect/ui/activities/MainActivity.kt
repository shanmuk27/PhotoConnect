package com.photoconnect.ui.activities
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.photoconnect.R
import com.photoconnect.databinding.ActivityMainBinding
import com.photoconnect.utils.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var session: SessionManager
    private lateinit var b: ActivityMainBinding
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        // Hilt injects `session` during super.onCreate; must not touch `session` before that.
        session.applyTheme()
        b = ActivityMainBinding.inflate(layoutInflater); setContentView(b.root)
        val nav = (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController
        b.bottomNav.setupWithNavController(nav)
    }
}
