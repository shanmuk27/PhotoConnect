package com.photoconnect.ui.activities
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.photoconnect.R
import com.photoconnect.databinding.ActivityTakerMainBinding
import com.photoconnect.utils.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TakerMainActivity : AppCompatActivity() {
    @Inject lateinit var session: SessionManager
    private lateinit var b: ActivityTakerMainBinding
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        session.applyTheme()
        b = ActivityTakerMainBinding.inflate(layoutInflater); setContentView(b.root)
        val nav = (supportFragmentManager.findFragmentById(R.id.nav_host_fragment_taker) as NavHostFragment).navController
        b.bottomNavTaker.setupWithNavController(nav)
    }
}
