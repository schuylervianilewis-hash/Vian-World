package com.example.feature.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private data class BackupModuleChoice(
    val id: String,
    val title: String,
    val description: String
)

private val backupModuleChoices = listOf(
    BackupModuleChoice(
        ModularBackupArchive.MODULE_LAUNCHER,
        "Launcher",
        "Home screen, folders, launcher preferences, gestures, app lists and custom icons."
    ),
    BackupModuleChoice(
        ModularBackupArchive.MODULE_FIREWALL,
        "Firewall",
        "Safe Firewall settings, app rules, blocklists, forwarding rules and domain exceptions. WireGuard is excluded."
    ),
    BackupModuleChoice(
        ModularBackupArchive.MODULE_SIDEBAR,
        "Sidebar",
        "All handles and gestures, every container and full page configuration, page elements, Hybrid/Apps/Widgets Grid content, supported element metadata and icons, Scheduler reminders and App Tracker whitelist."
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportSelection by remember {
        mutableStateOf(backupModuleChoices.map { it.id }.toSet())
    }
    var restoreSelection by remember { mutableStateOf(emptySet<String>()) }
    var parsedArchive by remember { mutableStateOf<ModularBackupArchive.Parsed?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            val selected = exportSelection
            busy = true
            status = "Creating backup…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: error("Could not open the selected destination")
                        output.use { ModularBackupManager.export(context, selected, it) }
                    }
                }
                busy = false
                status = result.fold(
                    onSuccess = { "Backup created with ${selected.size} module(s)." },
                    onFailure = { "Backup failed: ${it.message ?: "unknown error"}" }
                )
            }
        }
    }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            busy = true
            status = "Validating backup…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected backup")
                        input.use { ModularBackupArchive.read(it, context.packageName) }
                    }
                }
                busy = false
                result.fold(
                    onSuccess = { archive ->
                        parsedArchive = archive
                        restoreSelection = archive.modules.keys
                        status = "Backup validated. Choose which modules to restore."
                    },
                    onFailure = {
                        parsedArchive = null
                        restoreSelection = emptySet()
                        status = "Backup rejected: ${it.message ?: "invalid archive"}"
                    }
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Create one ZIP backup with independently selectable modules, or restore selected modules from an existing ZIP.",
                style = MaterialTheme.typography.bodyMedium
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Create backup", style = MaterialTheme.typography.titleMedium)
                    backupModuleChoices.forEach { choice ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = choice.id in exportSelection,
                                enabled = !busy,
                                onCheckedChange = { checked ->
                                    exportSelection = if (checked) exportSelection + choice.id
                                    else exportSelection - choice.id
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(choice.title, style = MaterialTheme.typography.bodyLarge)
                                Text(choice.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Button(
                        onClick = { createDocument.launch("vian-world-backup.zip") },
                        enabled = !busy && exportSelection.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Create ZIP backup")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Restore backup", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Choose a Vian World ZIP backup. Its contents are validated before any restore begins.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = { openDocument.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Choose backup ZIP")
                    }

                    parsedArchive?.let { archive ->
                        HorizontalDivider()
                        Text("Backup preview", style = MaterialTheme.typography.titleSmall)
                        Text("App version: ${archive.appVersion}")
                        Text(
                            "Created: " + if (archive.createdAt > 0L) {
                                DateFormat.getDateTimeInstance().format(Date(archive.createdAt))
                            } else "Unknown"
                        )
                        backupModuleChoices.filter { it.id in archive.modules }.forEach { choice ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = choice.id in restoreSelection,
                                    enabled = !busy,
                                    onCheckedChange = { checked ->
                                        restoreSelection = if (checked) restoreSelection + choice.id
                                        else restoreSelection - choice.id
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(choice.title, style = MaterialTheme.typography.bodyLarge)
                                    Text(choice.description, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Text(
                            "Not included: WireGuard profiles or secrets, floating mini-app and Reader/EPUB content, PWA package/site data, App Lock credentials and biometric enrollment, pinned shortcut IDs, and live system state. Android widget positions/provider hints are preserved, but widgets must be rebound on this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(
                            onClick = {
                                val selected = restoreSelection
                                busy = true
                                status = "Restoring selected modules…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            ModularBackupManager.restore(context, archive, selected)
                                        }
                                    }
                                    busy = false
                                    status = result.fold(
                                        onSuccess = {
                                            parsedArchive = null
                                            restoreSelection = emptySet()
                                            val restored = "Restored: ${it.restoredModules.joinToString(", ")}."
                                            val details = it.details.joinToString(" ")
                                            val warningText = if (it.warnings.isEmpty()) "" else
                                                " Warnings: " + it.warnings.take(8).joinToString(" ")
                                            listOf(restored, details, warningText).filter { part -> part.isNotBlank() }.joinToString(" ")
                                        },
                                        onFailure = { "Restore failed: ${it.message ?: "unknown error"}" }
                                    )
                                }
                            },
                            enabled = !busy && restoreSelection.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Restore selected modules")
                        }
                    }
                }
            }

            if (busy) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(1.dp))
                }
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
