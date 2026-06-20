package com.photoconnect.utils

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles runtime text translation for user generated/support content.
 *
 * App chrome still comes from Android string resources so the UI stays stable offline.
 * For dialects not directly supported by ML Kit, this engine routes to the nearest
 * regional language instead of failing the request.
 */
class AdvancedTranslationEngine(private val context: Context) {

    private val localeManager = AppLocaleManager

    private val regionalFallbacks = mapOf(
        "brx" to "hi",
        "doi" to "hi",
        "kok" to "hi",
        "mai" to "hi",
        "ne" to "hi",
        "sa" to "hi",
        "as" to "bn",
        "mni" to "bn",
        "ks" to "ur",
        "sd" to "ur",
        "sat" to "hi",
    )
    private val translators = ConcurrentHashMap<String, Translator>()

    suspend fun translateDynamically(text: String, targetLanguageTag: String): String {
        if (text.isBlank()) return text
        val targetTag = resolveTranslationTag(targetLanguageTag)
        if (targetTag.isBlank() || targetTag == "en") return text

        return withContext(Dispatchers.IO) {
            try {
                val translator = translatorFor(targetTag) ?: return@withContext text
                val conditions = DownloadConditions.Builder().build()
                translator.downloadModelIfNeeded(conditions).await()
                translator.translate(text).await()
            } catch (e: Exception) {
                Log.w("TranslationEngine", "Translation fallback used for $targetTag: ${e.message}")
                text
            }
        }
    }

    private fun translatorFor(targetTag: String): Translator? {
        val mlKitLanguage = TranslateLanguage.fromLanguageTag(targetTag) ?: return null
        return translators.getOrPut(targetTag) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(mlKitLanguage)
                .build()
            Translation.getClient(options)
        }
    }

    fun resolveTranslationTag(tag: String): String {
        val cleaned = tag.trim().lowercase(Locale.US)
        val baseTag = cleaned.substringBefore('-').substringBefore('_')
        if (baseTag.isBlank()) return AppLocaleManager.SYSTEM_LANGUAGE_TAG
        if (baseTag == "en") return "en"
        val supportedTag = localeManager.supportedLanguages.firstOrNull { it.tag == baseTag }?.tag ?: baseTag
        return regionalFallbacks[supportedTag] ?: supportedTag
    }

    fun getCurrentLanguageTag(): String {
        return localeManager.getSavedLanguageTag(context)
    }

    fun setLanguage(tag: String) {
        localeManager.setLanguageTag(context, tag)
    }
}
