package com.example.feature.backup

import android.content.Context
import eu.faircode.netguard.ModularFirewallBackup
import org.json.JSONObject
import shura.home.helpers.BackupHelper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

data class BackupRestoreReport(
    val restoredModules: List<String>,
    val rollbackAttempted: Boolean = false,
    val warnings: List<String> = emptyList()
)

/**
 * Coordinates module adapters and takes pre-restore snapshots of every selected module.
 * Payload validation is completed before the first adapter is allowed to mutate state.
 */
object ModularBackupManager {
    private val supportedModules = setOf(
        ModularBackupArchive.MODULE_LAUNCHER,
        ModularBackupArchive.MODULE_FIREWALL,
        ModularBackupArchive.MODULE_SIDEBAR
    )

    fun export(context: Context, selectedModules: Set<String>, output: OutputStream) {
        require(selectedModules.isNotEmpty()) { "Select at least one module" }
        require(selectedModules.all { it in supportedModules }) { "Unknown backup module selected" }

        val payloads = linkedMapOf<String, JSONObject>()
        selectedModules.sorted().forEach { module ->
            payloads[module] = exportModule(context, module)
        }
        ModularBackupArchive.write(output, com.example.BuildConfig.VERSION_NAME, payloads)
    }

    fun inspect(input: ByteArrayInputStream): ModularBackupArchive.Parsed =
        ModularBackupArchive.read(input)

    fun restore(
        context: Context,
        archive: ModularBackupArchive.Parsed,
        selectedModules: Set<String>
    ): BackupRestoreReport {
        require(selectedModules.isNotEmpty()) { "Select at least one module to restore" }
        require(selectedModules.all { it in supportedModules && archive.modules.containsKey(it) }) {
            "A selected module is not present in this backup"
        }

        // Validate every selected module before taking snapshots or applying any writes.
        selectedModules.forEach { module ->
            val payload = archive.modules.getValue(module)
            when (module) {
                ModularBackupArchive.MODULE_LAUNCHER -> validateLauncher(payload)
                ModularBackupArchive.MODULE_FIREWALL -> ModularFirewallBackup.validate(payload)
                ModularBackupArchive.MODULE_SIDEBAR -> SidebarBackupModule.validate(payload)
            }
        }

        val snapshots = linkedMapOf<String, JSONObject>()
        selectedModules.sorted().forEach { module ->
            snapshots[module] = exportModule(context, module)
        }

        val attempted = mutableListOf<String>()
        try {
            // Stable ordering makes restore and rollback behavior deterministic.
            selectedModules.sorted().forEach { module ->
                attempted.add(module)
                applyModule(context, module, archive.modules.getValue(module))
            }
            return BackupRestoreReport(restoredModules = attempted)
        } catch (failure: Throwable) {
            val rollbackErrors = mutableListOf<String>()
            attempted.asReversed().forEach { module ->
                try {
                    applyModule(context, module, snapshots.getValue(module), exactRollback = true)
                } catch (rollbackFailure: Throwable) {
                    rollbackErrors.add("$module: ${rollbackFailure.message ?: rollbackFailure.javaClass.simpleName}")
                }
            }
            val detail = buildString {
                append("Backup restore failed: ")
                append(failure.message ?: failure.javaClass.simpleName)
                if (rollbackErrors.isNotEmpty()) {
                    append(". Rollback was incomplete: ")
                    append(rollbackErrors.joinToString("; "))
                } else {
                    append(". Selected modules were rolled back to their pre-restore snapshots.")
                }
            }
            throw IllegalStateException(detail, failure)
        }
    }

    private fun exportModule(context: Context, module: String): JSONObject =
        when (module) {
            ModularBackupArchive.MODULE_LAUNCHER -> {
                val bytes = ByteArrayOutputStream()
                check(BackupHelper.exportBackup(context, bytes)) { "Launcher backup export failed" }
                JSONObject(bytes.toString(Charsets.UTF_8.name()))
            }
            ModularBackupArchive.MODULE_FIREWALL -> ModularFirewallBackup.export(context)
            ModularBackupArchive.MODULE_SIDEBAR -> SidebarBackupModule.export(context)
            else -> error("Unsupported backup module: $module")
        }

    private fun applyModule(
        context: Context,
        module: String,
        payload: JSONObject,
        exactRollback: Boolean = false
    ) {
        when (module) {
            ModularBackupArchive.MODULE_LAUNCHER -> {
                val bytes = payload.toString().toByteArray(Charsets.UTF_8)
                check(BackupHelper.importBackup(context, ByteArrayInputStream(bytes))) {
                    "Launcher restore failed"
                }
            }
            ModularBackupArchive.MODULE_FIREWALL -> ModularFirewallBackup.restore(context, payload, exactRollback)
            ModularBackupArchive.MODULE_SIDEBAR -> SidebarBackupModule.restore(context, payload, exactRollback)
            else -> error("Unsupported backup module: $module")
        }
    }

    private fun validateLauncher(payload: JSONObject) {
        val version = payload.optInt("schema_version", 1)
        require(version in 1..2) { "Unsupported Launcher backup schema: $version" }
        require(payload.optJSONArray("grid_items") != null) { "Launcher grid data is missing" }
    }
}
