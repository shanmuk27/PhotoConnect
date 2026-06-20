package com.photoconnect.ui.activities
import android.Manifest
import android.os.Bundle
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.photoconnect.R
import com.photoconnect.databinding.ActivityMainBinding
import com.photoconnect.repository.AuthRepository
import com.photoconnect.ui.fragments.BookingsFragment
import com.photoconnect.utils.AppTourManager
import com.photoconnect.utils.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var session: SessionManager
    @Inject lateinit var authRepository: AuthRepository
    private lateinit var b: ActivityMainBinding
    private val startupPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        syncPushToken()
        showTourIfAppropriate()
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        // Hilt injects `session` during super.onCreate; must not touch `session` before that.
        session.applyLocale()
        session.applyTheme()
        b = ActivityMainBinding.inflate(layoutInflater); setContentView(b.root)
        val nav = (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController
        b.bottomNav.setupWithNavController(nav)
        handleNavigationIntent()
        requestStartupPermissionsAndSyncToken()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent()
    }

    private fun handleNavigationIntent() {
        when {
            intent.getIntExtra(BookingsFragment.EXTRA_NAV_EVENT_ID, 0) > 0 -> b.bottomNav.selectedItemId = R.id.bookingsFragment
            intent.getIntExtra(BookingsFragment.EXTRA_NAV_BOOKING_ID, 0) > 0 -> b.bottomNav.selectedItemId = R.id.bookingsFragment
            intent.getBooleanExtra("NAV_TO_BOOKINGS", false) -> b.bottomNav.selectedItemId = R.id.bookingsFragment
            intent.getBooleanExtra("NAV_TO_HOME", false) -> b.bottomNav.selectedItemId = R.id.homeFragment
        }
    }

    private fun requestStartupPermissionsAndSyncToken() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        startupPermissionsLauncher.launch(permissions)
    }

    private fun showTourIfAppropriate() {
        val openedForSpecificContent = intent.getIntExtra(BookingsFragment.EXTRA_NAV_EVENT_ID, 0) > 0 ||
            intent.getIntExtra(BookingsFragment.EXTRA_NAV_BOOKING_ID, 0) > 0 ||
            intent.getBooleanExtra("NAV_TO_BOOKINGS", false) ||
            intent.getBooleanExtra("NAV_TO_HOME", false)
        if (openedForSpecificContent) return
        AppTourManager.showIfNeeded(
            this,
            if (session.isGuest()) AppTourManager.Role.GUEST else AppTourManager.Role.CLIENT,
            session.getActiveActorId().toString(),
        )
    }

    private fun syncPushToken() {
        if (!session.isLoggedIn()) return
        lifecycleScope.launch { authRepository.registerDeviceForPush() }
    }
}
