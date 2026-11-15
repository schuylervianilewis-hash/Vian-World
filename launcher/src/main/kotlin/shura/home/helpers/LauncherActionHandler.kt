package shura.home.helpers

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Context.DEVICE_POLICY_SERVICE
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import org.fossify.commons.extensions.toast
import shura.home.R
import shura.home.activities.MainActivity
import shura.home.extensions.launchApp
import shura.home.models.HomeScreenGridItem
import shura.home.receivers.LockDeviceAdminReceiver

/**
 * Executes curated system and launcher actions with safe permission checks and LogCatcher integration.
 */
object LauncherActionHandler {

    const val ACTION_NONE = "none"
    const val ACTION_LOCK_SCREEN = "lock_screen"
    const val ACTION_MUTE_VOLUME = "mute_volume"
    const val ACTION_MUTE_RINGTONE = "mute_ringtone"
    const val ACTION_NOTIFICATIONS = "notifications"
    const val ACTION_QUICK_SETTINGS = "quick_settings"
    const val ACTION_APP_DRAWER = "app_drawer"
    const val ACTION_FOLDER_POPUP = "folder_popup"
    const val ACTION_POPUP_WIDGET = "popup_widget"
    const val ACTION_APP_SHORTCUTS = "app_shortcuts"
    const val ACTION_LAUNCH_APP = "launch_app"
    const val ACTION_ADD_TO_HOME = "add_to_home"
    const val ACTION_ADD_APP = "add_app"
    const val ACTION_ADD_WIDGET = "add_widget"

    val AVAILABLE_ACTIONS = listOf(
        ACTION_NONE,
        ACTION_LOCK_SCREEN,
        ACTION_MUTE_VOLUME,
        ACTION_MUTE_RINGTONE,
        ACTION_NOTIFICATIONS,
        ACTION_QUICK_SETTINGS,
        ACTION_APP_DRAWER,
        ACTION_FOLDER_POPUP,
        ACTION_POPUP_WIDGET,
        ACTION_APP_SHORTCUTS,
        ACTION_LAUNCH_APP,
        ACTION_ADD_TO_HOME,
        ACTION_ADD_APP,
        ACTION_ADD_WIDGET
    )

    fun executeAction(
        activity: MainActivity,
        action: String,
        item: HomeScreenGridItem? = null,
        targetPackage: String = ""
    ): Boolean {
        if (action == ACTION_NONE) return false

        LogCatcher.log("LauncherActionHandler", "Executing action: $action for item: ${item?.title ?: item?.packageName ?: "none"}")
        return try {
            when (action) {
                ACTION_LOCK_SCREEN -> lockScreen(activity)
                ACTION_MUTE_VOLUME -> toggleMediaMute(activity)
                ACTION_MUTE_RINGTONE -> toggleRingtone(activity)
                ACTION_NOTIFICATIONS -> expandNotifications(activity)
                ACTION_QUICK_SETTINGS -> expandQuickSettings(activity)
                ACTION_APP_DRAWER -> {
                    activity.openAppDrawer()
                    true
                }
                ACTION_FOLDER_POPUP -> {
                    if (item != null && item.type == ITEM_TYPE_FOLDER) {
                        activity.openFolderPopup(item)
                        true
                    } else false
                }
                ACTION_POPUP_WIDGET -> {
                    if (item != null) {
                        activity.openPopupWidget(item)
                        true
                    } else false
                }
                ACTION_APP_SHORTCUTS -> {
                    if (item != null) {
                        activity.showItemShortcuts(item)
                        true
                    } else false
                }
                ACTION_LAUNCH_APP -> {
                    val pkg = targetPackage.ifEmpty { item?.packageName.orEmpty() }
                    if (pkg.isNotEmpty()) {
                        activity.launchApp(pkg, "", "")
                        true
                    } else false
                }
                ACTION_ADD_TO_HOME -> {
                    AddElementBridge.open(activity)
                    true
                }
                ACTION_ADD_APP -> {
                    activity.showAddAppDialog()
                    true
                }
                ACTION_ADD_WIDGET -> {
                    activity.showWidgetsFragment()
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            LogCatcher.log("LauncherActionHandler", "Failed to execute action: $action", e)
            false
        }
    }

    private fun lockScreen(context: Context): Boolean {
        val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, LockDeviceAdminReceiver::class.java)
        return if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
            true
        } else {
            context.toast(R.string.lock_device_admin_hint)
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.lock_device_admin_hint))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            false
        }
    }

    private fun toggleMediaMute(context: Context): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val isMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audio.isStreamMute(AudioManager.STREAM_MUSIC)
        } else {
            audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        }

        return if (isMuted) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            context.toast(R.string.volume_unmuted)
            true
        } else {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            context.toast(R.string.volume_muted)
            true
        }
    }

    private fun toggleRingtone(context: Context): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        return try {
            when (audio.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> {
                    audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    context.toast(R.string.ringer_mode_vibrate)
                    true
                }
                AudioManager.RINGER_MODE_VIBRATE,
                AudioManager.RINGER_MODE_SILENT -> {
                    // Safe switch back to normal ringer
                    audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    context.toast(R.string.ringer_mode_normal)
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            LogCatcher.log("LauncherActionHandler", "Ringtone toggle exception", e)
            false
        }
    }

    @SuppressLint("WrongConstant")
    private fun expandNotifications(context: Context): Boolean {
        return try {
            val statusBarService = context.getSystemService("statusbar")
            val statusBarManagerClass = Class.forName("android.app.StatusBarManager")
            val method = statusBarManagerClass.getMethod("expandNotificationsPanel")
            method.invoke(statusBarService)
            true
        } catch (e: Exception) {
            LogCatcher.log("LauncherActionHandler", "StatusBarManager.expandNotificationsPanel failed", e)
            false
        }
    }

    @SuppressLint("WrongConstant")
    private fun expandQuickSettings(context: Context): Boolean {
        return try {
            val statusBarService = context.getSystemService("statusbar")
            val statusBarManagerClass = Class.forName("android.app.StatusBarManager")
            val method = statusBarManagerClass.getMethod("expandSettingsPanel")
            method.invoke(statusBarService)
            true
        } catch (e: Exception) {
            LogCatcher.log("LauncherActionHandler", "StatusBarManager.expandSettingsPanel failed", e)
            false
        }
    }

    fun getActionLabel(context: Context, action: String, targetPackage: String = ""): String {
        return when (action) {
            ACTION_LOCK_SCREEN -> context.getString(R.string.action_lock_screen)
            ACTION_MUTE_VOLUME -> context.getString(R.string.action_mute_volume)
            ACTION_MUTE_RINGTONE -> context.getString(R.string.action_mute_ringtone)
            ACTION_NOTIFICATIONS -> context.getString(R.string.action_notifications)
            ACTION_QUICK_SETTINGS -> context.getString(R.string.action_quick_settings)
            ACTION_APP_DRAWER -> context.getString(R.string.action_app_drawer)
            ACTION_FOLDER_POPUP -> context.getString(R.string.action_folder_popup)
            ACTION_POPUP_WIDGET -> context.getString(R.string.action_popup_widget)
            ACTION_APP_SHORTCUTS -> context.getString(R.string.action_app_shortcuts)
            ACTION_LAUNCH_APP -> {
                if (targetPackage.isNotEmpty()) {
                    try {
                        val pm = context.packageManager
                        val appInfo = pm.getApplicationInfo(targetPackage, 0)
                        val appLabel = pm.getApplicationLabel(appInfo).toString()
                        "${context.getString(R.string.action_launch_app)} ($appLabel)"
                    } catch (e: Exception) {
                        context.getString(R.string.action_launch_app)
                    }
                } else {
                    context.getString(R.string.action_launch_app)
                }
            }
            ACTION_ADD_TO_HOME -> context.getString(R.string.action_add_to_home)
            ACTION_ADD_APP -> context.getString(R.string.action_add_app)
            ACTION_ADD_WIDGET -> context.getString(R.string.action_add_widget)
            else -> context.getString(R.string.action_none)
        }
    }
}
