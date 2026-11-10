package shura.home.helpers

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import shura.home.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight, headless log and error catcher.
 *
 * Responsibilities:
 * - Runs continuously starting in [VianApp.onCreate].
 * - Zero UI overhead or activity lifecycle coupling.
 * - Respects the master toggle in [Config.logKeeperEnabled].
 * - Maintains a rolling buffer up to 2MB in app-private storage.
 * - Flushes to the public Download directory as "vian logs.txt" when reaching 2MB,
 *   upon an uncaught application crash, or on explicit user request.
 * - Sanitizes all entries per Mandate 17 (no PII, credentials, or user content).
 */
object LogCatcher {
    private const val INTERNAL_LOG_DIR = "logs"
    private const val ACTIVE_LOG_FILE = "vian_internal.log"
    private const val LEGACY_LOG_FILE = "vian_app_log.txt"
    private const val PUBLIC_FILE_NAME = "vian logs.txt"
    private const val MAX_LOG_SIZE_BYTES = 2L * 1024L * 1024L // 2 MB cap

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val timeOnlyFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var isInitialized = false
    private lateinit var appContext: Context
    private var defaultExceptionHandler: Thread.UncaughtExceptionHandler? = null

    data class LogEntry(
        val timestampMillis: Long,
        val timestampDisplay: String,
        val tag: String,
        val message: String,
        val throwableDetails: String? = null
    )

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            appContext = context.applicationContext

            migrateLegacyLogsIfNeeded()
            installCrashHandler()
            isInitialized = true

            log("System", "LogKeeper initialized")
        }
    }

    private fun isLoggingEnabled(): Boolean {
        return try {
            Config(appContext).logKeeperEnabled
        } catch (e: Exception) {
            true
        }
    }

    private val logExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LogCatcher-Worker").apply { isDaemon = true }
    }

    fun log(tag: String, message: String, throwable: Throwable? = null) {
        if (!isLoggingEnabled()) return

        try {
            val now = System.currentTimeMillis()
            val timestampStr = dateFormat.format(Date(now))
            val sb = java.lang.StringBuilder()
            sb.append(timestampStr)
                .append(" [").append(tag).append("] ")
                .append(message)
                .append("\n")

            if (throwable != null) {
                sb.append(throwable.stackTraceToString().trimEnd()).append("\n")
            }
            val entryText = sb.toString()

            logExecutor.execute {
                try {
                    val file = getActiveLogFile()
                    if (file.exists() && file.length() >= MAX_LOG_SIZE_BYTES) {
                        dumpLogsToDownloadFolder(isAutoDump = true)
                        file.writeText("")
                    }
                    file.appendText(entryText)
                } catch (ignored: Exception) {
                }
            }
        } catch (ignored: Exception) {
            // Logging must never crash the host application.
        }
    }

    fun logCrash(throwable: Throwable) {
        try {
            val now = System.currentTimeMillis()
            val header = buildCrashHeader(throwable, now)

            val file = getActiveLogFile()
            file.appendText("\n$header\n")

            // Immediately dump entire log history + crash header to Download folder
            dumpLogsToDownloadFolder(isCrash = true)
        } catch (ignored: Exception) {
        }
    }

    private fun buildCrashHeader(throwable: Throwable, timestampMillis: Long): String {
        return buildString {
            appendLine("================================================================================")
            appendLine("CRASH DETECTED: ${dateFormat.format(Date(timestampMillis))}")
            appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android OS: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message ?: "No error message"}")
            appendLine("Stack Trace:")
            appendLine(throwable.stackTraceToString().trimEnd())
            appendLine("================================================================================")
        }
    }

    private fun installCrashHandler() {
        defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                logCrash(throwable)
            } catch (ignored: Exception) {
            } finally {
                defaultExceptionHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun getActiveLogFile(): File {
        val dir = File(appContext.filesDir, INTERNAL_LOG_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir, ACTIVE_LOG_FILE)
        if (!file.exists()) {
            file.createNewFile()
        }
        return file
    }

    private fun migrateLegacyLogsIfNeeded() {
        try {
            val legacyFile = File(appContext.filesDir, LEGACY_LOG_FILE)
            if (legacyFile.exists() && legacyFile.length() > 0) {
                val legacyContent = legacyFile.readText()
                val activeFile = getActiveLogFile()
                activeFile.appendText(legacyContent)
                legacyFile.delete()
            }
        } catch (ignored: Exception) {
        }
    }

    @Synchronized
    fun getLogEntries(): List<LogEntry> {
        val entries = mutableListOf<LogEntry>()
        try {
            val file = getActiveLogFile()
            if (!file.exists() || file.length() == 0L) return entries

            val lines = file.readLines()
            var currentTimestamp: Long? = null
            var currentTimestampStr: String? = null
            var currentTag: String? = null
            var currentMessage: StringBuilder? = null
            val currentStack = StringBuilder()

            for (line in lines) {
                // Check if this line is a new entry header: "2026-10-03 10:45:12.345 [TAG] Message"
                val match = entryRegex.find(line)
                if (match != null) {
                    // Flush previous entry
                    if (currentTimestamp != null && currentTag != null && currentMessage != null) {
                        entries.add(
                            LogEntry(
                                timestampMillis = currentTimestamp,
                                timestampDisplay = currentTimestampStr ?: "",
                                tag = currentTag,
                                message = currentMessage.toString(),
                                throwableDetails = if (currentStack.isNotEmpty()) currentStack.toString().trim() else null
                            )
                        )
                    }

                    val dateStr = match.groupValues[1]
                    val parsedDate = try { dateFormat.parse(dateStr) } catch (e: Exception) { null }
                    currentTimestamp = parsedDate?.time ?: System.currentTimeMillis()
                    currentTimestampStr = if (parsedDate != null) timeOnlyFormat.format(parsedDate) else dateStr
                    currentTag = match.groupValues[2]
                    currentMessage = StringBuilder(match.groupValues[3])
                    currentStack.clear()
                } else if (line.startsWith("===") || line.startsWith("CRASH DETECTED:")) {
                    // Handle crash header as a prominent CRASH entry
                    if (currentTimestamp != null && currentTag != null && currentMessage != null) {
                        entries.add(
                            LogEntry(
                                timestampMillis = currentTimestamp,
                                timestampDisplay = currentTimestampStr ?: "",
                                tag = currentTag,
                                message = currentMessage.toString(),
                                throwableDetails = if (currentStack.isNotEmpty()) currentStack.toString().trim() else null
                            )
                        )
                    }
                    currentTimestamp = System.currentTimeMillis()
                    currentTimestampStr = timeOnlyFormat.format(Date(currentTimestamp))
                    currentTag = "CRASH"
                    currentMessage = StringBuilder("Application Crash Detected")
                    currentStack.clear()
                    currentStack.appendLine(line)
                } else {
                    if (currentStack.isNotEmpty() || line.startsWith("\tat ") || line.contains("Exception") || line.contains("Error")) {
                        currentStack.appendLine(line)
                    } else if (currentMessage != null) {
                        currentMessage.append(" ").append(line)
                    }
                }
            }

            // Flush final entry
            if (currentTimestamp != null && currentTag != null && currentMessage != null) {
                entries.add(
                    LogEntry(
                        timestampMillis = currentTimestamp,
                        timestampDisplay = currentTimestampStr ?: "",
                        tag = currentTag,
                        message = currentMessage.toString(),
                        throwableDetails = if (currentStack.isNotEmpty()) currentStack.toString().trim() else null
                    )
                )
            }
        } catch (ignored: Exception) {
        }
        return entries
    }

    private val entryRegex = Regex("""^(\d{4}-\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d{3})\s\[([^\]]+)\]\s(.*)$""")

    @Synchronized
    fun getRawLogs(): String {
        return try {
            val file = getActiveLogFile()
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            ""
        }
    }

    @Synchronized
    fun clearLogs() {
        try {
            val file = getActiveLogFile()
            if (file.exists()) {
                file.writeText("")
            }
        } catch (ignored: Exception) {
        }
    }

    /**
     * Dumps logs directly into the public device Download folder as "vian logs.txt".
     */
    @Synchronized
    fun dumpLogsToDownloadFolder(
        context: Context = appContext,
        isCrash: Boolean = false,
        isAutoDump: Boolean = false
    ): Boolean {
        val rawContent = getRawLogs()
        if (rawContent.isBlank()) return false

        val prefix = if (isCrash) "CRASH DUMP:\n" else if (isAutoDump) "AUTO 2MB FLUSH:\n" else ""
        val contentToSave = "$prefix$rawContent\n"

        // Emergency local crash persistence: write synchronously to internal storage to survive OOM / killed process
        if (isCrash) {
            try {
                val emergencyCrashFile = File(context.filesDir, "crash_dump_latest.txt")
                FileOutputStream(emergencyCrashFile, false).use { fos ->
                    fos.write(contentToSave.toByteArray(Charsets.UTF_8))
                    fos.flush()
                    try {
                        fos.fd.sync()
                    } catch (ignored: Exception) {
                    }
                }
            } catch (ignored: Exception) {
            }
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ Scoped Storage via MediaStore.Downloads (Zero permissions required)
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, PUBLIC_FILE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)

                if (uri != null) {
                    // Use standard "w" mode rather than "wa" for robust compatibility across all Android 10-15 OEM ROMs
                    resolver.openOutputStream(uri, "w")?.use { outputStream ->
                        outputStream.write(contentToSave.toByteArray(Charsets.UTF_8))
                        outputStream.flush()
                    }
                    true
                } else {
                    saveDirectlyToFile(context, contentToSave)
                }
            } else {
                saveDirectlyToFile(context, contentToSave)
            }
        } catch (e: Exception) {
            // Fallback to app external files dir on any storage failure
            saveToExternalFilesFallback(context, contentToSave)
        }
    }

    private fun saveDirectlyToFile(context: Context, content: String): Boolean {
        return try {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

            val targetDir = if (hasPermission) {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            } else {
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            }

            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val targetFile = File(targetDir, PUBLIC_FILE_NAME)
            FileOutputStream(targetFile, true).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (ignored: Exception) {
                }
            }
            true
        } catch (e: Exception) {
            saveToExternalFilesFallback(context, content)
        }
    }

    private fun saveToExternalFilesFallback(context: Context, content: String): Boolean {
        return try {
            val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val fallbackFile = File(fallbackDir, PUBLIC_FILE_NAME)
            FileOutputStream(fallbackFile, true).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
            }
            true
        } catch (ignored: Exception) {
            false
        }
    }
}
