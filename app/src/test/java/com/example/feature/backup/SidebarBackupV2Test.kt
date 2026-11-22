package com.example.feature.backup

import com.example.core.HandleGestures
import com.example.core.HandleManager
import com.example.core.PageTypes
import com.example.core.SidebarPage
import com.example.util.HandleEdge
import com.example.util.HandleShape
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarBackupV2Test {
    @Test
    fun mergeRestoresFullPageConfigurationAndRetainsDestinationOnlyPages() {
        val currentPage = SidebarPage.createDefault("saved_page", PageTypes.HYBRID_GRID, "Old title", 0)
            .copy(width = 180, transparency = 0.4f)
        val destinationOnly = SidebarPage.createDefault("destination_only", PageTypes.CALCULATOR, "Keep", 1)
        val backupPage = currentPage.copy(
            title = "Restored title",
            width = 320,
            height = 640,
            transparency = 0.75f,
            gridColumns = 5,
            iconName = "custom_icon",
            isMiniApp = true,
            miniAppType = "notes",
            order = 0
        )

        val merged = SidebarBackupV2.mergePageStack(
            current = listOf(currentPage, destinationOnly),
            backup = listOf(backupPage)
        )

        assertEquals(listOf("saved_page", "destination_only"), merged.map { it.pageId })
        assertEquals("Restored title", merged.first().title)
        assertEquals(320, merged.first().width)
        assertEquals(640, merged.first().height)
        assertEquals(0.75f, merged.first().transparency, 0.0001f)
        assertEquals(5, merged.first().gridColumns)
        assertEquals("custom_icon", merged.first().iconName)
        assertTrue(merged.first().isMiniApp)
        assertEquals("notes", merged.first().miniAppType)
    }

    @Test
    fun mergeDoesNotReplacePageWhenItsTypeConflicts() {
        val existing = SidebarPage.createDefault("same_id", PageTypes.CALCULATOR, "Keep existing", 0)
        val backup = SidebarPage.createDefault("same_id", PageTypes.WIDGETS_GRID, "Do not apply", 0)

        val merged = SidebarBackupV2.mergePageStack(listOf(existing), listOf(backup))

        assertEquals(1, merged.size)
        assertEquals(PageTypes.CALCULATOR, merged.single().pageType)
        assertEquals("Keep existing", merged.single().title)
    }

    @Test
    fun validatesCompleteSchemaWithPageAndContainerState() {
        SidebarBackupV2.validate(validPayload())
    }

    @Test
    fun acceptsEarlierSchemaTwoPayloadWithoutNotificationFilters() {
        val payload = validPayload()
        payload.put("schemaVersion", 2)
        payload.remove("notificationFilters")

        SidebarBackupV2.validate(payload)
    }

    @Test
    fun rejectsInvalidNotificationFilterPackageNames() {
        val payload = validPayload()
        payload.getJSONObject("notificationFilters")
            .getJSONArray("sidebarHiddenPackages")
            .put("../not-a-package")

        val failure = runCatching { SidebarBackupV2.validate(payload) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun acceptsPersistedContainerForGestureThatDoesNotCurrentlyOpenSidebar() {
        val payload = validPayload()
        val containers = payload.getJSONArray("containers")
        val inactive = JSONObject(containers.getJSONObject(0).toString())
        val containerId = HandleManager.getContainerId("handle_1", HandleGestures.TAP)
        inactive.put("gesture", HandleGestures.TAP)
        inactive.put("containerId", containerId)
        val pageContent = inactive.getJSONArray("pageContent")
        for (index in 0 until pageContent.length()) {
            pageContent.getJSONObject(index).put("containerId", containerId)
        }
        containers.put(inactive)

        SidebarBackupV2.validate(payload)
    }

    @Test
    fun validatesPageElementPlacementInItsOriginalContainer() {
        val payload = validPayload()
        val container = payload.getJSONArray("containers").getJSONObject(0)
        val pageContent = container.getJSONArray("pageContent").getJSONObject(0)
        pageContent.put("elementsPresent", true)
        pageContent.getJSONArray("elements").put(
            JSONObject()
                .put("placementId", "placement_1")
                .put("containerId", container.getString("containerId"))
                .put("pageId", "default_hybrid")
                .put("actionKey", "app:com.example.app")
                .put("position", 0)
                .put("config", JSONObject())
        )

        SidebarBackupV2.validate(payload)
    }

    @Test
    fun rejectsPageElementPlacementFromAnotherContainer() {
        val payload = validPayload()
        val container = payload.getJSONArray("containers").getJSONObject(0)
        val pageContent = container.getJSONArray("pageContent").getJSONObject(0)
        pageContent.put("elementsPresent", true)
        pageContent.getJSONArray("elements").put(
            JSONObject()
                .put("placementId", "placement_1")
                .put("containerId", "handle_2_swipe_left")
                .put("pageId", "default_hybrid")
                .put("actionKey", "app:com.example.app")
                .put("position", 0)
                .put("config", JSONObject())
        )

        val failure = runCatching { SidebarBackupV2.validate(payload) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun rejectsContainerContentThatDoesNotMatchItsPage() {
        val payload = validPayload()
        val container = payload.getJSONArray("containers").getJSONObject(0)
        container.getJSONArray("pageContent").getJSONObject(0).put("pageId", "missing_page")

        val failure = runCatching { SidebarBackupV2.validate(payload) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    private fun validPayload(): JSONObject {
        val handleId = "handle_1"
        val gesture = HandleGestures.SWIPE_LEFT
        val containerId = HandleManager.getContainerId(handleId, gesture)
        val page = SidebarPage.createDefault(
            id = "default_hybrid",
            type = PageTypes.HYBRID_GRID,
            title = "Home Grid",
            order = 0
        )
        val gestures = JSONObject()
        HandleGestures.ALL.forEach { actionGesture ->
            gestures.put(
                actionGesture,
                if (actionGesture == gesture) HandleManager.ACTION_OPEN_SIDEBAR else HandleManager.ACTION_NONE
            )
        }
        fun absentSetting() = JSONObject().put("present", false)
        val handle = JSONObject()
            .put("id", handleId)
            .put("name", "Handle 1")
            .put("enabled", true)
            .put("edge", HandleEdge.RIGHT.name)
            .put("position", 0.5)
            .put("widthDp", 12)
            .put("heightDp", 120)
            .put("color", 0)
            .put("shape", HandleShape.SLANTED_BLOCK.name)
            .put("alphaPercent", 14)
            .put("settings", JSONObject()
                .put("columns", absentSetting())
                .put("legacyEdge", absentSetting())
                .put("sidebarColor", absentSetting())
                .put("sidebarTransparency", absentSetting()))
            .put("gestures", gestures)

        val pageData = JSONObject()
            .put("gridJsonPresent", false)
            .put("elementsJsonPresent", false)
            .put("columnsPresent", false)
            .put("modifiedPresent", false)
            .put("heightPresent", false)
        val pageContent = JSONObject()
            .put("containerId", containerId)
            .put("pageId", page.pageId)
            .put("pageType", page.pageType)
            .put("kind", "hybrid_grid")
            .put("elementsPresent", false)
            .put("elements", JSONArray())
            .put("data", pageData)

        val settings = JSONObject()
            .put("sidebarColor", absentSetting())
            .put("sidebarTransparency", absentSetting())
            .put("wrapContent", absentSetting())
            .put("height", absentSetting())
            .put("width", absentSetting())
            .put("columns", absentSetting())

        val container = JSONObject()
            .put("handleId", handleId)
            .put("gesture", gesture)
            .put("containerId", containerId)
            .put("selectedPageId", page.pageId)
            .put("openingFaceId", page.pageId)
            .put("settings", settings)
            .put("pages", JSONArray().put(page.toJson()))
            .put("pageContent", JSONArray().put(pageContent))

        val globalSettings = JSONObject()
            .put("sidebarColor", absentSetting())
            .put("sidebarTransparency", absentSetting())
            .put("wrapContent", absentSetting())
            .put("height", absentSetting())
            .put("width", absentSetting())
            .put("columns", absentSetting())
            .put("positionLeft", absentSetting())

        return JSONObject()
            .put("schemaVersion", SidebarBackupV2.SCHEMA_VERSION)
            .put("handles", JSONArray().put(handle))
            .put("containers", JSONArray().put(container))
            .put("globalSettings", globalSettings)
            .put("elementMetadata", JSONArray())
            .put("customIcons", JSONArray())
            .put("savedLinkIds", JSONArray())
            .put("notificationFilters", JSONObject()
                .put("sidebarHiddenPresent", true)
                .put("sidebarHiddenPackages", JSONArray().put("com.example.hidden"))
                .put("historyHiddenPresent", false)
                .put("historyHiddenPackages", JSONArray()))
    }
}
