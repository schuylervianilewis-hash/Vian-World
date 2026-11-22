package com.example.feature.element

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ElementSelectionSessionTest {
    @Test
    fun accumulatesSelectionsInOrderAcrossMultiplePickerReturns() {
        val session = ElementSelectionSession()

        assertEquals(1, session.add("app:com.example.first"))
        assertEquals(2, session.add("shortcut:second"))
        assertEquals(3, session.add("widget:third"))

        assertEquals(
            listOf("app:com.example.first", "shortcut:second", "widget:third"),
            session.snapshot()
        )
    }

    @Test
    fun restoredSessionRetainsPriorSelectionsAndContinuesAppending() {
        val restored = ElementSelectionSession(listOf("app:first", "app:second"))

        assertEquals(2, restored.size)
        restored.add("system:action")

        assertEquals(listOf("app:first", "app:second", "system:action"), restored.snapshot())
    }

    @Test
    fun rejectsBlankElementIds() {
        val session = ElementSelectionSession()

        assertThrows(IllegalArgumentException::class.java) {
            session.add("  ")
        }
        assertEquals(0, session.size)
    }
}
