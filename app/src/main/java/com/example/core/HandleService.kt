package com.example.core

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.MainActivity
import com.example.R
import com.example.feature.sidebar.SidebarManager
import eu.faircode.netguard.ShieldNotificationFactory

/**
 * HandleService: Persistent resident foreground service in the Main process (com.example).
 * Manages floating trigger handles, network speed monitor with dynamic status-bar icon,
 * and call recorder sensor state.
 */
class HandleService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var handleManager: HandleManager
    private lateinit var notificationManager: NotificationManager

    private var netSpeedManager: NetSpeedManager? = null
    private val activeHandleViews = mutableListOf<TriggerHandleView>()

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(HandleManager.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var isSpeedMonitorEnabled = false
    private var isScreenOn = true
    private var serviceStartTime: Long = System.currentTimeMillis()
    private var isFirstSpeedCallback = true
    private var isQueryingTodayData = false
    private var overlaySuppressed = false

    private var cachedTodayDataFormatted: String = "--"
    private var lastDailyQueryTimestamp: Long = 0L
    private var cachedDayOfYear: Int = -1

    private var lastLiveLoggedVal = ""
    private var lastLiveLoggedUnit = ""
    private var lastLiveLogTimestamp = 0L

    private val bulkSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    LogKeeper.logLifecycle(context, "HandleService", "SCREEN_ON", "Handles attached, NetSpeed resumed")
                    netSpeedManager?.setScreenState(true)
                    attachHandles()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    LogKeeper.logLifecycle(context, "HandleService", "SCREEN_OFF", "Handles detached, Sidebar closed, NetSpeed suspended, CallSensor active")
                    netSpeedManager?.setScreenState(false)
                    SidebarManager.getInstance(this@HandleService).closeContainer()
                    detachHandles()
                }
                Intent.ACTION_USER_PRESENT -> {
                    isScreenOn = true
                    LogKeeper.logLifecycle(context, "HandleService", "USER_PRESENT", "Keyguard unlocked - ensuring handles attached")
                    if (activeHandleViews.isEmpty()) {
                        attachHandles()
                    }
                }
                ACTION_RELOAD_HANDLES -> {
                    LogKeeper.logLifecycle(context, "HandleService", "RELOAD_HANDLES", "Reloading handles on configuration change")
                    applyOverlayAccessPolicy()
                }
                OverlayAccessPolicy.ACTION_POLICY_CHANGED -> {
                    OverlayAccessPolicy.applyBroadcast(this@HandleService, intent)
                    applyOverlayAccessPolicy()
                }
                OverlaySyncManager.ACTION_SYNC_PREF -> {
                    val type = intent.getStringExtra(OverlaySyncManager.EXTRA_TYPE) ?: ""
                    val useBroadcastFallback = intent.getBooleanExtra(OverlaySyncManager.EXTRA_BULK_FALLBACK, false)
                    if (type == "SYNC_BULK_FILE") {
                        if (useBroadcastFallback) scheduleBulkSync(intent, goAsync())
                    } else {
                        LogKeeper.logLifecycle(context, "HandleService", "ACTION_SYNC_PREF", "Syncing preferences from Heavy process")
                        handleSyncPrefIntent(intent)
                    }
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID = ShieldNotificationFactory.CHANNEL_ID
        const val NOTIFICATION_ID = ShieldNotificationFactory.NOTIFICATION_ID
        const val KEY_NET_SPEED_ENABLED = "net_speed_enabled"
        const val ACTION_RELOAD_HANDLES = "com.example.action.RELOAD_HANDLES"

        fun startIfConfigured(context: Context) {
            try {
                val isHeavy = LogKeeper.getProcessName(context).endsWith(":heavy")
                val prefs = context.getSharedPreferences(HandleManager.PREFS_NAME, Context.MODE_PRIVATE)
                val autoStart = prefs.getBoolean("auto_start_on_boot", true)
                val canOverlay = Settings.canDrawOverlays(context)
                val tag = if (isHeavy) "LauncherBridge" else "HandleService"
                val prefix = if (isHeavy) "startIfConfigured requested from :heavy" else "startIfConfigured called"
                LogKeeper.log(
                    context,
                    tag,
                    "$prefix: autoStart=$autoStart, canDrawOverlays=$canOverlay"
                )
                if (autoStart) {
                    if (!canOverlay) {
                        LogKeeper.log(context, tag, "Overlay permission missing; starting resident service for non-overlay features only")
                    }
                    if (isHeavy) {
                        LogKeeper.log(context, tag, "Dispatching HandleService start to Main process")
                    } else {
                        LogKeeper.log(context, tag, "startIfConfigured: launching service")
                    }
                    start(context)
                } else {
                    LogKeeper.log(
                        context,
                        tag,
                        "startIfConfigured skipped (autoStart=$autoStart, canOverlay=$canOverlay)"
                    )
                }
            } catch (e: Exception) {
                LogKeeper.logError(context, "HandleService", "Failed to startIfConfigured", e)
            }
        }

        fun start(context: Context) {
            val serviceIntent = Intent(context, HandleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        fun stop(context: Context) {
            val serviceIntent = Intent(context, HandleService::class.java)
            context.stopService(serviceIntent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceStartTime = System.currentTimeMillis()
        LogKeeper.logLifecycle(this, "HandleService", "CREATED")

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        val activeChannel = notificationManager.getNotificationChannel(CHANNEL_ID)
        LogKeeper.log(this, "HandleService",
            "Notification diagnostics: appEnabled=" + notificationManager.areNotificationsEnabled() +
                ", channelImportance=" + (activeChannel?.importance ?: -1) +
                ", postPermission=" + (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED))

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null) {
            isScreenOn = powerManager.isInteractive
        }

        // 1. Determine Net Speed Monitor state and build initial foreground notification
        isSpeedMonitorEnabled = FeaturePreferenceDefaults.internetSpeedMonitorEnabled(prefs) &&
            FeaturePermissionChecks.hasNotificationPermission(this)
        val initialTitle: String
        val initialIconResId: Int
        val initialContentText: String
        if (isSpeedMonitorEnabled) {
            initialTitle = "Data: ${getTodayDataFormatted()} • ${formatElapsedTime()}"
            initialIconResId = SpeedIconProvider.resolve("0", "kB/s").resId
            initialContentText = "Down: 0 kB/s   Up: 0 kB/s"
        } else {
            initialTitle = "Handles Active"
            initialIconResId = resolveSafeIconResId(R.drawable.ic_view_sidebar)
            initialContentText = "Listening for edge gestures"
        }
        val initialNotification = buildNotification(initialIconResId, initialTitle, initialContentText)

        // 2. Start Foreground IMMEDIATELY to satisfy system startForegroundService contract
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
            LogKeeper.logLifecycle(this, "HandleService", "FOREGROUND_STARTED", "Primary startForeground succeeded (icon=$initialIconResId)")
        } catch (e: Exception) {
            LogKeeper.logError(this, "HandleService", "Primary startForeground failed; retrying with guaranteed system fallback icon", e)
            val fallbackNotification = buildNotification(R.drawable.ic_speed, initialTitle, initialContentText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    fallbackNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, fallbackNotification)
            }
            LogKeeper.logLifecycle(this, "HandleService", "FOREGROUND_STARTED", "Fallback startForeground succeeded with R.drawable.ic_speed")
        }

        // 3. Validate runtime configuration
        val autoStart = prefs.getBoolean("auto_start_on_boot", true)
        if (!autoStart) {
            LogKeeper.log(this, "HandleService", "Stopping service in onCreate: auto_start_on_boot=false")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // 4. Initialize Net Speed Monitor immediately after entering foreground
        setupNetSpeedManager()

        // 5. Secondary component registrations
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(ACTION_RELOAD_HANDLES)
            addAction(OverlayAccessPolicy.ACTION_POLICY_CHANGED)
            addAction(OverlaySyncManager.ACTION_SYNC_PREF)
        }
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        prefs.registerOnSharedPreferenceChangeListener(this)

        handleManager = HandleManager.getInstance(this)
        applyOverlayAccessPolicy()
        val callRecorder = CallRecorderManager.getInstance(this)
        if (callRecorder.shouldListen()) {
            callRecorder.startListening()
        }
        LogKeeper.logLifecycle(this, "CallSensor", "STARTUP_CHECK",
            "autoEnabled=" + callRecorder.isEnabled() + " sensorEnabled=" + callRecorder.shouldListen() +
                " status=" + callRecorder.listenerStatus + " listening=" + callRecorder.isListening)
        SidebarManager.getInstance(this).registerReceiver(this)
        com.example.feature.element.ElementActionRegistry.getInstance(this).registerReceiver(this)

        // 6. Notify MainProcessRecovery of successful startup to monitor stable runtime
        MainProcessRecovery.onServiceStarted(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null) {
            isScreenOn = powerManager.isInteractive
        }
        val autoStart = prefs.getBoolean("auto_start_on_boot", true)
        if (!autoStart) {
            LogKeeper.log(this, "HandleService", "Stopping service in onStartCommand: auto_start_on_boot=false")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent == null) {
            LogKeeper.logLifecycle(this, "HandleService", "SERVICE_RECREATED_STICKY", "Recreated by Android framework after process termination (START_STICKY)")
            applyOverlayAccessPolicy()
        } else {
            when (intent.action) {
                OverlaySyncManager.ACTION_SYNC_PREF -> {
                    if (intent.getStringExtra(OverlaySyncManager.EXTRA_TYPE) == "SYNC_BULK_FILE") {
                        scheduleBulkSync(intent)
                    } else {
                        handleSyncPrefIntent(intent)
                    }
                }
                ACTION_RELOAD_HANDLES -> {
                    applyOverlayAccessPolicy()
                }
                OverlayAccessPolicy.ACTION_POLICY_CHANGED -> {
                    OverlayAccessPolicy.applyBroadcast(this, intent)
                    applyOverlayAccessPolicy()
                }
                else -> {
                    if (isScreenOn && Settings.canDrawOverlays(this)) {
                        attachHandles()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        LogKeeper.logLifecycle(this, "HandleService", "TASK_REMOVED", "App task removed from recent apps - ensuring service remains active")
        startIfConfigured(this)
    }

    private fun setupNetSpeedManager() {
        if (isSpeedMonitorEnabled && !FeaturePermissionChecks.hasNotificationPermission(this)) {
            isSpeedMonitorEnabled = false
        }
        if (isSpeedMonitorEnabled) {
            if (netSpeedManager == null) {
                netSpeedManager = NetSpeedManager { speedData ->
                    if (isScreenOn) {
                        updateSpeedNotification(speedData)
                    }
                }
            }
            netSpeedManager?.start()
            LogKeeper.logLifecycle(this, "NetSpeedManager", "STARTED", "enabled=true screenOn=" + isScreenOn)
        } else {
            netSpeedManager?.stop()
            netSpeedManager = null
            // Update to a static handles active notification
            val handlesTitle = "Handles Active"
            val handlesIconResId = resolveSafeIconResId(R.drawable.ic_view_sidebar)
            val handlesNotification = buildNotification(handlesIconResId, handlesTitle, "Listening for edge gestures")
            notificationManager.notify(NOTIFICATION_ID, handlesNotification)
            LogKeeper.logLifecycle(this, "NetSpeedManager", "STOPPED", "enabled=false; static Sidebar notification restored")
        }
    }

    private fun updateSpeedNotification(speedData: SpeedData) {
        if (!FeaturePermissionChecks.hasNotificationPermission(this)) {
            isSpeedMonitorEnabled = false
            netSpeedManager?.stop()
            netSpeedManager = null
            LogKeeper.log(this, "HandleService", "Speed monitor stopped because notification permission is unavailable")
            return
        }
        try {
            val speedVal = speedData.downValue
            val speedUnit = speedData.downUnit

            // Final Pre-Rendered Resource Lookup:
            // speed value -> display formatting -> resource ID lookup -> Notification.Builder.setSmallIcon(resourceId)
            val iconInfo = SpeedIconProvider.resolve(speedVal, speedUnit)
            val selectedResId = iconInfo.resId
            val selectedResName = iconInfo.resName

            val titleText = "Data: ${getTodayDataFormatted()} • ${formatElapsedTime()}"
            val contentText = "Down: ${speedData.downFormatted}   Up: ${speedData.upFormatted}"

            val notification = buildNotification(selectedResId, titleText, contentText)
            notificationManager.notify(NOTIFICATION_ID, notification)

            val now = System.currentTimeMillis()
            if (isFirstSpeedCallback) {
                isFirstSpeedCallback = false
                val tFirst = now - serviceStartTime
                LogKeeper.log(
                    this,
                    "StartupDiagnostics",
                    "first speed callback (t=${tFirst}ms): initial notification replaced by live speed notification (val='$speedVal', unit='$speedUnit', resName=$selectedResName)"
                )
            }

            // Concise diagnostics:
            // displayed value, displayed unit, selected resource name, and confirmation that setSmallIcon received the resource ID
            val valChanged = (speedVal != lastLiveLoggedVal || speedUnit != lastLiveLoggedUnit)
            val timeElapsed = (now - lastLiveLogTimestamp) > 10000L

            if (valChanged || timeElapsed) {
                lastLiveLoggedVal = speedVal
                lastLiveLoggedUnit = speedUnit
                lastLiveLogTimestamp = now
                LogKeeper.log(
                    this,
                    "IconDiagnostics",
                    "LiveUpdate -> displayedVal='$speedVal', displayedUnit='$speedUnit', mode=RESOURCE, resName=$selectedResName, setSmallIconReceived=true, notifyExecuted=true"
                )
            }
        } catch (e: Exception) {
            LogKeeper.logError(this, "HandleService", "Failed to update notification", e)
        }
    }

    private fun getTodayDataFormatted(): String {
        val currentDay = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val now = System.currentTimeMillis()
        // Non-blocking asynchronous query if day rolled over or 30 seconds elapsed
        if (currentDay != cachedDayOfYear || (now - lastDailyQueryTimestamp) > 30000L) {
            lastDailyQueryTimestamp = now
            cachedDayOfYear = currentDay
            queryTodayDataUsageAsync()
        }
        return cachedTodayDataFormatted
    }

    private fun queryTodayDataUsageAsync() {
        if (isQueryingTodayData) return
        isQueryingTodayData = true
        Thread {
            try {
                val bytes = DailyDataUsageHelper.getTodayDataUsageBytes(this@HandleService)
                val formatted = DailyDataUsageHelper.formatDataBytes(bytes)
                synchronized(this@HandleService) {
                    cachedTodayDataFormatted = formatted
                }
            } catch (_: Exception) {
            } finally {
                isQueryingTodayData = false
            }
        }.start()
    }

    private fun formatElapsedTime(): String {
        val elapsedMillis = System.currentTimeMillis() - serviceStartTime
        val elapsedMin = (elapsedMillis / 60000L).coerceAtLeast(0L)
        return if (elapsedMin < 60) {
            "${elapsedMin}m"
        } else {
            val hours = elapsedMin / 60
            val mins = elapsedMin % 60
            if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
        }
    }

    private val validatedIconCache = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    private fun resolveSafeIconResId(candidateResId: Int): Int {
        if (candidateResId > 0 && isValidDrawableResource(candidateResId)) {
            return candidateResId
        }
        val speedZero = SpeedIconProvider.resolve("0", "kB/s").resId
        if (speedZero > 0 && isValidDrawableResource(speedZero)) {
            return speedZero
        }
        return R.drawable.ic_speed
    }

    private fun isValidDrawableResource(resId: Int): Boolean {
        if (resId <= 0) return false
        return validatedIconCache.getOrPut(resId) {
            try {
                // Actually verify that the Android framework can decode and create this drawable
                val drawable = ContextCompat.getDrawable(this, resId)
                drawable != null
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun buildNotification(
        iconResId: Int,
        titleText: String,
        contentText: String
    ): Notification {
        val safeIcon = resolveSafeIconResId(iconResId)
        if (safeIcon != iconResId) {
            LogKeeper.logError(this, "HandleService",
                "Unsafe/unusable icon candidate detected (resId=$iconResId); substituted safe fallback icon (resId=$safeIcon)")
        }
        return ShieldNotificationFactory.buildSidebarNotification(
            this, safeIcon, titleText, contentText, isSpeedMonitorEnabled)
    }

    private fun createNotificationChannel() {
        ShieldNotificationFactory.createNotificationChannel(this)
    }

    private fun scheduleBulkSync(
        intent: Intent,
        pendingResult: BroadcastReceiver.PendingResult? = null
    ) {
        val prefName = intent.getStringExtra(OverlaySyncManager.EXTRA_PREF_NAME) ?: "FloatingReaderPrefs"
        val path = intent.getStringExtra(OverlaySyncManager.EXTRA_BULK_FILE_PATH)
        if (path.isNullOrBlank()) {
            pendingResult?.finish()
            return
        }
        bulkSyncScope.launch {
            try {
                val applied = runCatching {
                    OverlaySyncManager.applyBulkFile(this@HandleService, prefName, path)
                }.onFailure {
                    android.util.Log.e("HandleService", "Could not apply bulk preference sync file", it)
                }.getOrDefault(false)
                if (applied) {
                    withContext(Dispatchers.Main) {
                        SidebarManager.getInstance(this@HandleService).refreshPages()
                        if (isScreenOn && Settings.canDrawOverlays(this@HandleService)) attachHandles()
                        IconCacheManager.invalidateMemoryCache()
                        sendBroadcast(Intent("com.example.UPDATE_SIDEBAR_ICONS").setPackage(packageName))
                    }
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private fun handleSyncPrefIntent(intent: Intent) {
        val key = intent.getStringExtra(OverlaySyncManager.EXTRA_KEY) ?: ""
        val value = intent.getStringExtra(OverlaySyncManager.EXTRA_VALUE) ?: ""
        val type = intent.getStringExtra(OverlaySyncManager.EXTRA_TYPE) ?: ""
        val prefName = intent.getStringExtra(OverlaySyncManager.EXTRA_PREF_NAME) ?: "FloatingReaderPrefs"
        val targetPrefs = if (prefName == "FloatingReaderPrefs") prefs else getSharedPreferences(prefName, Context.MODE_PRIVATE)

        when (type) {
            "STRING" -> targetPrefs.edit().putString(key, value).commit()
            "INT" -> targetPrefs.edit().putInt(key, value.toIntOrNull() ?: 0).commit()
            "BOOLEAN" -> {
                val bVal = value.toBoolean()
                targetPrefs.edit().putBoolean(key, bVal).commit()
                if (key == KEY_NET_SPEED_ENABLED || key == "net_speed_enabled" || key == "netspeed_enabled" || key == "speed_indicator_enabled") {
                    isSpeedMonitorEnabled = bVal
                    setupNetSpeedManager()
                }
            }
            "FLOAT" -> targetPrefs.edit().putFloat(key, value.toFloatOrNull() ?: 0f).commit()
            "STRING_SET" -> {
                val set = if (value.isEmpty()) emptySet() else value.split("|||").toSet()
                targetPrefs.edit().putStringSet(key, set).commit()
            }
            "REMOVE" -> targetPrefs.edit().remove(key).commit()
            "RELOAD_ALL" -> {
                // Backup restore and other bulk preference writes need to refresh the
                // currently visible Sidebar state, not only reattach trigger handles.
                SidebarManager.getInstance(this).refreshPages()
            }
            "SYNC_BULK_FILE" -> {
                val path = intent.getStringExtra(OverlaySyncManager.EXTRA_BULK_FILE_PATH)
                    ?: error("Bulk preference sync file path is missing")
                val applied = runCatching {
                    OverlaySyncManager.applyBulkFile(this, prefName, path)
                }.onFailure {
                    android.util.Log.e("HandleService", "Could not apply bulk preference sync file", it)
                }.getOrDefault(false)
                if (applied) SidebarManager.getInstance(this).refreshPages()
            }
            "SYNC_HANDLE" -> {
                val bundle = intent.extras
                if (bundle != null) {
                    val editor = prefs.edit()
                    for (k in bundle.keySet()) {
                        if (k.startsWith("handle_") || k == HandleManager.KEY_HANDLE_IDS || k == HandleManager.KEY_HANDLES_COUNT) {
                            when (val v = bundle.get(k)) {
                                is String -> editor.putString(k, v)
                                is Boolean -> editor.putBoolean(k, v)
                                is Int -> editor.putInt(k, v)
                                is Float -> editor.putFloat(k, v)
                            }
                        }
                    }
                    editor.commit()
                }
            }
        }
        if (isScreenOn && Settings.canDrawOverlays(this)) {
            attachHandles()
        }
    }

    private fun applyOverlayAccessPolicy() {
        overlaySuppressed = OverlayAccessPolicy.isForegroundExcluded(this)
        OverlayWindowRegistry.setSuppressed(overlaySuppressed)
        FloatingWindowManager.getInstance(this).setOverlaySuppressed(overlaySuppressed)
        if (overlaySuppressed) {
            detachHandles()
        } else if (isScreenOn && Settings.canDrawOverlays(this)) {
            attachHandles()
        }
    }

    private fun attachHandles() {
        detachHandles()

        if (!OverlayAccessPolicy.canShowApplicationOverlays(this)) {
            LogKeeper.log(this, "HandleService", "attachHandles skipped: overlay permission missing or current app excluded")
            return
        }

        val configs = handleManager.getActiveHandles()
        for (config in configs) {
            val handleView = TriggerHandleView(this, config) { actionKey, gesture, handleConfig ->
                handleGestureAction(actionKey, gesture, handleConfig)
            }
            try {
                handleView.attachToWindow()
                activeHandleViews.add(handleView)
            } catch (e: Exception) {
                LogKeeper.logError(this, "HandleService", "Failed to attach handle view ${config.id}", e)
            }
        }
    }

    private fun detachHandles() {
        for (view in activeHandleViews) {
            try {
                view.detachFromWindow()
            } catch (e: Exception) {
                // Ignore if already detached
            }
        }
        activeHandleViews.clear()
    }

    private fun handleGestureAction(actionKey: String, gesture: String, handleConfig: HandleConfig) {
        val containerId = HandleManager.getContainerId(handleConfig.id, gesture)
        when (val target = HandleManager.resolveGestureTarget(handleConfig.id, gesture, actionKey)) {
            is GestureTarget.Container -> {
                // Synchronously resolve active Sidebar container in Main process
                SidebarManager.getInstance(this).openTarget(handleConfig.id, gesture, target)

                // Dispatch broadcast for Sidebar trigger with independent container identity
                val intent = Intent("com.example.action.OPEN_SIDEBAR").apply {
                    putExtra("handle_id", handleConfig.id)
                    putExtra("gesture", gesture)
                    putExtra("container_id", target.containerId)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                sendBroadcast(intent)
            }
            is GestureTarget.None -> {
                // No action
            }
            is GestureTarget.Action -> {
                val intent = Intent("com.example.action.TRIGGER_ACTION").apply {
                    putExtra("action_key", target.actionKey)
                    putExtra("handle_id", handleConfig.id)
                    putExtra("gesture", gesture)
                    putExtra("container_id", containerId)
                }
                sendBroadcast(intent)
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == null) return
        if (key.startsWith("handle_") || key == HandleManager.KEY_HANDLES_COUNT) {
            if (isScreenOn) {
                attachHandles()
            }
        } else if (key == KEY_NET_SPEED_ENABLED || key == "net_speed_enabled" || key == "netspeed_enabled" || key == "speed_indicator_enabled") {
            isSpeedMonitorEnabled = FeaturePreferenceDefaults.internetSpeedMonitorEnabled(prefs) &&
                FeaturePermissionChecks.hasNotificationPermission(this)
            setupNetSpeedManager()
        } else if (key == CallRecorderManager.KEY_CALL_RECORDER_ENABLED ||
            key == CallRecorderManager.KEY_CALL_RECORDER_MANUAL_ENABLED) {
            val callRecorder = CallRecorderManager.getInstance(this)
            if (callRecorder.shouldListen()) callRecorder.startListening()
            else callRecorder.stopListening()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bulkSyncScope.cancel()
        LogKeeper.logLifecycle(this, "HandleService", "DESTROYED")
        val shieldPrefs = getSharedPreferences(packageName + "_preferences", Context.MODE_PRIVATE)
        val shieldEnabled = shieldPrefs.getBoolean("enabled", false)
        val showStats = shieldPrefs.getBoolean("show_stats", false)
        ShieldNotificationFactory.setHandleServiceActive(this, false)
        if (shieldEnabled || showStats) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_DETACH)
            else stopForeground(false)
            notificationManager.notify(NOTIFICATION_ID,
                ShieldNotificationFactory.buildShieldOnlyNotification(this, shieldEnabled))
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else stopForeground(true)
        }
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            // Ignored if already unregistered
        }

        netSpeedManager?.stop()
        netSpeedManager = null

        CallRecorderManager.getInstance(this).stopListening()
        SidebarManager.getInstance(this).dismiss()
        SidebarManager.getInstance(this).unregisterReceiver(this)
        com.example.feature.element.ElementActionRegistry.getInstance(this).unregisterReceiver(this)
        detachHandles()
        MainProcessRecovery.onServiceDestroyed()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
