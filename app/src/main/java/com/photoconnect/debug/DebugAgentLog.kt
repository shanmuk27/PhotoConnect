package com.photoconnect.debug

import android.util.Log
import com.photoconnect.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** NDJSON-style debug events → local ingest (debug builds only). No PII / secrets. */
object DebugAgentLog {
    private const val SESSION = "fc7f5c"
    private val executor = Executors.newSingleThreadExecutor()

    private val ingestUrls = listOf(
        "http://10.0.2.2:7785/ingest/f32fe264-b89e-46d6-8924-3909505b4054",
        "http://127.0.0.1:7785/ingest/f32fe264-b89e-46d6-8924-3909505b4054",
    )

    fun log(
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        if (!BuildConfig.DEBUG) return
        executor.execute {
            val payload = JSONObject().apply {
                put("sessionId", SESSION)
                put("hypothesisId", hypothesisId)
                put("location", location)
                put("message", message)
                put("timestamp", System.currentTimeMillis())
                put(
                    "data",
                    JSONObject().apply {
                        data.forEach { (k, v) ->
                            when (v) {
                                null -> put(k, JSONObject.NULL)
                                is Boolean -> put(k, v)
                                is Int -> put(k, v)
                                is Long -> put(k, v)
                                is Float -> put(k, v.toDouble())
                                is Double -> put(k, v)
                                else -> put(k, v.toString())
                            }
                        }
                    },
                )
            }
            val body = payload.toString().toByteArray(Charsets.UTF_8)
            Log.i(
                "DBG_FC7F5C",
                "$hypothesisId [$location] $message ${data.keys}",
            )
            for (urlStr in ingestUrls) {
                try {
                    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("X-Debug-Session-Id", SESSION)
                        connectTimeout = 4000
                        readTimeout = 4000
                        doOutput = true
                    }
                    conn.outputStream.use { it.write(body) }
                    conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    break
                } catch (_: Exception) {
                    continue
                }
            }
        }
    }
}
