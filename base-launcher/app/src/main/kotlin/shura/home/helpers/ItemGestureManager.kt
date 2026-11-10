package shura.home.helpers

import android.content.Context
import android.util.AtomicFile
import org.fossify.commons.helpers.ensureBackgroundThread
import shura.home.models.HomeScreenGridItem
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Gesture configuration model for an individual home screen grid item.
 */
data class ItemGestureConfig(
    var doubleTapAction: String = LauncherActionHandler.ACTION_NONE,
    var swipeUpAction: String = LauncherActionHandler.ACTION_NONE,
    var swipeDownAction: String = LauncherActionHandler.ACTION_NONE,
    var swipeLeftAction: String = LauncherActionHandler.ACTION_NONE,
    var swipeRightAction: String = LauncherActionHandler.ACTION_NONE,
    var targetPackage: String = "",
    var doubleTapPackage: String = "",
    var swipeUpPackage: String = "",
    var swipeDownPackage: String = "",
    var swipeLeftPackage: String = "",
    var swipeRightPackage: String = "",
    var linkedWidgetId: Int = -1,
    var linkedWidgetProvider: String = ""
) {
    fun getAction(gesture: String): String {
        return when (gesture) {
            ItemGestureManager.GESTURE_DOUBLE_TAP -> doubleTapAction
            ItemGestureManager.GESTURE_SWIPE_UP -> swipeUpAction
            ItemGestureManager.GESTURE_SWIPE_DOWN -> swipeDownAction
            ItemGestureManager.GESTURE_SWIPE_LEFT -> swipeLeftAction
            ItemGestureManager.GESTURE_SWIPE_RIGHT -> swipeRightAction
            else -> LauncherActionHandler.ACTION_NONE
        }
    }

    fun setAction(gesture: String, action: String) {
        when (gesture) {
            ItemGestureManager.GESTURE_DOUBLE_TAP -> doubleTapAction = action
            ItemGestureManager.GESTURE_SWIPE_UP -> swipeUpAction = action
            ItemGestureManager.GESTURE_SWIPE_DOWN -> swipeDownAction = action
            ItemGestureManager.GESTURE_SWIPE_LEFT -> swipeLeftAction = action
            ItemGestureManager.GESTURE_SWIPE_RIGHT -> swipeRightAction = action
        }
    }

    fun getTargetPackage(gesture: String): String {
        val pkg = when (gesture) {
            ItemGestureManager.GESTURE_DOUBLE_TAP -> doubleTapPackage
            ItemGestureManager.GESTURE_SWIPE_UP -> swipeUpPackage
            ItemGestureManager.GESTURE_SWIPE_DOWN -> swipeDownPackage
            ItemGestureManager.GESTURE_SWIPE_LEFT -> swipeLeftPackage
            ItemGestureManager.GESTURE_SWIPE_RIGHT -> swipeRightPackage
            else -> ""
        }
        return if (pkg.isNotEmpty()) pkg else targetPackage
    }

    fun setTargetPackage(gesture: String, pkg: String) {
        when (gesture) {
            ItemGestureManager.GESTURE_DOUBLE_TAP -> doubleTapPackage = pkg
            ItemGestureManager.GESTURE_SWIPE_UP -> swipeUpPackage = pkg
            ItemGestureManager.GESTURE_SWIPE_DOWN -> swipeDownPackage = pkg
            ItemGestureManager.GESTURE_SWIPE_LEFT -> swipeLeftPackage = pkg
            ItemGestureManager.GESTURE_SWIPE_RIGHT -> swipeRightPackage = pkg
        }
        if (pkg.isNotEmpty()) {
            targetPackage = pkg
        }
    }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("doubleTapAction", doubleTapAction)
            put("swipeUpAction", swipeUpAction)
            put("swipeDownAction", swipeDownAction)
            put("swipeLeftAction", swipeLeftAction)
            put("swipeRightAction", swipeRightAction)
            put("targetPackage", targetPackage)
            put("doubleTapPackage", doubleTapPackage)
            put("swipeUpPackage", swipeUpPackage)
            put("swipeDownPackage", swipeDownPackage)
            put("swipeLeftPackage", swipeLeftPackage)
            put("swipeRightPackage", swipeRightPackage)
            put("linkedWidgetId", linkedWidgetId)
            put("linkedWidgetProvider", linkedWidgetProvider)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): ItemGestureConfig {
            val fallbackPkg = json.optString("targetPackage", "")
            return ItemGestureConfig(
                doubleTapAction = json.optString("doubleTapAction", LauncherActionHandler.ACTION_NONE),
                swipeUpAction = json.optString("swipeUpAction", LauncherActionHandler.ACTION_NONE),
                swipeDownAction = json.optString("swipeDownAction", LauncherActionHandler.ACTION_NONE),
                swipeLeftAction = json.optString("swipeLeftAction", LauncherActionHandler.ACTION_NONE),
                swipeRightAction = json.optString("swipeRightAction", LauncherActionHandler.ACTION_NONE),
                targetPackage = fallbackPkg,
                doubleTapPackage = json.optString("doubleTapPackage", fallbackPkg),
                swipeUpPackage = json.optString("swipeUpPackage", fallbackPkg),
                swipeDownPackage = json.optString("swipeDownPackage", fallbackPkg),
                swipeLeftPackage = json.optString("swipeLeftPackage", fallbackPkg),
                swipeRightPackage = json.optString("swipeRightPackage", fallbackPkg),
                linkedWidgetId = json.optInt("linkedWidgetId", -1),
                linkedWidgetProvider = json.optString("linkedWidgetProvider", "")
            )
        }
    }
}

/**
 * Thread-safe, memory-cached gesture configuration manager for home screen items.
 * Persists to an isolated private JSON file, avoiding Room schema migrations while
 * guaranteeing 0ms lookup latency during user touch events.
 */
object ItemGestureManager {

    const val GESTURE_DOUBLE_TAP = "double_tap"
    const val GESTURE_SWIPE_UP = "swipe_up"
    const val GESTURE_SWIPE_DOWN = "swipe_down"
    const val GESTURE_SWIPE_LEFT = "swipe_left"
    const val GESTURE_SWIPE_RIGHT = "swipe_right"

    val SUPPORTED_GESTURES = listOf(
        GESTURE_SWIPE_UP,
        GESTURE_SWIPE_DOWN,
        GESTURE_SWIPE_LEFT,
        GESTURE_SWIPE_RIGHT
    )

    private val configs = ConcurrentHashMap<Long, ItemGestureConfig>()
    private var isInitialized = false

    private fun ensureInitialized(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            loadFromFile(context)
            isInitialized = true
        }
    }

    fun getGestureAction(context: Context, item: HomeScreenGridItem, gesture: String): String {
        ensureInitialized(context)
        val id = item.id ?: return getDefaultActionForItem(item, gesture)
        val config = configs[id]
        val configuredAction = config?.getAction(gesture) ?: LauncherActionHandler.ACTION_NONE

        return if (configuredAction != LauncherActionHandler.ACTION_NONE) {
            configuredAction
        } else {
            getDefaultActionForItem(item, gesture)
        }
    }

    fun getTargetPackage(context: Context, item: HomeScreenGridItem, gesture: String): String {
        ensureInitialized(context)
        val id = item.id ?: return ""
        return configs[id]?.getTargetPackage(gesture).orEmpty()
    }

    fun hasHorizontalGesture(context: Context, item: HomeScreenGridItem): Boolean {
        ensureInitialized(context)
        val id = item.id ?: return false
        val config = configs[id] ?: return false
        return (config.swipeLeftAction != LauncherActionHandler.ACTION_NONE) ||
                (config.swipeRightAction != LauncherActionHandler.ACTION_NONE)
    }

    fun setGestureAction(
        context: Context,
        itemId: Long,
        gesture: String,
        action: String,
        targetPackage: String = ""
    ) {
        ensureInitialized(context)
        val config = configs.getOrPut(itemId) { ItemGestureConfig() }
        config.setAction(gesture, action)
        if (targetPackage.isNotEmpty()) {
            config.setTargetPackage(gesture, targetPackage)
        }
        saveToFileAsync(context)
        LogCatcher.log("ItemGestureManager", "Set gesture: $gesture to $action for itemId: $itemId")
    }

    fun getLinkedWidget(context: Context, itemId: Long): Pair<Int, String>? {
        ensureInitialized(context)
        val config = configs[itemId] ?: return null
        if (config.linkedWidgetId != -1) {
            return Pair(config.linkedWidgetId, config.linkedWidgetProvider)
        }
        return null
    }

    fun setLinkedWidget(context: Context, itemId: Long, widgetId: Int, provider: String) {
        ensureInitialized(context)
        val config = configs.getOrPut(itemId) { ItemGestureConfig() }
        config.linkedWidgetId = widgetId
        config.linkedWidgetProvider = provider
        saveToFileAsync(context)
        LogCatcher.log("ItemGestureManager", "Linked widget $widgetId ($provider) to item $itemId")
    }

    fun unlinkWidget(context: Context, itemId: Long): Int {
        ensureInitialized(context)
        val config = configs[itemId] ?: return -1
        val oldId = config.linkedWidgetId
        config.linkedWidgetId = -1
        config.linkedWidgetProvider = ""
        saveToFileAsync(context)
        LogCatcher.log("ItemGestureManager", "Unlinked widget $oldId from item $itemId")
        return oldId
    }

    fun getOrCreateConfig(context: Context, itemId: Long): ItemGestureConfig {
        ensureInitialized(context)
        return configs.getOrPut(itemId) { ItemGestureConfig() }
    }

    fun removeConfig(context: Context, itemId: Long) {
        ensureInitialized(context)
        configs.remove(itemId)
        saveToFileAsync(context)
    }

    fun getAllConfigs(context: Context): Map<Long, ItemGestureConfig> {
        ensureInitialized(context)
        return HashMap(configs)
    }

    fun restoreConfigs(context: Context, newConfigs: Map<Long, ItemGestureConfig>) {
        configs.clear()
        configs.putAll(newConfigs)
        saveToFileAsync(context)
        LogCatcher.log("ItemGestureManager", "Restored ${newConfigs.size} gesture configurations")
    }

    private fun getDefaultActionForItem(item: HomeScreenGridItem, gesture: String): String {
        // Folder industry standard: double tap opens folder popup
        if (item.type == ITEM_TYPE_FOLDER && gesture == GESTURE_DOUBLE_TAP) {
            return LauncherActionHandler.ACTION_FOLDER_POPUP
        }
        return LauncherActionHandler.ACTION_NONE
    }

    private fun getStorageFile(context: Context): File {
        return File(context.filesDir, "item_gestures.json")
    }

    private fun loadFromFile(context: Context) {
        try {
            val file = getStorageFile(context)
            if (!file.exists()) return

            val atomicFile = AtomicFile(file)
            val bytes = atomicFile.readFully()
            val content = String(bytes, Charsets.UTF_8)
            if (content.isEmpty()) return

            val rootJson = JSONObject(content)
            val keys = rootJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val itemId = key.toLongOrNull() ?: continue
                val itemJson = rootJson.getJSONObject(key)
                configs[itemId] = ItemGestureConfig.fromJsonObject(itemJson)
            }
            LogCatcher.log("ItemGestureManager", "Loaded ${configs.size} gesture configs from storage")
        } catch (e: Exception) {
            LogCatcher.log("ItemGestureManager", "Failed to load gesture configs", e)
        }
    }

    private fun saveToFileAsync(context: Context) {
        ensureBackgroundThread {
            try {
                val file = getStorageFile(context)
                val atomicFile = AtomicFile(file)
                val rootJson = JSONObject()
                for ((id, config) in configs) {
                    rootJson.put(id.toString(), config.toJsonObject())
                }
                val bytes = rootJson.toString().toByteArray(Charsets.UTF_8)
                var stream: java.io.FileOutputStream? = null
                try {
                    stream = atomicFile.startWrite()
                    stream.write(bytes)
                    atomicFile.finishWrite(stream)
                } catch (e: Exception) {
                    if (stream != null) {
                        atomicFile.failWrite(stream)
                    }
                    throw e
                }
            } catch (e: Exception) {
                LogCatcher.log("ItemGestureManager", "Failed to save gesture configs", e)
            }
        }
    }
}
