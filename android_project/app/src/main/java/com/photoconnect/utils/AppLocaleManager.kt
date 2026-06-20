package com.photoconnect.utils

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

data class AppLanguage(
    val tag: String,
    val englishName: String,
    val nativeName: String,
)

object AppLocaleManager {
    private const val PREFS = "pc_preferences"
    private const val KEY_LANGUAGE_TAG = "app_language_tag"
    const val SYSTEM_LANGUAGE_TAG = ""

    val supportedLanguages = listOf(
        AppLanguage(SYSTEM_LANGUAGE_TAG, "System default", "System default"),
        AppLanguage("en", "English", "English"),
        AppLanguage("as", "Assamese", "অসমীয়া"),
        AppLanguage("bn", "Bengali", "বাংলা"),
        AppLanguage("brx", "Bodo", "बड़ो"),
        AppLanguage("doi", "Dogri", "डोगरी"),
        AppLanguage("gu", "Gujarati", "ગુજરાતી"),
        AppLanguage("hi", "Hindi", "हिन्दी"),
        AppLanguage("kn", "Kannada", "ಕನ್ನಡ"),
        AppLanguage("ks", "Kashmiri", "کٲشُر"),
        AppLanguage("kok", "Konkani", "कोंकणी"),
        AppLanguage("mai", "Maithili", "मैथिली"),
        AppLanguage("ml", "Malayalam", "മലയാളം"),
        AppLanguage("mni", "Manipuri", "মৈতৈলোন্"),
        AppLanguage("mr", "Marathi", "मराठी"),
        AppLanguage("ne", "Nepali", "नेपाली"),
        AppLanguage("or", "Odia", "ଓଡ଼ିଆ"),
        AppLanguage("pa", "Punjabi", "ਪੰਜਾਬੀ"),
        AppLanguage("sa", "Sanskrit", "संस्कृतम्"),
        AppLanguage("sat", "Santali", "ᱥᱟᱱᱛᱟᱲᱤ"),
        AppLanguage("sd", "Sindhi", "سنڌي"),
        AppLanguage("ta", "Tamil", "தமிழ்"),
        AppLanguage("te", "Telugu", "తెలుగు"),
        AppLanguage("ur", "Urdu", "اردو"),
    )

    private val rightToLeftScriptTags = setOf("ks", "sd", "ur")

    fun applySavedLocale(context: Context) {
        applyLocale(getSavedLanguageTag(context))
    }

    fun setLanguageTag(context: Context, tag: String) {
        val safeTag = supportedLanguages.firstOrNull { it.tag == tag }?.tag ?: SYSTEM_LANGUAGE_TAG
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE_TAG, safeTag)
            .apply()
        applyLocale(safeTag)
    }

    fun getSavedLanguageTag(context: Context): String {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_TAG, SYSTEM_LANGUAGE_TAG)
            ?: SYSTEM_LANGUAGE_TAG
        return supportedLanguages.firstOrNull { it.tag == stored }?.tag ?: SYSTEM_LANGUAGE_TAG
    }

    fun getDisplayName(context: Context, tag: String = getSavedLanguageTag(context)): String {
        val language = supportedLanguages.firstOrNull { it.tag == tag } ?: supportedLanguages.first()
        if (language.tag == SYSTEM_LANGUAGE_TAG) {
            return context.getString(com.photoconnect.R.string.language_system_default)
        }
        return if (language.nativeName == language.englishName) {
            language.englishName
        } else {
            "${language.nativeName} (${language.englishName})"
        }
    }

    fun getPickerLabel(context: Context, language: AppLanguage): String {
        val label = if (language.tag == SYSTEM_LANGUAGE_TAG) {
            context.getString(com.photoconnect.R.string.language_system_default)
        } else if (language.nativeName == language.englishName) {
            language.englishName
        } else if (language.tag in rightToLeftScriptTags) {
            "${language.englishName} - ${language.nativeName}"
        } else {
            "${language.nativeName} - ${language.englishName}"
        }
        return label.stableLeftToRightText()
    }

    private fun String.stableLeftToRightText(): String = "\u202A$this\u202C"

    private fun applyLocale(tag: String) {
        val locales = LocaleListCompat.forLanguageTags(tag)
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != locales.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
