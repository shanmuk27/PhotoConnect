package com.photoconnect.utils

/**
 * Tiny single-flight + freshness window for expensive server reads.
 *
 * It prevents duplicate identical requests while one is active, then keeps a
 * short success window where cached UI can remain on screen without hitting
 * the server again. Manual refresh can pass force = true.
 */
class FetchGate(private val ttlMs: Long) {
    private val inFlight = mutableSetOf<String>()
    private val lastSuccessAt = mutableMapOf<String, Long>()

    @Synchronized
    fun tryStart(key: String, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (key in inFlight) return false
        val lastSuccess = lastSuccessAt[key] ?: 0L
        if (!force && now - lastSuccess < ttlMs) return false
        inFlight.add(key)
        return true
    }

    @Synchronized
    fun finish(key: String, success: Boolean) {
        inFlight.remove(key)
        if (success) {
            lastSuccessAt[key] = System.currentTimeMillis()
        }
    }

    @Synchronized
    fun invalidate(prefix: String? = null) {
        if (prefix == null) {
            lastSuccessAt.clear()
            return
        }
        lastSuccessAt.keys.filter { it.startsWith(prefix) }.forEach(lastSuccessAt::remove)
    }
}
