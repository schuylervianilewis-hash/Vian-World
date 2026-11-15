package shura.home.helpers

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compatibility facade for existing launcher call sites.
 *
 * Log storage and uncaught-exception handling are owned by Sidebar's LogKeeper.
 * This class intentionally does not install a Thread default exception handler
 * and does not create a second private log store.
 */
object LogCatcher {
    data class LogEntry(
        val timestampMillis: Long,
        val timestampDisplay: String,
        val tag: String,
        val message: String,
        val throwableDetails: String? = null
    )

    interface Backend {
        fun log(tag: String, message: String, throwable: Throwable?)
        fun logCrash(threadName: String, throwable: Throwable)
        fun readLogs(): String
        fun clearLogs()
        fun setEnabled(enabled: Boolean)
    }

    @Volatile
    private var backend: Backend? = null

    fun setBackend(value: Backend) {
        backend = value
    }

    fun init(context: Context) {
        // The Application installs Sidebar LogKeeper in every process.
    }

    fun log(tag: String, message: String, throwable: Throwable? = null) {
        backend?.log(tag, message, throwable)
    }

    fun logCrash(throwable: Throwable) {
        backend?.logCrash(Thread.currentThread().name, throwable)
    }

    fun getRawLogs(): String = backend?.readLogs().orEmpty()

    fun clearLogs() {
        backend?.clearLogs()
    }

    fun setLoggingEnabled(enabled: Boolean) {
        backend?.setEnabled(enabled)
    }

    fun getLogEntries(): List<LogEntry> {
        val raw = getRawLogs()
        if (raw.isBlank()) return emptyList()

        val parser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val display = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val entries = ArrayList<LogEntry>()
        var timestampMillis = 0L
        var timestampDisplay = ""
        var tag = ""
        var message = ""
        val stack = StringBuilder()

        fun flush() {
            if (timestampMillis != 0L) {
                entries.add(
                    LogEntry(
                        timestampMillis = timestampMillis,
                        timestampDisplay = timestampDisplay,
                        tag = tag,
                        message = message,
                        throwableDetails = stack.toString().trim().ifBlank { null }
                    )
                )
            }
            stack.clear()
        }

        raw.lineSequence().forEach { line ->
            if (line.startsWith("[")) {
                val timestampEnd = line.indexOf("] ")
                if (timestampEnd > 1) {
                    val fields = ArrayList<String>(3)
                    var remaining = line.substring(timestampEnd + 2)
                    repeat(3) {
                        if (!remaining.startsWith("[")) return@repeat
                        val fieldEnd = remaining.indexOf("] ")
                        if (fieldEnd <= 1) return@repeat
                        fields.add(remaining.substring(1, fieldEnd))
                        remaining = remaining.substring(fieldEnd + 2)
                    }
                    if (fields.size == 3) {
                        val parsed = try { parser.parse(line.substring(1, timestampEnd)) } catch (_: Exception) { null }
                        if (parsed != null) {
                            flush()
                            timestampMillis = parsed.time
                            timestampDisplay = display.format(Date(timestampMillis))
                            tag = fields[2].removePrefix("ERROR:")
                            message = remaining
                            return@forEach
                        }
                    }
                }
            }
            if (timestampMillis != 0L && line.isNotBlank()) {
                if (stack.isNotEmpty()) stack.append('\n')
                stack.append(line)
            }
        }
        flush()
        return entries.asReversed()
    }

    fun dumpLogsToDownloadFolder(
        context: Context,
        isCrash: Boolean = false,
        isAutoDump: Boolean = false
    ): Boolean {
        val raw = getRawLogs()
        if (raw.isBlank()) return false
        val prefix = when {
            isCrash -> "CRASH DUMP:\n"
            isAutoDump -> "AUTO DUMP:\n"
            else -> ""
        }
        val content = prefix + raw + "\n"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "vian_world_logs_" + stamp + ".txt"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return saveFallback(context, fileName, content)
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(content.toByteArray(Charsets.UTF_8))
                    it.flush()
                } ?: return false
                true
            } else {
                saveFallback(context, fileName, content)
            }
        } catch (_: Exception) {
            saveFallback(context, fileName, content)
        }
    }

    private fun saveFallback(context: Context, fileName: String, content: String): Boolean {
        return try {
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            if (!directory.exists() && !directory.mkdirs()) return false
            FileOutputStream(File(directory, fileName), false).use {
                it.write(content.toByteArray(Charsets.UTF_8))
                it.flush()
                it.fd.sync()
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
