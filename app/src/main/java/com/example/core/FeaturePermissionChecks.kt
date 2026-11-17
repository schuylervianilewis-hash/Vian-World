package com.example.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Shared permission checks for notification-backed features. */
object FeaturePermissionChecks {
    /** True only when Android's runtime permission (where required) and app notifications are enabled. */
    @JvmStatic
    fun hasNotificationPermission(context: Context): Boolean {
        val runtimePermissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        val appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return hasNotificationPermission(runtimePermissionGranted, appNotificationsEnabled)
    }

    /** Whether the caller must show Android's POST_NOTIFICATIONS runtime prompt. */
    @JvmStatic
    fun needsRuntimeNotificationPermission(context: Context): Boolean {
        val runtimePermissionGranted =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        return needsRuntimePermission(Build.VERSION.SDK_INT, runtimePermissionGranted)
    }

    /** Android 13+ has a runtime permission; older versions use the app notification setting. */
    @JvmStatic
    fun createNotificationSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        }
    }

    internal fun hasNotificationPermission(
        runtimePermissionGranted: Boolean,
        appNotificationsEnabled: Boolean
    ): Boolean = runtimePermissionGranted && appNotificationsEnabled

    internal fun needsRuntimePermission(
        sdkInt: Int,
        runtimePermissionGranted: Boolean
    ): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU && !runtimePermissionGranted
}
