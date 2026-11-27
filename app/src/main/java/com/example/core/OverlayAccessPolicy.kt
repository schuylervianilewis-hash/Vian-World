package com.example.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Shared package-based allow-list for Vian visual overlays. Unlisted apps are allowed. */
object OverlayAccessPolicy {
    const val ACTION_POLICY_CHANGED = "com.example.action.OVERLAY_POLICY_CHANGED"
    const val EXTRA_EXCLUDED_PACKAGES = "excluded_packages"
    const val EXTRA_FOREGROUND_PACKAGE = "foreground_package"
    private const val PREFS_NAME = "OverlayExclusionsPrefs"
    private const val KEY_EXCLUDED_PACKAGES = "excluded_packages"
    private const val KEY_FOREGROUND_PACKAGE = "foreground_package"
    private const val KEY_FOREGROUND_OBSERVED_AT = "foreground_observed_at"

    @Volatile private var excludedPackagesCache: Set<String>? = null
    @Volatile private var foregroundPackageCache: String? = null
    @Volatile private var foregroundObservedAtCache: Long? = null
    @Volatile private var receiverRegistered = false

    fun getExcludedPackages(context: Context): Set<String> {
        excludedPackagesCache?.let { return it }
        synchronized(this) {
            excludedPackagesCache?.let { return it }
            return (getPrefs(context).getStringSet(KEY_EXCLUDED_PACKAGES, emptySet())
                ?.toSet() ?: emptySet()).also { excludedPackagesCache = it }
        }
    }

    fun getForegroundPackage(context: Context): String {
        foregroundPackageCache?.let { return it }
        synchronized(this) {
            foregroundPackageCache?.let { return it }
            return getPrefs(context).getString(KEY_FOREGROUND_PACKAGE, "").orEmpty()
                .also { foregroundPackageCache = it }
        }
    }

    fun isForegroundExcluded(context: Context): Boolean {
        val foreground = getForegroundPackage(context)
        return foreground.isNotBlank() && foreground in getExcludedPackages(context)
    }

    fun canShowApplicationOverlays(context: Context): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context)
        return permissionGranted && !isForegroundExcluded(context)
    }

    fun isPackageAllowed(packageName: String, excludedPackages: Set<String>): Boolean =
        packageName !in excludedPackages

    fun updatedExcludedPackages(current: Set<String>, packageName: String, allowed: Boolean): Set<String> =
        current.toMutableSet().apply {
            if (allowed) remove(packageName) else add(packageName)
        }.toSet()

    fun setOverlayAllowed(context: Context, packageName: String, allowed: Boolean) {
        if (packageName.isBlank()) return
        val updated = updatedExcludedPackages(getExcludedPackages(context), packageName, allowed)
        excludedPackagesCache = updated
        getPrefs(context).edit().putStringSet(KEY_EXCLUDED_PACKAGES, updated).apply()
        send(context, Intent(ACTION_POLICY_CHANGED).putStringArrayListExtra(
            EXTRA_EXCLUDED_PACKAGES, ArrayList(updated)
        ))
    }

    /**
     * Returns only a recently observed foreground package. The persisted package string alone
     * is not proof of current foreground state because it can survive service disconnection.
     */
    fun getFreshForegroundPackage(
        context: Context,
        maxAgeMillis: Long = 60_000L
    ): String? {
        val prefs = getPrefs(context)
        val observedAt = foregroundObservedAtCache
            ?: prefs.getLong(KEY_FOREGROUND_OBSERVED_AT, 0L).also { foregroundObservedAtCache = it }
        val age = System.currentTimeMillis() - observedAt
        if (observedAt <= 0L || age !in 0..maxAgeMillis) return null
        return getForegroundPackage(context).takeIf { it.isNotBlank() }
    }

    /** The app's own process is never excluded, so the user can always reopen settings. */
    fun updateForegroundPackage(context: Context, packageName: String?) {
        val normalized = packageName.orEmpty().takeUnless { it == context.packageName }.orEmpty()
        val now = System.currentTimeMillis()
        foregroundObservedAtCache = now
        getPrefs(context).edit().putLong(KEY_FOREGROUND_OBSERVED_AT, now).apply()
        if (getForegroundPackage(context) == normalized) return
        foregroundPackageCache = normalized
        getPrefs(context).edit().putString(KEY_FOREGROUND_PACKAGE, normalized).apply()
        send(context, Intent(ACTION_POLICY_CHANGED).putExtra(EXTRA_FOREGROUND_PACKAGE, normalized))
    }

    fun applyBroadcast(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_POLICY_CHANGED) return
        intent.getStringArrayListExtra(EXTRA_EXCLUDED_PACKAGES)?.let { values ->
            val updated = values.toSet()
            excludedPackagesCache = updated
            getPrefs(context).edit().putStringSet(KEY_EXCLUDED_PACKAGES, updated).commit()
        }
        if (intent.hasExtra(EXTRA_FOREGROUND_PACKAGE)) {
            val foreground = intent.getStringExtra(EXTRA_FOREGROUND_PACKAGE).orEmpty()
            foregroundPackageCache = foreground
            getPrefs(context).edit().putString(KEY_FOREGROUND_PACKAGE, foreground).commit()
        }
    }

    /** Every app process registers so :heavy/:home receive fresh state, not stale prefs caches. */
    fun registerProcessReceiver(context: Context, onChanged: (Boolean) -> Unit) {
        if (receiverRegistered) return
        synchronized(this) {
            if (receiverRegistered) return
            val appContext = context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val safeContext = ctx ?: appContext
                    applyBroadcast(safeContext, intent)
                    onChanged(isForegroundExcluded(safeContext))
                }
            }
            ContextCompat.registerReceiver(
                appContext, receiver, IntentFilter(ACTION_POLICY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun send(context: Context, intent: Intent) {
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
}
