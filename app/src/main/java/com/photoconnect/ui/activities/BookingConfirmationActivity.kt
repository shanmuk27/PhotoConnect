package com.photoconnect.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.photoconnect.R
import com.photoconnect.databinding.ActivityBookingConfirmationBinding
import com.photoconnect.utils.SessionManager
import com.photoconnect.utils.toDisplayDate
import com.photoconnect.utils.toServiceLabel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BookingConfirmationActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ID = "booking_id"; const val EXTRA_NAME = "taker_name"
        const val EXTRA_DATE = "date"; const val EXTRA_SVC = "service"
    }

    private lateinit var b: ActivityBookingConfirmationBinding
    @Inject lateinit var session: SessionManager

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityBookingConfirmationBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvBookingId.text   = "#${intent.getIntExtra(EXTRA_ID, 0)}"
        b.tvTakerName.text   = intent.getStringExtra(EXTRA_NAME) ?: ""
        b.tvBookingDate.text = (intent.getStringExtra(EXTRA_DATE) ?: "").toDisplayDate()
        b.tvServiceType.text = (intent.getStringExtra(EXTRA_SVC) ?: "").toServiceLabel()

        b.btnBackHome.setOnClickListener {
            // Navigate to Bookings tab in MainActivity
            val intent = Intent(this, if (session.isTaker()) TakerMainActivity::class.java else MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("NAV_TO_BOOKINGS", true)
            }
            startActivity(intent)
            finish()
        }

        b.btnBrowseMore.setOnClickListener {
            val dest = if (session.isTaker()) TakerMainActivity::class.java else MainActivity::class.java
            startActivity(Intent(this, dest).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
            finish()
        }
    }
}
