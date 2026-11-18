package com.example.feature.settings

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.core.OverlayAccessPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private data class InstalledOverlayApp(
    val packageName: String, val label: String, val icon: Drawable, val isSystem: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayExclusionsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledOverlayApp>>(emptyList()) }
    var excluded by remember { mutableStateOf(OverlayAccessPolicy.getExcludedPackages(context)) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var descending by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != context.packageName }
                .mapNotNull { info ->
                    try {
                        InstalledOverlayApp(
                            info.packageName,
                            pm.getApplicationLabel(info).toString(),
                            pm.getApplicationIcon(info),
                            (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    } catch (_: PackageManager.NameNotFoundException) { null }
                }.toList()
        }
    }

    val visibleApps = remember(apps, excluded, query, filter, descending) {
        val q = query.trim().lowercase(Locale.ROOT)
        apps.asSequence()
            .filter { app ->
                when (filter) {
                    "user" -> !app.isSystem
                    "system" -> app.isSystem
                    "excluded" -> app.packageName in excluded
                    else -> true
                }
            }
            .filter { app -> q.isEmpty() ||
                app.label.lowercase(Locale.ROOT).contains(q) ||
                app.packageName.lowercase(Locale.ROOT).contains(q) }
            .sortedWith(compareBy<InstalledOverlayApp> { it.label.lowercase(Locale.ROOT) }
                .let { if (descending) it.reversed() else it })
            .toList()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Overlay Exclusions") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Allow overlays", style = MaterialTheme.typography.titleMedium)
                Text(
                    "ON lets Vian overlays appear while an app is open. OFF hides Vian visual overlays for that app. This does not change Android's overlay permission or Firewall protection.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Foreground detection uses Vian's Accessibility service. Call sensing, Internet Speed Monitor and Firewall protection remain active.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    singleLine = true, label = { Text("Search apps") }
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("all" to "All", "user" to "User", "system" to "System", "excluded" to "Excluded")
                        .forEach { (value, label) ->
                            FilterChip(filter == value, { filter = value }, label = { Text(label) })
                        }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sort by app name", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { descending = !descending }) {
                        Text(if (descending) "Z–A" else "A–Z")
                    }
                    Text("${visibleApps.size} apps", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                }
            }
            HorizontalDivider()
            if (visibleApps.isEmpty()) {
                Text(if (apps.isEmpty()) "Loading installed apps…" else "No matching apps",
                    modifier = Modifier.padding(20.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visibleApps, key = { it.packageName }) { app ->
                        val allowed = OverlayAccessPolicy.isPackageAllowed(app.packageName, excluded)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                bitmap = remember(app.packageName) {
                                    app.icon.toBitmap(width = 96, height = 96).asImageBitmap()
                                },
                                contentDescription = null,
                                modifier = Modifier.size(40.dp)
                            )
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(if (allowed) "Overlays allowed" else "Overlays blocked",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = allowed, onCheckedChange = { newAllowed ->
                                OverlayAccessPolicy.setOverlayAllowed(context, app.packageName, newAllowed)
                                excluded = OverlayAccessPolicy.getExcludedPackages(context)
                            })
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
