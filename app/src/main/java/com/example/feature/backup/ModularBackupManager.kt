package com.example.feature.backup

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import eu.faircode.netguard.ModularFirewallBackup
import org.json.JSONObject
import shura.home.helpers.BackupHelper
import shura.home.helpers.ITEM_TYPE_SHORTCUT
import shura.home.helpers.ITEM_TYPE_WIDGET
import shura.home.helpers.SIDEBAR_ELEMENT_CLASS_NAME
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
            val payload = exportModule(context, module)
            validateModule(module, payload)
            payloads[module] = payload
        }
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        val buildType = if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            "debug"
        } else {
            "release"
        }
        ModularBackupArchive.write(
            output,
            context.packageName,
            packageInfo.versionName ?: "unknown",
            versionCode,
            buildType,
            payloads
        )
    }

    fun inspect(context: Context, input: ByteArrayInputStream): ModularBackupArchive.Parsed =
        ModularBackupArchive.read(input, context.packageName)

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
            validateModule(module, archive.modules.getValue(module))
        }

        val snapshots = linkedMapOf<String, JSONObject>()
        selectedModules.sorted().forEach { module ->
            snapshots[module] = exportModule(context, module)
        }

        val attempted = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        try {
            // Stable ordering makes restore and rollback behavior deterministic.
            selectedModules.sorted().forEach { module ->
                attempted.add(module)
                warnings.addAll(applyModule(context, module, archive.modules.getValue(module)))
            }
            return BackupRestoreReport(restoredModules = attempted, warnings = warnings)
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

    private fun validateModule(module: String, payload: JSONObject) {
        when (module) {
            ModularBackupArchive.MODULE_LAUNCHER -> validateLauncher(payload)
            ModularBackupArchive.MODULE_FIREWALL -> ModularFirewallBackup.validate(payload)
            ModularBackupArchive.MODULE_SIDEBAR -> SidebarBackupModule.validate(payload)
            else -> error("Unsupported backup module: $module")
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
    ): List<String> =
        when (module) {
            ModularBackupArchive.MODULE_LAUNCHER -> {
                val warnings = launcherWarnings(context, payload)
                val bytes = payload.toString().toByteArray(Charsets.UTF_8)
                check(BackupHelper.importBackup(context, ByteArrayInputStream(bytes))) {
                    "Launcher restore failed"
                }
                warnings
            }
            ModularBackupArchive.MODULE_FIREWALL ->
                ModularFirewallBackup.restore(context, payload)
            ModularBackupArchive.MODULE_SIDEBAR ->
                SidebarBackupModule.restore(context, payload, exactRollback)
            else -> error("Unsupported backup module: $module")
        }

    private fun launcherWarnings(context: Context, payload: JSONObject): List<String> {
        val warnings = linkedSetOf<String>()
        val items = payload.optJSONArray("grid_items") ?: return emptyList()
        if (payload.optJSONObject("config")?.optBoolean("appLockEnabled", false) == true) {
            warnings.add("App Lock is enabled in this backup, but its password hash and biometric/device enrollment are not transferred.")
        }
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val packageName = item.optString("package_name")
            if (packageName.isNotBlank()) {
                val installed = runCatching {
                    context.packageManager.getApplicationInfo(packageName, 0)
                }.isSuccess
                if (!installed) warnings.add("Launcher app is not installed: $packageName")
            }
            if (item.optInt("type", -1) == ITEM_TYPE_WIDGET && item.optInt("widget_id", -1) >= 0) {
                warnings.add("An Android widget position was preserved; it will be rebound with a new widget ID or shown as an unavailable placeholder.")
            }
            if (item.optInt("type", -1) == ITEM_TYPE_SHORTCUT &&
                item.optString("class_name") != SIDEBAR_ELEMENT_CLASS_NAME &&
                item.optString("shortcut_id").isNotBlank()) {
                warnings.add("A pinned shortcut ID cannot be transferred; its stale system ID was cleared.")
            }
        }
        return warnings.toList()
    }

    private fun validateLauncher(payload: JSONObject) {
        val version = payload.optInt("schema_version", 1)
        require(version in 1..2) { "Unsupported Launcher backup schema: $version" }
        BackupHelper.validateBackupPayload(payload)
    }
}
