package shura.home.extensions

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.widget.PopupWindow
import shura.home.views.AppShortcutsPopupWindow
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.MenuCompat
import androidx.core.view.forEach
import com.google.android.material.color.MaterialColors
import org.fossify.commons.extensions.getPopupMenuTheme
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.isDynamicTheme
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isSPlus
import shura.home.R
import shura.home.activities.MainActivity
import shura.home.activities.SettingsActivity
import androidx.fragment.app.FragmentActivity
import shura.home.helpers.AppLockManager
import shura.home.helpers.BUILT_IN_CLOCK_CLASS_NAME
import shura.home.helpers.ITEM_TYPE_FOLDER
import shura.home.helpers.ITEM_TYPE_ICON
import shura.home.helpers.ITEM_TYPE_WIDGET
import shura.home.helpers.UNINSTALL_APP_REQUEST_CODE
import shura.home.interfaces.ItemMenuListener
import shura.home.models.HomeScreenGridItem

fun Activity.launchApp(packageName: String, activityName: String, appTitle: String = "") {
    if (shura.home.helpers.ArchivedAppsManager.isArchived(this, packageName)) {
        shura.home.helpers.ArchivedAppsManager.unarchive(this, packageName, appTitle)
        return
    }

    if (this is FragmentActivity && !AppLockManager.canLaunchWithoutAuth(this, packageName)) {
        val title = if (appTitle.isNotEmpty()) appTitle else {
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageName
            }
        }
        AppLockManager.authenticateAndLaunch(this, packageName, title) {
            performActualAppLaunch(packageName, activityName)
        }
    } else {
        performActualAppLaunch(packageName, activityName)
    }
}

private fun Activity.performActualAppLaunch(packageName: String, activityName: String) {
    config.removeNewInstalledPackage(packageName)
    if (this is MainActivity) {
        refreshHomeScreenBadges()
    }
    try {
        Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            `package` = packageName
            component = ComponentName.unflattenFromString("$packageName/$activityName")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            startActivity(this)
        }
    } catch (e: Exception) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            startActivity(launchIntent)
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }
}

fun Activity.launchAppInfo(packageName: String) {
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        startActivity(this)
    }
}

fun Activity.canAppBeUninstalled(packageName: String): Boolean {
    return try {
        val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
        (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
    } catch (ignored: Exception) {
        shura.home.helpers.ArchivedAppsManager.isArchived(this, packageName)
    }
}

fun Activity.uninstallApp(packageName: String) {
    Intent(Intent.ACTION_DELETE).apply {
        data = Uri.fromParts("package", packageName, null)
        startActivityForResult(this, UNINSTALL_APP_REQUEST_CODE)
    }
}

fun Activity.handleGridItemPopupMenu(
    anchorView: View,
    gridItem: HomeScreenGridItem,
    isOnAllAppsFragment: Boolean,
    listener: ItemMenuListener,
    iconRect: Rect? = null,
): PopupWindow {
    val popup = AppShortcutsPopupWindow(
        activity = this,
        gridItem = gridItem,
        isOnAllAppsFragment = isOnAllAppsFragment,
        listener = listener,
        iconRect = iconRect
    )
    popup.show(anchorView)
    return popup
}
