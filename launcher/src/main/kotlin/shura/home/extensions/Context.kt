package shura.home.extensions

import android.app.role.RoleManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.util.Size
import androidx.annotation.RequiresApi
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isSPlus
import shura.home.databases.AppsDatabase
import shura.home.helpers.Config
import shura.home.interfaces.AppLaunchersDao
import shura.home.interfaces.HiddenIconsDao
import shura.home.interfaces.HomeScreenGridItemsDao
import shura.home.interfaces.LockedAppsDao
import kotlin.math.ceil
import kotlin.math.max

val Context.config: Config get() = Config.newInstance(applicationContext)

val Context.launchersDB: AppLaunchersDao
    get() = AppsDatabase.getInstance(applicationContext).AppLaunchersDao()

val Context.homeScreenGridItemsDB: HomeScreenGridItemsDao
    get() = AppsDatabase.getInstance(
        applicationContext
    ).HomeScreenGridItemsDao()

val Context.hiddenIconsDB: HiddenIconsDao
    get() = AppsDatabase.getInstance(applicationContext).HiddenIconsDao()

val Context.lockedAppsDB: LockedAppsDao
    get() = AppsDatabase.getInstance(applicationContext).LockedAppsDao()

@get:RequiresApi(Build.VERSION_CODES.Q)
val Context.roleManager: RoleManager
    get() = getSystemService(RoleManager::class.java)

fun Context.getDrawableForPackageName(packageName: String, activityName: String? = null): Drawable? {
    val customIcon = shura.home.helpers.CustomIconManager.getCustomIcon(this, packageName)
    if (customIcon != null) {
        return customIcon
    }

    var drawable: Drawable? = null
    try {
        // try getting the properly colored launcher icons
        val launcher = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val activityList = launcher.getActivityList(packageName, Process.myUserHandle())
        val matchingActivity = if (!activityName.isNullOrEmpty()) {
            activityList.firstOrNull { it.componentName.className == activityName } ?: activityList.firstOrNull()
        } else {
            activityList.firstOrNull()
        }
        drawable = matchingActivity?.getBadgedIcon(0)
    } catch (e: Exception) {
    } catch (e: Error) {
    }

    if (drawable == null && !activityName.isNullOrEmpty()) {
        drawable = try {
            packageManager.getActivityIcon(android.content.ComponentName(packageName, activityName))
        } catch (ignored: Exception) {
            null
        }
    }

    if (drawable == null) {
        drawable = try {
            packageManager.getApplicationIcon(packageName)
        } catch (ignored: Exception) {
            null
        }
    }

    return drawable
}

fun Context.getInitialCellSize(
    info: AppWidgetProviderInfo,
    fallbackWidth: Int,
    fallbackHeight: Int
): Size {
    return if (isSPlus() && info.targetCellWidth != 0 && info.targetCellHeight != 0) {
        Size(info.targetCellWidth, info.targetCellHeight)
    } else {
        val widthCells = getCellCount(fallbackWidth)
        val heightCells = getCellCount(fallbackHeight)
        Size(widthCells, heightCells)
    }
}

fun Context.getCellCount(size: Int): Int {
    val tiles = ceil(((size / resources.displayMetrics.density) - 30) / 70.0).toInt()
    return max(tiles, 1)
}

fun Context.isDefaultLauncher(): Boolean {
    return if (isQPlus()) {
        with(roleManager) {
            isRoleAvailable(RoleManager.ROLE_HOME) && isRoleHeld(RoleManager.ROLE_HOME)
        }
    } else {
        val filters = ArrayList<IntentFilter>()
        val activities = ArrayList<ComponentName>()
        @Suppress("DEPRECATION")
        packageManager.getPreferredActivities(filters, activities, null)
        return activities.indices.any { i ->
            activities[i].packageName == packageName &&
                    filters[i].hasAction(Intent.ACTION_MAIN) &&
                    filters[i].hasCategory(Intent.CATEGORY_HOME)
        }
    }
}
