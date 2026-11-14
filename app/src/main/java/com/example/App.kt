package com.example

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Process
import com.example.core.FloatingWindowManager
import com.example.core.LogKeeper
import org.fossify.commons.FossifyApp
import shura.home.helpers.AddElementBridge
import shura.home.helpers.Config
import shura.home.helpers.LogCatcher

/**
 * Single Vian World Application entry point.
 *
 * Sidebar keeps its resident runtime in the default process. The Android-managed launcher
 * Home Activity lives in :home, while Settings and other heavyweight UI live in :heavy.
 */
class App : FossifyApp() {

    override fun onCreate() {
        super.onCreate()

        // One crash/logging foundation is installed by Sidebar in each process.
        LogKeeper.init(this)
        LogCatcher.setBackend(object : LogCatcher.Backend {
            override fun log(tag: String, message: String, throwable: Throwable?) {
                if (throwable == null) {
                    LogKeeper.log(this@App, tag, message)
                } else {
                    LogKeeper.logError(this@App, tag, message, throwable)
                }
            }

            override fun logCrash(threadName: String, throwable: Throwable) {
                LogKeeper.logCrash(this@App, threadName, throwable)
            }

            override fun readLogs(): String {
                return LogKeeper.getDiagnosticLogs(this@App) +
                    "\n\n" + LogKeeper.getCrashLogs(this@App)
            }

            override fun clearLogs() {
                LogKeeper.clearLogs(this@App)
            }

            override fun setEnabled(enabled: Boolean) {
                LogKeeper.setMasterEnabled(enabled)
            }
        })
        LogCatcher.init(this)

        when {
            isProcess(":home") -> {
                initializeLauncherTheme()
                installSidebarAddElementDelegate()
            }
            isProcess(":heavy") -> {
                // Launcher Settings uses the same Fossify-backed preference/theme layer,
                // but Home-only integrations are deliberately not installed here.
                initializeLauncherTheme()
            }
            else -> {
                // Keep Sidebar's resident default process lightweight.
            }
        }
    }

    private fun initializeLauncherTheme() {
        try {
            val config = Config(this)
            if (!config.sharedPrefs.getBoolean("vian_home_light_theme_applied", false)) {
                config.backgroundColor = Color.parseColor("#FAFAFA")
                config.textColor = Color.parseColor("#212121")
                val skyBlue = Color.parseColor("#0288D1")
                config.primaryColor = skyBlue
                config.accentColor = skyBlue
                config.appIconColor = skyBlue
                config.sharedPrefs.edit()
                    .putBoolean("vian_home_light_theme_applied", true)
                    .apply()
            }
        } catch (_: Exception) {
            // Theme defaults must never prevent Sidebar or the Home Activity from starting.
        }
    }

    private fun installSidebarAddElementDelegate() {
        AddElementBridge.setDelegate(object : AddElementBridge.Delegate {
            override fun openAddElementUi(
                activity: shura.home.activities.MainActivity,
                targetX: Float?,
                targetY: Float?
            ) {
                val intent = Intent(
                    activity,
                    com.example.feature.settings.AddElementActivity::class.java
                ).putExtra("SIDEBAR_HOME_PICKER", true)
                activity.startActivityForResult(
                    intent,
                    AddElementBridge.REQUEST_SIDEBAR_ELEMENT
                )
            }
        })
    }

    private fun isProcess(suffix: String): Boolean =
        getProcessNameCompat()?.endsWith(suffix) == true

    private fun getProcessNameCompat(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.app.Application.getProcessName()
        } else {
            try {
                val pid = Process.myPid()
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            isProcess(":home") -> {
                // The launcher owns its own Home lifecycle/cache cleanup.
            }
            isProcess(":heavy") -> {
                com.example.feature.floating.HeavyFloatingHost.getInstance(this).onTrimMemory(level)
            }
            else -> {
                FloatingWindowManager.getInstance(this).onTrimMemory(level)
            }
        }
    }
}
