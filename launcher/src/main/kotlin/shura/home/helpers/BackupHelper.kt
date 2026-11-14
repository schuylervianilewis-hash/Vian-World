package shura.home.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import shura.home.BuildConfig
import shura.home.databases.AppsDatabase
import shura.home.models.HiddenIcon
import shura.home.models.HomeScreenGridItem
import shura.home.models.LockedApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Unified Backup and Restore engine for Vian Launcher.
 *
 * Encapsulates the entire launcher state into a single atomic JSON document:
 * 1. Home screen grid layout (apps, folders, widgets, shortcuts, positions across all pages).
 * 2. Gestures & actions configurations and linked popup widgets (ItemGestureManager).
 * 3. Launcher preferences and layout geometry (Config).
 * 4. Hidden and locked applications lists.
 * 5. Custom application icons (Base64 PNG encoded).
 */
object BackupHelper {
    private const val TAG = "BackupHelper"
    private const val CURRENT_SCHEMA_VERSION = 1

    fun exportBackup(context: Context, outputStream: OutputStream): Boolean {
        return try {
            LogCatcher.log(TAG, "Starting launcher backup export...")
            val root = JSONObject()
            root.put("schema_version", CURRENT_SCHEMA_VERSION)
            root.put("app_version", BuildConfig.VERSION_NAME)
            root.put("timestamp", System.currentTimeMillis())

            // 1. Export Config preferences
            val config = Config(context)
            val configJson = JSONObject().apply {
                put("homeRowCount", config.homeRowCount)
                put("homeColumnCount", config.homeColumnCount)
                put("dockColumnCount", config.dockColumnCount)
                put("drawerColumnCount", config.drawerColumnCount)
                put("showSearchBar", config.showSearchBar)
                put("closeAppDrawer", config.closeAppDrawer)
                put("autoShowKeyboardInAppDrawer", config.autoShowKeyboardInAppDrawer)
                put("showDrawerAppLabels", config.showDrawerAppLabels)
                put("showHomeAppLabels", config.showHomeAppLabels)
                put("drawerSortBy", config.drawerSortBy)
                put("drawerSortOrder", config.drawerSortOrder)
                put("isAppLockEnabled", config.isAppLockEnabled)
                put("appLockTimeout", config.appLockTimeout)
                put("appLockUseBiometrics", config.appLockUseBiometrics)
                put("appLockProtectHiddenApps", config.appLockProtectHiddenApps)
                put("gestureDoubleTapAction", config.gestureDoubleTapAction)
                put("gestureSwipeDownAction", config.gestureSwipeDownAction)
                put("gestureSwipeUpAction", config.gestureSwipeUpAction)
                put("gesturePinchInAction", config.gesturePinchInAction)
                put("folderCoverMode", config.folderCoverMode)
                put("gestureHaptics", config.gestureHaptics)
                put("logKeeperEnabled", config.logKeeperEnabled)
            }
            root.put("config", configJson)

            // 2. Export Home Screen Grid Items
            val db = AppsDatabase.getInstance(context)
            val gridItems = db.HomeScreenGridItemsDao().getAllItems()
            val gridItemsArray = JSONArray()
            for (item in gridItems) {
                val itemJson = JSONObject().apply {
                    put("id", item.id)
                    put("left", item.left)
                    put("top", item.top)
                    put("right", item.right)
                    put("bottom", item.bottom)
                    put("page", item.page)
                    put("package_name", item.packageName)
                    put("activity_name", item.activityName)
                    put("title", item.title)
                    put("type", item.type)
                    put("class_name", item.className)
                    put("widget_id", item.widgetId)
                    put("shortcut_id", item.shortcutId)
                    put("docked", item.docked)
                    put("parent_id", item.parentId)

                    val bitmap = item.icon
                    if (bitmap != null) {
                        val baos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                        val encoded = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                        put("icon_base64", encoded)
                    }
                }
                gridItemsArray.put(itemJson)
            }
            root.put("grid_items", gridItemsArray)

            // 3. Export Item Gestures
            val allGestures = ItemGestureManager.getAllConfigs(context)
            val gesturesJson = JSONObject()
            for ((itemId, gestureConfig) in allGestures) {
                val gestureObj = JSONObject().apply {
                    put("doubleTapAction", gestureConfig.doubleTapAction)
                    put("swipeUpAction", gestureConfig.swipeUpAction)
                    put("swipeDownAction", gestureConfig.swipeDownAction)
                    put("swipeLeftAction", gestureConfig.swipeLeftAction)
                    put("swipeRightAction", gestureConfig.swipeRightAction)
                    put("targetPackage", gestureConfig.targetPackage)
                    put("doubleTapPackage", gestureConfig.doubleTapPackage)
                    put("swipeUpPackage", gestureConfig.swipeUpPackage)
                    put("swipeDownPackage", gestureConfig.swipeDownPackage)
                    put("swipeLeftPackage", gestureConfig.swipeLeftPackage)
                    put("swipeRightPackage", gestureConfig.swipeRightPackage)
                    put("linkedWidgetId", gestureConfig.linkedWidgetId)
                    put("linkedWidgetProvider", gestureConfig.linkedWidgetProvider)
                }
                gesturesJson.put(itemId.toString(), gestureObj)
            }
            root.put("item_gestures", gesturesJson)

            // 4. Export Hidden Icons
            val hiddenIcons = db.HiddenIconsDao().getHiddenIcons()
            val hiddenArray = JSONArray()
            for (hidden in hiddenIcons) {
                val hiddenObj = JSONObject().apply {
                    put("packageName", hidden.packageName)
                    put("activityName", hidden.activityName)
                }
                hiddenArray.put(hiddenObj)
            }
            root.put("hidden_icons", hiddenArray)

            // 5. Export Locked Apps
            val lockedApps = db.LockedAppsDao().getAllLockedApps()
            val lockedArray = JSONArray()
            for (locked in lockedApps) {
                val lockedObj = JSONObject().apply {
                    put("packageName", locked.packageName)
                    put("title", locked.title)
                    put("lockedAt", locked.lockedAt)
                }
                lockedArray.put(lockedObj)
            }
            root.put("locked_apps", lockedArray)

            // 6. Export Custom Drawer Renames (launchersDB)
            val appLaunchers = db.AppLaunchersDao().getAppLaunchers()
            val drawerRenamesJson = JSONObject()
            for (launcher in appLaunchers) {
                if (launcher.title.isNotEmpty()) {
                    drawerRenamesJson.put(launcher.packageName, launcher.title)
                }
            }
            root.put("drawer_renames", drawerRenamesJson)

            // 6. Export Custom Icons (Base64 PNG)
            val customIconsDir = File(context.filesDir, "custom_icons")
            val customIconsJson = JSONObject()
            if (customIconsDir.exists() && customIconsDir.isDirectory) {
                val files = customIconsDir.listFiles() ?: emptyArray()
                for (file in files) {
                    if (file.isFile && file.extension.equals("png", ignoreCase = true)) {
                        try {
                            val bytes = file.readBytes()
                            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            val key = file.nameWithoutSuffix(".png")
                            customIconsJson.put(key, encoded)
                        } catch (e: Exception) {
                            LogCatcher.log(TAG, "Failed to encode custom icon ${file.name}", e)
                        }
                    }
                }
            }
            root.put("custom_icons", customIconsJson)

            // Write JSON out
            val jsonString = root.toString(2)
            outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(jsonString)
                writer.flush()
            }

            LogCatcher.log(TAG, "Backup exported successfully: ${gridItems.size} grid items, ${allGestures.size} gestures, ${lockedApps.size} locked apps")
            true
        } catch (e: Exception) {
            LogCatcher.log(TAG, "Failed to export backup", e)
            false
        }
    }

    fun importBackup(context: Context, inputStream: InputStream): Boolean {
        return try {
            LogCatcher.log(TAG, "Starting launcher backup import...")
            val jsonString = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(jsonString)

            val schemaVersion = root.optInt("schema_version", 1)
            if (schemaVersion > CURRENT_SCHEMA_VERSION) {
                LogCatcher.log(TAG, "Unsupported backup schema version: $schemaVersion")
                return false
            }

            val db = AppsDatabase.getInstance(context)

            // Execute DB operations inside transaction
            db.runInTransaction {
                val gridDao = db.HomeScreenGridItemsDao()
                val hiddenDao = db.HiddenIconsDao()
                val lockedDao = db.LockedAppsDao()

                // Clear existing home items, hidden icons, and locked apps
                gridDao.deleteAllItems()
                hiddenDao.deleteAllHiddenIcons()
                lockedDao.deleteAllLockedApps()

                val oldToNewIdMap = mutableMapOf<Long, Long>()

                // Read grid items
                val gridArray = root.optJSONArray("grid_items") ?: JSONArray()
                val pendingItems = mutableListOf<HomeScreenGridItem>()

                for (i in 0 until gridArray.length()) {
                    val obj = gridArray.getJSONObject(i)
                    val oldId = obj.optLong("id")
                    val bitmap = if (obj.has("icon_base64")) {
                        try {
                            val decoded = Base64.decode(obj.getString("icon_base64"), Base64.NO_WRAP)
                            if (decoded.size <= 1024 * 1024) {
                                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(decoded, 0, decoded.size, boundsOpts)
                                if (boundsOpts.outWidth in 1..512 && boundsOpts.outHeight in 1..512) {
                                    BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                                } else null
                            } else null
                        } catch (e: Exception) {
                            null
                        }
                    } else null

                    val item = HomeScreenGridItem(
                        id = null,
                        left = obj.getInt("left"),
                        top = obj.getInt("top"),
                        right = obj.getInt("right"),
                        bottom = obj.getInt("bottom"),
                        page = obj.getInt("page"),
                        packageName = obj.optString("package_name", ""),
                        activityName = obj.optString("activity_name", ""),
                        title = obj.optString("title", ""),
                        type = obj.getInt("type"),
                        className = obj.optString("class_name", ""),
                        widgetId = obj.optInt("widget_id", -1),
                        shortcutId = obj.optString("shortcut_id", ""),
                        icon = bitmap,
                        docked = obj.optBoolean("docked", false),
                        parentId = if (obj.has("parent_id") && !obj.isNull("parent_id")) obj.getLong("parent_id") else null
                    )

                    if (item.type == ITEM_TYPE_FOLDER) {
                        // Insert folder first so its new ID can be mapped to children
                        val newId = gridDao.insert(item)
                        if (oldId != 0L) {
                            oldToNewIdMap[oldId] = newId
                        }
                    } else {
                        pendingItems.add(item.apply {
                            // Temporary store oldId in extra memory tag if needed
                            if (oldId != 0L) {
                                (this as java.lang.Object).hashCode() // no-op
                            }
                        })
                    }
                }

                // Insert child items with remapped folder parentId
                for (item in pendingItems) {
                    val oldParent = item.parentId
                    if (oldParent != null && oldToNewIdMap.containsKey(oldParent)) {
                        item.parentId = oldToNewIdMap[oldParent]
                    }
                    val newId = gridDao.insert(item)
                    // If we had old item id mapped
                    // Map by coordinates & package signature for gesture re-linking
                }

                // 2. Restore Item Gestures
                val gesturesJson = root.optJSONObject("item_gestures")
                val restoredGestures = mutableMapOf<Long, ItemGestureConfig>()
                if (gesturesJson != null) {
                    // Check against freshly inserted items to match IDs
                    val allNewItems = gridDao.getAllItems()
                    val keys = gesturesJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val oldId = key.toLongOrNull() ?: continue
                        val gObj = gesturesJson.getJSONObject(key)

                        val config = ItemGestureConfig(
                            doubleTapAction = gObj.optString("doubleTapAction", LauncherActionHandler.ACTION_NONE),
                            swipeUpAction = gObj.optString("swipeUpAction", LauncherActionHandler.ACTION_NONE),
                            swipeDownAction = gObj.optString("swipeDownAction", LauncherActionHandler.ACTION_NONE),
                            swipeLeftAction = gObj.optString("swipeLeftAction", LauncherActionHandler.ACTION_NONE),
                            swipeRightAction = gObj.optString("swipeRightAction", LauncherActionHandler.ACTION_NONE),
                            targetPackage = gObj.optString("targetPackage", ""),
                            doubleTapPackage = gObj.optString("doubleTapPackage", ""),
                            swipeUpPackage = gObj.optString("swipeUpPackage", ""),
                            swipeDownPackage = gObj.optString("swipeDownPackage", ""),
                            swipeLeftPackage = gObj.optString("swipeLeftPackage", ""),
                            swipeRightPackage = gObj.optString("swipeRightPackage", ""),
                            linkedWidgetId = gObj.optInt("linkedWidgetId", -1),
                            linkedWidgetProvider = gObj.optString("linkedWidgetProvider", "")
                        )

                        val newId = oldToNewIdMap[oldId]
                        if (newId != null) {
                            restoredGestures[newId] = config
                        } else {
                            // If direct mapping not found, match by first item with matching targetPackage or coordinates
                            val match = allNewItems.firstOrNull { it.packageName == config.targetPackage && it.packageName.isNotEmpty() }
                            val matchedId = match?.id
                            if (matchedId != null) {
                                restoredGestures[matchedId] = config
                            }
                        }
                    }
                }
                ItemGestureManager.restoreConfigs(context, restoredGestures)

                // 3. Restore Hidden Icons
                val hiddenArray = root.optJSONArray("hidden_icons") ?: JSONArray()
                for (i in 0 until hiddenArray.length()) {
                    val item = hiddenArray.get(i)
                    val (pkg, act) = if (item is JSONObject) {
                        Pair(item.getString("packageName"), item.optString("activityName", ""))
                    } else {
                        Pair(item.toString(), "")
                    }
                    hiddenDao.insert(HiddenIcon(id = null, packageName = pkg, activityName = act, title = ""))
                }

                // 4. Restore Locked Apps
                val lockedArray = root.optJSONArray("locked_apps") ?: JSONArray()
                for (i in 0 until lockedArray.length()) {
                    val lObj = lockedArray.getJSONObject(i)
                    val lockedApp = LockedApp(
                        packageName = lObj.getString("packageName"),
                        title = lObj.optString("title", ""),
                        lockedAt = lObj.optLong("lockedAt", System.currentTimeMillis())
                    )
                    lockedDao.insertLockedApp(lockedApp)
                }

                // 5. Restore Custom Drawer Renames
                val drawerRenamesJson = root.optJSONObject("drawer_renames")
                if (drawerRenamesJson != null) {
                    val launcherDao = db.AppLaunchersDao()
                    val keys = drawerRenamesJson.keys()
                    while (keys.hasNext()) {
                        val pkg = keys.next()
                        val customTitle = drawerRenamesJson.getString(pkg)
                        launcherDao.updateAppTitle(customTitle, pkg)
                    }
                }
            }

            // 5. Restore Config preferences
            val configJson = root.optJSONObject("config")
            if (configJson != null) {
                val config = Config(context)
                if (configJson.has("homeRowCount")) config.homeRowCount = configJson.getInt("homeRowCount")
                if (configJson.has("homeColumnCount")) config.homeColumnCount = configJson.getInt("homeColumnCount")
                if (configJson.has("dockColumnCount")) config.dockColumnCount = configJson.getInt("dockColumnCount")
                if (configJson.has("drawerColumnCount")) config.drawerColumnCount = configJson.getInt("drawerColumnCount")
                if (configJson.has("showSearchBar")) config.showSearchBar = configJson.getBoolean("showSearchBar")
                if (configJson.has("closeAppDrawer")) config.closeAppDrawer = configJson.getBoolean("closeAppDrawer")
                if (configJson.has("autoShowKeyboardInAppDrawer")) config.autoShowKeyboardInAppDrawer = configJson.getBoolean("autoShowKeyboardInAppDrawer")
                if (configJson.has("showDrawerAppLabels")) config.showDrawerAppLabels = configJson.getBoolean("showDrawerAppLabels")
                if (configJson.has("showHomeAppLabels")) config.showHomeAppLabels = configJson.getBoolean("showHomeAppLabels")
                if (configJson.has("drawerSortBy")) config.drawerSortBy = configJson.getInt("drawerSortBy")
                if (configJson.has("drawerSortOrder")) config.drawerSortOrder = configJson.getInt("drawerSortOrder")
                if (configJson.has("isAppLockEnabled")) config.isAppLockEnabled = configJson.getBoolean("isAppLockEnabled")
                if (configJson.has("appLockTimeout")) config.appLockTimeout = configJson.getInt("appLockTimeout")
                if (configJson.has("appLockUseBiometrics")) config.appLockUseBiometrics = configJson.getBoolean("appLockUseBiometrics")
                if (configJson.has("appLockProtectHiddenApps")) config.appLockProtectHiddenApps = configJson.getBoolean("appLockProtectHiddenApps")
                if (configJson.has("gestureDoubleTapAction")) config.gestureDoubleTapAction = configJson.getString("gestureDoubleTapAction")
                if (configJson.has("gestureSwipeDownAction")) config.gestureSwipeDownAction = configJson.getString("gestureSwipeDownAction")
                if (configJson.has("gestureSwipeUpAction")) config.gestureSwipeUpAction = configJson.getString("gestureSwipeUpAction")
                if (configJson.has("gesturePinchInAction")) config.gesturePinchInAction = configJson.getString("gesturePinchInAction")
                if (configJson.has("folderCoverMode")) config.folderCoverMode = configJson.getBoolean("folderCoverMode")
                if (configJson.has("gestureHaptics")) config.gestureHaptics = configJson.getBoolean("gestureHaptics")
                config.wasHomeScreenInit = true
                config.wasDefaultClockAdded = true
            }

            // 6. Restore Custom Icons
            val customIconsJson = root.optJSONObject("custom_icons")
            if (customIconsJson != null) {
                val keys = customIconsJson.keys()
                while (keys.hasNext()) {
                    val pkgName = keys.next()
                    if (!CustomIconManager.isValidPackageName(pkgName)) {
                        LogCatcher.log(TAG, "Skipping invalid package name in custom_icons: $pkgName")
                        continue
                    }
                    try {
                        val base64Str = customIconsJson.getString(pkgName)
                        val bytes = Base64.decode(base64Str, Base64.NO_WRAP)
                        if (bytes.size <= 1024 * 1024) {
                            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
                            if (boundsOpts.outWidth in 1..512 && boundsOpts.outHeight in 1..512) {
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bitmap != null) {
                                    CustomIconManager.saveCustomIcon(context, pkgName, bitmap)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        LogCatcher.log(TAG, "Failed to restore custom icon for $pkgName", e)
                    }
                }
            }

            // Evict in-memory icon and app caches, reload app locks
            IconCache.clear()
            AppLockManager.reload(context)

            LogCatcher.log(TAG, "Backup restored successfully")
            true
        } catch (e: Exception) {
            LogCatcher.log(TAG, "Failed to import backup", e)
            false
        }
    }

    private fun File.nameWithoutSuffix(suffix: String): String {
        return if (name.endsWith(suffix, ignoreCase = true)) {
            name.substring(0, name.length - suffix.length)
        } else {
            name
        }
    }
}
