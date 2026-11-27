package com.example.utils

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import com.example.AppTrackerOpenerActivity
import com.example.feature.sidebar.TrackedAppInfo
import java.util.concurrent.TimeUnit

object AppTrackerHelper {

    fun isAppTrackerConfigured(context: Context): Boolean {
        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        if (com.example.core.PageManager.isPageTypePresentInPrefs(prefs, "app_tracker")) return true
        val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return com.example.core.PageManager.isPageTypePresentInPrefs(appPrefs, "app_tracker")
    }

    /**
     * Checks if the specific container currently hosts an App Tracker page.
     * Container isolation: only sync within the same container.
     */
    fun hasAppTrackerInContainer(context: Context, containerId: String): Boolean {
        return try {
            val pageManager = com.example.core.PageManager.getInstance(context)
            val pages = pageManager.getPageStack(containerId)
            pages.any { it.pageType == "app_tracker" }
        } catch (e: Exception) {
            false
        }
    }

    fun checkUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Returns best-effort active-app candidates from recent activity lifecycle events.
     *
     * Android does not expose a complete list of other apps' live background processes to
     * ordinary apps. This avoids presenting 24-hour/7-day usage history as a process inventory.
     * Candidates are activities observed as resumed/paused without a later stop/background event
     * in a bounded observation window, plus the current package reported by Vian's observer.
     */
    fun getRecentApps(context: Context, containerId: String = "sidebar"): List<TrackedAppInfo> {
        if (!checkUsageStatsPermission(context)) return emptyList()
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val now = System.currentTimeMillis()
        val windowStart = now - ACTIVE_CANDIDATE_WINDOW_MS
        val latestLifecycle = linkedMapOf<String, Pair<Int, Long>>()

        try {
            val events = usageStatsManager.queryEvents(windowStart, now)
            if (events != null) {
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: continue
                    val type = event.eventType
                    val isForeground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        type == UsageEvents.Event.ACTIVITY_RESUMED ||
                            type == UsageEvents.Event.ACTIVITY_PAUSED
                    } else {
                        type == UsageEvents.Event.MOVE_TO_FOREGROUND
                    }
                    val isBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        type == UsageEvents.Event.ACTIVITY_STOPPED
                    } else {
                        type == UsageEvents.Event.MOVE_TO_BACKGROUND
                    }
                    if (isForeground || isBackground) {
                        val previous = latestLifecycle[pkg]
                        if (previous == null || event.timeStamp >= previous.second) {
                            latestLifecycle[pkg] = type to event.timeStamp
                        }
                    }
                }
            }
        } catch (error: SecurityException) {
            com.example.core.LogKeeper.logError(
                context,
                "AppTrackerHelper",
                "Usage event query was denied; using only a fresh foreground observation",
                error
            )
        } catch (error: RuntimeException) {
            com.example.core.LogKeeper.logError(
                context,
                "AppTrackerHelper",
                "Usage event query failed; using only a fresh foreground observation",
                error
            )
        }

        val candidateTimes = latestLifecycle
            .filterValues { (type, timestamp) ->
                timestamp in windowStart..now && isForegroundLifecycleEvent(type)
            }
            .mapValues { it.value.second }
            .toMutableMap()

        // Covers an app already foregrounded before the bounded UsageEvents window.
        try {
            val foreground = com.example.core.OverlayAccessPolicy.getFreshForegroundPackage(context)
            if (!foreground.isNullOrBlank() && foreground != context.packageName) {
                candidateTimes[foreground] = now
            }
        } catch (_: Throwable) {
            // UsageEvents remains a best-effort source when Accessibility is unavailable.
        }

        val whitelist = getWhitelist(context, containerId)
        val pm = context.packageManager
        val trackedApps = mutableListOf<TrackedAppInfo>()

        for ((packageName, lastObserved) in candidateTimes) {
            if (packageName == context.packageName) continue
            if (packageName.contains("launcher", ignoreCase = true)) continue
            if (packageName in whitelist) continue

            try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                if ((appInfo.flags and ApplicationInfo.FLAG_STOPPED) != 0) continue
                val appName = pm.getApplicationLabel(appInfo).toString()
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                trackedApps.add(
                    TrackedAppInfo(
                        packageName = packageName,
                        appName = appName,
                        lastUsedTime = lastObserved,
                        isSystem = isSystem
                    )
                )
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                // The package may have been uninstalled after its usage event.
            } catch (_: SecurityException) {
                // Ignore package metadata unavailable to this process.
            }
        }

        return trackedApps.sortedWith(
            compareByDescending<TrackedAppInfo> { it.lastUsedTime }.thenBy { it.packageName }
        ).take(MAX_ACTIVE_CANDIDATES)
    }

    private fun isForegroundLifecycleEvent(eventType: Int): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                eventType == UsageEvents.Event.ACTIVITY_PAUSED
        } else {
            eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
        }

    private const val ACTIVE_CANDIDATE_WINDOW_MS = 30L * 60L * 1000L
    private const val MAX_ACTIVE_CANDIDATES = 28

    fun getContainerWhitelistKey(containerId: String): String =
        if (containerId.isNotBlank() && containerId != "sidebar") "handle_${containerId}_app_tracker_whitelist" else "app_tracker_whitelist_current"

    fun getForceStopWhitelistKey(containerId: String): String =
        if (containerId.isNotBlank() && containerId != "sidebar") "handle_${containerId}_force_stop_whitelist" else "force_stop_whitelist_current"

    fun syncOnDemand(context: Context, containerId: String) {
        // Clean sync without merging to prevent zombie whitelisted apps
        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        val forceStopKey = getForceStopWhitelistKey(containerId)
        val trackerKey = getContainerWhitelistKey(containerId)

        val trackerSet = prefs.getStringSet(trackerKey, null)
        val forceStopSet = prefs.getStringSet(forceStopKey, null)

        if (trackerSet != null && forceStopSet == null) {
            prefs.edit().putStringSet(forceStopKey, trackerSet).commit()
            com.example.core.OverlaySyncManager.syncStringSet(context, forceStopKey, trackerSet)
        } else if (forceStopSet != null && trackerSet == null) {
            prefs.edit().putStringSet(trackerKey, forceStopSet).commit()
            com.example.core.OverlaySyncManager.syncStringSet(context, trackerKey, forceStopSet)
        }
    }

    fun getForceStopWhitelist(context: Context, containerId: String = "sidebar"): Set<String> {
        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        val forceStopKey = getForceStopWhitelistKey(containerId)
        val forceStopSet = prefs.getStringSet(forceStopKey, null)
        if (forceStopSet != null) return forceStopSet
        return getWhitelist(context, containerId)
    }

    fun saveForceStopWhitelist(context: Context, containerId: String = "sidebar", whitelist: Set<String>) {
        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        val forceStopKey = getForceStopWhitelistKey(containerId)
        val editor = prefs.edit().putStringSet(forceStopKey, whitelist)
        if (containerId == "sidebar" || containerId == "handle_1_swipe_left" || containerId == "handle_1") {
            editor.putStringSet("force_stop_whitelist_current", whitelist)
        }
        if (hasAppTrackerInContainer(context, containerId)) {
            val trackerKey = getContainerWhitelistKey(containerId)
            editor.putStringSet(trackerKey, whitelist)
            if (containerId == "sidebar" || containerId == "handle_1_swipe_left" || containerId == "handle_1") {
                editor.putStringSet("app_tracker_whitelist_current", whitelist)
            }
            com.example.core.OverlaySyncManager.syncStringSet(context, trackerKey, whitelist)
        }
        editor.commit()
        com.example.core.OverlaySyncManager.syncStringSet(context, forceStopKey, whitelist)
    }

    fun getWhitelist(context: Context, containerId: String = "sidebar"): Set<String> {
        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        val containerKey = getContainerWhitelistKey(containerId)
        val containerSet = prefs.getStringSet(containerKey, null)
        if (containerSet != null) return containerSet

        val forceStopKey = getForceStopWhitelistKey(containerId)
        val forceStopSet = prefs.getStringSet(forceStopKey, null)
        if (forceStopSet != null) return forceStopSet

        // Backward compatibility fallback for primary/legacy container
        if (containerId == "sidebar" || containerId == "handle_1_swipe_left" || containerId == "handle_1") {
            val legacy = prefs.getStringSet("app_tracker_whitelist_current", null)
                ?: prefs.getStringSet("force_stop_whitelist_current", null)
            if (legacy != null) {
                return legacy
            }
        }

        return emptySet()
    }

    fun saveWhitelist(context: Context, containerId: String = "sidebar", whitelist: Set<String>) {
        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        val containerKey = getContainerWhitelistKey(containerId)
        val forceStopKey = getForceStopWhitelistKey(containerId)
        val editor = prefs.edit()
            .putStringSet(containerKey, whitelist)
            .putStringSet(forceStopKey, whitelist)
        if (containerId == "sidebar" || containerId == "handle_1_swipe_left" || containerId == "handle_1") {
            editor.putStringSet("app_tracker_whitelist_current", whitelist)
            editor.putStringSet("force_stop_whitelist_current", whitelist)
        }
        editor.commit()
        com.example.core.OverlaySyncManager.syncStringSet(context, containerKey, whitelist)
        com.example.core.OverlaySyncManager.syncStringSet(context, forceStopKey, whitelist)
    }

    fun getRunningPackagesToStop(context: Context, containerId: String = "sidebar"): List<String> {
        return getRecentApps(context, containerId).map { it.packageName }
    }

    fun startForceStopSequence(context: Context, containerId: String = "sidebar"): Boolean {
        if (!checkUsageStatsPermission(context)) {
            Toast.makeText(context, "Grant Usage Access to track active apps", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (_: Exception) {}
            return false
        }

        val packagesToStop = getRunningPackagesToStop(context, containerId)
        if (packagesToStop.isEmpty()) {
            Toast.makeText(context, "No active app candidates found", Toast.LENGTH_SHORT).show()
            return false
        }

        val intent = Intent(context, AppTrackerOpenerActivity::class.java).apply {
            putStringArrayListExtra("packages", ArrayList(packagesToStop))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            Toast.makeText(context, "Unable to open the app sequence", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
