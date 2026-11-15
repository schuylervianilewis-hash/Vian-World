package shura.home.helpers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.fossify.commons.extensions.toast
import shura.home.R

object ArchivedAppsManager {

    /**
     * Determines whether a package is currently archived on the system.
     * In Android 15 (API 35+), checks system package manager for archived package state.
     */
    fun isArchived(context: Context, packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                val appInfo = context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_ARCHIVED_PACKAGES.toLong())
                )
                return appInfo.isArchived
            } catch (e: Throwable) {
                return false
            }
        }
        return false
    }

    /**
     * Triggers unarchival of an archived package.
     * On Android 15 (API 35+), requests the system PackageInstaller to restore the application.
     * Falls back to market/store intent or App Info settings if unarchival request is unsupported.
     */
    fun unarchive(context: Context, packageName: String, appTitle: String = "") {
        val title = appTitle.ifEmpty { packageName }
        context.toast(context.getString(R.string.unarchiving_app, title))

        if (Build.VERSION.SDK_INT >= 35) {
            try {
                val intent = Intent("shura.home.UNARCHIVE_STATUS").setPackage(context.packageName)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    packageName.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                context.packageManager.packageInstaller.requestUnarchive(packageName, pendingIntent.intentSender)
                return
            } catch (e: Exception) {
                LogCatcher.log("ArchivedAppsManager", "requestUnarchive failed: ${e.message}")
            }
        }

        // Fallback: Launch Play Store / market page or App info settings
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(marketIntent)
        } catch (e: Exception) {
            try {
                val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
            } catch (ignored: Exception) {
            }
        }
    }
}
