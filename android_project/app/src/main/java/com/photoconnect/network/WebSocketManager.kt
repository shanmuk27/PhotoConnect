package com.photoconnect.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocketManager handles the persistent connection to the server for real-time events.
 */
class WebSocketManager(private val okHttpClient: OkHttpClient) {

    private var webSocket: WebSocket? = null
    private val isConnecting = AtomicBoolean(false)
    private var isConnected = false

    // You would typically get this from BuildConfig or inject it
    private val socketUrl = "wss://supriyadigitals.store/ws" 

    fun connect() {
        if (isConnected || isConnecting.getAndSet(true)) return

        val request = Request.Builder().url(socketUrl).build()
        
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                isConnecting.set(false)
                Log.d("WebSocketManager", "Connected to Real-time server")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("WebSocketManager", "Received: $text")
                // Handle incoming events (e.g., dispatch via EventBus, Flow, or LiveData)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                isConnecting.set(false)
                Log.d("WebSocketManager", "Disconnected: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                isConnecting.set(false)
                Log.e("WebSocketManager", "Connection failure", t)
                
                // Implement exponential backoff retry here in production
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "User requested disconnect")
        webSocket = null
        isConnected = false
        isConnecting.set(false)
    }

    fun sendMessage(message: String) {
        if (isConnected) {
            webSocket?.send(message)
        } else {
            Log.w("WebSocketManager", "Cannot send message. WebSocket is not connected.")
        }
    }
}
