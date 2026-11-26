package com.example.feature.language

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.core.LogKeeper
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

data class LanguageFeatureState(
    val dictionaryEnabled: Boolean = true,
    val translationEnabled: Boolean = false
)

/**
 * Durable feature master switches shared by the Main and :heavy processes.
 *
 * Values are read from disk on every access (never cached in process memory) and updates are
 * serialized with an OS file lock. This is intentional because Settings runs in :heavy, while
 * floating mini-apps and ACTION_PROCESS_TEXT activities run in the default process.
 */
class LanguageFeatureSettings private constructor(context: Context) {

    private val appContext = context.applicationContext ?: context
    private val stateFile = File(appContext.filesDir, "language_features/feature_state.json")
    private val lockFile = File(stateFile.parentFile, "feature_state.lock")
    private val processLock = Any()

    fun getState(): LanguageFeatureState {
        return try {
            withFileLock { readStateLocked() }
        } catch (t: Throwable) {
            LogKeeper.logError(appContext, TAG, "Could not read language feature switches; failing closed", t)
            LanguageFeatureState(dictionaryEnabled = false, translationEnabled = false)
        }
    }

    fun isDictionaryEnabled(): Boolean = getState().dictionaryEnabled

    fun isTranslationEnabled(): Boolean = getState().translationEnabled

    fun isMiniAppEnabled(appType: String): Boolean = when (appType) {
        DictionaryMiniAppInstance.APP_TYPE -> isDictionaryEnabled()
        TranslationMiniAppInstance.APP_TYPE -> isTranslationEnabled()
        else -> true
    }

    fun setDictionaryEnabled(enabled: Boolean): Boolean =
        updateState { it.copy(dictionaryEnabled = enabled) }

    fun setTranslationEnabled(enabled: Boolean): Boolean =
        updateState { it.copy(translationEnabled = enabled) }

    /**
     * Synchronizes Android PROCESS_TEXT component visibility with persisted master switches.
     * Translate is declared disabled in the manifest and only enabled when explicitly switched on.
     */
    fun syncProcessTextComponents() {
        try {
            // Hold the same cross-process lock through PackageManager updates. This prevents a
            // startup sync in one process from overwriting a newer toggle from the other process.
            withFileLock {
                val state = readStateLocked()
                setComponentEnabled(
                    com.example.feature.selection.MeaningSelectionActivity::class.java,
                    state.dictionaryEnabled
                )
                setComponentEnabled(
                    com.example.feature.selection.TranslateSelectionActivity::class.java,
                    state.translationEnabled
                )
            }
        } catch (t: Throwable) {
            LogKeeper.logError(appContext, TAG, "Could not synchronize PROCESS_TEXT components", t)
        }
    }

    private fun updateState(transform: (LanguageFeatureState) -> LanguageFeatureState): Boolean {
        val saved = try {
            withFileLock {
                writeStateLocked(transform(readStateLocked()))
            }
        } catch (t: Throwable) {
            LogKeeper.logError(appContext, TAG, "Could not save language feature switches", t)
            false
        }

        if (saved) {
            syncProcessTextComponents()
            val state = getState()
            LogKeeper.log(
                appContext,
                TAG,
                "MASTER_SWITCH_UPDATED dictionary_enabled=${state.dictionaryEnabled} translation_enabled=${state.translationEnabled}"
            )
            try {
                appContext.sendBroadcast(
                    Intent(ACTION_FEATURE_SETTINGS_CHANGED).setPackage(appContext.packageName)
                )
            } catch (t: Throwable) {
                LogKeeper.logError(appContext, TAG, "Could not notify Main process of feature switch change", t)
            }
        }
        return saved
    }

    private fun setComponentEnabled(componentClass: Class<*>, enabled: Boolean) {
        try {
            appContext.packageManager.setComponentEnabledSetting(
                ComponentName(appContext, componentClass),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (t: Throwable) {
            // Activity-level feature guards remain authoritative if a ROM rejects visibility changes.
            LogKeeper.logError(
                appContext,
                TAG,
                "Could not update PROCESS_TEXT component ${componentClass.simpleName} enabled=$enabled",
                t
            )
        }
    }

    private val backupFile: File
        get() = File(stateFile.parentFile, "${stateFile.name}.bak")

    private val pendingFile: File
        get() = File(stateFile.parentFile, "${stateFile.name}.new")

    /**
     * Recover the previous committed state if the process died between the two same-directory
     * renames in writeStateLocked(). A leftover .new file was never committed and is discarded.
     */
    private fun recoverStateFileLocked() {
        if (backupFile.exists()) {
            if (stateFile.exists()) {
                backupFile.delete()
            } else {
                backupFile.renameTo(stateFile)
            }
        }
        if (pendingFile.exists()) pendingFile.delete()
    }

    private fun readStateLocked(): LanguageFeatureState {
        return try {
            recoverStateFileLocked()
            if (!stateFile.isFile) return LanguageFeatureState()
            stateFile.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val json = JSONObject(reader.readText())
                LanguageFeatureState(
                    dictionaryEnabled = json.optBoolean("dictionaryEnabled", true),
                    translationEnabled = json.optBoolean("translationEnabled", false)
                )
            }
        } catch (t: Throwable) {
            LogKeeper.logError(appContext, TAG, "Language feature switch file is unreadable; using safe defaults", t)
            LanguageFeatureState()
        }
    }

    /**
     * Write and fsync a sibling temp file, then swap it into place under the cross-process lock.
     * The backup makes the rename sequence recoverable after process death. This uses ordinary
     * java.io APIs so the same persistence path is exercised by local JVM tests as on-device.
     */
    private fun writeStateLocked(state: LanguageFeatureState): Boolean {
        return try {
            val parent = stateFile.parentFile
                ?: throw java.io.IOException("Feature state has no parent directory")
            if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                throw java.io.IOException("Could not create feature state directory")
            }
            recoverStateFileLocked()
            val json = JSONObject().apply {
                put("dictionaryEnabled", state.dictionaryEnabled)
                put("translationEnabled", state.translationEnabled)
            }
            FileOutputStream(pendingFile).use { output ->
                output.write(json.toString().toByteArray(StandardCharsets.UTF_8))
                output.flush()
                output.fd.sync()
            }

            if (stateFile.exists()) {
                if (backupFile.exists() && !backupFile.delete()) {
                    throw java.io.IOException("Could not clear previous feature state backup")
                }
                if (!stateFile.renameTo(backupFile)) {
                    throw java.io.IOException("Could not preserve previous feature state")
                }
            }

            if (!pendingFile.renameTo(stateFile)) {
                if (backupFile.exists() && !stateFile.exists()) backupFile.renameTo(stateFile)
                throw java.io.IOException("Could not commit new feature state")
            }
            backupFile.delete()
            true
        } catch (t: Throwable) {
            pendingFile.delete()
            if (backupFile.exists() && !stateFile.exists()) backupFile.renameTo(stateFile)
            LogKeeper.logError(appContext, TAG, "Could not atomically write language feature switches", t)
            false
        }
    }

    private fun <T> withFileLock(block: () -> T): T = synchronized(processLock) {
        lockFile.parentFile?.mkdirs()
        RandomAccessFile(lockFile, "rw").use { randomAccessFile ->
            val lock = randomAccessFile.channel.lock()
            try {
                block()
            } finally {
                lock.release()
            }
        }
    }

    companion object {
        private const val TAG = "LanguageFeatureSettings"
        const val ACTION_FEATURE_SETTINGS_CHANGED =
            "com.example.action.LANGUAGE_FEATURE_SETTINGS_CHANGED"

        @Volatile
        private var instance: LanguageFeatureSettings? = null

        fun getInstance(context: Context): LanguageFeatureSettings {
            return instance ?: synchronized(this) {
                instance ?: LanguageFeatureSettings(context).also { instance = it }
            }
        }

        internal fun resetForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }
}
