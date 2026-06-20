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
import com.photoconnect.databinding.ActivityTakerMainBinding
import com.photoconnect.repository.AuthRepository
import com.photoconnect.ui.fragments.BookingsFragment
import com.photoconnect.utils.AppTourManager
import com.photoconnect.utils.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TakerMainActivity : AppCompatActivity() {
    @Inject lateinit var session: SessionManager
    @Inject lateinit var authRepository: AuthRepository
    private lateinit var b: ActivityTakerMainBinding
    private val startupPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        syncPushToken()
        showTourIfAppropriate()
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        session.applyLocale()
        session.applyTheme()
        b = ActivityTakerMainBinding.inflate(layoutInflater); setContentView(b.root)
        val nav = (supportFragmentManager.findFragmentById(R.id.nav_host_fragment_taker) as NavHostFragment).navController
        b.bottomNavTaker.setupWithNavController(nav)
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
            intent.getIntExtra(BookingsFragment.EXTRA_NAV_EVENT_ID, 0) > 0 -> b.bottomNavTaker.selectedItemId = R.id.bookingsFragment
            intent.getIntExtra(BookingsFragment.EXTRA_NAV_BOOKING_ID, 0) > 0 -> b.bottomNavTaker.selectedItemId = R.id.bookingsFragment
            intent.getBooleanExtra("NAV_TO_BOOKINGS", false) -> b.bottomNavTaker.selectedItemId = R.id.bookingsFragment
            intent.getBooleanExtra("NAV_TO_HOME", false) -> b.bottomNavTaker.selectedItemId = R.id.homeFragment
            else -> {
                b.bottomNavTaker.selectedItemId = session.consumeLastBottomNavItem(R.id.takerDashboardFragment)
            }
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
            AppTourManager.Role.CREATOR,
            session.getActiveActorId().toString(),
        )
    }

    private fun syncPushToken() {
        if (!session.isLoggedIn()) return
        lifecycleScope.launch { authRepository.registerDeviceForPush() }
    }
}
