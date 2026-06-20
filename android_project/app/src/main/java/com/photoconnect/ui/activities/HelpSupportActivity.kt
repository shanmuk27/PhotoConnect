package com.photoconnect.ui.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.photoconnect.R
import com.photoconnect.databinding.ActivityHelpSupportBinding
import com.photoconnect.databinding.ItemHelpTicketBinding
import com.photoconnect.db.HelpTicketDao
import com.photoconnect.db.HelpTicketEntity
import com.photoconnect.debug.ErrorConsoleRecorder
import com.photoconnect.model.SubmitHelpTicketRequest
import com.photoconnect.network.PhotoConnectApiService
import com.photoconnect.utils.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class HelpSupportActivity : AppCompatActivity() {

    private lateinit var b: ActivityHelpSupportBinding
    private val adapter = HelpTicketAdapter()

    @Inject lateinit var helpTicketDao: HelpTicketDao
    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var apiService: PhotoConnectApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHelpSupportBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setNavigationOnClickListener { finish() }

        b.rvPreviousRequests.layoutManager = LinearLayoutManager(this)
        b.rvPreviousRequests.adapter = adapter

        helpTicketDao.getAllTickets().observe(this) { tickets ->
            adapter.submitList(tickets)
            b.tvEmptyState.isVisible = tickets.isEmpty()
        }

        fetchTicketsFromServer()

        b.btnSend.setOnClickListener {
            val problem = b.inputProblem.editText?.text?.toString()?.trim()
            if (problem.isNullOrEmpty()) {
                b.inputProblem.error = "Please describe your problem"
                return@setOnClickListener
            }
            b.inputProblem.error = null

            lifecycleScope.launch {
                val ticket = HelpTicketEntity(message = problem)
                helpTicketDao.insert(ticket)

                b.inputProblem.editText?.setText("")
                
                android.widget.Toast.makeText(
                    this@HelpSupportActivity,
                    "Your request has been sent to our support team.",
                    android.widget.Toast.LENGTH_LONG
                ).show()

                // Send to backend
                val phone = sessionManager.getUserPhone()
                val logs = gatherLogs()
                sendToBackend(problem, phone, logs)
            }
        }
    }

    private fun fetchTicketsFromServer() {
        lifecycleScope.launch {
            try {
                val response = apiService.getHelpTickets()
                if (response.isSuccessful) {
                    val tickets = response.body()?.data?.tickets ?: emptyList()
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    
                    // Convert server tickets to local entities
                    val entities = tickets.map {
                        val timestamp = try {
                            dateFormat.parse(it.createdAt)?.time ?: System.currentTimeMillis()
                        } catch (e: Exception) {
                            System.currentTimeMillis()
                        }
                        HelpTicketEntity(id = it.id, message = it.problem, timestamp = timestamp)
                    }

                    // Replace local tickets with server tickets (to keep in sync)
                    helpTicketDao.deleteAll()
                    entities.forEach { helpTicketDao.insert(it) }
                }
            } catch (e: Exception) {
                android.util.Log.e("HelpSupport", "Failed to fetch tickets", e)
            }
        }
    }

    private suspend fun gatherLogs(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        sanitizeSupportLogs(ErrorConsoleRecorder.readAll(this@HelpSupportActivity).takeLast(8000))
    }

    private fun sanitizeSupportLogs(raw: String): String =
        raw
            .replace(Regex("Bearer\\s+[A-Za-z0-9._-]+"), "Bearer [redacted]")
            .replace(Regex("(?i)(password|otp|token|authorization)\\s*[:=]\\s*[^\\s,}]+")) { match ->
                "${match.groupValues[1]}=[redacted]"
            }

    private suspend fun sendToBackend(problem: String, phone: String, logs: String) {
        try {
            val request = SubmitHelpTicketRequest(
                phone = phone,
                problem = problem,
                logs = logs
            )
            val response = apiService.submitHelpTicket(request)
            if (response.isSuccessful) {
                android.util.Log.d("HelpSupport", "Ticket submitted successfully")
            } else {
                android.util.Log.e("HelpSupport", "Failed to submit ticket: ${response.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            android.util.Log.e("HelpSupport", "Exception sending ticket", e)
        }
    }

    inner class HelpTicketAdapter : RecyclerView.Adapter<HelpTicketAdapter.VH>() {
        private var tickets = listOf<HelpTicketEntity>()
        private val dateFormat = SimpleDateFormat("MMM dd, yyyy - hh:mm a", Locale.getDefault())

        fun submitList(list: List<HelpTicketEntity>) {
            tickets = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemHelpTicketBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ticket = tickets[position]
            holder.binding.tvTicketMessage.text = ticket.message
            holder.binding.tvTicketDate.text = dateFormat.format(Date(ticket.timestamp))
        }

        override fun getItemCount() = tickets.size

        inner class VH(val binding: ItemHelpTicketBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
