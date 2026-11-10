package shura.home.helpers

import android.content.Context
import org.fossify.commons.helpers.BaseConfig
import shura.home.R

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    val sharedPrefs: android.content.SharedPreferences get() = prefs

    var wasHomeScreenInit: Boolean
        get() = prefs.getBoolean(WAS_HOME_SCREEN_INIT, false)
        set(wasHomeScreenInit) = prefs.edit().putBoolean(WAS_HOME_SCREEN_INIT, wasHomeScreenInit).apply()

    var wasDefaultClockAdded: Boolean
        get() = prefs.getBoolean(WAS_DEFAULT_CLOCK_ADDED, false)
        set(wasDefaultClockAdded) = prefs.edit().putBoolean(WAS_DEFAULT_CLOCK_ADDED, wasDefaultClockAdded).apply()

    var homeColumnCount: Int
        get() = prefs.getInt(HOME_COLUMN_COUNT, COLUMN_COUNT)
        set(homeColumnCount) = prefs.edit().putInt(HOME_COLUMN_COUNT, homeColumnCount).apply()

    var dockColumnCount: Int
        get() = prefs.getInt(DOCK_COLUMN_COUNT, 5)
        set(dockColumnCount) = prefs.edit().putInt(DOCK_COLUMN_COUNT, dockColumnCount).apply()

    var homeRowCount: Int
        get() = prefs.getInt(HOME_ROW_COUNT, ROW_COUNT)
        set(homeRowCount) = prefs.edit().putInt(HOME_ROW_COUNT, homeRowCount).apply()

    var drawerColumnCount: Int
        get() = prefs.getInt(DRAWER_COLUMN_COUNT, context.resources.getInteger(R.integer.portrait_column_count))
        set(drawerColumnCount) = prefs.edit().putInt(DRAWER_COLUMN_COUNT, drawerColumnCount).apply()

    var showSearchBar: Boolean
        get() = prefs.getBoolean(SHOW_SEARCH_BAR, true)
        set(showSearchBar) = prefs.edit().putBoolean(SHOW_SEARCH_BAR, showSearchBar).apply()

    var closeAppDrawer: Boolean
        get() = prefs.getBoolean(CLOSE_APP_DRAWER, false)
        set(closeAppDrawer) = prefs.edit().putBoolean(CLOSE_APP_DRAWER, closeAppDrawer).apply()

    var autoShowKeyboardInAppDrawer: Boolean
        get() = prefs.getBoolean(AUTO_SHOW_KEYBOARD_IN_APP_DRAWER, false)
        set(autoShowKeyboardInAppDrawer) = prefs.edit()
            .putBoolean(AUTO_SHOW_KEYBOARD_IN_APP_DRAWER, autoShowKeyboardInAppDrawer).apply()

    var showDrawerAppLabels: Boolean
        get() = prefs.getBoolean(SHOW_DRAWER_APP_LABELS, true)
        set(showDrawerAppLabels) = prefs.edit().putBoolean(SHOW_DRAWER_APP_LABELS, showDrawerAppLabels).apply()

    var showHomeAppLabels: Boolean
        get() = prefs.getBoolean(SHOW_HOME_APP_LABELS, false)
        set(showHomeAppLabels) = prefs.edit().putBoolean(SHOW_HOME_APP_LABELS, showHomeAppLabels).apply()

    var logKeeperEnabled: Boolean
        get() = prefs.getBoolean(LOG_KEEPER_ENABLED, true)
        set(logKeeperEnabled) = prefs.edit().putBoolean(LOG_KEEPER_ENABLED, logKeeperEnabled).apply()

    var isAppLockEnabled: Boolean
        get() = prefs.getBoolean(APP_LOCK_ENABLED, false)
        set(isAppLockEnabled) = prefs.edit().putBoolean(APP_LOCK_ENABLED, isAppLockEnabled).apply()

    var appLockTimeout: Int
        get() = prefs.getInt(APP_LOCK_TIMEOUT, APP_LOCK_TIMEOUT_IMMEDIATELY)
        set(appLockTimeout) = prefs.edit().putInt(APP_LOCK_TIMEOUT, appLockTimeout).apply()

    var appLockUseBiometrics: Boolean
        get() = prefs.getBoolean(APP_LOCK_USE_BIOMETRICS, true)
        set(appLockUseBiometrics) = prefs.edit().putBoolean(APP_LOCK_USE_BIOMETRICS, appLockUseBiometrics).apply()

    var appLockProtectHiddenApps: Boolean
        get() = prefs.getBoolean(APP_LOCK_PROTECT_HIDDEN_APPS, false)
        set(appLockProtectHiddenApps) = prefs.edit().putBoolean(APP_LOCK_PROTECT_HIDDEN_APPS, appLockProtectHiddenApps).apply()

    var drawerSortBy: Int
        get() = prefs.getInt(DRAWER_SORT_BY, DRAWER_SORT_BY_TIME)
        set(drawerSortBy) = prefs.edit().putInt(DRAWER_SORT_BY, drawerSortBy).apply()

    var drawerSortOrder: Int
        get() = prefs.getInt(DRAWER_SORT_ORDER, DRAWER_SORT_DESCENDING)
        set(drawerSortOrder) = prefs.edit().putInt(DRAWER_SORT_ORDER, drawerSortOrder).apply()

    var gestureDoubleTapAction: String
        get() = prefs.getString(GESTURE_DOUBLE_TAP_ACTION, LauncherActionHandler.ACTION_LOCK_SCREEN) ?: LauncherActionHandler.ACTION_LOCK_SCREEN
        set(value) = prefs.edit().putString(GESTURE_DOUBLE_TAP_ACTION, value).apply()

    var gestureSwipeDownAction: String
        get() = prefs.getString(GESTURE_SWIPE_DOWN_ACTION, LauncherActionHandler.ACTION_NOTIFICATIONS) ?: LauncherActionHandler.ACTION_NOTIFICATIONS
        set(value) = prefs.edit().putString(GESTURE_SWIPE_DOWN_ACTION, value).apply()

    var gestureSwipeUpAction: String
        get() = prefs.getString(GESTURE_SWIPE_UP_ACTION, LauncherActionHandler.ACTION_APP_DRAWER) ?: LauncherActionHandler.ACTION_APP_DRAWER
        set(value) = prefs.edit().putString(GESTURE_SWIPE_UP_ACTION, value).apply()

    var gesturePinchInAction: String
        get() = prefs.getString(GESTURE_PINCH_IN_ACTION, LauncherActionHandler.ACTION_ADD_TO_HOME) ?: LauncherActionHandler.ACTION_ADD_TO_HOME
        set(value) = prefs.edit().putString(GESTURE_PINCH_IN_ACTION, value).apply()

    var folderCoverMode: Boolean
        get() = prefs.getBoolean(FOLDER_COVER_MODE, false)
        set(value) = prefs.edit().putBoolean(FOLDER_COVER_MODE, value).apply()

    var gestureHaptics: Boolean
        get() = prefs.getBoolean(GESTURE_HAPTICS, false)
        set(value) = prefs.edit().putBoolean(GESTURE_HAPTICS, value).apply()

    var showNotificationBadges: Boolean
        get() = prefs.getBoolean(SHOW_NOTIFICATION_BADGES, false)
        set(value) = prefs.edit().putBoolean(SHOW_NOTIFICATION_BADGES, value).apply()

    var drawerUseCustomWallpaper: Boolean
        get() = prefs.getBoolean(DRAWER_USE_CUSTOM_WALLPAPER, false)
        set(value) = prefs.edit().putBoolean(DRAWER_USE_CUSTOM_WALLPAPER, value).apply()

    var newInstalledPackages: Set<String>
        get() = prefs.getStringSet(NEW_INSTALLED_PACKAGES, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(NEW_INSTALLED_PACKAGES, value).apply()

    fun addNewInstalledPackage(packageName: String) {
        val current = newInstalledPackages.toMutableSet()
        current.add(packageName)
        newInstalledPackages = current
    }

    fun removeNewInstalledPackage(packageName: String) {
        if (newInstalledPackages.contains(packageName)) {
            val current = newInstalledPackages.toMutableSet()
            current.remove(packageName)
            newInstalledPackages = current
        }
    }
}
