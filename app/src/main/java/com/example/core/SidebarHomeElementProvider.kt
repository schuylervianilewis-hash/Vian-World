package com.example.core

import android.appwidget.AppWidgetManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.core.graphics.drawable.toBitmap
import com.example.feature.element.ElementIdParser
import com.example.feature.sidebar.SidebarAppsManager
import com.example.feature.sidebar.SidebarItem
import com.example.feature.system_hub.DisplayHandler
import com.example.feature.system_hub.EInkPaperFilterManager
import com.example.feature.system_hub.PrivacyCurtainManager
import com.example.feature.system_hub.BlueLightFilterManager
import com.example.service.ScreenRecordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Supplies the launcher with a snapshot of Sidebar element labels, icons and supported
 * active states. The launcher explicitly requests a snapshot on load; this provider never
 * observes changes or runs a polling loop.
 */
class SidebarHomeElementProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val appContext = context ?: return null

        if (method == METHOD_SET_ELEMENT_STATE) {
            val elementId = extras?.getString(KEY_ELEMENT_ID)?.takeIf { it.isNotBlank() } ?: return Bundle()
            val active = extras.getBoolean(KEY_ACTIVE, false)
            val glowColor = extras.getInt(KEY_GLOW_COLOR, 0)
            appContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(STATE_PREFIX + elementId, active)
                .putInt(COLOR_PREFIX + elementId, glowColor)
                .apply()
            return Bundle().apply { putBoolean(KEY_SUCCESS, true) }
        }

        if (method != METHOD_GET_HOME_ELEMENTS) return null
        val ids = extras?.getStringArrayList(KEY_ELEMENT_IDS).orEmpty()
        if (ids.isEmpty()) return Bundle()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = appContext.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        val appsManager = SidebarAppsManager(appContext, prefs, scope, "launcher_home", {})
        val result = ArrayList<Bundle>(ids.size)

        try {
            ids.distinct().forEach { id ->
                val item = ElementIdParser.parse(appContext, id) ?: return@forEach
                val icon = resolveIcon(appContext, appsManager, id, item)
                val state = resolveState(appContext, id, item)
                result.add(Bundle().apply {
                    putString(KEY_ID, id)
                    putString(KEY_LABEL, item.label)
                    putParcelable(KEY_ICON, icon)
                    putBoolean(KEY_ACTIVE, state.first)
                    putInt(KEY_GLOW_COLOR, state.second)
                })
            }
        } finally {
            appsManager.destroy()
            scope.cancel()
        }

        return Bundle().apply { putParcelableArrayList(KEY_ELEMENTS, result) }
    }

    private fun resolveIcon(
        context: Context,
        appsManager: SidebarAppsManager,
        id: String,
        item: SidebarItem
    ): Bitmap? {
        val cached = appsManager.getIconBitmap(id)
        if (cached != null) return normalize(cached)

        val drawable = try {
            when (item) {
                is SidebarItem.App -> context.packageManager.getApplicationIcon(item.packageName)
                is SidebarItem.PopupWidget -> AppWidgetManager.getInstance(context)
                    .getAppWidgetInfo(item.widgetId)?.loadIcon(context, context.resources.displayMetrics.densityDpi)
                is SidebarItem.Widget -> AppWidgetManager.getInstance(context)
                    .getAppWidgetInfo(item.widgetId)?.loadIcon(context, context.resources.displayMetrics.densityDpi)
                else -> context.getDrawable(android.R.drawable.ic_menu_view)
            }
        } catch (_: Exception) {
            context.getDrawable(android.R.drawable.ic_menu_view)
        }
        return drawable?.toBitmap(width = ICON_SIZE, height = ICON_SIZE, config = Bitmap.Config.ARGB_8888)
    }

    private fun normalize(bitmap: Bitmap): Bitmap {
        return if (bitmap.width == ICON_SIZE && bitmap.height == ICON_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, ICON_SIZE, ICON_SIZE, true)
        }
    }

    private fun resolveState(context: Context, elementId: String, item: SidebarItem): Pair<Boolean, Int> {
        val statePrefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val stateKey = STATE_PREFIX + elementId
        if (statePrefs.contains(stateKey)) {
            return statePrefs.getBoolean(stateKey, false) to
                statePrefs.getInt(COLOR_PREFIX + elementId, 0)
        }

        return when (item) {
            is SidebarItem.DisplayAction -> when (item.action) {
                "torch_toggle" -> DisplayHandler.torchEnabled to TORCH_GLOW
                "blue_light_filter" -> BlueLightFilterManager.isEnabled to BLUE_GLOW
                "e_ink_mode" -> EInkPaperFilterManager.isEnabled to EINK_GLOW
                "privacy_curtain" -> PrivacyCurtainManager.isEnabled to PRIVACY_GLOW
                "keep_screen_on" -> DisplayHandler.keepScreenOnEnabled to KEEP_AWAKE_GLOW
                else -> false to 0
            }
            is SidebarItem.QuickTile -> {
                if (item.action == "torch") DisplayHandler.torchEnabled to TORCH_GLOW else false to 0
            }
            is SidebarItem.SystemAction -> {
                if (item.action == "screen_record") ScreenRecordService.isRecording to RECORD_GLOW else false to 0
            }
            else -> false to 0
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val METHOD_GET_HOME_ELEMENTS = "get_home_elements"
        const val METHOD_SET_ELEMENT_STATE = "set_element_state"
        const val KEY_ELEMENT_ID = "element_id"
        const val KEY_ACTIVE = "active"
        const val KEY_GLOW_COLOR = "glow_color"
        const val KEY_SUCCESS = "success"
        private const val KEY_ELEMENT_IDS = "element_ids"
        private const val KEY_ELEMENTS = "elements"
        private const val KEY_ID = "id"
        private const val KEY_LABEL = "label"
        private const val KEY_ICON = "icon"
        private const val STATE_PREFS = "SidebarHomeElementState"
        private const val STATE_PREFIX = "active:"
        private const val COLOR_PREFIX = "color:"
        private const val ICON_SIZE = 96
        private const val TORCH_GLOW = 0xFFFFC928.toInt()
        private const val BLUE_GLOW = 0xFFFF9900.toInt()
        private const val EINK_GLOW = 0xFFE6C280.toInt()
        private const val PRIVACY_GLOW = 0xFF1DB954.toInt()
        private const val KEEP_AWAKE_GLOW = 0xFF4CAF50.toInt()
        private const val RECORD_GLOW = 0xFFF44336.toInt()
    }
}
