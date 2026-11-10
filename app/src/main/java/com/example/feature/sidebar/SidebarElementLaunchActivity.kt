package com.example.feature.sidebar

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.core.LogKeeper
import com.example.feature.element.ElementActionDispatcher
import com.example.feature.element.ElementIdParser

/**
 * Executes a Sidebar element selected for the launcher Home grid.
 * This Activity runs in :heavy so it can use Sidebar's existing action runtime.
 */
class SidebarElementLaunchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val elementId = intent.getStringExtra(EXTRA_ELEMENT_ID)
        if (elementId.isNullOrBlank()) {
            finish()
            return
        }

        val item = ElementIdParser.parse(this, elementId)
        if (item == null) {
            Toast.makeText(this, "This Sidebar element is no longer available", Toast.LENGTH_SHORT).show()
            LogKeeper.log(this, TAG, "Unable to resolve launcher Sidebar element: $elementId")
            finish()
            return
        }

        executeSidebarItem(item)
    }

    private fun executeSidebarItem(item: SidebarItem) {
        when (item) {
            is SidebarItem.Folder -> showFolder(item)
            is SidebarItem.Widget -> {
                Toast.makeText(
                    this,
                    "Android widgets must be managed through the launcher's widget picker",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
            is SidebarItem.Spacer -> {
                Toast.makeText(this, "This Sidebar item has no action", Toast.LENGTH_SHORT).show()
                finish()
            }
            else -> {
                val handled = ElementActionDispatcher.execute(
                    context = this,
                    item = item,
                    onShowFolder = { showFolder(it) },
                    onShowWidget = {
                        Toast.makeText(
                            this,
                            "This widget is managed by Sidebar and cannot be opened as an action",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                )
                if (!handled) {
                    Toast.makeText(this, "Unable to open this Sidebar element", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }
    }

    private fun showFolder(folder: SidebarItem.Folder) {
        if (folder.items.isEmpty()) {
            Toast.makeText(this, "This Sidebar folder is empty", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val labels = folder.items.map { id ->
            ElementIdParser.parse(this, id)?.label ?: id.substringAfter(":").ifBlank { "Element" }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(folder.name)
            .setItems(labels) { _, which ->
                val child = ElementIdParser.parse(this, folder.items[which])
                if (child != null) {
                    executeSidebarItem(child)
                } else {
                    Toast.makeText(this, "This folder item is no longer available", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setOnCancelListener { finish() }
            .show()
    }

    companion object {
        const val EXTRA_ELEMENT_ID = "ELEMENT_ID"
        private const val TAG = "SidebarElementLaunch"
    }
}
