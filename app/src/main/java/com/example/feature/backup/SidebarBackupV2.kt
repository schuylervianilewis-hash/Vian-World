package com.example.feature.backup

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.appwidget.AppWidgetManager
import android.graphics.Color
import android.util.Base64
import com.example.core.HandleGestures
import com.example.core.HandleManager
import com.example.core.OverlaySyncManager
import com.example.core.PageManager
import com.example.core.PageTypes
import com.example.core.SidebarPage
import com.example.data.AppDatabase
import com.example.data.SchedulerTask
import com.example.feature.element.ElementPlacement
import com.example.feature.element.ElementPlacementManager
import com.example.feature.sidebar.ElementMetadata
import com.example.feature.sidebar.ElementMetadataStore
import com.example.core.IconCacheManager
import com.example.util.HandleEdge
import com.example.util.HandleShape
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Complete, explicitly allowlisted Sidebar backup schema.
 *
 * The v1 adapter remains available for old archives. This schema captures every configured
 * handle/gesture container, complete SidebarPage models, per-page placements and the supported
 * persisted page content. It deliberately does not copy arbitrary SharedPreferences or the
 * whole application database.
 */
object SidebarBackupV2 {
    const val SCHEMA_VERSION = 2

    private const val PREFS_NAME = "FloatingReaderPrefs"
    private const val MAX_HANDLES = 100
    private const val MAX_CONTAINERS = 700
    private const val MAX_PAGES = 1000
    private const val MAX_PAGE_ELEMENTS = 500
    private const val MAX_GRID_ITEMS = 500
    private const val MAX_METADATA = 1000
    private const val MAX_CUSTOM_ICONS = 1000
    private const val MAX_ICON_BYTES = 1024 * 1024
    private const val MAX_CUSTOM_ICON_BYTES = 256 * 1024
    private const val MAX_CUSTOM_ICON_TOTAL_BYTES = 8 * 1024 * 1024
    private const val MAX_PAYLOAD_BYTES = 22 * 1024 * 1024

    private val handleIdPattern = Regex("^handle_[1-9][0-9]{0,2}$")
    private val pageIdPattern = Regex("^[a-zA-Z0-9_.:-]{1,128}$")
    private val pageTypePattern = Regex("^[a-zA-Z0-9_]{1,64}$")
    private val actionPattern = Regex("^[a-zA-Z0-9_./:-]{1,512}$")
    private val gestureSet = HandleGestures.ALL.toSet()
    private val safeIconNamePattern = Regex("^[a-zA-Z0-9._-]{1,256}\\.webp$")

    data class RestoreResult(
        val warnings: List<String>,
        val details: List<String>
    )

    fun export(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val handleManager = HandleManager.getInstance(context)
        val pageManager = PageManager.getInstance(context)
        val handles = handleManager.getAllHandles()
        require(handles.isNotEmpty() && handles.size <= MAX_HANDLES) { "Invalid Sidebar handle count" }

        val handlesJson = JSONArray()
        handles.forEach { handle ->
            val item = SidebarBackupModule.handleToJson(handle)
            val index = handle.id.removePrefix("handle_")
            item.put("settings", exportHandleSettings(prefs, index))
            handlesJson.put(item)
        }

        val primaryContainerId = handleManager.getFirstHandlePrimaryContainerId()
        val containersJson = JSONArray()
        var totalPages = 0
        var hasSchedulerPage = false
        var hasAppTrackerPage = false

        handles.forEach { handle ->
            HandleGestures.ALL.forEach gestureLoop@ { gesture ->
                if (handle.getActionForGesture(gesture) != HandleManager.ACTION_OPEN_SIDEBAR) {
                    return@gestureLoop
                }
                val containerId = HandleManager.getContainerId(handle.id, gesture)
                val stack = pageManager.getPageStack(containerId)
                totalPages += stack.size
                require(totalPages <= MAX_PAGES) { "Too many Sidebar pages to back up" }
                val pageIds = stack.map { it.pageId }.toSet()
                val selectedPageId = pageManager.getSelectedPageId(containerId)?.takeIf { it in pageIds }
                val openingFaceId = pageManager.getOpeningFaceId(containerId)?.takeIf { it in pageIds }

                val pagesJson = JSONArray()
                val pageContentJson = JSONArray()
                stack.forEachIndexed { index, page ->
                    pagesJson.put(page.toJson().put("order", index))
                    pageContentJson.put(
                        exportPageContent(
                            context = context,
                            prefs = prefs,
                            handleId = handle.id,
                            containerId = containerId,
                            primaryContainerId = primaryContainerId,
                            page = page
                        )
                    )
                    if (page.pageType == PageTypes.SCHEDULER) hasSchedulerPage = true
                    if (page.pageType == PageTypes.APP_TRACKER) hasAppTrackerPage = true
                }

                containersJson.put(
                    JSONObject()
                        .put("handleId", handle.id)
                        .put("gesture", gesture)
                        .put("containerId", containerId)
                        .put("selectedPageId", selectedPageId ?: JSONObject.NULL)
                        .put("openingFaceId", openingFaceId ?: JSONObject.NULL)
                        .put("settings", exportContainerSettings(prefs, containerId))
                        .put("pages", pagesJson)
                        .put("pageContent", pageContentJson)
                )
            }
        }
        require(containersJson.length() in 1..MAX_CONTAINERS) { "No Sidebar containers were found to back up" }

        // Migration is deterministic and local; it ensures legacy element IDs have their
        // metadata before the metadata inventory is read.
        ElementMetadataStore.migrateLegacyElements(context)

        val payload = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("handles", handlesJson)
            .put("containers", containersJson)
            .put("globalSettings", exportGlobalSettings(prefs))
            .put("elementMetadata", exportAllElementMetadata(context, prefs))
            .put("customIcons", exportCustomIconFiles(context))
            .put("savedLinkIds", JSONArray().apply {
                ElementMetadataStore.getSavedLinkIds(context).filter { it.isNotBlank() }.distinct().sorted().forEach(::put)
            })

        if (hasSchedulerPage) {
            payload.put("scheduler", exportScheduler(context, prefs))
        }
        if (hasAppTrackerPage) {
            val whitelist = prefs.getStringSet("app_tracker_whitelist_cache", emptySet()) ?: emptySet()
            payload.put("appTrackerWhitelist", JSONArray().apply {
                whitelist.sorted().forEach(::put)
            })
        }

        require(payload.toString().toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Sidebar backup is too large. Reduce the number or size of custom icons and try again."
        }
        validate(payload)
        return payload
    }

    private fun exportHandleSettings(prefs: SharedPreferences, index: String): JSONObject {
        return JSONObject()
            .put("columns", readSetting(prefs, "handle_${index}_columns", "handle_${index}_columns", false))
            .put("legacyEdge", readSetting(prefs, "handle_${index}_edge", "handle_${index}_edge", false))
            .put("sidebarColor", readSetting(prefs, "handle_${index}_sidebar_color", "handle_${index}_sidebar_color", false))
            .put("sidebarTransparency", readSetting(prefs, "handle_${index}_sidebar_transparency", "handle_${index}_sidebar_transparency", false))
    }

    private fun exportContainerSettings(
        prefs: SharedPreferences,
        containerId: String
    ): JSONObject {
        return JSONObject()
            .put("sidebarColor", readSetting(prefs, "handle_${containerId}_sidebar_color", "handle_${containerId}_sidebar_color", false))
            .put("sidebarTransparency", readSetting(prefs, "handle_${containerId}_sidebar_transparency", "handle_${containerId}_sidebar_transparency", false))
            .put("wrapContent", readSetting(prefs, "handle_${containerId}_sidebar_wrap_content", "handle_${containerId}_sidebar_wrap_content", false))
            .put("height", readSetting(prefs, "handle_${containerId}_sidebar_height", "handle_${containerId}_sidebar_height", false))
            .put("width", readSetting(prefs, "handle_${containerId}_sidebar_width", "handle_${containerId}_sidebar_width", false))
            .put("columns", readSetting(prefs, "handle_${containerId}_columns", "handle_${containerId}_columns", false))
    }

    private fun exportGlobalSettings(prefs: SharedPreferences): JSONObject {
        return JSONObject()
            .put("sidebarColor", readSetting(prefs, "sidebar_color", "sidebar_color", false))
            .put("sidebarTransparency", readSetting(prefs, "sidebar_transparency", "sidebar_transparency", false))
            .put("wrapContent", readSetting(prefs, "sidebar_wrap_content", "sidebar_wrap_content", false))
            .put("height", readSetting(prefs, "sidebar_height", "sidebar_height", false))
            .put("width", readSetting(prefs, "sidebar_width", "sidebar_width", false))
            .put("columns", readSetting(prefs, "sidebar_columns", "sidebar_columns", false))
            .put("positionLeft", readSetting(prefs, "sidebar_position_left", "sidebar_position_left", false))
    }

    private fun readSetting(
        prefs: SharedPreferences,
        canonicalKey: String,
        legacyKey: String,
        allowLegacy: Boolean
    ): JSONObject {
        val key = when {
            prefs.contains(canonicalKey) -> canonicalKey
            allowLegacy && prefs.contains(legacyKey) -> legacyKey
            else -> return JSONObject().put("present", false)
        }
        val value = prefs.all[key] ?: return JSONObject().put("present", false)
        return JSONObject().put("present", true).put("value", value)
    }

    private fun exportPageContent(
        context: Context,
        prefs: SharedPreferences,
        handleId: String,
        containerId: String,
        primaryContainerId: String,
        page: SidebarPage
    ): JSONObject {
        val placements = ElementPlacementManager.getInstance(context).getElementsForPage(containerId, page.pageId)
        val placementJson = JSONArray()
        placements.forEach { placement ->
            val safeAction = sanitizeWidgetElementId(context, placement.actionKey)
            placementJson.put(placement.copy(actionKey = safeAction).toJson())
        }

        val primary = containerId == primaryContainerId ||
            containerId == "sidebar" ||
            containerId == "handle_1"
        val kind = when (page.pageType) {
            PageTypes.HYBRID_GRID, "hybrid_grid", "default_hybrid", "tools" -> "hybrid_grid"
            PageTypes.WIDGETS_GRID, "widgets_grid", "widgets" -> "widgets_grid"
            PageTypes.APPS, "apps", "apps_grid" -> "apps"
            PageTypes.WIDGET, "widget", "single_widget" -> "widget_page"
            else -> "none"
        }
        val data = JSONObject()

        when (kind) {
            "hybrid_grid" -> {
                val pageId = page.pageId
                val gridRaw = readString(
                    prefs,
                    "handle_${containerId}_hybrid_grid_${pageId}",
                    "${containerId}_hybrid_grid_${pageId}",
                    "hybrid_grid_${pageId}".takeIf { primary }
                )
                val elementsRaw = readString(
                    prefs,
                    "hg_${containerId}_${pageId}",
                    "hg_${pageId}".takeIf { primary }
                )
                val columns = readInt(
                    prefs,
                    "handle_${containerId}_hybrid_grid_cols_${pageId}",
                    "${containerId}_hybrid_grid_cols_${pageId}",
                    "hybrid_grid_cols_${pageId}".takeIf { primary }
                )
                val modified = readBoolean(
                    prefs,
                    "handle_${containerId}_hybrid_grid_modified_${pageId}",
                    "${containerId}_hybrid_grid_modified_${pageId}",
                    "hybrid_grid_modified_${pageId}".takeIf { primary }
                )
                val height = readInt(prefs, "handle_${containerId}_hybrid_grid_height_${pageId}")

                data.put("gridJsonPresent", gridRaw != null)
                if (gridRaw != null) data.put("gridJson", sanitizeWidgetGridJson(context, gridRaw))
                data.put("elementsJsonPresent", elementsRaw != null)
                if (elementsRaw != null) data.put("elementsJson", sanitizeWidgetElementArray(context, elementsRaw))
                data.put("columnsPresent", columns != null)
                if (columns != null) data.put("columns", columns)
                data.put("modifiedPresent", modified != null)
                if (modified != null) data.put("modified", modified)
                data.put("heightPresent", height != null)
                if (height != null) data.put("height", height)
            }
            "widgets_grid" -> {
                val pageId = page.pageId
                val gridRaw = readString(
                    prefs,
                    "handle_${containerId}_widgets_grid_${pageId}",
                    "${containerId}_widgets_grid_${pageId}",
                    "widgets_grid_${pageId}".takeIf { primary }
                )
                val columns = readInt(
                    prefs,
                    "handle_${containerId}_widgets_grid_cols_${pageId}",
                    "${containerId}_widgets_grid_cols_${pageId}",
                    "widgets_grid_cols_${pageId}".takeIf { primary }
                )
                data.put("gridJsonPresent", gridRaw != null)
                if (gridRaw != null) data.put("gridJson", sanitizeWidgetGridJson(context, gridRaw))
                data.put("columnsPresent", columns != null)
                if (columns != null) data.put("columns", columns)
            }
            "widget_page" -> {
                val widgetId = page.pageId.toIntOrNull()
                val provider = widgetId?.takeIf { it >= 0 }?.let { id ->
                    runCatching {
                        AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider?.flattenToString()
                    }.getOrNull()
                }
                data.put("providerPresent", provider != null)
                if (!provider.isNullOrBlank()) data.put("provider", provider.take(512))
            }
            "apps" -> {
                val pageId = page.pageId
                val handleIndex = handleId.removePrefix("handle_")
                val appsRaw = readString(
                    prefs,
                    "sidebar_apps_${containerId}_${pageId}",
                    "sidebar_apps_${pageId}".takeIf { primary },
                    "sidebar_apps".takeIf { primary }
                )
                val pageColumns = readInt(
                    prefs,
                    "handle_${containerId}_page_${pageId}_columns",
                    "handle_${handleIndex}_page_${pageId}_columns"
                )
                data.put("appsJsonPresent", appsRaw != null)
                if (appsRaw != null) data.put("appsJson", sanitizeWidgetElementArray(context, appsRaw))
                data.put("pageColumnsPresent", pageColumns != null)
                if (pageColumns != null) data.put("pageColumns", pageColumns)
            }
        }

        return JSONObject()
            .put("containerId", containerId)
            .put("pageId", page.pageId)
            .put("pageType", page.pageType)
            .put("kind", kind)
            .put("elements", placementJson)
            .put("data", data)
    }

    private fun readString(prefs: SharedPreferences, vararg keys: String?): String? {
        for (key in keys) {
            if (key == null) continue
            val value = prefs.all[key]
            if (value is String) return value
        }
        return null
    }

    private fun readInt(prefs: SharedPreferences, vararg keys: String?): Int? {
        for (key in keys) {
            if (key == null) continue
            val value = prefs.all[key]
            if (value is Int) return value
        }
        return null
    }

    private fun readBoolean(prefs: SharedPreferences, vararg keys: String?): Boolean? {
        for (key in keys) {
            if (key == null) continue
            val value = prefs.all[key]
            if (value is Boolean) return value
        }
        return null
    }

    private fun sanitizeWidgetGridJson(context: Context, source: String): String {
        val items = JSONArray(source)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: error("Invalid grid placement")
            item.put("id", sanitizeWidgetElementId(context, item.optString("id"), appWidgetManager))
        }
        return items.toString()
    }

    private fun sanitizeWidgetElementArray(context: Context, source: String): String {
        val items = JSONArray(source)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        for (index in 0 until items.length()) {
            val raw = items.optString(index)
            if (raw.startsWith("widget:")) items.put(index, sanitizeWidgetElementId(context, raw, appWidgetManager))
        }
        return items.toString()
    }

    private fun sanitizeWidgetElementId(
        context: Context,
        source: String,
        manager: AppWidgetManager = AppWidgetManager.getInstance(context)
    ): String {
        if (source.startsWith("folder:")) {
            val parts = source.split(":", limit = 3)
            if (parts.size >= 3) {
                val rewritten = runCatching {
                    val folder = JSONObject(parts[2])
                    val items = folder.optJSONArray("items")
                    if (items != null) {
                        val sanitized = JSONArray()
                        for (index in 0 until items.length()) {
                            sanitized.put(sanitizeWidgetElementId(context, items.optString(index), manager))
                        }
                        folder.put("items", sanitized)
                    }
                    "folder:${parts[1]}:${folder}"
                }.getOrNull()
                if (rewritten != null) return rewritten
            }
            return source
        }
        if (!source.startsWith("widget:")) return source
        val suffix = source.removePrefix("widget:")
        val widgetId = suffix.substringBefore(":").toIntOrNull()
        val existingHint = suffix.substringAfter(":", "")
        val provider = widgetId?.takeIf { it >= 0 }?.let { id ->
            runCatching { manager.getAppWidgetInfo(id)?.provider?.flattenToString() }.getOrNull()
        } ?: existingHint
        return "widget:-1:${provider.take(255)}"
    }

    private fun exportAllElementMetadata(context: Context, prefs: SharedPreferences): JSONArray {
        val byId = linkedMapOf<String, ElementMetadata>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("elem_meta_") && value is String) {
                ElementMetadata.fromJson(value)?.let { metadata ->
                    if (metadata.type in setOf("app", "link", "pwa")) byId[metadata.id] = metadata
                }
            }
        }
        require(byId.size <= MAX_METADATA) { "Too many Sidebar element metadata records" }

        return JSONArray().apply {
            byId.values.sortedBy { it.id }.forEach { metadata ->
                val item = JSONObject()
                    .put("id", metadata.id)
                    .put("type", metadata.type)
                    .put("target", metadata.target)
                    .put("label", metadata.label.take(200))
                metadata.browserPackage?.takeIf { it.isNotBlank() }?.let { item.put("browserPackage", it) }
                metadata.account?.takeIf { it.isNotBlank() }?.let { item.put("account", it) }
                val iconFile = metadata.iconPath.takeIf { it.isNotBlank() }?.let(::File)
                if (iconFile != null && iconFile.exists()) {
                    require(iconFile.isFile && iconFile.length() in 1..MAX_ICON_BYTES.toLong()) {
                        "A Sidebar element icon is too large or unreadable to back up: ${metadata.id}"
                    }
                    item.put("iconBase64", Base64.encodeToString(iconFile.readBytes(), Base64.NO_WRAP))
                }
                put(item)
            }
        }
    }

    private fun exportCustomIconFiles(context: Context): JSONArray {
        val dir = File(context.filesDir, "custom_icons")
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".webp", ignoreCase = true) }.orEmpty()
        require(files.size <= MAX_CUSTOM_ICONS) { "Too many Sidebar custom icons" }
        var totalBytes = 0L
        return JSONArray().apply {
            files.sortedBy { it.name }.forEach { file ->
                require(safeIconNamePattern.matches(file.name)) { "Invalid custom icon filename" }
                require(file.length() in 1..MAX_CUSTOM_ICON_BYTES.toLong()) {
                    "A Sidebar custom icon is too large to back up: ${file.name}"
                }
                totalBytes += file.length()
                require(totalBytes <= MAX_CUSTOM_ICON_TOTAL_BYTES) {
                    "Sidebar custom icons exceed the backup size limit"
                }
                put(
                    JSONObject()
                        .put("fileName", file.name)
                        .put("base64", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
                )
            }
        }
    }

    private fun exportScheduler(context: Context, prefs: SharedPreferences): JSONObject {
        val tasks = runBlocking {
            AppDatabase.getDatabase(context).schedulerTaskDao().getAllTasks().first()
        }
        val taskArray = JSONArray()
        tasks.forEach { task ->
            taskArray.put(
                JSONObject()
                    .put("id", task.id)
                    .put("title", task.title)
                    .put("note", task.note)
                    .put("timeMillis", task.timeMillis)
                    .put("tags", task.tags)
                    .put("status", task.status)
            )
        }
        return JSONObject()
            .put("tasks", taskArray)
            .put("tagsPresent", prefs.contains("scheduler_tags"))
            .put("tags", prefs.getString("scheduler_tags", "") ?: "")
    }

    fun validate(payload: JSONObject) {
        require(payload.optInt("schemaVersion", -1) == SCHEMA_VERSION) { "Unsupported Sidebar backup schema" }
        require(payload.toString().toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Sidebar backup payload exceeds the size limit"
        }

        val handles = payload.optJSONArray("handles") ?: error("Sidebar handles are missing")
        require(handles.length() in 1..MAX_HANDLES) { "Invalid Sidebar handle count" }
        val handleActions = linkedMapOf<String, Map<String, String>>()
        var firstHandleHasSidebarGesture = false
        for (i in 0 until handles.length()) {
            val item = handles.optJSONObject(i) ?: error("Invalid handle record")
            val id = item.optString("id")
            require(handleIdPattern.matches(id) && !handleActions.containsKey(id)) { "Invalid or duplicate handle ID" }
            require(item.optString("name").length <= 100) { "Handle name is too long" }
            require(item.optDouble("position", 0.5) in 0.0..1.0) { "Invalid handle position" }
            require(item.optInt("widthDp", 12) in 4..200) { "Invalid handle width" }
            require(item.optInt("heightDp", 120) in 4..400) { "Invalid handle height" }
            require(item.optInt("alphaPercent", 14) in 0..100) { "Invalid handle alpha" }
            require(runCatching { HandleEdge.valueOf(item.optString("edge")) }.isSuccess) { "Invalid handle edge" }
            require(runCatching { HandleShape.valueOf(item.optString("shape")) }.isSuccess) { "Invalid handle shape" }
            validateHandleSettings(item.optJSONObject("settings") ?: error("Handle settings are missing"))
            val gestures = item.optJSONObject("gestures") ?: error("Handle gestures are missing")
            val actions = linkedMapOf<String, String>()
            HandleGestures.ALL.forEach { gesture ->
                val action = gestures.optString(gesture, HandleManager.ACTION_NONE)
                require(Regex("^[a-zA-Z0-9_]{1,96}$").matches(action)) { "Invalid gesture action" }
                actions[gesture] = action
                if (id == "handle_1" && action == HandleManager.ACTION_OPEN_SIDEBAR) {
                    firstHandleHasSidebarGesture = true
                }
            }
            handleActions[id] = actions
        }
        require("handle_1" in handleActions && firstHandleHasSidebarGesture) {
            "Sidebar backup must preserve Handle 1's Sidebar-opening gesture"
        }

        val containers = payload.optJSONArray("containers") ?: error("Sidebar containers are missing")
        require(containers.length() in 1..MAX_CONTAINERS) { "Invalid Sidebar container count" }
        val seenContainers = mutableSetOf<String>()
        var totalPages = 0
        for (i in 0 until containers.length()) {
            val container = containers.optJSONObject(i) ?: error("Invalid container record")
            val handleId = container.optString("handleId")
            val gesture = container.optString("gesture")
            val containerId = container.optString("containerId")
            require(handleIdPattern.matches(handleId) && gesture in gestureSet) { "Invalid container identity" }
            require(containerId == HandleManager.getContainerId(handleId, gesture)) { "Container identity mismatch" }
            require(seenContainers.add(containerId)) { "Duplicate Sidebar container" }
            require(handleActions[handleId]?.get(gesture) == HandleManager.ACTION_OPEN_SIDEBAR) {
                "Container gesture is not configured to open Sidebar"
            }

            val pages = container.optJSONArray("pages") ?: error("Container page list is missing")
            val content = container.optJSONArray("pageContent") ?: error("Container page content is missing")
            totalPages += pages.length()
            require(totalPages <= MAX_PAGES) { "Too many Sidebar pages" }
            require(content.length() == pages.length()) { "Page content count does not match page metadata" }

            val pagesById = linkedMapOf<String, SidebarPage>()
            for (pageIndex in 0 until pages.length()) {
                val pageJson = pages.optJSONObject(pageIndex) ?: error("Invalid page metadata")
                val page = SidebarPage.fromJson(pageJson, pageIndex)
                require(pageIdPattern.matches(page.pageId)) { "Invalid page ID" }
                require(pageTypePattern.matches(page.pageType)) { "Invalid page type" }
                require(page.title.length <= 100) { "Page title is too long" }
                require(page.iconName.length <= 128) { "Page icon name is too long" }
                require(page.customIconBase64.length <= 1_400_000) { "Page custom icon is too large" }
                require(page.width in 0..2000 && page.height in 0..2000) { "Invalid page dimensions" }
                require(page.transparency in 0f..1f) { "Invalid page transparency" }
                require(page.gridColumns in 1..12) { "Invalid page grid column count" }
                require(page.stickAlignment in setOf("top", "center", "bottom")) { "Invalid page stick alignment" }
                require(page.appGroupPackageNames.size <= 100) { "Too many app group members" }
                require(page.appGroupPackageNames.all { it.length in 1..256 }) { "Invalid app group member" }
                require(page.order in 0..MAX_PAGES) { "Invalid page order" }
                require(!pagesById.containsKey(page.pageId)) { "Duplicate page ID in container" }
                pagesById[page.pageId] = page
            }

            validateOptionalPageId(container, "selectedPageId", pagesById.keys)
            validateOptionalPageId(container, "openingFaceId", pagesById.keys)
            validateContainerSettings(container.optJSONObject("settings") ?: error("Container settings are missing"))

            val seenContent = mutableSetOf<String>()
            for (contentIndex in 0 until content.length()) {
                val record = content.optJSONObject(contentIndex) ?: error("Invalid page content record")
                require(record.optString("containerId") == containerId) { "Page content container mismatch" }
                val pageId = record.optString("pageId")
                val page = pagesById[pageId] ?: error("Page content references an unknown page")
                require(record.optString("pageType") == page.pageType) { "Page content type mismatch" }
                require(seenContent.add(pageId)) { "Duplicate page content record" }
                validatePageContent(record, page)
            }
            require(seenContent == pagesById.keys) { "A configured page is missing its content record" }
        }

        validateGlobalSettings(payload.optJSONObject("globalSettings") ?: error("Global Sidebar settings are missing"))
        validateMetadata(payload.optJSONArray("elementMetadata") ?: JSONArray())
        validateCustomIcons(payload.optJSONArray("customIcons") ?: JSONArray())
        validateStringArray(payload.optJSONArray("savedLinkIds") ?: JSONArray(), 500, 128, "saved link")

        if (payload.has("scheduler")) validateScheduler(payload.getJSONObject("scheduler"))
        if (payload.has("appTrackerWhitelist")) {
            validateStringArray(payload.getJSONArray("appTrackerWhitelist"), 1000, 256, "App Tracker package")
        }
    }

    private fun validateOptionalPageId(container: JSONObject, key: String, pageIds: Set<String>) {
        if (!container.has(key) || container.isNull(key)) return
        val value = container.optString(key)
        require(value in pageIds) { "Container $key references a page that is not in its stack" }
    }

    private fun validateContainerSettings(settings: JSONObject) {
        val color = settings.optJSONObject("sidebarColor") ?: error("Sidebar color setting is missing")
        if (color.optBoolean("present")) {
            val value = color.optString("value")
            require(value.length <= 32 && runCatching { Color.parseColor(value) }.isSuccess) {
                "Invalid Sidebar color"
            }
        }
        val transparency = settings.optJSONObject("sidebarTransparency") ?: error("Sidebar transparency setting is missing")
        if (transparency.optBoolean("present")) {
            require(transparency.optDouble("value", -1.0) in 0.0..1.0) { "Invalid Sidebar transparency" }
        }
        val wrap = settings.optJSONObject("wrapContent") ?: error("Sidebar wrap setting is missing")
        if (wrap.optBoolean("present")) require(wrap.opt("value") is Boolean) { "Invalid Sidebar wrap setting" }
        val height = settings.optJSONObject("height") ?: error("Sidebar height setting is missing")
        if (height.optBoolean("present")) require(height.optInt("value", -1) in 0..2000) { "Invalid Sidebar height" }
        val width = settings.optJSONObject("width") ?: error("Sidebar width setting is missing")
        if (width.optBoolean("present")) require(width.optInt("value", -1) in 0..2000) { "Invalid Sidebar width" }
        val columns = settings.optJSONObject("columns") ?: error("Sidebar columns setting is missing")
        if (columns.optBoolean("present")) require(columns.optInt("value", -1) in 1..12) { "Invalid Sidebar columns" }
    }

    private fun validateHandleSettings(settings: JSONObject) {
        val columns = settings.optJSONObject("columns") ?: error("Handle columns setting is missing")
        if (columns.optBoolean("present")) require(columns.optInt("value", -1) in 1..12) { "Invalid handle columns" }
        val legacyEdge = settings.optJSONObject("legacyEdge") ?: error("Handle legacy edge setting is missing")
        if (legacyEdge.optBoolean("present")) require(legacyEdge.optString("value").lowercase() in setOf("left", "right")) {
            "Invalid legacy handle edge"
        }
        val color = settings.optJSONObject("sidebarColor") ?: error("Handle color setting is missing")
        if (color.optBoolean("present")) require(
            color.optString("value").length <= 32 && runCatching { Color.parseColor(color.optString("value")) }.isSuccess
        ) { "Invalid handle color" }
        val transparency = settings.optJSONObject("sidebarTransparency") ?: error("Handle transparency setting is missing")
        if (transparency.optBoolean("present")) require(transparency.optDouble("value", -1.0) in 0.0..1.0) {
            "Invalid handle transparency"
        }
    }

    private fun restoreHandleConfigs(
        handles: JSONArray,
        previousHandleIds: Set<String>,
        previousPrefKeys: Set<String>,
        exactRollback: Boolean,
        bulkValues: MutableMap<String, Any>,
        bulkRemovals: MutableSet<String>
    ) {
        val backupIds = mutableListOf<String>()
        for (i in 0 until handles.length()) {
            val item = handles.getJSONObject(i)
            val id = item.getString("id")
            val index = id.removePrefix("handle_").toInt()
            backupIds.add(id)

            val gestures = item.getJSONObject("gestures")
            val actions = HandleGestures.ALL.associateWith { gesture ->
                gestures.optString(gesture, HandleManager.ACTION_NONE)
            }.toMutableMap()
            if (id == "handle_1" && actions.values.none { it == HandleManager.ACTION_OPEN_SIDEBAR }) {
                actions[HandleGestures.SWIPE_LEFT] = HandleManager.ACTION_OPEN_SIDEBAR
            }

            val handleValues = linkedMapOf<String, Any>(
                "handle_name_$index" to item.optString("name", "Handle $index").take(100),
                "handle_enabled_$index" to item.optBoolean("enabled", true),
                "handle_edge_$index" to item.getString("edge"),
                "handle_pos_$index" to item.optDouble("position", 0.5).toFloat().coerceIn(0f, 1f),
                "handle_width_$index" to item.optInt("widthDp", 12).coerceIn(4, 200),
                "handle_height_$index" to item.optInt("heightDp", 120).coerceIn(4, 400),
                "handle_color_$index" to item.optInt("color", Color.parseColor("#242962ff")),
                "handle_shape_$index" to item.getString("shape"),
                "handle_alpha_$index" to item.optInt("alphaPercent", 14).coerceIn(0, 100)
            )
            handleValues.forEach { (key, value) ->
                bulkValues[key] = value
                bulkRemovals.remove(key)
            }
            actions.forEach { (gesture, action) ->
                val key = HandleManager.getGesturePrefKey(index, gesture)
                bulkValues[key] = action
                bulkRemovals.remove(key)
            }

            val settings = item.getJSONObject("settings")
            restoreSetting(settings, "columns", "handle_${index}_columns", bulkValues, bulkRemovals)
            val legacyEdge = settings.getJSONObject("legacyEdge")
            val legacyEdgeKey = "handle_${index}_edge"
            if (legacyEdge.optBoolean("present")) {
                bulkValues[legacyEdgeKey] = legacyEdge.optString("value").lowercase()
                bulkRemovals.remove(legacyEdgeKey)
            } else {
                bulkValues.remove(legacyEdgeKey)
                bulkRemovals.add(legacyEdgeKey)
            }
            restoreSetting(settings, "sidebarColor", "handle_${index}_sidebar_color", bulkValues, bulkRemovals)
            restoreSetting(settings, "sidebarTransparency", "handle_${index}_sidebar_transparency", bulkValues, bulkRemovals)
        }

        val mergedIds = if (exactRollback) {
            backupIds.distinct().toMutableList()
        } else {
            (backupIds + previousHandleIds.filterNot { it in backupIds }).distinct().toMutableList()
        }
        if ("handle_1" !in mergedIds) mergedIds.add(0, "handle_1")
        bulkValues[HandleManager.KEY_HANDLE_IDS] = mergedIds.joinToString(",")
        bulkValues[HandleManager.KEY_HANDLES_COUNT] = mergedIds.size
        bulkRemovals.remove(HandleManager.KEY_HANDLE_IDS)
        bulkRemovals.remove(HandleManager.KEY_HANDLES_COUNT)

        if (exactRollback) {
            previousHandleIds.filterNot { it in backupIds }.forEach { staleId ->
                val index = staleId.removePrefix("handle_")
                val baseKeys = listOf(
                    "handle_name_$index",
                    "handle_enabled_$index",
                    "handle_edge_$index",
                    "handle_pos_$index",
                    "handle_width_$index",
                    "handle_height_$index",
                    "handle_color_$index",
                    "handle_shape_$index",
                    "handle_alpha_$index"
                )
                (baseKeys + previousPrefKeys.filter { it.startsWith("handle_${index}_") }).forEach { key ->
                    bulkValues.remove(key)
                    bulkRemovals.add(key)
                }
            }
        }
    }

    private fun validateGlobalSettings(settings: JSONObject) {
        val color = settings.optJSONObject("sidebarColor") ?: error("Global Sidebar color setting is missing")
        if (color.optBoolean("present")) require(
            color.optString("value").length <= 32 && runCatching { Color.parseColor(color.optString("value")) }.isSuccess
        ) { "Invalid global Sidebar color" }
        val transparency = settings.optJSONObject("sidebarTransparency") ?: error("Global Sidebar transparency setting is missing")
        if (transparency.optBoolean("present")) require(transparency.optDouble("value", -1.0) in 0.0..1.0) {
            "Invalid global Sidebar transparency"
        }
        val wrap = settings.optJSONObject("wrapContent") ?: error("Global Sidebar wrap setting is missing")
        if (wrap.optBoolean("present")) require(wrap.opt("value") is Boolean) { "Invalid global Sidebar wrap setting" }
        val height = settings.optJSONObject("height") ?: error("Global Sidebar height setting is missing")
        if (height.optBoolean("present")) require(height.optInt("value", -1) in 0..2000) { "Invalid global Sidebar height" }
        val width = settings.optJSONObject("width") ?: error("Global Sidebar width setting is missing")
        if (width.optBoolean("present")) require(width.optInt("value", -1) in 0..2000) { "Invalid global Sidebar width" }
        val columns = settings.optJSONObject("columns") ?: error("Global Sidebar columns setting is missing")
        if (columns.optBoolean("present")) require(columns.optInt("value", -1) in 1..12) { "Invalid global Sidebar columns" }
        val positionLeft = settings.optJSONObject("positionLeft") ?: error("Global Sidebar position setting is missing")
        if (positionLeft.optBoolean("present")) require(positionLeft.opt("value") is Boolean) {
            "Invalid global Sidebar position setting"
        }
    }

    private fun restoreGlobalSettings(
        settings: JSONObject,
        bulkValues: MutableMap<String, Any>,
        bulkRemovals: MutableSet<String>
    ) {
        restoreSetting(settings, "sidebarColor", "sidebar_color", bulkValues, bulkRemovals)
        restoreSetting(settings, "sidebarTransparency", "sidebar_transparency", bulkValues, bulkRemovals)
        restoreSetting(settings, "wrapContent", "sidebar_wrap_content", bulkValues, bulkRemovals)
        restoreSetting(settings, "height", "sidebar_height", bulkValues, bulkRemovals)
        restoreSetting(settings, "width", "sidebar_width", bulkValues, bulkRemovals)
        restoreSetting(settings, "columns", "sidebar_columns", bulkValues, bulkRemovals)
        restoreSetting(settings, "positionLeft", "sidebar_position_left", bulkValues, bulkRemovals)
    }

    private fun validatePageContent(record: JSONObject, page: SidebarPage) {
        val elements = record.optJSONArray("elements") ?: error("Page element placements are missing")
        require(elements.length() <= MAX_PAGE_ELEMENTS) { "Too many elements on a Sidebar page" }
        for (i in 0 until elements.length()) {
            val item = elements.optJSONObject(i) ?: error("Invalid page element placement")
            require(item.optString("placementId").length in 1..256) { "Invalid placement ID" }
            require(item.optString("containerId") == record.optString("containerId")) { "Placement container mismatch" }
            require(item.optString("pageId") == page.pageId) { "Placement page mismatch" }
            require(actionPattern.matches(item.optString("actionKey"))) { "Invalid page element action" }
            require(item.optInt("position", -1) in 0..MAX_PAGE_ELEMENTS) { "Invalid page element position" }
            require(item.optString("customTitle").length <= 200) { "Custom element title is too long" }
            require(item.optString("customIconUri").length <= 4096) { "Custom element icon reference is too long" }
            val config = item.optJSONObject("config") ?: JSONObject()
            require(config.length() <= 100) { "Too much page element configuration" }
            val keys = config.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                require(key.length in 1..128 && config.optString(key).length <= 4096) {
                    "Invalid page element configuration"
                }
            }
        }

        val kind = record.optString("kind")
        val expectedKind = when (page.pageType) {
            PageTypes.HYBRID_GRID, "hybrid_grid", "default_hybrid", "tools" -> "hybrid_grid"
            PageTypes.WIDGETS_GRID, "widgets_grid", "widgets" -> "widgets_grid"
            PageTypes.APPS, "apps", "apps_grid" -> "apps"
            PageTypes.WIDGET, "widget", "single_widget" -> "widget_page"
            else -> "none"
        }
        require(kind == expectedKind) { "Unsupported page content kind for ${page.pageType}" }
        val data = record.optJSONObject("data") ?: error("Page content data is missing")

        when (kind) {
            "hybrid_grid" -> {
                validateOptionalGrid(data, "gridJsonPresent", "gridJson")
                validateOptionalStringArray(data, "elementsJsonPresent", "elementsJson", MAX_GRID_ITEMS, 512)
                validateOptionalInt(data, "columnsPresent", "columns", 1..12)
                validateOptionalBoolean(data, "modifiedPresent", "modified")
                validateOptionalInt(data, "heightPresent", "height", 0..2000)
            }
            "widgets_grid" -> {
                validateOptionalGrid(data, "gridJsonPresent", "gridJson")
                validateOptionalInt(data, "columnsPresent", "columns", 1..12)
            }
            "apps" -> {
                validateOptionalStringArray(data, "appsJsonPresent", "appsJson", MAX_GRID_ITEMS, 512)
                validateOptionalInt(data, "pageColumnsPresent", "pageColumns", 1..12)
            }
            "widget_page" -> {
                val providerPresent = data.optBoolean("providerPresent", false)
                if (providerPresent) require(data.optString("provider").length in 1..512) {
                    "Invalid single-widget provider hint"
                } else require(!data.has("provider")) { "Unexpected single-widget provider hint" }
            }
        }
    }

    private fun validateOptionalGrid(data: JSONObject, presentKey: String, valueKey: String) {
        val present = data.optBoolean(presentKey, false)
        if (!present) {
            require(!data.has(valueKey)) { "Unexpected grid data" }
            return
        }
        val raw = data.optString(valueKey)
        require(raw.length <= 8 * 1024 * 1024) { "Grid data is too large" }
        val grid = JSONArray(raw)
        require(grid.length() <= MAX_GRID_ITEMS) { "Too many grid placements" }
        for (i in 0 until grid.length()) {
            val item = grid.optJSONObject(i) ?: error("Invalid grid placement")
            require(item.optString("id").length in 1..512) { "Invalid grid element reference" }
            require(item.optInt("cols", 1) in 1..12 && item.optInt("rows", 1) in 1..12) {
                "Invalid grid element span"
            }
            require(item.optInt("x", 0) in 0..1000 && item.optInt("y", 0) in 0..1000) {
                "Invalid grid element position"
            }
        }
    }

    private fun validateOptionalStringArray(
        data: JSONObject,
        presentKey: String,
        valueKey: String,
        maxItems: Int,
        maxLength: Int
    ) {
        val present = data.optBoolean(presentKey, false)
        if (!present) {
            require(!data.has(valueKey)) { "Unexpected page list data" }
            return
        }
        validateStringArray(JSONArray(data.optString(valueKey)), maxItems, maxLength, valueKey)
    }

    private fun validateOptionalInt(data: JSONObject, presentKey: String, valueKey: String, range: IntRange) {
        val present = data.optBoolean(presentKey, false)
        if (present) require(data.optInt(valueKey, Int.MIN_VALUE) in range) { "Invalid $valueKey" }
        else require(!data.has(valueKey)) { "Unexpected $valueKey" }
    }

    private fun validateOptionalBoolean(data: JSONObject, presentKey: String, valueKey: String) {
        val present = data.optBoolean(presentKey, false)
        if (present) require(data.opt(valueKey) is Boolean) { "Invalid $valueKey" }
        else require(!data.has(valueKey)) { "Unexpected $valueKey" }
    }

    private fun validateStringArray(array: JSONArray, maxItems: Int, maxLength: Int, label: String) {
        require(array.length() <= maxItems) { "Too many $label records" }
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            require(value.length in 1..maxLength) { "Invalid $label value" }
        }
    }

    private fun validateMetadata(metadata: JSONArray) {
        require(metadata.length() <= MAX_METADATA) { "Too many element metadata records" }
        val seenIds = mutableSetOf<String>()
        for (i in 0 until metadata.length()) {
            val item = metadata.optJSONObject(i) ?: error("Invalid element metadata")
            require(seenIds.add(item.optString("id"))) { "Duplicate element metadata ID" }
            require(item.optString("id").length in 1..512) { "Invalid metadata element ID" }
            require(item.optString("type") in setOf("app", "link", "pwa")) { "Unsupported element metadata type" }
            require(item.optString("target").length in 1..4096) { "Invalid metadata target" }
            require(item.optString("label").length in 1..200) { "Invalid metadata label" }
            require(item.optString("browserPackage").length <= 256) { "Invalid browser package" }
            require(item.optString("account").length <= 256) { "Invalid element account metadata" }
            val encoded = item.optString("iconBase64")
            require(encoded.length <= 1_400_000) { "Element metadata icon is too large" }
            if (encoded.isNotBlank()) require(Base64.decode(encoded, Base64.NO_WRAP).size in 1..MAX_ICON_BYTES) {
                "Invalid element metadata icon"
            }
        }
    }

    private fun validateCustomIcons(icons: JSONArray) {
        require(icons.length() <= MAX_CUSTOM_ICONS) { "Too many custom icon files" }
        var total = 0L
        val seenNames = mutableSetOf<String>()
        for (i in 0 until icons.length()) {
            val item = icons.optJSONObject(i) ?: error("Invalid custom icon")
            val name = item.optString("fileName")
            require(seenNames.add(name)) { "Duplicate custom icon filename" }
            require(safeIconNamePattern.matches(name)) { "Invalid custom icon filename" }
            val encoded = item.optString("base64")
            require(encoded.length in 1..400_000) { "Custom icon is too large" }
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size in 1..MAX_CUSTOM_ICON_BYTES) { "Invalid custom icon bytes" }
            total += bytes.size
            require(total <= MAX_CUSTOM_ICON_TOTAL_BYTES) { "Custom icon payload is too large" }
        }
    }

    private fun validateScheduler(scheduler: JSONObject) {
        val tasks = scheduler.optJSONArray("tasks") ?: error("Scheduler task list is missing")
        require(tasks.length() <= 10000) { "Too many Scheduler reminders" }
        val taskIds = mutableSetOf<Int>()
        for (i in 0 until tasks.length()) {
            val task = tasks.optJSONObject(i) ?: error("Invalid Scheduler reminder")
            require(taskIds.add(task.optInt("id", -1))) { "Duplicate Scheduler reminder ID" }
            require(task.optInt("id", -1) >= 0) { "Invalid Scheduler reminder ID" }
            require(task.optString("title").length <= 1000) { "Scheduler title is too long" }
            require(task.optString("note").length <= 5000) { "Scheduler note is too long" }
            require(task.optLong("timeMillis", -1L) >= 0L) { "Invalid Scheduler time" }
            require(task.optString("tags").length <= 2000) { "Scheduler tags are too long" }
            require(task.optString("status") in setOf("PENDING", "DONE", "UNFINISHED")) { "Invalid Scheduler status" }
        }
        require(scheduler.optString("tags").length <= 2000) { "Scheduler tag list is too long" }
    }

    fun restoreDetailed(context: Context, payload: JSONObject, exactRollback: Boolean = false): RestoreResult {
        validate(payload)
        val warnings = linkedSetOf<String>()
        val manager = HandleManager.getInstance(context)
        val pageManager = PageManager.getInstance(context)
        val primaryContainerId = manager.getFirstHandlePrimaryContainerId()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bulkValues = linkedMapOf<String, Any>()
        val bulkRemovals = linkedSetOf<String>()
        val previousHandleIds = manager.getHandleIds().toSet()
        val previousPrefKeys = prefs.all.keys.toSet()
        val backupHandles = payload.getJSONArray("handles")
        val backupHandleActions = (0 until backupHandles.length()).associate { index ->
            val handle = backupHandles.getJSONObject(index)
            handle.getString("id") to handle
        }
        val backupContainers = payload.getJSONArray("containers")
        val backupContainerIds = (0 until backupContainers.length()).map {
            backupContainers.getJSONObject(it).getString("containerId")
        }.toSet()
        val previousContainerIds = currentContainerIds(manager)

        restoreHandleConfigs(
            payload.getJSONArray("handles"),
            previousHandleIds,
            previousPrefKeys,
            exactRollback,
            bulkValues,
            bulkRemovals
        )
        restoreGlobalSettings(payload.getJSONObject("globalSettings"), bulkValues, bulkRemovals)

        if (exactRollback) {
            previousContainerIds.filterNot { it in backupContainerIds }
                .forEach { clearContainerData(it, previousPrefKeys, bulkRemovals) }
        }

        var restoredContainers = 0
        var restoredPages = 0
        var restoredPlacements = 0
        var restoredGridItems = 0

        for (containerIndex in 0 until backupContainers.length()) {
            val container = backupContainers.getJSONObject(containerIndex)
            val handleId = container.getString("handleId")
            val gesture = container.getString("gesture")
            val containerId = container.getString("containerId")
            val handleJson = backupHandleActions[handleId]
            val gestureActions = handleJson?.optJSONObject("gestures")
            if (gestureActions?.optString(gesture) != HandleManager.ACTION_OPEN_SIDEBAR) {
                warnings.add("Skipped container $containerId: its saved gesture does not open Sidebar.")
                continue
            }

            val pagesJson = container.getJSONArray("pages")
            val backupPages = (0 until pagesJson.length()).map { index ->
                SidebarPage.fromJson(pagesJson.getJSONObject(index), index)
            }.sortedBy { it.order }
            val currentPages = pageManager.getPageStack(containerId)
            if (!exactRollback) {
                backupPages.forEach { saved ->
                    val existing = currentPages.firstOrNull { it.pageId == saved.pageId }
                    if (existing != null && existing.pageType != saved.pageType) {
                        warnings.add("Skipped page ${saved.title}: its existing page type differs.")
                    }
                }
            } else {
                val backupPageIds = backupPages.map { it.pageId }.toSet()
                currentPages.filterNot { it.pageId in backupPageIds }.forEach { stale ->
                    clearPageData(containerId, stale.pageId, primaryContainerId, bulkRemovals)
                }
            }

            val restoredStack = if (exactRollback) backupPages else mergePageStack(currentPages, backupPages)
            val pageStackJson = JSONArray().apply {
                restoredStack.forEachIndexed { index, page -> put(page.copy(order = index).toJson()) }
            }.toString()
            val pagesKey = HandleManager.getContainerPagesKey(containerId)
            bulkValues[pagesKey] = pageStackJson
            bulkRemovals.remove(pagesKey)
            restoreContainerSettings(containerId, container.optJSONObject("settings") ?: JSONObject(), bulkValues, bulkRemovals)

            val restoredIds = backupPages
                .filter { saved -> restoredStack.any { it.pageId == saved.pageId && it.pageType == saved.pageType } }
                .map { it.pageId }
                .toSet()
            restoreContainerPageReference(container, "openingFaceId", containerId, restoredIds, exactRollback, bulkValues, bulkRemovals, warnings)
            restoreContainerPageReference(container, "selectedPageId", containerId, restoredIds, exactRollback, bulkValues, bulkRemovals, warnings)

            val pageContent = container.getJSONArray("pageContent")
            for (contentIndex in 0 until pageContent.length()) {
                val record = pageContent.getJSONObject(contentIndex)
                val pageId = record.getString("pageId")
                val savedType = record.getString("pageType")
                val currentPage = restoredStack.firstOrNull { it.pageId == pageId }
                if (currentPage == null || currentPage.pageType != savedType) {
                    warnings.add("Skipped content for page $pageId: the page was not restored.")
                    continue
                }

                val placementsJson = record.getJSONArray("elements")
                val placements = (0 until placementsJson.length()).map {
                    ElementPlacement.fromJson(placementsJson.getJSONObject(it))
                }
                val placementsKey = HandleManager.getContainerPageElementsKey(containerId, pageId)
                bulkValues[placementsKey] = JSONArray().apply {
                    placements.forEach { put(it.toJson()) }
                }.toString()
                bulkRemovals.remove(placementsKey)
                if (currentPage.pageType == PageTypes.WIDGET || currentPage.pageType == "single_widget") {
                    warnings.add("Single Android widget page $pageId was preserved as metadata but must be rebound on this device.")
                }
                if (currentPage.isMiniApp || currentPage.miniAppType.isNotBlank()) {
                    warnings.add("Mini-app page metadata was preserved for $pageId, but mini-app runtime data is not included in Sidebar backup.")
                }
                if (placements.any { it.customIconUri?.startsWith("content://") == true }) {
                    warnings.add("Some custom element icons use external content URIs and may need to be selected again after restore.")
                }
                if (hasWidgetContent(record)) {
                    warnings.add("Android widget positions/provider hints were preserved; widget instances must be rebound on this device.")
                }
                restoredPlacements += placements.size
                restoredGridItems += restorePageData(containerId, currentPage, record, bulkValues, bulkRemovals)
                restoredPages++
            }
            restoredContainers++
        }

        val metadataKeysBefore = prefs.all.keys.filter { it.startsWith("elem_meta_") }.toSet()
        restoreElementMetadata(context, prefs, payload.optJSONArray("elementMetadata") ?: JSONArray(), exactRollback, warnings)
        val metadataKeysAfter = prefs.all.keys.filter { it.startsWith("elem_meta_") }.toSet()
        if (exactRollback) bulkRemovals.addAll(metadataKeysBefore - metadataKeysAfter)
        metadataKeysAfter.forEach { key ->
            (prefs.all[key] as? String)?.let {
                bulkValues[key] = it
                bulkRemovals.remove(key)
            }
        }

        restoreCustomIconFiles(context, payload.optJSONArray("customIcons") ?: JSONArray(), exactRollback)
        restoreSavedLinks(context, payload.optJSONArray("savedLinkIds") ?: JSONArray(), exactRollback, bulkValues)

        var schedulerCount = 0
        if (payload.has("scheduler")) {
            schedulerCount = restoreScheduler(
                context,
                prefs,
                payload.getJSONObject("scheduler"),
                exactRollback,
                bulkValues,
                bulkRemovals
            )
        }

        if (payload.has("appTrackerWhitelist")) {
            restoreAppTrackerWhitelist(prefs, payload.getJSONArray("appTrackerWhitelist"), exactRollback, bulkValues)
        }

        // One sync transaction updates Main and Heavy process preference caches, then refreshes
        // the active page once. This avoids a handle detach/reattach for every restored key.
        OverlaySyncManager.syncBulk(context, bulkValues, bulkRemovals)

        val metadataCount = payload.optJSONArray("elementMetadata")?.length() ?: 0
        val iconCount = payload.optJSONArray("customIcons")?.length() ?: 0
        val savedLinkCount = payload.optJSONArray("savedLinkIds")?.length() ?: 0
        val appTrackerCount = payload.optJSONArray("appTrackerWhitelist")?.length() ?: 0
        val details = listOf(
            "Sidebar: restored $restoredContainers containers, $restoredPages pages, $restoredPlacements page elements, $restoredGridItems grid items and $schedulerCount Scheduler reminders.",
            "Sidebar metadata: processed $metadataCount element records, $iconCount custom icons, $savedLinkCount saved links and $appTrackerCount App Tracker whitelist entries."
        )
        return RestoreResult(warnings.toList(), details)
    }

    internal fun mergePageStack(current: List<SidebarPage>, backup: List<SidebarPage>): List<SidebarPage> {
        val currentById = current.associateBy { it.pageId }
        val applicable = backup.distinctBy { it.pageId }.sortedBy { it.order }.filter { saved ->
            val existing = currentById[saved.pageId]
            existing == null || existing.pageType == saved.pageType
        }
        val applicableIds = applicable.map { it.pageId }.toSet()
        val result = current.filterNot { it.pageId in applicableIds }.toMutableList()
        applicable.forEach { saved ->
            val restored = saved.copy()
            result.add(saved.order.coerceIn(0, result.size), restored)
        }
        return result.mapIndexed { index, page -> page.copy(order = index) }
    }

    private fun restoreContainerPageReference(
        container: JSONObject,
        key: String,
        containerId: String,
        restoredIds: Set<String>,
        exactRollback: Boolean,
        bulkValues: MutableMap<String, Any>,
        bulkRemovals: MutableSet<String>,
        warnings: MutableSet<String>
    ) {
        val value = if (container.has(key) && !container.isNull(key)) container.optString(key) else null
        val prefKey = if (key == "openingFaceId") {
            HandleManager.getContainerOpeningFaceKey(containerId)
        } else {
            HandleManager.getContainerSelectedPageKey(containerId)
        }
        if (value != null && value !in restoredIds) {
            warnings.add("Skipped $key for $containerId because its page is unavailable.")
            return
        }
        if (value != null) {
            bulkValues[prefKey] = value
            bulkRemovals.remove(prefKey)
        } else if (exactRollback) {
            bulkValues.remove(prefKey)
            bulkRemovals.add(prefKey)
        }
    }

    private fun restoreContainerSettings(
        containerId: String,
        settings: JSONObject,
        bulkValues: MutableMap<String, Any>,
        bulkRemovals: MutableSet<String>
    ) {
        restoreSetting(settings, "sidebarColor", "handle_${containerId}_sidebar_color", bulkValues, bulkRemovals)
        restoreSetting(settings, "sidebarTransparency", "handle_${containerId}_sidebar_transparency", bulkValues, bulkRemovals)
        restoreSetting(settings, "wrapContent", "handle_${containerId}_sidebar_wrap_content", bulkValues, bulkRemovals)
        restoreSetting(settings, "height", "handle_${containerId}_sidebar_height", bulkValues, bulkRemovals)
        restoreSetting(settings, "width", "handle_${containerId}_sidebar_width", bulkValues, bulkRemovals)
        restoreSetting(settings, "columns", "handle_${containerId}_columns", bulkValues, bulkRemovals)
    }

    private fun restoreSetting(
        settings: JSONObject,
        name: String,
        key: String,
        bulkValues: MutableMap<String, Any>,
        bulkRemovals: MutableSet<String>
    ) {
        val setting = settings.optJSONObject(name) ?: JSONObject().put("present", false)
        if (!setting.optBoolean("present", false)) {
            bulkValues.remove(key)
            bulkRemovals.add(key)
            return
        }
        val rawValue = setting.opt("value")
        val value = if (rawValue is Double) rawValue.toFloat() else rawValue
        require(value is String || value is Boolean || value is Int || value is Float) {
            "Unsupported Sidebar setting value for $name"
        }
        bulkValues[key] = value
        bulkRemovals.remove(key)
    }

    private fun restorePageData(
        containerId: String,
        page: SidebarPage,
        record: JSONObject,
        bulkValues: MutableMap<String, Any>,
        bulkRemovals: MutableSet<String>
    ): Int {
        val data = record.optJSONObject("data") ?: JSONObject()
        val pageId = page.pageId
        var restoredItems = 0
        when (record.optString("kind")) {
            "hybrid_grid" -> {
                val gridPresent = data.optBoolean("gridJsonPresent", false)
                val grid = if (gridPresent) sanitizeWidgetGridJsonForRestore(data.optString("gridJson", "[]")) else "[]"
                val elementsPresent = data.optBoolean("elementsJsonPresent", false)
                val elements = if (elementsPresent) JSONArray(data.optString("elementsJson", "[]")).toString() else "[]"
                val columns = if (data.optBoolean("columnsPresent", false)) data.optInt("columns", 4) else 4
                val modified = if (data.optBoolean("modifiedPresent", false)) data.optBoolean("modified", false) else false
                val gridItems = runCatching { JSONArray(grid).length() }.getOrDefault(0)
                restoredItems += gridItems
                val gridKey = "handle_${containerId}_hybrid_grid_${pageId}"
                val elementsKey = "hg_${containerId}_${pageId}"
                val columnsKey = "handle_${containerId}_hybrid_grid_cols_${pageId}"
                val modifiedKey = "handle_${containerId}_hybrid_grid_modified_${pageId}"
                bulkValues[gridKey] = grid
                bulkValues[elementsKey] = elements
                bulkValues[columnsKey] = columns
                bulkValues[modifiedKey] = modified
                bulkRemovals.remove(gridKey)
                bulkRemovals.remove(elementsKey)
                bulkRemovals.remove(columnsKey)
                bulkRemovals.remove(modifiedKey)
                val heightKey = "handle_${containerId}_hybrid_grid_height_${pageId}"
                if (data.optBoolean("heightPresent", false)) {
                    bulkValues[heightKey] = data.optInt("height", 0)
                    bulkRemovals.remove(heightKey)
                } else {
                    bulkValues.remove(heightKey)
                    bulkRemovals.add(heightKey)
                }
            }
            "widgets_grid" -> {
                val gridPresent = data.optBoolean("gridJsonPresent", false)
                val grid = if (gridPresent) sanitizeWidgetGridJsonForRestore(data.optString("gridJson", "[]")) else "[]"
                val columns = if (data.optBoolean("columnsPresent", false)) data.optInt("columns", 4) else 4
                restoredItems += runCatching { JSONArray(grid).length() }.getOrDefault(0)
                val gridKey = "handle_${containerId}_widgets_grid_${pageId}"
                val columnsKey = "handle_${containerId}_widgets_grid_cols_${pageId}"
                bulkValues[gridKey] = grid
                bulkValues[columnsKey] = columns
                bulkRemovals.remove(gridKey)
                bulkRemovals.remove(columnsKey)
            }
            "apps" -> {
                val appsPresent = data.optBoolean("appsJsonPresent", false)
                val apps = if (appsPresent) sanitizeWidgetElementArrayForRestore(data.optString("appsJson", "[]")) else "[]"
                val columnsPresent = data.optBoolean("pageColumnsPresent", false)
                val columnsKey = "handle_${containerId}_page_${pageId}_columns"
                val appsKey = "sidebar_apps_${containerId}_${pageId}"
                bulkValues[appsKey] = apps
                bulkRemovals.remove(appsKey)
                if (columnsPresent) {
                    bulkValues[columnsKey] = data.optInt("pageColumns", 3)
                    bulkRemovals.remove(columnsKey)
                } else {
                    bulkValues.remove(columnsKey)
                    bulkRemovals.add(columnsKey)
                }
                restoredItems += runCatching { JSONArray(apps).length() }.getOrDefault(0)
            }
        }
        return restoredItems
    }

    private fun hasWidgetContent(record: JSONObject): Boolean {
        val elements = record.optJSONArray("elements") ?: JSONArray()
        if (record.optString("kind") == "widget_page") return true
        if ((0 until elements.length()).any { elements.optJSONObject(it)?.optString("actionKey")?.startsWith("widget:") == true }) {
            return true
        }
        val data = record.optJSONObject("data") ?: return false
        val rawValues = listOf("gridJson", "appsJson", "elementsJson")
        return rawValues.any { key ->
            val raw = data.optString(key)
            if (raw.isBlank()) false else runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).any { index ->
                    val item = array.opt(index)
                    when (item) {
                        is JSONObject -> item.optString("id").startsWith("widget:")
                        is String -> item.startsWith("widget:")
                        else -> false
                    }
                }
            }.getOrDefault(false)
        }
    }

    private fun sanitizeWidgetGridJsonForRestore(source: String): String {
        val items = JSONArray(source)
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val id = item.optString("id")
            if (id.startsWith("widget:")) {
                val suffix = id.removePrefix("widget:")
                val hint = if (suffix.startsWith("-1:")) suffix.substringAfter(":", "") else ""
                item.put("id", "widget:-1:${hint.take(255)}")
            }
        }
        return items.toString()
    }

    private fun sanitizeWidgetElementArrayForRestore(source: String): String {
        val items = JSONArray(source)
        for (index in 0 until items.length()) {
            val id = items.optString(index)
            if (id.startsWith("widget:")) {
                val suffix = id.removePrefix("widget:")
                val hint = if (suffix.startsWith("-1:")) suffix.substringAfter(":", "") else ""
                items.put(index, "widget:-1:${hint.take(255)}")
            }
        }
        return items.toString()
    }

    private fun restoreElementMetadata(
        context: Context,
        prefs: SharedPreferences,
        metadata: JSONArray,
        exactRollback: Boolean,
        warnings: MutableSet<String>
    ) {
        val iconDir = File(context.filesDir, "sidebar_backup_icons")
        if (exactRollback && iconDir.exists()) iconDir.deleteRecursively()
        if (!iconDir.exists()) iconDir.mkdirs()

        if (exactRollback) {
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith("elem_meta_") }.forEach(editor::remove)
            check(editor.commit()) { "Could not clear previous Sidebar element metadata for rollback" }
        }

        for (i in 0 until metadata.length()) {
            val item = metadata.getJSONObject(i)
            val id = item.getString("id")
            val type = item.getString("type")
            val target = item.getString("target")
            val label = item.getString("label")
            val existing = ElementMetadataStore.get(context, id)
            var iconPath = existing?.iconPath ?: ""
            val encoded = item.optString("iconBase64")
            if (encoded.isNotBlank()) {
                val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                require(bytes.size in 1..MAX_ICON_BYTES)
                val file = File(iconDir, safeFileName(id) + ".webp")
                file.writeBytes(bytes)
                iconPath = file.absolutePath
            } else if (exactRollback) {
                iconPath = ""
            }

            ElementMetadataStore.save(
                context,
                ElementMetadata(
                    id = id,
                    type = type,
                    target = target,
                    label = label,
                    iconPath = iconPath,
                    browserPackage = item.optString("browserPackage").takeIf { it.isNotBlank() },
                    account = item.optString("account").takeIf { it.isNotBlank() }
                )
            )

            if (type == "app" && target.isNotBlank() && runCatching {
                    context.packageManager.getApplicationInfo(target, 0)
                }.isFailure) {
                warnings.add("Sidebar app is not installed: $target")
            }
            if (type == "pwa") {
                warnings.add("PWA metadata was restored for $id, but its PWA package and site data must be imported separately.")
            }
        }
    }

    private fun restoreCustomIconFiles(
        context: Context,
        icons: JSONArray,
        exactRollback: Boolean
    ) {
        val dir = File(context.filesDir, "custom_icons")
        if (exactRollback && dir.exists()) dir.listFiles()?.filter { it.isFile && it.name.endsWith(".webp") }?.forEach { it.delete() }
        if (!dir.exists()) dir.mkdirs()
        for (i in 0 until icons.length()) {
            val item = icons.getJSONObject(i)
            val name = item.getString("fileName")
            require(safeIconNamePattern.matches(name)) { "Invalid custom icon filename" }
            val bytes = Base64.decode(item.getString("base64"), Base64.NO_WRAP)
            require(bytes.size in 1..MAX_CUSTOM_ICON_BYTES)
            File(dir, name).writeBytes(bytes)
        }
    }

    private fun restoreSavedLinks(
        context: Context,
        savedLinks: JSONArray,
        exactRollback: Boolean,
        bulkValues: MutableMap<String, Any>
    ) {
        val existing = if (exactRollback) emptySet() else ElementMetadataStore.getSavedLinkIds(context).toSet()
        val restored = existing + (0 until savedLinks.length()).map { savedLinks.optString(it) }.filter { it.isNotBlank() }
        val array = JSONArray().apply { restored.sorted().forEach(::put) }
        bulkValues["saved_link_uuids"] = array.toString()
    }

    private fun restoreScheduler(
        context: Context,
        prefs: SharedPreferences,
        scheduler: JSONObject,
        exactRollback: Boolean,
        bulkValues: MutableMap<String, Any>,
        bulkRemovals: MutableSet<String>
    ): Int {
        val tasksJson = scheduler.getJSONArray("tasks")
        val tasks = (0 until tasksJson.length()).map { index ->
            val item = tasksJson.getJSONObject(index)
            SchedulerTask(
                id = item.optInt("id", 0),
                title = item.optString("title"),
                note = item.optString("note"),
                timeMillis = item.optLong("timeMillis", 0L),
                tags = item.optString("tags"),
                status = item.optString("status", "PENDING")
            )
        }

        val imported = runBlocking {
            val dao = AppDatabase.getDatabase(context).schedulerTaskDao()
            val current = dao.getAllTasks().first()
            if (exactRollback) {
                current.forEach { dao.delete(it) }
                tasks.forEach { dao.insert(it) }
                tasks.size
            } else {
                val currentById = current.associateBy { it.id }
                val fingerprints = current.mapTo(mutableSetOf()) { taskFingerprint(it) }
                var count = 0
                tasks.forEach { task ->
                    val sameId = currentById[task.id]
                    val fingerprint = taskFingerprint(task)
                    if (sameId != null) {
                        if (sameId != task && fingerprint !in fingerprints) {
                            dao.insert(task.copy(id = 0))
                            fingerprints.add(fingerprint)
                            count++
                        }
                    } else if (fingerprint !in fingerprints) {
                        dao.insert(task)
                        fingerprints.add(fingerprint)
                        count++
                    }
                }
                count
            }
        }

        val tagsPresent = scheduler.optBoolean("tagsPresent", false)
        if (tagsPresent) {
            val incoming = scheduler.optString("tags")
            val currentTags = if (exactRollback) emptyList() else (prefs.getString("scheduler_tags", "") ?: "")
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val merged = (currentTags + incoming.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                .distinct()
            bulkValues["scheduler_tags"] = merged.joinToString(",")
            bulkRemovals.remove("scheduler_tags")
        } else if (exactRollback) {
            bulkValues.remove("scheduler_tags")
            bulkRemovals.add("scheduler_tags")
        }
        return imported
    }

    private fun restoreAppTrackerWhitelist(
        prefs: SharedPreferences,
        whitelist: JSONArray,
        exactRollback: Boolean,
        bulkValues: MutableMap<String, Any>
    ) {
        val incoming = (0 until whitelist.length())
            .map { whitelist.optString(it) }
            .filter { it.isNotBlank() }
            .toSet()
        val current = if (exactRollback) {
            emptySet()
        } else {
            prefs.getStringSet("app_tracker_whitelist_cache", emptySet())?.toSet().orEmpty()
        }
        bulkValues["app_tracker_whitelist_cache"] = current + incoming
    }

    private fun currentContainerIds(manager: HandleManager): Set<String> =
        manager.getHandleIds().flatMap { id ->
            HandleGestures.ALL.map { gesture -> HandleManager.getContainerId(id, gesture) }
        }.toSet()

    private fun clearContainerData(
        containerId: String,
        existingKeys: Set<String>,
        bulkRemovals: MutableSet<String>
    ) {
        val prefixes = listOf(
            "handle_${containerId}_",
            "hg_${containerId}_",
            "wg_${containerId}_",
            "sidebar_apps_${containerId}_"
        )
        bulkRemovals.add(HandleManager.getContainerPagesKey(containerId))
        bulkRemovals.add(HandleManager.getContainerSelectedPageKey(containerId))
        bulkRemovals.add(HandleManager.getContainerOpeningFaceKey(containerId))
        existingKeys.filter { key -> prefixes.any(key::startsWith) }.forEach(bulkRemovals::add)
    }

    private fun clearPageData(
        containerId: String,
        pageId: String,
        primaryContainerId: String,
        bulkRemovals: MutableSet<String>
    ) {
        val keys = linkedSetOf(
            HandleManager.getContainerPageElementsKey(containerId, pageId),
            "handle_${containerId}_hybrid_grid_${pageId}",
            "handle_${containerId}_hybrid_grid_cols_${pageId}",
            "handle_${containerId}_hybrid_grid_modified_${pageId}",
            "handle_${containerId}_hybrid_grid_height_${pageId}",
            "${containerId}_hybrid_grid_${pageId}",
            "${containerId}_hybrid_grid_cols_${pageId}",
            "${containerId}_hybrid_grid_modified_${pageId}",
            "hg_${containerId}_${pageId}",
            "handle_${containerId}_widgets_grid_${pageId}",
            "handle_${containerId}_widgets_grid_cols_${pageId}",
            "${containerId}_widgets_grid_${pageId}",
            "${containerId}_widgets_grid_cols_${pageId}",
            "sidebar_apps_${containerId}_${pageId}",
            "handle_${containerId}_page_${pageId}_columns"
        )
        val handleIndex = containerId.removePrefix("handle_").substringBefore("_")
        keys.add("handle_${handleIndex}_page_${pageId}_columns")
        if (containerId == primaryContainerId || containerId == "sidebar" || containerId == "handle_1") {
            keys.addAll(
                listOf(
                    "hybrid_grid_${pageId}",
                    "hybrid_grid_cols_${pageId}",
                    "hybrid_grid_modified_${pageId}",
                    "hg_${pageId}",
                    "widgets_grid_${pageId}",
                    "widgets_grid_cols_${pageId}",
                    "sidebar_apps_${pageId}",
                    "sidebar_apps"
                )
            )
        }
        bulkRemovals.addAll(keys)
    }

    private fun safeFileName(value: String): String =
        value.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .take(160)
}
