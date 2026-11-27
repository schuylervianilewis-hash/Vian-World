package com.example.feature.floating.trigger

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingTriggerRemovalClientTest {

    @Test
    fun collectsDirectFloatingTriggerTarget() {
        assertEquals(
            listOf("quicktile:torch"),
            FloatingTriggerRemovalClient.collectTriggerTargets("floating_trigger:quicktile:torch")
        )
    }

    @Test
    fun recursivelyCollectsTriggersInsideNestedFoldersAndDeduplicates() {
        val nestedFolder = "folder:nested:" + JSONObject()
            .put("items", JSONArray().put("floating_trigger:system:dictionary_floating"))
            .toString()
        val rootFolder = "folder:root:" + JSONObject()
            .put(
                "items",
                JSONArray()
                    .put("floating_trigger:quicktile:torch")
                    .put("app:com.example.app")
                    .put(nestedFolder)
                    .put("floating_trigger:quicktile:torch")
            )
            .toString()

        assertEquals(
            listOf("quicktile:torch", "system:dictionary_floating"),
            FloatingTriggerRemovalClient.collectTriggerTargets(rootFolder)
        )
    }

    @Test
    fun ignoresOrdinaryElementsAndMalformedFolderPayloads() {
        assertEquals(emptyList<String>(), FloatingTriggerRemovalClient.collectTriggerTargets("app:com.example.app"))
        assertEquals(emptyList<String>(), FloatingTriggerRemovalClient.collectTriggerTargets("folder:broken:{not-json"))
    }
}
