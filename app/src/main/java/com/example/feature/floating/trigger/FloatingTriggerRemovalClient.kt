package com.example.feature.floating.trigger

import android.content.Context
import com.example.core.LogKeeper
import com.example.core.ipc.HeavyProcessHost
import com.example.core.ipc.MainActionType
import com.example.core.ipc.MainCommandRequest
import org.json.JSONObject

/**
 * Removes persisted Floating Trigger records when their Sidebar element (or a containing folder)
 * is deleted from the Heavy-process editors.
 */
object FloatingTriggerRemovalClient {
    private const val TAG = "FloatingTriggerRemoval"

    /**
     * Collects trigger target IDs from a removed element. Folder IDs carry serialized child IDs,
     * so deleting a folder also cleans up any Floating Trigger elements nested inside it.
     */
    fun collectTriggerTargets(elementId: String): List<String> {
        val targets = LinkedHashSet<String>()
        collectTriggerTargets(elementId, targets)
        return targets.toList()
    }

    fun removeForElement(context: Context, elementId: String) {
        collectTriggerTargets(elementId).forEach { targetId ->
            val result = HeavyProcessHost.getInstance(context).requestMainAction(
                MainCommandRequest(
                    action = MainActionType.CUSTOM,
                    targetId = "FLOATING_TRIGGER_ACTION",
                    parameters = mapOf(
                        "subAction" to "REMOVE",
                        "targetElementId" to targetId
                    )
                )
            )
            if (result.success) {
                LogKeeper.log(context, TAG, "Requested removal for target=$targetId")
            } else {
                LogKeeper.log(context, TAG, "Removal request failed for target=$targetId: ${result.message}")
            }
        }
    }

    private fun collectTriggerTargets(elementId: String, targets: MutableSet<String>) {
        when {
            elementId.startsWith("floating_trigger:") -> {
                elementId.substringAfter("floating_trigger:")
                    .takeIf { it.isNotBlank() }
                    ?.let(targets::add)
            }
            elementId.startsWith("folder:") -> {
                val payload = elementId.split(":", limit = 3).getOrNull(2) ?: return
                val items = try {
                    JSONObject(payload).optJSONArray("items")
                } catch (_: Exception) {
                    null
                } ?: return

                for (index in 0 until items.length()) {
                    val childId = items.optString(index).takeIf { it.isNotBlank() } ?: continue
                    collectTriggerTargets(childId, targets)
                }
            }
        }
    }
}
