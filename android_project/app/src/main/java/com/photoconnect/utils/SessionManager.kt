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
        private const val SAFE_PREFS = "pc_session_safe"
        private const val META_PREFS = "pc_session_meta"
        private const val KEY_USE_SAFE_PREFS = "use_safe_prefs"
        private const val KEY_ROLE = "role"
        private const val KEY_ID = "user_id"
        private const val KEY_NAME = "user_name"
        private const val KEY_PHONE = "user_phone"
        private const val KEY_EMAIL = "user_email"
        private const val KEY_PROFILE_IMAGE_URL = "profile_image_url"
        private const val KEY_PROFILE_THUMB_URL = "profile_thumb_url"
        private const val KEY_CLIENT_PROFILE_ID = "client_profile_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_GUEST = "is_guest"
        private const val KEY_DARK = "dark_mode"
        private const val KEY_VERIFICATION_REMINDER_PREFIX = "verification_reminder_muted"
        private const val KEY_TAKER_VERIFICATION_SUBMISSION_PENDING_PREFIX = "taker_verification_submission_pending"
        private const val KEY_STUDIO_VERIFICATION_SUBMISSION_PENDING_PREFIX = "studio_verification_submission_pending"
        private const val KEY_LAST_BOTTOM_NAV_ITEM = "last_bottom_nav_item"
        private const val KEY_PENDING_BOTTOM_NAV_RESTORE = "pending_bottom_nav_restore"

        const val ROLE_TAKER = "taker"
        const val ROLE_CLIENT = "client"

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }

    private val metaPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
    }

    private val safePrefs: SharedPreferences by lazy {
        context.getSharedPreferences(SAFE_PREFS, Context.MODE_PRIVATE)
    }

    private var encryptedPrefs: SharedPreferences? = null

    private val prefs: SharedPreferences
        get() {
            if (metaPrefs.getBoolean(KEY_USE_SAFE_PREFS, false)) return safePrefs
            return encryptedPrefs ?: createEncryptedPrefs().also { encryptedPrefs = it }
        }

    private fun createEncryptedPrefs(): SharedPreferences =
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
            useSafePrefs()
        }

    private fun useSafePrefs(): SharedPreferences {
        metaPrefs.edit().putBoolean(KEY_USE_SAFE_PREFS, true).apply()
        encryptedPrefs = null
        return safePrefs
    }

    private inline fun editPrefs(block: SharedPreferences.Editor.() -> Unit) {
        try {
            prefs.edit().apply {
                block()
                apply()
            }
        } catch (_: Exception) {
            useSafePrefs().edit().apply {
                block()
                apply()
            }
        }
    }

    private inline fun <T> readPrefs(default: T, block: SharedPreferences.() -> T): T =
        try {
            prefs.block()
        } catch (_: Exception) {
            try {
                useSafePrefs().block()
            } catch (_: Exception) {
                default
            }
        }

    fun saveSession(
        role: String,
        userId: Int,
        name: String,
        phone: String,
        email: String = "",
        takerProfileId: Int = 0,
        clientProfileId: Int = 0,
        profileImageUrl: String? = null,
        profileThumbUrl: String? = null,
        accessToken: String,
        refreshToken: String,
    ) {
        editPrefs {
            putString(KEY_ROLE, role)
            putInt(KEY_ID, userId)
            putString(KEY_NAME, name)
            putString(KEY_PHONE, phone)
            putString(KEY_EMAIL, email)
            putInt("taker_profile_id", takerProfileId)
            putInt(KEY_CLIENT_PROFILE_ID, clientProfileId)
            putString(KEY_PROFILE_IMAGE_URL, profileImageUrl.orEmpty())
            putString(KEY_PROFILE_THUMB_URL, profileThumbUrl.orEmpty())
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            putBoolean(KEY_GUEST, false)
        }
    }

    fun saveTokens(accessToken: String, refreshToken: String) {
        editPrefs {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            putBoolean(KEY_GUEST, false)
        }
    }

    fun setGuest() {
        val darkMode = getDarkMode()
        editPrefs {
            clear()
            putBoolean(KEY_GUEST, true)
            putInt(KEY_DARK, darkMode)
        }
    }

    fun isGuest() = readPrefs(false) { getBoolean(KEY_GUEST, false) }
    fun isLoggedIn() = getRole().isNotBlank() && getAccessToken().isNotBlank() && !isGuest()
    fun getRole() = readPrefs("") { getString(KEY_ROLE, "") ?: "" }
    fun getUserId() = readPrefs(0) { getInt(KEY_ID, 0) }
    fun getUserName() = readPrefs("") { getString(KEY_NAME, "") ?: "" }
    fun getUserPhone() = readPrefs("") { getString(KEY_PHONE, "") ?: "" }
    fun getUserEmail() = readPrefs("") { getString(KEY_EMAIL, "") ?: "" }
    fun getProfileImageUrl() = readPrefs("") { getString(KEY_PROFILE_IMAGE_URL, "") ?: "" }
    fun getProfileThumbUrl() = readPrefs("") { getString(KEY_PROFILE_THUMB_URL, "") ?: "" }
    fun getTakerProfileId() = readPrefs(0) { getInt("taker_profile_id", 0) }
    fun getClientProfileId() = readPrefs(0) { getInt(KEY_CLIENT_PROFILE_ID, 0) }
    fun getTakerActorId() = getTakerProfileId().takeIf { it > 0 } ?: getUserId()
    fun getClientActorId() = getClientProfileId().takeIf { it > 0 } ?: getUserId()
    fun getActiveActorId() = when (getRole()) {
        ROLE_TAKER -> getTakerActorId()
        ROLE_CLIENT -> getClientActorId()
        else -> getClientProfileId().takeIf { it > 0 }
            ?: getTakerProfileId().takeIf { it > 0 }
            ?: getUserId()
    }
    fun getFavoriteActorRole(): String =
        when {
            hasClientFeatures() -> ROLE_CLIENT
            hasTakerFeatures() -> ROLE_TAKER
            getRole() in setOf(ROLE_CLIENT, ROLE_TAKER) -> getRole()
            else -> ROLE_CLIENT
        }
    fun getFavoriteActorId(): Int =
        when (getFavoriteActorRole()) {
            ROLE_CLIENT -> getClientActorId()
            ROLE_TAKER -> getTakerActorId()
            else -> getActiveActorId()
        }
    fun getAccessToken() = readPrefs("") { getString(KEY_ACCESS_TOKEN, "") ?: "" }
    fun getRefreshToken() = readPrefs("") { getString(KEY_REFRESH_TOKEN, "") ?: "" }
    fun isTaker() = isLoggedIn() && getRole() == ROLE_TAKER
    fun isClient() = isLoggedIn() && getRole() == ROLE_CLIENT
    fun hasClientFeatures() = isLoggedIn() && getClientProfileId() != 0
    fun hasTakerFeatures() = isLoggedIn() && getTakerProfileId() != 0

    fun isVerificationReminderMuted(role: String = getRole(), actorId: Int = getActiveActorId()): Boolean =
        readPrefs(false) { getBoolean("${KEY_VERIFICATION_REMINDER_PREFIX}_${role}_$actorId", false) }

    fun setVerificationReminderMuted(
        muted: Boolean,
        role: String = getRole(),
        actorId: Int = getActiveActorId(),
    ) {
        editPrefs { putBoolean("${KEY_VERIFICATION_REMINDER_PREFIX}_${role}_$actorId", muted) }
    }

    fun isTakerVerificationSubmissionPending(takerId: Int = getTakerActorId()): Boolean =
        takerId > 0 && readPrefs(false) {
            getBoolean("${KEY_TAKER_VERIFICATION_SUBMISSION_PENDING_PREFIX}_$takerId", false)
        }

    fun setTakerVerificationSubmissionPending(
        pending: Boolean,
        takerId: Int = getTakerActorId(),
    ) {
        if (takerId <= 0) return
        editPrefs { putBoolean("${KEY_TAKER_VERIFICATION_SUBMISSION_PENDING_PREFIX}_$takerId", pending) }
    }

    fun isStudioVerificationSubmissionPending(clientId: Int = getClientActorId()): Boolean =
        clientId > 0 && readPrefs(false) {
            getBoolean("${KEY_STUDIO_VERIFICATION_SUBMISSION_PENDING_PREFIX}_$clientId", false)
        }

    fun setStudioVerificationSubmissionPending(
        pending: Boolean,
        clientId: Int = getClientActorId(),
    ) {
        if (clientId <= 0) return
        editPrefs { putBoolean("${KEY_STUDIO_VERIFICATION_SUBMISSION_PENDING_PREFIX}_$clientId", pending) }
    }

    fun setSessionProfileImage(profileImageUrl: String?, profileThumbUrl: String?) {
        editPrefs {
            putString(KEY_PROFILE_IMAGE_URL, profileImageUrl.orEmpty())
            putString(KEY_PROFILE_THUMB_URL, profileThumbUrl.orEmpty())
        }
    }

    fun setActiveRole(role: String) {
        editPrefs { putString(KEY_ROLE, role) }
    }

    fun getRoleLabel() = when {
        hasTakerFeatures() && hasClientFeatures() -> "Taker + Client"
        hasTakerFeatures() -> "Taker"
        hasClientFeatures() -> "Client"
        else -> "Guest"
    }

    fun clearSession() {
        val darkMode = getDarkMode()
        editPrefs {
            clear()
            putInt(KEY_DARK, darkMode)
        }
    }

    fun getDarkMode() = readPrefs(THEME_SYSTEM) { getInt(KEY_DARK, THEME_SYSTEM) }

    fun getAppLanguageTag() = AppLocaleManager.getSavedLanguageTag(context)

    fun getAppLanguageDisplayName() = AppLocaleManager.getDisplayName(context)

    fun setAppLanguageTag(tag: String) {
        AppLocaleManager.setLanguageTag(context, tag)
    }

    fun applyLocale() {
        AppLocaleManager.applySavedLocale(context)
    }

    fun setDarkMode(mode: Int) {
        val current = getDarkMode()
        if (mode == current) return

        editPrefs { putInt(KEY_DARK, mode) }
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

    fun saveLastBottomNavItem(itemId: Int) {
        metaPrefs.edit()
            .putInt(KEY_LAST_BOTTOM_NAV_ITEM, itemId)
            .putBoolean(KEY_PENDING_BOTTOM_NAV_RESTORE, true)
            .apply()
    }

    fun consumeLastBottomNavItem(defaultId: Int): Int {
        if (!metaPrefs.getBoolean(KEY_PENDING_BOTTOM_NAV_RESTORE, false)) {
            return defaultId
        }
        val itemId = metaPrefs.getInt(KEY_LAST_BOTTOM_NAV_ITEM, defaultId)
        metaPrefs.edit()
            .remove(KEY_LAST_BOTTOM_NAV_ITEM)
            .putBoolean(KEY_PENDING_BOTTOM_NAV_RESTORE, false)
            .apply()
        return itemId
    }
}
