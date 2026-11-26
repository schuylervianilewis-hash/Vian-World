package com.example.feature.language.translation

import android.content.Context
import java.util.Locale

/**
 * Abstraction boundary for the Translation engine. The app keeps the service contract and
 * Translation UI, but the production build intentionally ships without an engine for now.
 */
interface TranslationEngineBackend {
    fun isAvailable(): Boolean = true
    fun getSupportedLanguageCodes(): List<String>
    fun normalizeLanguageTag(tag: String): String?
    fun getDisplayName(code: String): String
    fun isNetworkConnected(): Boolean
    fun isWifiConnected(): Boolean

    suspend fun getDownloadedLanguageCodes(): Result<Set<String>>
    suspend fun downloadModel(languageCode: String, requireWifi: Boolean): Result<Unit>
    suspend fun deleteModel(languageCode: String): Result<Unit>
    suspend fun identifyLanguageTag(text: String): Result<String?>
    suspend fun translate(text: String, sourceCode: String, targetCode: String): Result<String>

    companion object {
        const val ENGINE_UNAVAILABLE_MESSAGE =
            "The Translation engine is temporarily unavailable in this build. The Translation interface and preferences are retained, but translation cannot run until an engine is restored."
    }
}

/**
 * Deliberately engine-free production backend.
 *
 * It preserves language labels and persisted language-code compatibility without loading ML Kit,
 * querying model state, downloading models, identifying text, or returning fabricated output.
 */
class UnavailableTranslationBackend(
    @Suppress("UNUSED_PARAMETER") context: Context? = null
) : TranslationEngineBackend {

    override fun isAvailable(): Boolean = false

    override fun getSupportedLanguageCodes(): List<String> = FALLBACK_LANGUAGE_CODES

    override fun normalizeLanguageTag(tag: String): String? {
        val clean = tag.trim()
        if (clean.isEmpty() || clean.equals("und", ignoreCase = true)) return null
        val base = Locale.forLanguageTag(clean).language.lowercase(Locale.ROOT)
            .ifBlank { clean.lowercase(Locale.ROOT).substringBefore('-') }
        return FALLBACK_LANGUAGE_CODES.firstOrNull { it.equals(base, ignoreCase = true) }
    }

    override fun getDisplayName(code: String): String {
        val locale = Locale.forLanguageTag(code)
        val display = locale.getDisplayLanguage(Locale.getDefault())
        return if (display.isNullOrBlank() || display.equals(code, ignoreCase = true)) {
            code.uppercase(Locale.ROOT)
        } else {
            display.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }
        }
    }

    override fun isNetworkConnected(): Boolean = false
    override fun isWifiConnected(): Boolean = false

    override suspend fun getDownloadedLanguageCodes(): Result<Set<String>> =
        unavailableResult()

    override suspend fun downloadModel(
        languageCode: String,
        requireWifi: Boolean
    ): Result<Unit> = unavailableResult()

    override suspend fun deleteModel(languageCode: String): Result<Unit> =
        unavailableResult()

    override suspend fun identifyLanguageTag(text: String): Result<String?> =
        unavailableResult()

    override suspend fun translate(
        text: String,
        sourceCode: String,
        targetCode: String
    ): Result<String> = unavailableResult()

    private fun <T> unavailableResult(): Result<T> =
        Result.failure(IllegalStateException(TranslationEngineBackend.ENGINE_UNAVAILABLE_MESSAGE))

    companion object {
        /**
         * Retained language-code list matches the previously bundled ML Kit language set so
         * existing source/target preferences remain readable and displayable.
         */
        internal val FALLBACK_LANGUAGE_CODES = listOf(
            "af", "sq", "ar", "be", "bg", "bn", "ca", "zh", "hr", "cs",
            "da", "nl", "en", "eo", "et", "fi", "fr", "gl", "ka", "de",
            "el", "gu", "ht", "he", "hi", "hu", "is", "id", "ga", "it",
            "ja", "kn", "ko", "lt", "lv", "mk", "mr", "ms", "mt", "no",
            "fa", "pl", "pt", "ro", "ru", "sk", "sl", "es", "sw", "sv",
            "tl", "ta", "te", "th", "tr", "uk", "ur", "vi", "cy"
        )

        @Deprecated("ML Kit is intentionally excluded; use UnavailableTranslationBackend")
        internal val FALLBACK_MLKIT_CODES = FALLBACK_LANGUAGE_CODES
    }
}

/**
 * Compatibility alias for older internal references. It now resolves to the engine-free backend;
 * no ML Kit classes or native libraries are loaded by this type.
 */
@Deprecated("ML Kit is intentionally excluded; use UnavailableTranslationBackend")
typealias MlKitTranslationBackend = UnavailableTranslationBackend
