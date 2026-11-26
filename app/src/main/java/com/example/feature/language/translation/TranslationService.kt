package com.example.feature.language.translation

import android.content.Context
import android.util.AtomicFile
import com.example.core.LogKeeper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Single shared on-device Translation service used by:
 * - [com.example.feature.language.TranslationMiniAppInstance] (Main process)
 * - [com.example.feature.selection.TranslateSelectionActivity] (Main process)
 * - Settings UI (reads the retained language preferences without initializing an engine)
 *
 * Cross-process consistency:
 * Shared language preferences are persisted atomically in the legacy `filesDir/mlkit_translation/prefs.json` location so existing settings survive engine removal.
 */
class TranslationService private constructor(
    private val appContext: Context,
    private val prefsFile: File,
    @Volatile private var backend: TranslationEngineBackend
) {

    private val prefsLock = Any()
    private val prefsLockFile = File(prefsFile.parentFile, "${prefsFile.name}.lock")
    private val activeDownloads = ConcurrentHashMap<String, Boolean>()
    private val _modelStates = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val modelStates: StateFlow<Map<String, ModelDownloadState>> = _modelStates.asStateFlow()

    private val _downloadedLanguages = MutableStateFlow<Set<String>>(emptySet())
    val downloadedLanguages: StateFlow<Set<String>> = _downloadedLanguages.asStateFlow()

    fun getSupportedTargetLanguages(): List<LanguageOption> {
        return backend.getSupportedLanguageCodes()
            .distinct()
            .map { code -> LanguageOption(code = code, displayName = backend.getDisplayName(code)) }
            .sortedBy { it.displayName.lowercase() }
    }

    fun getSupportedSourceLanguages(): List<LanguageOption> {
        val auto = LanguageOption(
            code = CODE_AUTO_DETECT,
            displayName = DISPLAY_AUTO_DETECT,
            isAutoDetect = true
        )
        return listOf(auto) + getSupportedTargetLanguages()
    }

    fun getLanguageDisplayName(code: String): String {
        if (code == CODE_AUTO_DETECT) return DISPLAY_AUTO_DETECT
        return backend.getDisplayName(code)
    }

    fun resolveLanguageCode(codeOrName: String?, allowAuto: Boolean): String? {
        val raw = codeOrName?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (allowAuto && (raw.equals(CODE_AUTO_DETECT, ignoreCase = true) || raw.equals(DISPLAY_AUTO_DETECT, ignoreCase = true))) {
            return CODE_AUTO_DETECT
        }
        val supported = backend.getSupportedLanguageCodes()
        supported.firstOrNull { it.equals(raw, ignoreCase = true) }?.let { return it }
        supported.firstOrNull { backend.getDisplayName(it).equals(raw, ignoreCase = true) }?.let { return it }
        return backend.normalizeLanguageTag(raw)?.takeIf { it in supported }
    }

    fun getConfig(): TranslationConfig {
        return try {
            withPrefsFileLock { readConfigLocked() }
        } catch (e: Throwable) {
            LogKeeper.logError(appContext, TAG, "Could not lock/read translation preferences", e)
            TranslationConfig()
        }
    }

    private fun readConfigLocked(): TranslationConfig {
        val defaultConfig = TranslationConfig()
        if (!prefsFile.isFile) return defaultConfig
        return try {
            AtomicFile(prefsFile).openRead().bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val obj = JSONObject(reader.readText())
                val src = resolveLanguageCode(
                    obj.optString("sourceLanguageCode", CODE_AUTO_DETECT),
                    allowAuto = true
                ) ?: CODE_AUTO_DETECT
                val tgt = resolveLanguageCode(
                    obj.optString("targetLanguageCode", DEFAULT_TARGET_LANGUAGE_CODE),
                    allowAuto = false
                ) ?: DEFAULT_TARGET_LANGUAGE_CODE
                TranslationConfig(
                    sourceLanguageCode = src,
                    targetLanguageCode = tgt,
                    wifiOnlyDownloads = obj.optBoolean("wifiOnlyDownloads", true)
                )
            }
        } catch (e: Throwable) {
            LogKeeper.logError(appContext, TAG, "Failed to read translation prefs", e)
            defaultConfig
        }
    }

    fun updateConfig(
        sourceLanguageCode: String? = null,
        targetLanguageCode: String? = null,
        wifiOnlyDownloads: Boolean? = null
    ): TranslationConfig {
        return try {
            withPrefsFileLock {
                val current = readConfigLocked()
                val newSrc = if (sourceLanguageCode != null) {
                    resolveLanguageCode(sourceLanguageCode, allowAuto = true) ?: current.sourceLanguageCode
                } else {
                    current.sourceLanguageCode
                }
                val newTgt = if (targetLanguageCode != null) {
                    resolveLanguageCode(targetLanguageCode, allowAuto = false) ?: current.targetLanguageCode
                } else {
                    current.targetLanguageCode
                }
                val newWifi = wifiOnlyDownloads ?: current.wifiOnlyDownloads

                val updated = TranslationConfig(
                    sourceLanguageCode = newSrc,
                    targetLanguageCode = newTgt,
                    wifiOnlyDownloads = newWifi
                )
                writeConfigLocked(updated)
                updated
            }
        } catch (e: Throwable) {
            LogKeeper.logError(appContext, TAG, "Could not lock/update translation preferences", e)
            getConfig()
        }
    }

    suspend fun refreshDownloadedModels(): Result<Set<String>> {
        val result = backend.getDownloadedLanguageCodes()
        result.onSuccess { downloaded ->
            _downloadedLanguages.value = downloaded
            val merged = _modelStates.value.toMutableMap()
            for (code in downloaded) {
                if (activeDownloads[code] != true) {
                    merged[code] = ModelDownloadState.Downloaded
                }
            }
            for (key in merged.keys.toList()) {
                if (key !in downloaded && merged[key] is ModelDownloadState.Downloaded) {
                    merged[key] = ModelDownloadState.NotDownloaded
                }
            }
            _modelStates.value = merged
        }
        return result
    }

    suspend fun downloadModel(
        languageCode: String,
        requireWifiOverride: Boolean? = null
    ): Result<Unit> {
        if (!backend.isAvailable()) {
            return Result.failure(IllegalStateException(TranslationEngineBackend.ENGINE_UNAVAILABLE_MESSAGE))
        }
        val cleanCode = resolveLanguageCode(languageCode, allowAuto = false)
            ?: return Result.failure(IllegalArgumentException("Unsupported language code: $languageCode"))

        if (activeDownloads.putIfAbsent(cleanCode, true) != null) {
            return Result.failure(IllegalStateException("${getLanguageDisplayName(cleanCode)} model is already downloading."))
        }

        val requireWifi = requireWifiOverride ?: getConfig().wifiOnlyDownloads
        if (!backend.isNetworkConnected()) {
            activeDownloads.remove(cleanCode)
            val err = "Device is offline. Connect to a network to download the ${getLanguageDisplayName(cleanCode)} model."
            updateModelState(cleanCode, ModelDownloadState.Failed(err))
            return Result.failure(IllegalStateException(err))
        }
        if (requireWifi && !backend.isWifiConnected()) {
            activeDownloads.remove(cleanCode)
            val err = "Wi-Fi is required for model downloads. Connect to Wi-Fi or disable Wi-Fi-only downloads in Settings."
            updateModelState(cleanCode, ModelDownloadState.Failed(err))
            return Result.failure(IllegalStateException(err))
        }

        updateModelState(cleanCode, ModelDownloadState.Downloading)
        return try {
            val res = backend.downloadModel(cleanCode, requireWifi)
            if (res.isSuccess) {
                updateModelState(cleanCode, ModelDownloadState.Downloaded)
                refreshDownloadedModels()
                LogKeeper.log(appContext, TAG, "MODEL_DOWNLOADED lang=$cleanCode wifiOnly=$requireWifi")
                Result.success(Unit)
            } else {
                val msg = res.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                    ?: "Failed to download ${getLanguageDisplayName(cleanCode)} model."
                updateModelState(cleanCode, ModelDownloadState.Failed(msg))
                LogKeeper.logError(appContext, TAG, "Model download failed lang=$cleanCode", res.exceptionOrNull())
                Result.failure(res.exceptionOrNull() ?: IllegalStateException(msg))
            }
        } finally {
            activeDownloads.remove(cleanCode)
        }
    }

    suspend fun downloadMissingModels(
        languageCodes: Collection<String>,
        requireWifiOverride: Boolean? = null
    ): Result<Unit> {
        if (!backend.isAvailable()) {
            return Result.failure(IllegalStateException(TranslationEngineBackend.ENGINE_UNAVAILABLE_MESSAGE))
        }
        val downloaded = refreshDownloadedModels().getOrDefault(_downloadedLanguages.value)
        val needed = languageCodes.distinct().filter { it !in downloaded }
        if (needed.isEmpty()) return Result.success(Unit)

        for (code in needed) {
            val res = downloadModel(code, requireWifiOverride)
            if (res.isFailure) return res
        }
        return Result.success(Unit)
    }

    suspend fun deleteModel(languageCode: String): Result<Unit> {
        if (!backend.isAvailable()) {
            return Result.failure(IllegalStateException(TranslationEngineBackend.ENGINE_UNAVAILABLE_MESSAGE))
        }
        val cleanCode = resolveLanguageCode(languageCode, allowAuto = false)
            ?: return Result.failure(IllegalArgumentException("Unsupported language code: $languageCode"))
        if (activeDownloads[cleanCode] == true) {
            return Result.failure(IllegalStateException("Cannot delete ${getLanguageDisplayName(cleanCode)} while it is downloading."))
        }
        val res = backend.deleteModel(cleanCode)
        if (res.isSuccess) {
            updateModelState(cleanCode, ModelDownloadState.NotDownloaded)
            refreshDownloadedModels()
            LogKeeper.log(appContext, TAG, "MODEL_DELETED lang=$cleanCode")
        } else {
            LogKeeper.logError(appContext, TAG, "Model delete failed lang=$cleanCode", res.exceptionOrNull())
        }
        return res
    }

    suspend fun translate(
        rawText: String,
        overrideSourceCode: String? = null,
        overrideTargetCode: String? = null
    ): TranslationOutcome {
        val text = rawText.trim()
        if (text.isEmpty()) return TranslationOutcome.EmptyInput
        if (!backend.isAvailable()) {
            return TranslationOutcome.Error(TranslationEngineBackend.ENGINE_UNAVAILABLE_MESSAGE)
        }

        val config = getConfig()
        val requestedSource = resolveLanguageCode(overrideSourceCode, allowAuto = true)
            ?: config.sourceLanguageCode
        val targetCode = resolveLanguageCode(overrideTargetCode, allowAuto = false)
            ?: config.targetLanguageCode

        var wasAutoDetected = false
        val resolvedSourceCode: String = if (requestedSource == CODE_AUTO_DETECT) {
            wasAutoDetected = true
            val idResult = backend.identifyLanguageTag(text)
            if (idResult.isFailure) {
                val msg = idResult.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                    ?: "Could not detect the source language."
                return TranslationOutcome.Error(msg)
            }
            val detectedTag = idResult.getOrNull()
                ?: return TranslationOutcome.UndeterminedLanguage
            val normalized = backend.normalizeLanguageTag(detectedTag)
            if (normalized == null || normalized !in backend.getSupportedLanguageCodes()) {
                return TranslationOutcome.UnsupportedLanguage(detectedTag)
            }
            normalized
        } else {
            requestedSource
        }

        if (resolvedSourceCode == targetCode) {
            return TranslationOutcome.SameLanguage(
                originalText = text,
                languageCode = resolvedSourceCode,
                wasAutoDetected = wasAutoDetected
            )
        }

        val downloadedResult = refreshDownloadedModels()
        if (downloadedResult.isFailure) {
            val msg = downloadedResult.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                ?: "Could not verify downloaded offline translation models."
            return TranslationOutcome.Error(msg)
        }
        val downloaded = downloadedResult.getOrDefault(emptySet())
        val required = linkedSetOf(resolvedSourceCode, targetCode)
        val missing = required.filter { it !in downloaded }
        if (missing.isNotEmpty()) {
            return TranslationOutcome.MissingModels(
                resolvedSourceCode = resolvedSourceCode,
                targetCode = targetCode,
                missingLanguageCodes = missing,
                wasAutoDetected = wasAutoDetected
            )
        }

        val translationResult = backend.translate(text, resolvedSourceCode, targetCode)
        return translationResult.fold(
            onSuccess = { translated ->
                TranslationOutcome.Success(
                    translatedText = translated,
                    resolvedSourceCode = resolvedSourceCode,
                    targetCode = targetCode,
                    wasAutoDetected = wasAutoDetected
                )
            },
            onFailure = { error ->
                LogKeeper.logError(appContext, TAG, "On-device translation failed", error)
                TranslationOutcome.Error(
                    error.message?.takeIf { it.isNotBlank() }
                        ?: "On-device translation failed."
                )
            }
        )
    }

    private fun updateModelState(code: String, state: ModelDownloadState) {
        val next = _modelStates.value.toMutableMap()
        next[code] = state
        _modelStates.value = next
    }

    private fun writeConfigLocked(config: TranslationConfig) {
        val atomicFile = AtomicFile(prefsFile)
        var stream: FileOutputStream? = null
        try {
            prefsFile.parentFile?.mkdirs()
            val obj = JSONObject().apply {
                put("sourceLanguageCode", config.sourceLanguageCode)
                put("targetLanguageCode", config.targetLanguageCode)
                put("wifiOnlyDownloads", config.wifiOnlyDownloads)
            }
            val output = atomicFile.startWrite()
            stream = output
            output.write(obj.toString().toByteArray(StandardCharsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (e: Throwable) {
            stream?.let { atomicFile.failWrite(it) }
            LogKeeper.logError(appContext, TAG, "Failed to save translation prefs", e)
        }
    }

    /**
     * Shared by Main and :heavy. The OS file lock prevents concurrent processes from reading a
     * partially-written preferences file or overwriting one another's updates.
     */
    private fun <T> withPrefsFileLock(block: () -> T): T = synchronized(prefsLock) {
        prefsLockFile.parentFile?.mkdirs()
        RandomAccessFile(prefsLockFile, "rw").use { randomAccessFile ->
            val lock = randomAccessFile.channel.lock()
            try {
                block()
            } finally {
                lock.release()
            }
        }
    }

    internal fun setBackendForTesting(newBackend: TranslationEngineBackend) {
        backend = newBackend
    }

    companion object {
        private const val TAG = "TranslationService"
        // Keep the legacy directory name to preserve previously saved source/target preferences.
        private const val DIR_TRANSLATION = "mlkit_translation"
        private const val FILE_PREFS_JSON = "prefs.json"

        const val CODE_AUTO_DETECT = "auto"
        const val DISPLAY_AUTO_DETECT = "Auto-detect"
        const val DEFAULT_TARGET_LANGUAGE_CODE = "en"

        @Volatile
        private var instance: TranslationService? = null

        fun getInstance(context: Context): TranslationService {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext ?: context
                    val dir = File(app.filesDir ?: File(System.getProperty("java.io.tmpdir"), "vianside_trans"), DIR_TRANSLATION)
                    val prefs = File(dir, FILE_PREFS_JSON)
                    TranslationService(
                        appContext = app,
                        prefsFile = prefs,
                        backend = UnavailableTranslationBackend(app)
                    ).also { instance = it }
                }
            }
        }

        internal fun createForTesting(
            context: Context,
            prefsFile: File,
            backend: TranslationEngineBackend
        ): TranslationService {
            return synchronized(this) {
                TranslationService(
                    appContext = context.applicationContext ?: context,
                    prefsFile = prefsFile,
                    backend = backend
                ).also { instance = it }
            }
        }

        internal fun resetForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }
}
