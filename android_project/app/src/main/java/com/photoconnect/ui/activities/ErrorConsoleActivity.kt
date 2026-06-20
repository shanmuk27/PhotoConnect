package com.photoconnect.ui.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.photoconnect.R
import com.photoconnect.databinding.ActivityErrorConsoleBinding
import com.photoconnect.debug.ErrorConsoleRecorder
import com.photoconnect.utils.toast

class ErrorConsoleActivity : AppCompatActivity() {

    private lateinit var b: ActivityErrorConsoleBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityErrorConsoleBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setNavigationOnClickListener { finish() }

        b.btnRefresh.setOnClickListener { refresh() }
        b.btnCopy.setOnClickListener { copyAll() }
        b.btnClear.setOnClickListener {
            ErrorConsoleRecorder.clear(this)
            refresh()
            toast(getString(R.string.error_console_log_cleared))
        }

        refresh()
    }

    private fun refresh() {
        val log = ErrorConsoleRecorder.readAll(this)
        b.tvLog.text = if (log == "(empty log)") getString(R.string.error_console_empty) else log
    }

    private fun copyAll() {
        val text = b.tvLog.text?.toString().orEmpty()
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PhotoConnect errors", text))
        Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }
}
