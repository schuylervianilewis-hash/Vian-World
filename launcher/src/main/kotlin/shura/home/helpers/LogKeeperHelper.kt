package shura.home.helpers

import android.content.Context

/**
 * Backward-compatible facade delegating directly to [LogCatcher].
 * Preserves all existing call sites across MainActivity and AllAppsFragment.
 */
class LogKeeperHelper(context: Context) {
    init {
        LogCatcher.init(context)
    }

    fun log(tag: String, message: String, throwable: Throwable? = null) {
        LogCatcher.log(tag, message, throwable)
    }

    fun logCrash(throwable: Throwable) {
        LogCatcher.logCrash(throwable)
    }

    fun getLogs(): String {
        return LogCatcher.getRawLogs()
    }

    fun clearLogs() {
        LogCatcher.clearLogs()
    }
}
