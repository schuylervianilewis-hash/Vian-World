package shura.home.services

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import shura.home.extensions.config
import java.util.concurrent.ConcurrentHashMap

class NotificationBadgeService : NotificationListenerService() {

    companion object {
        private var instance: NotificationBadgeService? = null
        private val badgeCounts = ConcurrentHashMap<String, Int>()

        fun isNotificationAccessGranted(context: Context): Boolean {
            val enabledListeners = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val myPackage = context.packageName
            return enabledListeners.split(":").any { it.startsWith(myPackage) }
        }

        fun getActiveBadgeCounts(context: Context): Map<String, Int> {
            if (!context.config.showNotificationBadges || !isNotificationAccessGranted(context)) {
                badgeCounts.clear()
                return emptyMap()
            }

            val service = instance
            if (service != null) {
                try {
                    val activeNotifs = service.activeNotifications ?: emptyArray()
                    val newCounts = mutableMapOf<String, Int>()
                    for (sbn in activeNotifs) {
                        if (shouldCountNotification(sbn)) {
                            val pkg = sbn.packageName
                            val count = if (sbn.notification.number > 0) sbn.notification.number else 1
                            newCounts[pkg] = (newCounts[pkg] ?: 0) + count
                        }
                    }
                    badgeCounts.clear()
                    badgeCounts.putAll(newCounts)
                } catch (e: Exception) {
                    // Ignore transient exceptions
                }
            }
            return badgeCounts.toMap()
        }

        private fun shouldCountNotification(sbn: StatusBarNotification): Boolean {
            val notification = sbn.notification ?: return false
            val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
            val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
            return !isOngoing && !isGroupSummary
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) {
            instance = null
        }
        badgeCounts.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Zero background processing: counts are queried purely on-demand when user visits Home Screen
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Zero background processing: counts are queried purely on-demand when user visits Home Screen
    }
}
