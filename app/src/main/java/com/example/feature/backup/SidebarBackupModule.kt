package com.example.feature.backup

import android.content.Context
import android.appwidget.AppWidgetManager
import android.graphics.Color
import android.util.Base64
import com.example.core.HandleConfig
import com.example.core.HandleGestures
import com.example.core.HandleManager
import com.example.core.OverlaySyncManager
import com.example.core.PageManager
import com.example.core.SidebarPage
import com.example.feature.sidebar.ElementMetadata
import com.example.feature.sidebar.ElementMetadataStore
import com.example.util.HandleEdge
import com.example.util.HandleShape
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Sidebar backup boundary.
 *
 * Includes handle configuration, minimal metadata for every configured page stack, and
 * only the resident Main-process default_hybrid Home Grid. It deliberately does not
 * enumerate or copy arbitrary FloatingReaderPrefs keys.
 */
object SidebarBackupModule {
    private const val PREFS_NAME = "FloatingReaderPrefs"
    private const val HOME_PAGE_ID = "default_hybrid"
    private val handleIdPattern = Regex("^handle_[1-9][0-9]{0,2}$")
    private val actionPattern = Regex("^[a-zA-Z0-9_]{1,96}$")
    private val pageIdPattern = Regex("^[a-zA-Z0-9_.:-]{1,128}$")
    private val pageTypePattern = Regex("^[a-zA-Z0-9_]{1,64}$")
    private val gestureSet = HandleGestures.ALL.toSet()

    fun export(context: Context): JSONObject {
        val manager = HandleManager.getInstance(context)
        val pageManager = PageManager.getInstance(context)
        val handles = manager.getAllHandles()
        val handlesJson = JSONArray()
        handles.forEach { handlesJson.put(handleToJson(it)) }

        val pageStacks = JSONArray()
        val primaryContainer = manager.getFirstHandlePrimaryContainerId()
        var homeGrid: JSONObject? = null

        handles.forEach { handle ->
            HandleGestures.ALL.forEach gestureLoop@ { gesture ->
                if (handle.getActionForGesture(gesture) != HandleManager.ACTION_OPEN_SIDEBAR) return@gestureLoop
                val containerId = HandleManager.getContainerId(handle.id, gesture)
                val stack = pageManager.getPageStack(containerId)
                stack.forEachIndexed { index, page ->
                    pageStacks.put(
                        JSONObject()
                            .put("handleId", handle.id)
                            .put("gesture", gesture)
                            .put("containerId", containerId)
                            .put("pageId", page.pageId)
                            .put("pageType", page.pageType)
                            .put("title", page.title)
                            .put("order", index)
                    )
                    if (containerId == primaryContainer && page.pageId == HOME_PAGE_ID) {
                        homeGrid = exportResidentHomeGrid(context, containerId)
                    }
                }
            }
        }

        return JSONObject()
            .put("schemaVersion", 1)
            .put("handles", handlesJson)
            .put("pages", pageStacks)
            .put("homeGrid", homeGrid ?: JSONObject.NULL)
    }

    fun validate(payload: JSONObject) {
        require(payload.optInt("schemaVersion", -1) == 1) { "Unsupported Sidebar backup schema" }
        require(payload.optJSONArray("handles") != null) { "Sidebar handles are missing" }
        require(payload.optJSONArray("pages") != null) { "Sidebar page metadata is missing" }
        val handles = payload.getJSONArray("handles")
        require(handles.length() in 1..100) { "Invalid Sidebar handle count" }
        val seen = mutableSetOf<String>()
        var firstHandleHasSidebarGesture = false
        for (i in 0 until handles.length()) {
            val item = handles.optJSONObject(i) ?: error("Invalid handle record")
            val id = item.optString("id")
            require(handleIdPattern.matches(id) && seen.add(id)) { "Invalid or duplicate handle ID" }
            require(item.optString("name").length <= 100) { "Handle name is too long" }
            require(item.optDouble("position", 0.5) in 0.05..0.95) { "Invalid handle position" }
            require(item.optInt("widthDp", 12) in 4..200) { "Invalid handle width" }
            require(item.optInt("heightDp", 120) in 4..400) { "Invalid handle height" }
            require(item.optInt("alphaPercent", 14) in 0..100) { "Invalid handle alpha" }
            require(runCatching { HandleEdge.valueOf(item.optString("edge")) }.isSuccess) { "Invalid handle edge" }
            require(runCatching { HandleShape.valueOf(item.optString("shape")) }.isSuccess) { "Invalid handle shape" }
            val gestures = item.optJSONObject("gestures") ?: error("Handle gestures are missing")
            HandleGestures.ALL.forEach { gesture ->
                val action = gestures.optString(gesture, HandleManager.ACTION_NONE)
                require(actionPattern.matches(action)) { "Invalid gesture action" }
                if (id == "handle_1" && action == HandleManager.ACTION_OPEN_SIDEBAR)
                    firstHandleHasSidebarGesture = true
            }
        }
        require("handle_1" in seen && firstHandleHasSidebarGesture) {
            "Sidebar backup must preserve Handle 1's Sidebar-opening gesture"
        }
        val pages = payload.getJSONArray("pages")
        require(pages.length() <= 1000) { "Too many Sidebar page records" }
        val seenPageRecords = mutableSetOf<String>()
        for (i in 0 until pages.length()) {
            val item = pages.optJSONObject(i) ?: error("Invalid Sidebar page record")
            val handleId = item.optString("handleId")
            val gesture = item.optString("gesture")
            require(handleIdPattern.matches(handleId) && gesture in gestureSet) { "Invalid page container identity" }
            require(item.optString("containerId") == HandleManager.getContainerId(handleId, gesture)) {
                "Page container identity mismatch"
            }
            require(seenPageRecords.add("${item.optString("containerId")}/${item.optString("pageId")}")) {
                "Duplicate Sidebar page metadata record"
            }
            require(pageIdPattern.matches(item.optString("pageId"))) { "Invalid page ID" }
            require(pageTypePattern.matches(item.optString("pageType"))) { "Invalid page type" }
            require(item.optString("title").length <= 100) { "Page title is too long" }
            require(item.optInt("order", -1) in 0..1000) { "Invalid page order" }
        }
        val homeGrid = payload.optJSONObject("homeGrid")
            ?: error("Resident Main-process Home Grid data is missing")
        run {
            require(homeGrid.optString("pageId") == HOME_PAGE_ID) { "Unexpected full Sidebar page payload" }
            require(homeGrid.optString("containerId").isNotBlank()) { "Home Grid container is missing" }
            homeGrid.optString("gridJson").takeIf { it.isNotBlank() }?.let { raw ->
                val grid = JSONArray(raw)
                require(grid.length() <= 500) { "Home Grid has too many placed elements" }
                for (i in 0 until grid.length()) {
                    val item = grid.optJSONObject(i) ?: error("Invalid Home Grid placement")
                    require(item.optString("id").length in 1..512) { "Invalid Home Grid element reference" }
                    require(item.optInt("cols", 1) in 1..12 && item.optInt("rows", 1) in 1..12) {
                        "Invalid Home Grid element span"
                    }
                    require(item.optInt("x", 0) in 0..1000 && item.optInt("y", 0) in 0..1000) {
                        "Invalid Home Grid element position"
                    }
                }
            }
            homeGrid.optString("elementsJson").takeIf { it.isNotBlank() }?.let { raw ->
                val elements = JSONArray(raw)
                require(elements.length() <= 500) { "Home Grid contains too many elements" }
                for (i in 0 until elements.length())
                    require(elements.optString(i).length in 1..512) { "Invalid Home Grid element ID" }
            }
            val metadata = homeGrid.optJSONArray("elementMetadata") ?: JSONArray()
            require(metadata.length() <= 500) { "Too much Home Grid element metadata" }
            for (i in 0 until metadata.length()) {
                val item = metadata.optJSONObject(i) ?: error("Invalid Home Grid element metadata")
                require(item.optString("id").length in 1..512) { "Invalid metadata element ID" }
                require(item.optString("type") in setOf("app", "link")) { "Unsupported Home Grid metadata type" }
                require(item.optString("target").length in 1..4096) { "Invalid Home Grid metadata target" }
                require(item.optString("label").length in 1..200) { "Invalid Home Grid metadata label" }
                require(item.optString("iconBase64").length <= 1_400_000) { "Home Grid icon is too large" }
            }
        }
    }

    fun restore(context: Context, payload: JSONObject, exactRollback: Boolean = false): List<String> {
        validate(payload)
        val warnings = mutableListOf<String>()
        restoreHandles(context, payload.getJSONArray("handles"), exactRollback)
        restorePageMetadata(context, payload.getJSONArray("pages"), exactRollback, warnings)
        restoreResidentHomeGrid(context, payload.getJSONObject("homeGrid"))
        OverlaySyncManager.triggerReload(context)
        return warnings
    }

    private fun handleToJson(handle: HandleConfig): JSONObject {
        val gestures = JSONObject()
        HandleGestures.ALL.forEach { gestures.put(it, handle.getActionForGesture(it)) }
        return JSONObject()
            .put("id", handle.id)
            .put("name", handle.name)
            .put("enabled", handle.enabled)
            .put("edge", handle.edge.name)
            .put("position", handle.positionPercent.toDouble())
            .put("widthDp", handle.widthDp)
            .put("heightDp", handle.heightDp)
            .put("color", handle.color)
            .put("shape", handle.shape.name)
            .put("alphaPercent", handle.alphaPercent)
            .put("gestures", gestures)
    }

    private fun restoreHandles(context: Context, handles: JSONArray, exactRollback: Boolean) {
        val manager = HandleManager.getInstance(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentIds = manager.getHandleIds()
        val restoredIds = mutableListOf<String>()
        val editor = prefs.edit()

        for (i in 0 until handles.length()) {
            val item = handles.getJSONObject(i)
            val id = item.getString("id")
            if (!handleIdPattern.matches(id) || id in restoredIds) continue
            val index = id.removePrefix("handle_").toIntOrNull() ?: continue
            val edge = runCatching { HandleEdge.valueOf(item.optString("edge")) }.getOrNull() ?: continue
            val shape = runCatching { HandleShape.valueOf(item.optString("shape")) }.getOrNull() ?: continue
            val gestures = item.optJSONObject("gestures") ?: continue
            val actions = HandleGestures.ALL.associateWith { gesture ->
                val candidate = gestures.optString(gesture, HandleManager.ACTION_NONE)
                if (actionPattern.matches(candidate)) candidate else HandleManager.ACTION_NONE
            }.toMutableMap()

            // Keep the non-removable first handle capable of opening a Sidebar.
            if (id == "handle_1" && actions.values.none { it == HandleManager.ACTION_OPEN_SIDEBAR }) {
                actions[HandleGestures.SWIPE_LEFT] = HandleManager.ACTION_OPEN_SIDEBAR
            }

            editor.putString("handle_name_$index", item.optString("name", "Handle $index").take(100))
            editor.putBoolean("handle_enabled_$index", item.optBoolean("enabled", true))
            editor.putString("handle_edge_$index", edge.name)
            editor.putFloat("handle_pos_$index", item.optDouble("position", 0.5).toFloat().coerceIn(0.05f, 0.95f))
            editor.putInt("handle_width_$index", item.optInt("widthDp", 12).coerceIn(4, 200))
            editor.putInt("handle_height_$index", item.optInt("heightDp", 120).coerceIn(4, 400))
            editor.putInt("handle_color_$index", item.optInt("color", Color.parseColor("#242962ff")))
            editor.putString("handle_shape_$index", shape.name)
            editor.putInt("handle_alpha_$index", item.optInt("alphaPercent", 14).coerceIn(0, 100))
            actions.forEach { (gesture, action) ->
                editor.putString(HandleManager.getGesturePrefKey(index, gesture), action)
            }
            restoredIds.add(id)
        }

        // Normal restore merges destination-only handles. Rollback is exact and
        // removes handles created by the failed restore attempt.
        val mergedIds = if (exactRollback) {
            currentIds.filterNot { it in restoredIds }.forEach { id ->
                val staleIndex = id.removePrefix("handle_").toIntOrNull() ?: return@forEach
                listOf(
                    "handle_name_$staleIndex", "handle_enabled_$staleIndex",
                    "handle_edge_$staleIndex", "handle_pos_$staleIndex",
                    "handle_width_$staleIndex", "handle_height_$staleIndex",
                    "handle_color_$staleIndex", "handle_shape_$staleIndex",
                    "handle_alpha_$staleIndex"
                ).forEach(editor::remove)
                HandleGestures.ALL.forEach { gesture ->
                    editor.remove(HandleManager.getGesturePrefKey(staleIndex, gesture))
                    val staleContainer = HandleManager.getContainerId(id, gesture)
                    editor.remove("handle_${staleContainer}_pages")
                }
            }
            restoredIds.distinct().toMutableList()
        } else {
            (restoredIds + currentIds.filterNot { it in restoredIds }).distinct().toMutableList()
        }
        if ("handle_1" !in mergedIds) mergedIds.add(0, "handle_1")
        editor.putString(HandleManager.KEY_HANDLE_IDS, mergedIds.joinToString(","))
        editor.putInt(HandleManager.KEY_HANDLES_COUNT, mergedIds.size)
        check(editor.commit()) { "Could not persist Sidebar handles" }
        manager.notifyMainReload(context)
    }

    internal data class PageRecord(
        val handleId: String,
        val gesture: String,
        val containerId: String,
        val pageId: String,
        val pageType: String,
        val title: String,
        val order: Int
    )

    private fun restorePageMetadata(
        context: Context,
        pages: JSONArray,
        exactRollback: Boolean,
        warnings: MutableList<String>
    ) {
        val manager = HandleManager.getInstance(context)
        val pageManager = PageManager.getInstance(context)
        val recordsByContainer = linkedMapOf<String, MutableList<PageRecord>>()

        for (i in 0 until pages.length()) {
            val item = pages.getJSONObject(i)
            val record = PageRecord(
                handleId = item.getString("handleId"),
                gesture = item.getString("gesture"),
                containerId = item.getString("containerId"),
                pageId = item.getString("pageId"),
                pageType = item.getString("pageType"),
                title = item.optString("title", "").take(100),
                order = item.optInt("order", 0)
            )
            val handle = manager.getHandle(record.handleId)
            if (handle == null) {
                warnings.add("Skipped page ${record.title}: its handle is unavailable.")
                continue
            }
            if (handle.getActionForGesture(record.gesture) != HandleManager.ACTION_OPEN_SIDEBAR) {
                // Do not invent a gesture binding to accommodate an orphaned page stack.
                warnings.add("Skipped page ${record.title}: its saved gesture does not open Sidebar.")
                continue
            }
            recordsByContainer.getOrPut(record.containerId) { mutableListOf() }.add(record)
        }

        recordsByContainer.forEach { (containerId, records) ->
            val current = pageManager.getPageStack(containerId)
            if (!exactRollback) {
                records.forEach { record ->
                    val existing = current.firstOrNull { it.pageId == record.pageId }
                    if (existing != null && existing.pageType != record.pageType)
                        warnings.add("Skipped page ${record.title}: its existing page type differs.")
                }
            }
            val restored = if (exactRollback) {
                restorePageStackExact(current, records)
            } else {
                mergePageStack(current, records)
            }
            pageManager.savePageStack(containerId, restored)
        }
    }

    private fun restorePageStackExact(
        current: List<SidebarPage>,
        records: List<PageRecord>
    ): List<SidebarPage> {
        val currentById = current.associateBy { it.pageId }
        return records.distinctBy { it.pageId }.sortedBy { it.order }.mapIndexed { index, record ->
            val existing = currentById[record.pageId]
            if (existing != null && existing.pageType == record.pageType) {
                existing.copy(title = record.title, order = index)
            } else {
                SidebarPage.createDefault(
                    id = record.pageId,
                    type = record.pageType,
                    title = record.title,
                    order = index
                )
            }
        }
    }

    /**
     * Merge semantics: backup records are reinserted at their saved indices, while
     * destination-only pages retain their relative order. Type conflicts stay untouched.
     */
    internal fun mergePageStack(current: List<SidebarPage>, records: List<PageRecord>): List<SidebarPage> {
        val currentById = current.associateBy { it.pageId }
        val applicable = records
            .distinctBy { it.pageId }
            .sortedBy { it.order }
            .filter { record ->
                val existing = currentById[record.pageId]
                existing == null || existing.pageType == record.pageType
            }
        val applicableIds = applicable.map { it.pageId }.toSet()
        val result = current.filterNot { it.pageId in applicableIds }.toMutableList()

        applicable.forEach { record ->
            val existing = currentById[record.pageId]
            val page = if (existing != null) {
                // Preserve every non-metadata field on an existing page.
                existing.copy(title = record.title)
            } else {
                SidebarPage.createDefault(
                    id = record.pageId,
                    type = record.pageType,
                    title = record.title,
                    order = record.order
                )
            }
            val insertionIndex = record.order.coerceIn(0, result.size)
            result.add(insertionIndex, page)
        }
        return result.mapIndexed { index, page -> page.copy(order = index) }
    }

    private fun exportResidentHomeGrid(context: Context, containerId: String): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val gridKey = "handle_${containerId}_hybrid_grid_${HOME_PAGE_ID}"
        val legacyGridKey = "hybrid_grid_${HOME_PAGE_ID}"
        val elementsKey = "hg_${containerId}_${HOME_PAGE_ID}"
        val legacyElementsKey = "hg_${HOME_PAGE_ID}"
        val columnsKey = "handle_${containerId}_hybrid_grid_cols_${HOME_PAGE_ID}"
        val alternateColumnsKey = "${containerId}_hybrid_grid_cols_${HOME_PAGE_ID}"
        val legacyColumnsKey = "hybrid_grid_cols_${HOME_PAGE_ID}"
        val modifiedKey = "handle_${containerId}_hybrid_grid_modified_${HOME_PAGE_ID}"
        val alternateModifiedKey = "${containerId}_hybrid_grid_modified_${HOME_PAGE_ID}"
        val legacyModifiedKey = "hybrid_grid_modified_${HOME_PAGE_ID}"
        val wrapKey = "handle_${containerId}_sidebar_wrap_content"
        val heightKey = "handle_${containerId}_hybrid_grid_height_${HOME_PAGE_ID}"

        val isLegacyPrimary = containerId == "handle_1_swipe_left" || containerId == "sidebar"
        val rawGridJson = prefs.getString(gridKey, null)
            ?: prefs.getString("${containerId}_hybrid_grid_${HOME_PAGE_ID}", null)
            ?: prefs.getString(legacyGridKey, null).takeIf { isLegacyPrimary }
        // Widget IDs are Android-host-specific. Preserve their cells and provider hint,
        // but never put the source installation's numeric widget ID in the archive.
        val gridJson = rawGridJson?.let { sanitizeWidgetIds(context, it) }
        val elementsJson = prefs.getString(elementsKey, null)
            ?: prefs.getString(legacyElementsKey, null).takeIf { isLegacyPrimary }
        val columns = when {
            prefs.contains(columnsKey) -> prefs.getInt(columnsKey, 4)
            prefs.contains(alternateColumnsKey) -> prefs.getInt(alternateColumnsKey, 4)
            isLegacyPrimary && prefs.contains(legacyColumnsKey) -> prefs.getInt(legacyColumnsKey, 4)
            else -> null
        }
        val modified = when {
            prefs.contains(modifiedKey) -> prefs.getBoolean(modifiedKey, false)
            prefs.contains(alternateModifiedKey) -> prefs.getBoolean(alternateModifiedKey, false)
            isLegacyPrimary && prefs.contains(legacyModifiedKey) -> prefs.getBoolean(legacyModifiedKey, false)
            else -> null
        }
        val colorKey = "handle_${containerId}_sidebar_color"
        val transparencyKey = "handle_${containerId}_sidebar_transparency"
        val color = prefs.getString(colorKey, null) ?: prefs.getString("sidebar_color", null)
        val transparency = if (prefs.contains(transparencyKey)) {
            prefs.getFloat(transparencyKey, 0.9f)
        } else {
            prefs.getFloat("sidebar_transparency", 0.9f).takeIf { isLegacyPrimary && prefs.contains("sidebar_transparency") }
        }

        val present = JSONObject()
            .put("gridJson", gridJson != null)
            .put("elementsJson", elementsJson != null)
            .put("columns", columns != null)
            .put("modified", modified != null)
            .put("wrapContent", prefs.contains(wrapKey))
            .put("height", prefs.contains(heightKey))
            .put("sidebarColor", color != null)
            .put("sidebarTransparency", transparency != null)
        val result = JSONObject()
            .put("pageId", HOME_PAGE_ID)
            .put("containerId", containerId)
            .put("present", present)
        if (gridJson != null) result.put("gridJson", gridJson)
        if (elementsJson != null) {
            result.put("elementsJson", elementsJson)
            result.put("elementMetadata", exportElementMetadata(context, elementsJson))
            result.put("savedLinkIds", extractSavedLinkIds(elementsJson))
        }
        if (columns != null) result.put("columns", columns)
        if (modified != null) result.put("modified", modified)
        if (prefs.contains(wrapKey)) result.put("wrapContent", prefs.getBoolean(wrapKey, true))
        if (prefs.contains(heightKey)) result.put("height", prefs.getInt(heightKey, 0))
        if (color != null) result.put("sidebarColor", color)
        if (transparency != null) result.put("sidebarTransparency", transparency.toDouble())
        return result
    }

    private fun sanitizeWidgetIds(context: Context, source: String): String {
        return runCatching {
            val widgets = JSONArray(source)
            val appWidgetManager = AppWidgetManager.getInstance(context)
            for (index in 0 until widgets.length()) {
                val item = widgets.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (!id.startsWith("widget:")) continue
                val suffix = id.removePrefix("widget:")
                val sourceId = suffix.substringBefore(":").toIntOrNull()
                val existingHint = suffix.substringAfter(":", "")
                val providerHint = sourceId?.takeIf { it >= 0 }?.let { widgetId ->
                    runCatching { appWidgetManager.getAppWidgetInfo(widgetId)?.provider?.flattenToString() }.getOrNull()
                } ?: existingHint
                item.put("id", "widget:-1:${providerHint.take(255)}")
            }
            widgets.toString()
        }.getOrDefault("[]")
    }

    private fun extractSavedLinkIds(elementsJson: String): JSONArray {
        val output = JSONArray()
        val elements = runCatching { JSONArray(elementsJson) }.getOrNull() ?: return output
        val seen = mutableSetOf<String>()
        for (i in 0 until elements.length()) {
            val id = elements.optString(i)
            if (!id.startsWith("link:")) continue
            val uuid = id.substringAfter("link:").substringBefore(":")
            if (uuid.isNotBlank() && seen.add(uuid)) output.put(uuid)
        }
        return output
    }

    private fun sanitizeWidgetIdsForRestore(source: String): String {
        val widgets = JSONArray(source)
        for (index in 0 until widgets.length()) {
            val item = widgets.optJSONObject(index) ?: continue
            val id = item.optString("id")
            if (!id.startsWith("widget:")) continue
            val suffix = id.removePrefix("widget:")
            val existingHint = if (suffix.startsWith("-1:")) suffix.substringAfter(":", "") else ""
            item.put("id", "widget:-1:${existingHint.take(255)}")
        }
        return widgets.toString()
    }

    private fun exportElementMetadata(context: Context, elementsJson: String): JSONArray {
        val output = JSONArray()
        val elements = runCatching { JSONArray(elementsJson) }.getOrNull() ?: return output
        for (i in 0 until elements.length()) {
            val id = elements.optString(i)
            if (id.isBlank() || id.startsWith("pwa:") || id.startsWith("miniapp:")) continue
            val metadata = ElementMetadataStore.get(context, id) ?: continue
            if (metadata.type != "app" && metadata.type != "link") continue
            val item = JSONObject()
                .put("id", metadata.id)
                .put("type", metadata.type)
                .put("target", metadata.target)
                .put("label", metadata.label.take(200))
            metadata.browserPackage?.takeIf { it.isNotBlank() }?.let { item.put("browserPackage", it) }
            val iconFile = metadata.iconPath.takeIf { it.isNotBlank() }?.let(::File)
            if (iconFile != null && iconFile.isFile && iconFile.length() in 1..(1024L * 1024L)) {
                runCatching {
                    item.put("iconBase64", Base64.encodeToString(iconFile.readBytes(), Base64.NO_WRAP))
                }
            }
            output.put(item)
        }
        return output
    }

    private fun restoreResidentHomeGrid(context: Context, homeGrid: JSONObject) {
        val manager = HandleManager.getInstance(context)
        val containerId = homeGrid.optString("containerId")
        require(containerId == manager.getFirstHandlePrimaryContainerId()) {
            "Backup Home Grid does not belong to the current resident container"
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val gridKey = "handle_${containerId}_hybrid_grid_${HOME_PAGE_ID}"
        val elementsKey = "hg_${containerId}_${HOME_PAGE_ID}"
        val columnsKey = "handle_${containerId}_hybrid_grid_cols_${HOME_PAGE_ID}"
        val modifiedKey = "handle_${containerId}_hybrid_grid_modified_${HOME_PAGE_ID}"
        val wrapKey = "handle_${containerId}_sidebar_wrap_content"
        val heightKey = "handle_${containerId}_hybrid_grid_height_${HOME_PAGE_ID}"

        val present = homeGrid.optJSONObject("present")
        fun isPresent(key: String, legacyPayload: JSONObject): Boolean =
            if (present != null && present.has(key)) present.optBoolean(key, false) else legacyPayload.has(key)

        // Write canonical container-scoped keys even when a value was absent in the
        // source backup. This prevents stale legacy aliases on the destination from
        // silently repopulating the restored resident grid.
        if (isPresent("gridJson", homeGrid)) {
            editor.putString(gridKey, sanitizeWidgetIdsForRestore(homeGrid.optString("gridJson", "[]")))
        } else {
            editor.putString(gridKey, "[]")
        }
        if (isPresent("elementsJson", homeGrid)) {
            val elements = JSONArray(homeGrid.getString("elementsJson"))
            require(elements.length() <= 500) { "Home Grid contains too many elements" }
            editor.putString(elementsKey, elements.toString())
        } else {
            editor.putString(elementsKey, "[]")
        }
        editor.putInt(columnsKey, if (isPresent("columns", homeGrid)) homeGrid.optInt("columns", 4).coerceIn(1, 12) else 4)
        editor.putBoolean(modifiedKey, if (isPresent("modified", homeGrid)) homeGrid.optBoolean("modified", false) else false)
        editor.putBoolean(wrapKey, if (isPresent("wrapContent", homeGrid)) homeGrid.optBoolean("wrapContent", true) else true)
        if (isPresent("height", homeGrid)) {
            editor.putInt(heightKey, homeGrid.optInt("height", 0).coerceIn(0, 2000))
        } else {
            editor.remove(heightKey)
        }
        val color = if (isPresent("sidebarColor", homeGrid)) homeGrid.optString("sidebarColor", "#1E1E2E") else "#1E1E2E"
        require(color.length <= 32) { "Invalid Home Grid color" }
        editor.putString("handle_${containerId}_sidebar_color", color)
        val alpha = if (isPresent("sidebarTransparency", homeGrid)) homeGrid.optDouble("sidebarTransparency", 0.9) else 0.9
        require(alpha in 0.0..1.0) { "Invalid Home Grid transparency" }
        editor.putFloat("handle_${containerId}_sidebar_transparency", alpha.toFloat())
        check(editor.commit()) { "Could not persist resident Home Grid" }
        restoreElementMetadata(context, homeGrid.optJSONArray("elementMetadata") ?: JSONArray())
        val savedLinks = homeGrid.optJSONArray("savedLinkIds") ?: JSONArray()
        for (i in 0 until savedLinks.length()) {
            val uuid = savedLinks.optString(i)
            if (uuid.isNotBlank() && uuid.length <= 128)
                ElementMetadataStore.addSavedLinkId(context, uuid)
        }
    }

    private fun restoreElementMetadata(context: Context, metadata: JSONArray) {
        val iconDir = File(context.filesDir, "sidebar_backup_icons")
        if (!iconDir.exists()) iconDir.mkdirs()
        for (i in 0 until metadata.length()) {
            val item = metadata.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val type = item.optString("type")
            if (id.isBlank() || id.startsWith("pwa:") || id.startsWith("miniapp:")) continue
            if (type != "app" && type != "link") continue
            val target = item.optString("target")
            val label = item.optString("label").take(200)
            if (target.isBlank() || label.isBlank()) continue
            var iconPath = ""
            val encoded = item.optString("iconBase64")
            if (encoded.isNotBlank()) {
                runCatching {
                    val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                    require(bytes.size in 1..(1024 * 1024))
                    val file = File(iconDir, "${safeFileName(id)}.webp")
                    file.writeBytes(bytes)
                    iconPath = file.absolutePath
                }
            }
            ElementMetadataStore.save(
                context,
                ElementMetadata(
                    id = id,
                    type = type,
                    target = target,
                    label = label,
                    iconPath = iconPath,
                    browserPackage = item.optString("browserPackage").takeIf { it.isNotBlank() }
                )
            )
        }
    }

    private fun safeFileName(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
