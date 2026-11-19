package com.example.core

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared, process-aware diagnostic and crash logger.
 * Ordinary diagnostics use a bounded asynchronous queue. Fatal crash records are synchronous.
 * The stable lock file coordinates appends/rotation across default, :home and :heavy processes.
 */
object LogKeeper {
    private const val TAG = "LogKeeper"
    const val FILE_DIAGNOSTIC_LOG = "LiteReader_Log.txt"
    const val FILE_CRASH_LOG = "LiteReader_CrashLog.txt"
    const val FILE_EXECUTION_LOG = "LiteReader_ExecutionLog.txt"
    private const val MAX_LOG_SIZE = 256 * 1024L
    private const val QUEUE_CAPACITY = 512
    private const val MAX_EXECUTION_LOG_SIZE = 256 * 1024L

    private val timestampFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
        .withZone(ZoneId.systemDefault())
    private val queue = ArrayBlockingQueue<PendingWrite>(QUEUE_CAPACITY)
    private val workerStarted = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)
    private val droppedEntries = AtomicLong(0)
    private val executionLock = Any()
    private val activeExecutions = ConcurrentHashMap<String, ExecutionEvent>()
    private val recentExecutionEvents = ArrayDeque<ExecutionEvent>()
    private const val MAX_RECENT_EXECUTION_EVENTS = 256
    private val sensitiveValuePatterns = listOf(
        Regex("(?i)(authorization\\s*[:=]\\s*)(?:bearer\\s+)?[^\\s,;]+"),
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+"),
        Regex("(?i)((?:password|passwd|token|secret|api[_-]?key|access[_-]?key)\\s*[:=]\\s*)[^\\s,;&]+"),
        Regex("(?i)([?&](?:token|key|password|secret|auth)=)[^&\\s]+")
    )

    @Volatile private var isMasterEnabled = true
    @Volatile private var cachedProcessName: String? = null
    @Volatile private var appContext: Context? = null

    private data class PendingWrite(val fileName: String, val text: String)

    data class ExecutionEvent(
        val id: String,
        val component: String,
        val operation: String,
        val process: String,
        val pid: Int,
        val thread: String,
        val timestampMillis: Long,
        val event: String,
        val detail: String = ""
    )

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        cachedProcessName = getProcessName(app)
        startWriterIfNeeded()

        // App.onCreate runs once per process, but guard against accidental repeated init calls.
        if (!initialized.compareAndSet(false, true)) return

        val current = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                logCrash(app, thread.name, throwable)
                logLifecycle(app, "Process", "UNCAUGHT_CRASH",
                    "Uncaught exception in thread '${thread.name}': ${throwable.javaClass.name}")
                try {
                    MainProcessRecovery.handleProcessCrash(app, thread.name, throwable)
                } catch (_: Exception) {
                    // Crash recording must not depend on recovery scheduling.
                }
            } finally {
                current?.uncaughtException(thread, throwable)
            }
        }

        log(app, "LogKeeper", "Initialized logger in process: $cachedProcessName")
        if (app is Application) {
            val activityTokens = WeakHashMap<Activity, String>()
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    logLifecycle(activity, activity.javaClass.simpleName, "CREATED")
                }
                override fun onActivityStarted(activity: Activity) {
                    logLifecycle(activity, activity.javaClass.simpleName, "STARTED")
                    val token = beginExecution(app, "Activity", activity.javaClass.simpleName, allowConcurrent = true)
                    synchronized(activityTokens) { activityTokens[activity] = token }
                }
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {
                    logLifecycle(activity, activity.javaClass.simpleName, "STOPPED")
                    val token = synchronized(activityTokens) { activityTokens.remove(activity) }
                    if (token != null) finishExecution(app, token, "COMPLETED", "Activity stopped")
                }
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {
                    logLifecycle(activity, activity.javaClass.simpleName, "DESTROYED")
                    val token = synchronized(activityTokens) { activityTokens.remove(activity) }
                    if (token != null) finishExecution(app, token, "COMPLETED", "Activity destroyed")
                }
            })
        }
    }

    private fun startWriterIfNeeded() {
        if (!workerStarted.compareAndSet(false, true)) return
        Thread({
            while (true) {
                try {
                    val batch = ArrayList<PendingWrite>(64)
                    batch.add(queue.take())
                    queue.drainTo(batch, 63)
                    val grouped = LinkedHashMap<String, StringBuilder>()
                    for (pending in batch) {
                        grouped.getOrPut(pending.fileName) { StringBuilder() }.append(pending.text)
                    }
                    val dropped = droppedEntries.getAndSet(0)
                    if (dropped > 0) {
                        grouped.getOrPut(FILE_DIAGNOSTIC_LOG) { StringBuilder() }
                            .append("[${now()}] [${cachedProcessName ?: "unknown"}] [LogKeeperWriter] [LogKeeper] Dropped $dropped diagnostic entries because the bounded queue was full.\\n")
                    }
                    val context = pendingContext()
                    for ((fileName, contents) in grouped) appendToFile(context, fileName, contents.toString())
                } catch (interrupted: InterruptedException) {
                    // This worker is process-lifetime infrastructure; continue unless the process exits.
                    Thread.currentThread().interrupt()
                    return@Thread
                } catch (error: Exception) {
                    Log.e(TAG, "Asynchronous log write failed", error)
                }
            }
        }, "Vian-LogWriter").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun pendingContext(): Context =
        appContext ?: throw IllegalStateException("LogKeeper is not initialized")

    fun setMasterEnabled(enabled: Boolean) {
        isMasterEnabled = enabled
    }

    fun isEnabled(): Boolean = isMasterEnabled && appContext?.let { isLoggingAllowed(it) } != false

    @Suppress("DEPRECATION")
    private fun isLoggingAllowed(context: Context): Boolean {
        if (!isMasterEnabled) return false
        return try {
            context.getSharedPreferences(
                "Prefs",
                Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
            ).getBoolean("log_keeper_enabled", true)
        } catch (_: Exception) {
            isMasterEnabled
        }
    }

    fun getProcessName(context: Context): String {
        cachedProcessName?.let { return it }
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            try {
                val pid = Process.myPid()
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: "pid-$pid"
            } catch (_: Exception) {
                "unknown"
            }
        }
        cachedProcessName = name
        return name
    }

    fun writeLog(tag: String, message: String) {
        val safe = sanitize(message)
        Log.d(tag, safe)
        appContext?.let { log(it, tag, safe) }
    }

    /** Skip regex passes for ordinary text; redact likely credential-bearing strings when hints exist. */
    fun sanitize(value: String): String {
        if (!(value.contains("://") || value.contains('/') || value.contains('.') ||
                value.contains('=') || value.contains(':'))) return value
        var safe = value
        for (pattern in sensitiveValuePatterns) {
            safe = pattern.replace(safe) { match -> match.groupValues[1] + "[REDACTED]" }
        }
        return safe
    }

    fun logLifecycle(context: Context, component: String, event: String, details: String = "") {
        val msg = if (details.isNotEmpty()) "$event - $details" else event
        log(context, "Lifecycle", "[$component] $msg")
    }

    fun log(context: Context, component: String, message: String) {
        if (!isLoggingAllowed(context)) return
        val line = "[${now()}] [${getProcessName(context)}] [${Thread.currentThread().name}] [$component] ${sanitize(message)}\n"
        enqueue(FILE_DIAGNOSTIC_LOG, line)
    }

    fun logError(context: Context, component: String, message: String, throwable: Throwable? = null) {
        if (!isLoggingAllowed(context)) return
        val timestamp = now()
        val process = getProcessName(context)
        val thread = Thread.currentThread().name
        val sb = StringBuilder()
        sb.append("[$timestamp] [$process] [$thread] [ERROR:$component] ${sanitize(message)}\n")
        if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            sb.append(sanitize(sw.toString())).append('\n')
        }
        val entry = sb.toString()
        if (throwable == null) {
            enqueue(FILE_DIAGNOSTIC_LOG, entry)
        } else {
            // Caught exceptions are important but are not fatal: keep their normal path off the
            // caller thread. If the bounded queue is saturated, fall back to a synchronous write.
            enqueueImportant(FILE_DIAGNOSTIC_LOG, entry)
            enqueueImportant(FILE_CRASH_LOG, entry)
        }
    }

    fun logCrash(context: Context, threadName: String, throwable: Throwable) {
        try {
            val timestamp = now()
            val process = getProcessName(context)
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val record = """
                ================================================================
                === UNCAUGHT EXCEPTION CRASH REPORT ===
                Timestamp: $timestamp
                Process: $process
                Thread: $threadName
                Exception: ${throwable.javaClass.name}
                Message: ${sanitize(throwable.message ?: "No message")}
                StackTrace:
                ${sanitize(sw.toString())}
                ================================================================

            """.trimIndent()
            // Fatal records bypass the queue so they survive immediate process termination.
            appendToFile(context, FILE_CRASH_LOG, record)
            appendToFile(context, FILE_DIAGNOSTIC_LOG,
                "[$timestamp] [$process] [$threadName] [CRASH] ${throwable.javaClass.name}: ${sanitize(throwable.message ?: "No message")}\n")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to record crash log", error)
        }
    }

    private fun now(): String = timestampFormatter.format(Instant.now())

    private fun enqueue(fileName: String, text: String) {
        startWriterIfNeeded()
        if (!queue.offer(PendingWrite(fileName, text))) droppedEntries.incrementAndGet()
    }

    /** Keep caught exceptions asynchronous in normal operation; only saturate-to-disk on overflow. */
    private fun enqueueImportant(fileName: String, text: String) {
        startWriterIfNeeded()
        if (queue.offer(PendingWrite(fileName, text))) return
        val context = appContext
        if (context != null) {
            appendToFile(context, fileName, text)
        } else {
            droppedEntries.incrementAndGet()
        }
    }

    /** All processes lock the same stable sidecar file, including while the log is compacted. */
    @Synchronized
    private fun appendToFile(context: Context, fileName: String, text: String) {
        val logFile = File(context.filesDir, fileName)
        val lockFile = File(context.filesDir, "$fileName.lock")
        try {
            FileOutputStream(lockFile, true).use { lockStream ->
                lockStream.channel.lock().use {
                    if (logFile.exists() && logFile.length() + text.toByteArray(Charsets.UTF_8).size > MAX_LOG_SIZE) {
                        compactFile(logFile, MAX_LOG_SIZE / 2)
                    }
                    FileOutputStream(logFile, true).use { output ->
                        output.write(text.toByteArray(Charsets.UTF_8))
                        output.flush()
                        if (fileName == FILE_CRASH_LOG) output.fd.sync()
                    }
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to append $fileName", error)
        }
    }

    /** Stream the newest tail to a temporary file; never read the full log into a String. */
    private fun compactFile(source: File, targetBytes: Long) {
        val temp = File(source.parentFile, source.name + ".compact")
        try {
            val length = source.length()
            val start = (length - targetBytes).coerceAtLeast(0L)
            BufferedInputStream(FileInputStream(source)).use { input ->
                var remaining = start
                while (remaining > 0L) {
                    val skipped = input.skip(remaining)
                    if (skipped <= 0L) {
                        if (input.read() == -1) break
                        remaining--
                    } else remaining -= skipped
                }
                // Drop a partial first line so the retained file starts at a record boundary.
                if (start > 0L) while (true) {
                    val b = input.read()
                    if (b == -1 || b == '\n'.code) break
                }
                BufferedOutputStream(FileOutputStream(temp, false)).use { output ->
                    output.write("[... logs truncated ...]\n".toByteArray(Charsets.UTF_8))
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            }
            try {
                Files.move(temp.toPath(), source.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temp.toPath(), source.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            temp.delete()
            Log.e(TAG, "Failed to compact ${source.name}", error)
        }
    }

    /** Start a tracked execution. This records observation only; concurrency policy stays with the caller. */
    fun beginExecution(
        context: Context,
        component: String,
        operation: String,
        allowConcurrent: Boolean = false
    ): String {
        val id = UUID.randomUUID().toString()
        val event = ExecutionEvent(id, component, operation, getProcessName(context),
            Process.myPid(), Thread.currentThread().name, System.currentTimeMillis(), "STARTED")
        synchronized(executionLock) {
            val active = activeExecutions.values.filter {
                it.component == component && it.operation == operation && it.pid == event.pid
            }
            if (active.isNotEmpty() && !allowConcurrent) {
                writeExecutionEvent(context, event.copy(event = "OVERLAP",
                    detail = "Concurrent execution already active; count=${active.size}"))
            }
            activeExecutions[id] = event
            writeExecutionEvent(context, event)
        }
        return id
    }

    fun finishExecution(context: Context, executionId: String, outcome: String = "COMPLETED", detail: String = "") {
        if (executionId.isBlank()) return
        // Ignore duplicate/late finish callbacks instead of recording misleading "unknown" events.
        val start = synchronized(executionLock) { activeExecutions.remove(executionId) } ?: return
        val safeOutcome = outcome.uppercase(java.util.Locale.US).take(32)
        writeExecutionEvent(context, ExecutionEvent(
            executionId, start.component, start.operation, start.process, start.pid,
            Thread.currentThread().name, System.currentTimeMillis(), safeOutcome, sanitize(detail).take(500)
        ))
    }

    fun getExecutionEvents(context: Context): List<ExecutionEvent> {
        val recent = synchronized(executionLock) {
            recentExecutionEvents.toList() + activeExecutions.values
        }
        return (readExecutionEvents(context) + recent)
            .distinctBy { "${it.id}:${it.event}:${it.timestampMillis}" }
            .sortedByDescending { it.timestampMillis }
    }

    private fun writeExecutionEvent(context: Context, event: ExecutionEvent) {
        synchronized(executionLock) {
            recentExecutionEvents.addLast(event)
            while (recentExecutionEvents.size > MAX_RECENT_EXECUTION_EVENTS) recentExecutionEvents.removeFirst()
        }
        val safeDetail = sanitize(event.detail).replace("|", "_").replace("\n", " ")
        val line = listOf(
            event.id, event.component.replace("|", "_"), event.operation.replace("|", "_"),
            event.process.replace("|", "_"), event.pid.toString(), event.thread.replace("|", "_"),
            event.timestampMillis.toString(), event.event, safeDetail
        ).joinToString("|") + "\n"
        enqueue(FILE_EXECUTION_LOG, line)
    }

    private fun readExecutionEvents(context: Context): List<ExecutionEvent> {
        val file = File(context.filesDir, FILE_EXECUTION_LOG)
        if (!file.exists()) return emptyList()
        return try {
            file.useLines { lines ->
                lines.mapNotNull { line ->
                    val p = line.split('|', limit = 9)
                    if (p.size < 8) null else try {
                        ExecutionEvent(p[0], p[1], p[2], p[3], p[4].toInt(),
                            p[5], p[6].toLong(), p[7], p.getOrElse(8) { "" })
                    } catch (_: Exception) { null }
                }.toList()
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun hasMatchingFinish(context: Context, id: String): Boolean =
        readExecutionEvents(context).any { it.id == id && it.event != "STARTED" }

    fun getDiagnosticLogs(context: Context): String = readLogFile(context, FILE_DIAGNOSTIC_LOG)
    fun getCrashLogs(context: Context): String = readLogFile(context, FILE_CRASH_LOG)
    fun getLogs(context: Context): String = getDiagnosticLogs(context)

    private fun readLogFile(context: Context, fileName: String): String = try {
        val file = File(context.filesDir, fileName)
        if (file.exists() && file.length() > 0L) file.readText() else "No records in $fileName."
    } catch (error: Exception) {
        "Error reading $fileName: ${error.message}"
    }

    fun clearDiagnosticLogs(context: Context) = deleteLogFile(context, FILE_DIAGNOSTIC_LOG)
    fun clearCrashLogs(context: Context) = deleteLogFile(context, FILE_CRASH_LOG)
    fun clearLogs(context: Context) {
        clearDiagnosticLogs(context)
        clearCrashLogs(context)
    }
    fun clearExecutionLogs(context: Context) {
        synchronized(executionLock) { recentExecutionEvents.clear() }
        deleteLogFile(context, FILE_EXECUTION_LOG)
    }

    @Synchronized
    private fun deleteLogFile(context: Context, fileName: String) {
        try {
            FileOutputStream(File(context.filesDir, "$fileName.lock"), true).use { lockStream ->
                lockStream.channel.lock().use {
                    File(context.filesDir, fileName).delete()
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to clear $fileName", error)
        }
    }
}
