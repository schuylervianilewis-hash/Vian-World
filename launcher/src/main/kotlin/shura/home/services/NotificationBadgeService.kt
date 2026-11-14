package shura.home.services

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import shura.home.extensions.config

/**
 * Cross-process launcher badge cache.
 *
 * The single notification listener is Sidebar's AppNotificationListener in the default
 * process. Counts are persisted so :home can read them without binding a second
 * NotificationListenerService or keeping :heavy alive.
 */
object NotificationBadgeService {
    private const val PREFS_NAME = "vian_launcher_badges"
    private const val KEY_COUNTS = "active_counts"
    private const val SIDEBAR_NOTIFICATION_LISTENER_CLASS = "com.example.service.AppNotificationListener"

    fun isNotificationAccessGranted(context: Context): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val expected = ComponentName(
            context.packageName,
            SIDEBAR_NOTIFICATION_LISTENER_CLASS
        )
        return enabled.split(":").any { flattened ->
            ComponentName.unflattenFromString(flattened) == expected
        }
    }

    @Suppress("DEPRECATION")
    fun getActiveBadgeCounts(context: Context): Map<String, Int> {
        if (!context.config.showNotificationBadges || !isNotificationAccessGranted(context)) {
            clear(context)
            return emptyMap()
        }

        val raw = try {
            context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
            ).getString(KEY_COUNTS, "{}") ?: "{}"
        } catch (_: Exception) {
            "{}"
        }

        return try {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val count = json.optInt(key, 0)
                    if (count > 0) put(key, count)
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun updateFromNotifications(context: Context, notifications: Array<StatusBarNotification>?) {
        if (!context.config.showNotificationBadges) {
            clear(context)
            return
        }

        val counts = HashMap<String, Int>()
        notifications.orEmpty().forEach { sbn ->
            if (shouldCountNotification(sbn)) {
                val packageName = sbn.packageName
                val count = sbn.notification.number.takeIf { it > 0 } ?: 1
                counts[packageName] = (counts[packageName] ?: 0) + count
            }
        }

        val json = JSONObject()
        counts.forEach { (packageName, count) -> json.put(packageName, count) }

        @Suppress("DEPRECATION")
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
        ).edit().putString(KEY_COUNTS, json.toString()).commit()
    }

    fun clear(context: Context) {
        @Suppress("DEPRECATION")
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
        ).edit().remove(KEY_COUNTS).commit()
    }

    private fun shouldCountNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification ?: return false
        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        return !isOngoing && !isGroupSummary
    }
}
