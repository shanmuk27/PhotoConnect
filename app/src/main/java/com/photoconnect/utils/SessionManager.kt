package com.photoconnect.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val PREFS = "pc_session"
        private const val KEY_ROLE = "role"
        private const val KEY_ID = "user_id"
        private const val KEY_NAME = "user_name"
        private const val KEY_PHONE = "user_phone"
        private const val KEY_EMAIL = "user_email"
        private const val KEY_CLIENT_PROFILE_ID = "client_profile_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_GUEST = "is_guest"
        private const val KEY_DARK = "dark_mode"

        const val ROLE_TAKER = "taker"
        const val ROLE_CLIENT = "client"

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun saveSession(
        role: String,
        userId: Int,
        name: String,
        phone: String,
        email: String = "",
        clientProfileId: Int = if (role == ROLE_CLIENT) userId else 0,
        accessToken: String,
        refreshToken: String,
    ) {
        prefs.edit()
            .putString(KEY_ROLE, role)
            .putInt(KEY_ID, userId)
            .putString(KEY_NAME, name)
            .putString(KEY_PHONE, phone)
            .putString(KEY_EMAIL, email)
            .putInt(KEY_CLIENT_PROFILE_ID, clientProfileId)
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putBoolean(KEY_GUEST, false)
            .apply()
    }

    fun saveTokens(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putBoolean(KEY_GUEST, false)
            .apply()
    }

    fun setGuest() {
        val darkMode = getDarkMode()
        prefs.edit()
            .clear()
            .putBoolean(KEY_GUEST, true)
            .putInt(KEY_DARK, darkMode)
            .apply()
    }

    fun isGuest() = prefs.getBoolean(KEY_GUEST, false)
    fun isLoggedIn() = getRole().isNotBlank() && getAccessToken().isNotBlank() && !isGuest()
    fun getRole() = prefs.getString(KEY_ROLE, "") ?: ""
    fun getUserId() = prefs.getInt(KEY_ID, 0)
    fun getUserName() = prefs.getString(KEY_NAME, "") ?: ""
    fun getUserPhone() = prefs.getString(KEY_PHONE, "") ?: ""
    fun getUserEmail() = prefs.getString(KEY_EMAIL, "") ?: ""
    fun getClientProfileId() = prefs.getInt(KEY_CLIENT_PROFILE_ID, 0)
    fun getAccessToken() = prefs.getString(KEY_ACCESS_TOKEN, "") ?: ""
    fun getRefreshToken() = prefs.getString(KEY_REFRESH_TOKEN, "") ?: ""
    fun isTaker() = isLoggedIn() && getRole() == ROLE_TAKER
    fun isClient() = isLoggedIn() && getRole() == ROLE_CLIENT
    fun hasClientFeatures() = isLoggedIn() && getClientProfileId() != 0

    fun getRoleLabel() = when {
        isTaker() && hasClientFeatures() -> "Taker + Client"
        isTaker() -> "Taker"
        isClient() -> "Client"
        else -> "Guest"
    }

    fun clearSession() {
        val darkMode = getDarkMode()
        prefs.edit()
            .clear()
            .putInt(KEY_DARK, darkMode)
            .apply()
    }

    fun getDarkMode() = prefs.getInt(KEY_DARK, THEME_SYSTEM)

    fun setDarkMode(mode: Int) {
        val current = prefs.getInt(KEY_DARK, THEME_SYSTEM)
        if (mode == current) return

        prefs.edit().putInt(KEY_DARK, mode).apply()
        applyTheme(mode)
    }

    fun applyTheme(mode: Int = getDarkMode()) {
        val target = when (mode) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != target) {
            AppCompatDelegate.setDefaultNightMode(target)
        }
    }
}
