package com.photoconnect.utils

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.textfield.TextInputLayout
import com.photoconnect.R
import java.security.MessageDigest
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object UiRuntimeTranslationManager {
    private const val PREFS = "pc_ui_translation_cache"
    private const val DEBOUNCE_MS = 140L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listeners = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()
    private val scheduled = WeakHashMap<Activity, Runnable>()
    private val memoryCache = ConcurrentHashMap<String, String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val englishResourceValues = ConcurrentHashMap<String, Set<String>>()

    @Volatile
    private var engine: AdvancedTranslationEngine? = null

    fun install(activity: Activity) {
        if (listeners.containsKey(activity)) {
            schedule(activity)
            return
        }
        val root = activity.window?.decorView ?: return
        val listener = ViewTreeObserver.OnGlobalLayoutListener { schedule(activity) }
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        listeners[activity] = listener
        schedule(activity)
    }

    fun uninstall(activity: Activity) {
        val root = activity.window?.decorView ?: return
        listeners.remove(activity)?.let { listener ->
            if (root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
        }
        scheduled.remove(activity)?.let(root::removeCallbacks)
    }

    fun schedule(activity: Activity) {
        val targetTag = currentLanguageTag(activity)
        if (targetTag == "en") return
        val root = activity.window?.decorView ?: return
        val task = scheduled.getOrPut(activity) {
            Runnable { translateTree(activity, root) }
        }
        root.removeCallbacks(task)
        root.postDelayed(task, DEBOUNCE_MS)
    }

    private fun translateTree(activity: Activity, root: View) {
        val targetTag = currentLanguageTag(activity)
        if (targetTag == "en") return
        val baseEnglish = englishStrings(activity.applicationContext)
        walk(root) { view ->
            when (view) {
                is TextInputLayout -> translateHint(
                    activity = activity,
                    view = view,
                    hint = view.hint?.toString().orEmpty(),
                    englishStrings = baseEnglish,
                    tagId = R.id.tag_runtime_translation_layout_hint_key,
                    apply = { translated -> view.hint = translated },
                    current = { view.hint?.toString().orEmpty() },
                )
                is TextView -> {
                    if (view !is EditText) {
                        translateText(
                            activity = activity,
                            view = view,
                            original = view.text?.toString().orEmpty(),
                            englishStrings = baseEnglish,
                            tagId = R.id.tag_runtime_translation_text_key,
                            apply = { translated -> view.text = translated },
                            current = { view.text?.toString().orEmpty() },
                        )
                    }
                    translateHint(
                        activity = activity,
                        view = view,
                        hint = view.hint?.toString().orEmpty(),
                        englishStrings = baseEnglish,
                        tagId = R.id.tag_runtime_translation_hint_key,
                        apply = { translated -> view.hint = translated },
                        current = { view.hint?.toString().orEmpty() },
                    )
                }
            }
        }
    }

    private fun translateText(
        activity: Activity,
        view: View,
        original: String,
        englishStrings: Set<String>,
        tagId: Int,
        apply: (String) -> Unit,
        current: () -> String,
    ) {
        translateValue(
            activity = activity,
            view = view,
            original = original,
            englishStrings = englishStrings,
            tagId = tagId,
            apply = apply,
            current = current,
        )
    }

    private fun translateHint(
        activity: Activity,
        view: View,
        hint: String,
        englishStrings: Set<String>,
        tagId: Int,
        apply: (String) -> Unit,
        current: () -> String,
    ) {
        translateValue(
            activity = activity,
            view = view,
            original = hint,
            englishStrings = englishStrings,
            tagId = tagId,
            apply = apply,
            current = current,
        )
    }

    private fun translateValue(
        activity: Activity,
        view: View,
        original: String,
        englishStrings: Set<String>,
        tagId: Int,
        apply: (String) -> Unit,
        current: () -> String,
    ) {
        val trimmed = original.trim()
        if (!shouldTranslate(trimmed, englishStrings)) return

        val targetTag = currentLanguageTag(activity)
        val key = cacheKey(targetTag, trimmed)
        if (view.getTag(tagId) == key) return

        val cached = translatedValue(activity, key)
        if (cached != null) {
            if (current() != cached) apply(cached)
            view.setTag(tagId, key)
            return
        }

        if (!inFlight.add(key)) return

        scope.launch {
            val translated = withContext(Dispatchers.IO) {
                translator(activity.applicationContext).translateDynamically(trimmed, targetTag)
            }.trim()

            inFlight.remove(key)

            if (translated.isBlank() || translated == trimmed) return@launch

            storeTranslation(activity, key, translated)

            if (!view.isAttachedToWindow) return@launch
            if (current().trim() != trimmed) return@launch

            apply(translated)
            view.setTag(tagId, key)
        }
    }

    private fun shouldTranslate(text: String, englishStrings: Set<String>): Boolean {
        if (text.isBlank()) return false
        if (text.length > 500) return false
        return englishStrings.contains(text)
    }

    private fun walk(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                walk(view.getChildAt(index), action)
            }
        }
    }

    private fun translator(context: Context): AdvancedTranslationEngine =
        engine ?: synchronized(this) {
            engine ?: AdvancedTranslationEngine(context.applicationContext).also { engine = it }
        }

    private fun englishStrings(context: Context): Set<String> {
        val cacheKey = context.packageName
        return englishResourceValues.getOrPut(cacheKey) {
            val config = Configuration(context.resources.configuration)
            config.setLocale(Locale.ENGLISH)
            val englishContext = context.createConfigurationContext(config)
            R.string::class.java.fields.mapNotNull { field ->
                runCatching {
                    englishContext.getString(field.getInt(null)).trim()
                }.getOrNull()
            }
                .filter { it.isNotBlank() }
                .toSet()
        }
    }

    private fun currentLanguageTag(context: Context): String {
        val applied = AppCompatDelegate.getApplicationLocales().toLanguageTags().trim()
        val raw = if (applied.isNotBlank()) {
            applied
        } else {
            context.resources.configuration.locales[0]?.toLanguageTag().orEmpty()
        }
        val base = raw.substringBefore(',').substringBefore('-').substringBefore('_').lowercase(Locale.US)
        return if (base.isBlank()) "en" else base
    }

    private fun translatedValue(context: Context, key: String): String? =
        memoryCache[key] ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)
            ?.also { memoryCache[key] = it }

    private fun storeTranslation(context: Context, key: String, value: String) {
        memoryCache[key] = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .apply()
    }

    private fun cacheKey(languageTag: String, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$languageTag|$text".toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2 + languageTag.length + 1) {
            append(languageTag)
            append(':')
            bytes.forEach { append("%02x".format(it)) }
        }
    }
}
