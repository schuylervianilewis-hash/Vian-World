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
    private val actionPattern = Regex("^[a-zA-Z0-9_.:-]{1,512}$")
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
        handles.forEach { handlesJson.put(SidebarBackupModule.handleToJson(it)) }

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
                        .put("selectedPageId", pageManager.getSelectedPageId(containerId) ?: JSONObject.NULL)
                        .put("openingFaceId", pageManager.getOpeningFaceId(containerId) ?: JSONObject.NULL)
                        .put("settings", exportContainerSettings(prefs, containerId, primaryContainerId))
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
            .put("elementMetadata", exportAllElementMetadata(context, prefs))
            .put("customIcons", exportCustomIconFiles(context))
            .put("savedLinkIds", JSONArray().apply {
                ElementMetadataStore.getSavedLinkIds(context).distinct().sorted().forEach(::put)
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

    private fun exportContainerSettings(
        prefs: SharedPreferences,
        containerId: String,
        primaryContainerId: String
    ): JSONObject {
        val primary = containerId == primaryContainerId ||
            containerId == "sidebar" ||
            containerId == "handle_1"
        return JSONObject()
            .put("sidebarColor", readSetting(prefs, "handle_${containerId}_sidebar_color", "sidebar_color", primary))
            .put("sidebarTransparency", readSetting(prefs, "handle_${containerId}_sidebar_transparency", "sidebar_transparency", primary))
            .put("wrapContent", readSetting(prefs, "handle_${containerId}_sidebar_wrap_content", "sidebar_wrap_content", primary))
            .put("height", readSetting(prefs, "handle_${containerId}_sidebar_height", "sidebar_height", primary))
            .put("columns", readSetting(prefs, "handle_${containerId}_columns", "sidebar_columns", primary))
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
                val appsRaw = readString(
                    prefs,
                    "sidebar_apps_${containerId}_${pageId}",
                    "sidebar_apps_${pageId}".takeIf { primary },
                    "sidebar_apps".takeIf { primary }
                )
                val pageColumns = readInt(
                    prefs,
                    "handle_${containerId}_page_${pageId}_columns",
                    "handle_${handleId}_page_${pageId}_columns"
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
                if (iconFile != null && iconFile.isFile && iconFile.length() in 1..MAX_ICON_BYTES.toLong()) {
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
        val columns = settings.optJSONObject("columns") ?: error("Sidebar columns setting is missing")
        if (columns.optBoolean("present")) require(columns.optInt("value", -1) in 1..12) { "Invalid Sidebar columns" }
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
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bulkValues = linkedMapOf<String, Any>()
        val bulkRemovals = linkedSetOf<String>()
        val backupContainers = payload.getJSONArray("containers")
        val backupContainerIds = (0 until backupContainers.length()).map {
            backupContainers.getJSONObject(it).getString("containerId")
        }.toSet()
        val previousContainerIds = currentSidebarContainerIds(manager)

        SidebarBackupModule.restoreHandles(context, payload.getJSONArray("handles"), exactRollback)

        if (exactRollback) {
            previousContainerIds.filterNot { it in backupContainerIds }
                .forEach { clearContainerData(prefs, it, bulkRemovals) }
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
            val handle = manager.getHandle(handleId)
            if (handle == null) {
                warnings.add("Skipped container $containerId: its handle is unavailable.")
                continue
            }
            if (handle.getActionForGesture(gesture) != HandleManager.ACTION_OPEN_SIDEBAR) {
                warnings.add("Skipped container $containerId: its saved gesture no longer opens Sidebar.")
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
                    clearPageData(prefs, containerId, stale.pageId, bulkRemovals)
                }
            }

            val restoredStack = if (exactRollback) backupPages else mergePageStack(currentPages, backupPages)
            val pageStackJson = JSONArray().apply {
                restoredStack.forEachIndexed { index, page -> put(page.copy(order = index).toJson()) }
            }.toString()
            bulkValues[HandleManager.getContainerPagesKey(containerId)] = pageStackJson
            restoreContainerSettings(containerId, container.optJSONObject("settings") ?: JSONObject(), exactRollback, bulkValues, bulkRemovals)

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
            (prefs.all[key] as? String)?.let { bulkValues[key] = it }
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
        IconCacheManager.invalidateMemoryCache()
        context.sendBroadcast(Intent("com.example.UPDATE_SIDEBAR_ICONS").setPackage(context.packageName))

        val details = listOf(
            "Sidebar: restored $restoredContainers containers, $restoredPages pages, $restoredPlacements page elements, $restoredGridItems grid items and $schedulerCount Scheduler reminders."
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
        exactRollback: Boolean,
        bulkValues: MutableMap<String, Any>,
        bulkRemovals: MutableSet<String>
    ) {
        restoreSetting(settings, "sidebarColor", "handle_${containerId}_sidebar_color", "#1E1E2E", exactRollback, bulkValues, bulkRemovals)
        restoreSetting(settings, "sidebarTransparency", "handle_${containerId}_sidebar_transparency", 0.9f, exactRollback, bulkValues, bulkRemovals)
        restoreSetting(settings, "wrapContent", "handle_${containerId}_sidebar_wrap_content", true, exactRollback, bulkValues, bulkRemovals)
        restoreSetting(settings, "height", "handle_${containerId}_sidebar_height", 280, exactRollback, bulkValues, bulkRemovals)
        restoreSetting(settings, "columns", "handle_${containerId}_columns", 3, exactRollback, bulkValues, bulkRemovals)
    }

    private fun restoreSetting(
        settings: JSONObject,
        name: String,
        key: String,
        defaultValue: Any,
        exactRollback: Boolean,
        bulkValues: MutableMap<String, Any>,
        bulkRemovals: MutableSet<String>
    ) {
        val setting = settings.optJSONObject(name) ?: JSONObject().put("present", false)
        val present = setting.optBoolean("present", false)
        if (!present && exactRollback) {
            bulkValues.remove(key)
            bulkRemovals.add(key)
            return
        }
        val value = if (present) setting.opt("value") else defaultValue
        require(value is String || value is Boolean || value is Int || value is Double || value is Float) {
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
                bulkValues["handle_${containerId}_hybrid_grid_${pageId}"] = grid
                bulkValues["hg_${containerId}_${pageId}"] = elements
                bulkValues["handle_${containerId}_hybrid_grid_cols_${pageId}"] = columns
                bulkValues["handle_${containerId}_hybrid_grid_modified_${pageId}"] = modified
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
                bulkValues["handle_${containerId}_widgets_grid_${pageId}"] = grid
                bulkValues["handle_${containerId}_widgets_grid_cols_${pageId}"] = columns
            }
            "apps" -> {
                val appsPresent = data.optBoolean("appsJsonPresent", false)
                val apps = if (appsPresent) sanitizeWidgetElementArrayForRestore(data.optString("appsJson", "[]")) else "[]"
                val columnsPresent = data.optBoolean("pageColumnsPresent", false)
                val columnsKey = "handle_${containerId}_page_${pageId}_columns"
                bulkValues["sidebar_apps_${containerId}_${pageId}"] = apps
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

    private fun currentSidebarContainerIds(manager: HandleManager): Set<String> =
        manager.getHandleIds().flatMap { id ->
            HandleGestures.ALL.mapNotNull { gesture ->
                val handle = manager.getHandle(id) ?: return@mapNotNull null
                if (handle.getActionForGesture(gesture) == HandleManager.ACTION_OPEN_SIDEBAR) {
                    HandleManager.getContainerId(id, gesture)
                } else null
            }
        }.toSet()

    private fun clearContainerData(
        prefs: SharedPreferences,
        containerId: String,
        bulkRemovals: MutableSet<String>
    ) {
        val prefixes = listOf(
            "handle_${containerId}_",
            "hg_${containerId}_",
            "wg_${containerId}_",
            "sidebar_apps_${containerId}_"
        )
        prefs.all.keys.filter { key ->
            key == HandleManager.getContainerPagesKey(containerId) ||
                key == HandleManager.getContainerSelectedPageKey(containerId) ||
                key == HandleManager.getContainerOpeningFaceKey(containerId) ||
                prefixes.any(key::startsWith)
        }.forEach(bulkRemovals::add)
    }

    private fun clearPageData(
        prefs: SharedPreferences,
        containerId: String,
        pageId: String,
        bulkRemovals: MutableSet<String>
    ) {
        val keys = listOf(
            HandleManager.getContainerPageElementsKey(containerId, pageId),
            "handle_${containerId}_hybrid_grid_${pageId}",
            "handle_${containerId}_hybrid_grid_cols_${pageId}",
            "handle_${containerId}_hybrid_grid_modified_${pageId}",
            "handle_${containerId}_hybrid_grid_height_${pageId}",
            "hg_${containerId}_${pageId}",
            "handle_${containerId}_widgets_grid_${pageId}",
            "handle_${containerId}_widgets_grid_cols_${pageId}",
            "sidebar_apps_${containerId}_${pageId}",
            "handle_${containerId}_page_${pageId}_columns"
        )
        keys.forEach(bulkRemovals::add)
    }

    private fun safeFileName(value: String): String =
        value.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .take(160)
}
