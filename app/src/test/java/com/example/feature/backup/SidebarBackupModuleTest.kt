package com.example.feature.backup

import com.example.core.SidebarPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarBackupModuleTest {
    @Test
    fun mergeRetainsDestinationOnlyPagesAndRestoresBackupOrder() {
        val pageA = SidebarPage.createDefault("current_a", "calculator", "Current A", 0)
        val pageB = SidebarPage.createDefault("saved_b", "calculator", "Old B", 1).copy(width = 222)
        val pageC = SidebarPage.createDefault("current_c", "compass", "Current C", 2)
        val current = listOf(pageA, pageB, pageC)
        val records = listOf(
            SidebarBackupModule.PageRecord(
                "handle_1", "swipe_left", "handle_1_swipe_left",
                "saved_b", "calculator", "Restored B", 0
            ),
            SidebarBackupModule.PageRecord(
                "handle_1", "swipe_left", "handle_1_swipe_left",
                "saved_new", "widgets_grid", "New Page", 2
            )
        )

        val merged = SidebarBackupModule.mergePageStack(current, records)

        assertEquals(listOf("saved_b", "current_a", "saved_new", "current_c"), merged.map { it.pageId })
        assertEquals("Restored B", merged.first().title)
        assertEquals(222, merged.first().width)
        assertTrue(merged.any { it.pageId == "current_c" })
    }

    @Test
    fun typeConflictDoesNotReplaceExistingPage() {
        val existing = SidebarPage.createDefault("same_id", "calculator", "Keep Me", 0).copy(width = 190)
        val records = listOf(
            SidebarBackupModule.PageRecord(
                "handle_1", "swipe_left", "handle_1_swipe_left",
                "same_id", "widgets_grid", "Do Not Apply", 0
            )
        )

        val merged = SidebarBackupModule.mergePageStack(listOf(existing), records)

        assertEquals(1, merged.size)
        assertEquals("calculator", merged.single().pageType)
        assertEquals("Keep Me", merged.single().title)
        assertEquals(190, merged.single().width)
    }
}
