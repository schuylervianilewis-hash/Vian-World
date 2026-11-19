package com.example

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.FloatingMiniAppLogFilter
import com.example.core.FirewallLogFilter
import com.example.core.LogKeeper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.activity.OnBackPressedCallback

class LogKeeperActivity : ComponentActivity() {

    companion object {
        const val EXTRA_LAUNCH_SOURCE = "extra_launch_source"
        const val SOURCE_SIDEBAR = "SOURCE_SIDEBAR"
        const val SOURCE_SETTINGS = "SOURCE_SETTINGS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.getStringExtra(EXTRA_LAUNCH_SOURCE) ?: SOURCE_SIDEBAR

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack(source)
            }
        })

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                LogKeeperScreen(onBack = { handleBack(source) })
            }
        }
    }

    private fun handleBack(source: String) {
        if (source == SOURCE_SIDEBAR) {
            // When opened from Sidebar/app UI, exit and remove task so it does not reveal unrelated background Settings
            finishAndRemoveTask()
        } else {
            // When opened from Settings, return normally to preserve the Settings navigation stack
            finish()
        }
    }
}

// Data models for the on-demand Heavy process Running tab
data class ActiveProcessItem(
    val name: String,
    val isMain: Boolean,
    val isHeavy: Boolean,
    val pid: Int,
    val importance: String,
    val isRunning: Boolean
)

data class ActiveServiceItem(
    val shortName: String,
    val fullName: String,
    val processName: String,
    val isMainProcess: Boolean,
    val isForeground: Boolean,
    val started: Boolean,
    val activeSinceMs: Long,
    val startTimeFormatted: String,
    val durationFormatted: String
)

data class LifecycleEventItem(
    val timestamp: String,
    val process: String,
    val isMainProcess: Boolean,
    val thread: String,
    val component: String,
    val eventType: String,
    val message: String,
    val durationText: String? = null
)

data class RunningRuntimeState(
    val processes: List<ActiveProcessItem> = emptyList(),
    val activeServices: List<ActiveServiceItem> = emptyList(),
    val lifecycleEvents: List<LifecycleEventItem> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogKeeperScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Diagnostic, 1: Crash, 2: Running
    var diagnosticLogText by remember { mutableStateOf("Loading diagnostic logs...") }
    var crashLogText by remember { mutableStateOf("Loading crash logs...") }
    var runningState by remember { mutableStateOf(RunningRuntimeState()) }
    var showClearDialog by remember { mutableStateOf(false) }
    var selectedTimeRange by remember { mutableStateOf("All") }
    var selectedSubsystem by remember { mutableStateOf("All") }
    var executionEvents by remember { mutableStateOf(emptyList<LogKeeper.ExecutionEvent>()) }
    var showActiveSubsystems by remember { mutableStateOf(false) }

    fun loadLogs() {
        scope.launch {
            val diag = withContext(Dispatchers.IO) { LogKeeper.getDiagnosticLogs(context) }
            val crash = withContext(Dispatchers.IO) { LogKeeper.getCrashLogs(context) }
            val running = withContext(Dispatchers.IO) { loadRunningState(context, diag) }
            val executions = withContext(Dispatchers.IO) { LogKeeper.getExecutionEvents(context) }
            diagnosticLogText = diag
            crashLogText = crash
            runningState = running
            executionEvents = executions
        }
    }

    LaunchedEffect(Unit) {
        loadLogs()
    }

    val rawLogContent = when (selectedTab) {
        0 -> diagnosticLogText
        1 -> crashLogText
        2 -> formatRunningSummary(runningState)
        else -> formatExecutionSummary(executionEvents)
    }

    val currentLogContent = if (selectedTab >= 2) {
        rawLogContent
    } else {
        filterLogText(rawLogContent, selectedTimeRange, selectedSubsystem)
    }

    val currentSubTitle = when (selectedTab) {
        0 -> LogKeeper.FILE_DIAGNOSTIC_LOG
        1 -> LogKeeper.FILE_CRASH_LOG
        2 -> "Runtime — currently active"
        else -> "Execution history"
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Log Keeper", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            text = currentSubTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("log_keeper_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { loadLogs() }, modifier = Modifier.testTag("log_keeper_refresh_button")) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Vian-Sidebar $currentSubTitle")
                                putExtra(Intent.EXTRA_TEXT, currentLogContent)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Logs"))
                        },
                        modifier = Modifier.testTag("log_keeper_share_button")
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share")
                    }
                    IconButton(
                        onClick = { showClearDialog = true },
                        modifier = Modifier.testTag(if (selectedTab == 3) "log_keeper_clear_execution_button" else "log_keeper_clear_button")
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = if (selectedTab == 3) "Clear Execution History" else "Clear Logs")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("log_keeper_screen")
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Diagnostic", fontWeight = FontWeight.Medium) },
                    modifier = Modifier.testTag("tab_diagnostic_logs")
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Crash Reports", fontWeight = FontWeight.Medium) },
                    modifier = Modifier.testTag("tab_crash_logs")
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Running", fontWeight = FontWeight.Medium) },
                    modifier = Modifier.testTag("tab_running")
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("History", fontWeight = FontWeight.Medium) },
                    modifier = Modifier.testTag("tab_execution_history")
                )
            }

            if (selectedTab == 2) {
                RunningTabContent(
                    state = runningState,
                    executionEvents = executionEvents,
                    onCopySummary = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("LogKeeper_Running", formatRunningSummary(runningState))
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied runtime summary to clipboard", Toast.LENGTH_SHORT).show()
                    }
                )
            } else if (selectedTab == 3) {
                ExecutionHistoryTab(events = executionEvents)
            } else {
                // Vian Board-style time and subsystem filter pills.
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("All", "1h", "6h", "12h", "24h").forEach { range ->
                            FilterChip(
                                selected = selectedTimeRange == range,
                                onClick = { selectedTimeRange = range },
                                label = { Text(range, fontSize = 12.sp) },
                                modifier = Modifier.testTag("filter_time_$range")
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("All", "Core", "Launcher", "Floating Mini-apps", "Firewall").forEach { subsystem ->
                            FilterChip(
                                selected = selectedSubsystem == subsystem,
                                onClick = { selectedSubsystem = subsystem },
                                label = { Text(subsystem, fontSize = 12.sp) },
                                modifier = Modifier.testTag("filter_subsystem_${subsystem.replace(" ", "_")}")
                            )
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("LogKeeper", currentLogContent)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied logs to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.testTag("copy_logs_button")
                        ) {
                            Text("Copy Logs", fontSize = 12.sp)
                        }
                    }
                }

                if (selectedTab == 0 && executionEvents.isNotEmpty()) {
                    ActiveSubsystemsCard(
                        events = executionEvents,
                        expanded = showActiveSubsystems,
                        onToggle = { showActiveSubsystems = !showActiveSubsystems }
                    )
                }
                LogKeeperLogList(
                    raw = currentLogContent,
                    emptyMessage = when (selectedTab) {
                        1 -> "No crash reports match the selected filters."
                        else -> if (selectedTimeRange == "All" && selectedSubsystem == "All")
                            "No logs recorded. System is running cleanly."
                        else "No logs for $selectedSubsystem in the selected timeframe."
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showClearDialog) {
        val clearTitle = when (selectedTab) {
            1 -> "Clear Crash Reports?"
            3 -> "Clear Execution History?"
            else -> "Clear Diagnostic Logs?"
        }
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(clearTitle) },
            text = { Text("This will permanently delete the recorded entries in this log file.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                when (selectedTab) {
                                    1 -> LogKeeper.clearCrashLogs(context)
                                    3 -> LogKeeper.clearExecutionLogs(context)
                                    else -> LogKeeper.clearDiagnosticLogs(context)
                                }
                            }
                            loadLogs()
                            Toast.makeText(context, "Cleared logs", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.testTag("confirm_clear_logs_button")
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun RunningTabContent(
    state: RunningRuntimeState,
    executionEvents: List<LogKeeper.ExecutionEvent>,
    onCopySummary: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("running_tab_content")
    ) {
        // Sub-toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Runtime Processes & Services",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            OutlinedButton(
                onClick = onCopySummary,
                modifier = Modifier.testTag("copy_running_summary_button")
            ) {
                Text("Copy Summary", fontSize = 12.sp)
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Section 1: Process Status
            item {
                Text(
                    text = "App Processes",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
            }

            items(state.processes) { proc ->
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (proc.isRunning) Color(0xFF2E7D32) else Color(0xFF757575),
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .height(10.dp)
                                        .width(10.dp)
                                ) {}
                                Text(
                                    text = proc.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = when (proc.name) {
                    "Main Process" -> "Sidebar resident process (default)"
                    "Home Process" -> "Android-managed launcher process (:home)"
                    else -> "On-demand UI process (:heavy)"
                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            StatusPill(
                                text = if (proc.isRunning) "RUNNING" else "IDLE",
                                containerColor = if (proc.isRunning) Color(0xFFE8F5E9) else Color(0xFFF5F5F5),
                                contentColor = if (proc.isRunning) Color(0xFF2E7D32) else Color(0xFF757575)
                            )
                            if (proc.isRunning) {
                                Text(
                                    text = "PID ${proc.pid} • ${proc.importance}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }

            // Structured execution tracking is separate from inferred text-based lifecycle history.
            val completedIds = executionEvents.filter {
                it.event != "STARTED" && it.event != "OVERLAP"
            }.map { it.id }.toSet()
            val openExecutions = executionEvents.filter { it.event == "STARTED" && it.id !in completedIds }
            item {
                Text(
                    text = "Tracked Operations (${openExecutions.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (openExecutions.isEmpty()) {
                item {
                    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                        Text(
                            "No tracked operations currently have an open execution record.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(openExecutions.sortedByDescending { it.timestampMillis }) { execution ->
                    val processPresent = state.processes.any { it.pid == execution.pid && it.isRunning }
                    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    execution.component + " / " + execution.operation,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                StatusPill(
                                    text = if (processPresent) "OPEN" else "STALE / UNKNOWN",
                                    containerColor = if (processPresent) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                                    contentColor = if (processPresent) Color(0xFF2E7D32) else Color(0xFFE65100)
                                )
                            }
                            Text(
                                "${execution.process} • PID ${execution.pid} • ${execution.thread}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!processPresent) {
                                Text(
                                    "No completion event was recorded and this PID is not currently visible. It may have been stopped or killed.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Section 2: Active Services
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Active Services (${state.activeServices.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }

            if (state.activeServices.isEmpty()) {
                item {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "No active background services running.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            } else {
                items(state.activeServices) { service ->
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = service.shortName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                StatusPill(
                                    text = when {
                                        service.isMainProcess -> "Main Process"
                                        service.processName.endsWith(":home") -> "Home Process"
                                        else -> "Heavy Process"
                                    },
                                    containerColor = if (service.isMainProcess) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = if (service.isMainProcess) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StatusPill(
                                    text = if (service.isForeground) "Foreground" else "Background",
                                    containerColor = if (service.isForeground) Color(0xFFE3F2FD) else Color(0xFFF5F5F5),
                                    contentColor = if (service.isForeground) Color(0xFF1565C0) else Color(0xFF616161)
                                )
                                TimePill(text = "Started: ${service.startTimeFormatted}")
                                TimePill(text = "Uptime: ${service.durationFormatted}")
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun TimePill(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}


/** Filters timestamped log blocks without parsing stack-trace continuation lines as separate events. */
fun filterLogText(raw: String, timeRange: String, subsystem: String, nowMillis: Long = System.currentTimeMillis()): String {
    val cutoff = when (timeRange) {
        "1h" -> nowMillis - 60L * 60L * 1000L
        "6h" -> nowMillis - 6L * 60L * 60L * 1000L
        "12h" -> nowMillis - 12L * 60L * 60L * 1000L
        "24h" -> nowMillis - 24L * 60L * 60L * 1000L
        else -> Long.MIN_VALUE
    }
    val blocks = FloatingMiniAppLogFilter.splitIntoBlocks(raw)
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    val filtered = blocks.filter { block ->
        val firstLine = block.lineSequence().firstOrNull().orEmpty()
        val timestampText = if (firstLine.startsWith("[")) {
            firstLine.substringAfter("[", "").substringBefore("]")
        } else {
            block.lineSequence().firstOrNull { it.startsWith("Timestamp:") }
                ?.substringAfter("Timestamp:")?.trim().orEmpty()
        }
        if (cutoff != Long.MIN_VALUE) {
            val timestamp = try { formatter.parse(timestampText)?.time } catch (_: Exception) { null }
            if (timestamp == null || timestamp < cutoff) return@filter false
        }
        val parts = firstLine.split("] [")
        val component = if (parts.size >= 4) {
            parts[3].substringBefore("]").removePrefix("ERROR:")
        } else if (block.contains("UNCAUGHT EXCEPTION CRASH REPORT")) {
            "CRASH"
        } else ""
        when (subsystem) {
            "Core" -> component in setOf("LogKeeper", "Lifecycle", "StartupDiagnostics", "Process", "Execution", "CRASH") ||
                component.startsWith("Core") || block.contains("com.example.core.")
            "Launcher" -> component.contains("launcher", true) || component.contains("home", true) ||
                component in setOf("MainActivity", "AllAppsFragment", "LauncherSettings") ||
                block.contains("shura.home.")
            "Floating Mini-apps" -> FloatingMiniAppLogFilter.isMiniAppLogEntry(block)
            "Firewall" -> FirewallLogFilter.isFirewallLogEntry(block)
            else -> true
        }
    }
    return if (filtered.isEmpty()) "No matching logs found." else filtered.joinToString("\n\n")
}
fun formatExecutionSummary(events: List<LogKeeper.ExecutionEvent>): String {
    if (events.isEmpty()) return "No structured execution events have been recorded yet.\nInstrumented operations will appear here with their start/end times and outcomes."
    return events.sortedByDescending { it.timestampMillis }.joinToString("\n") {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(it.timestampMillis))
        "[$time] [${it.process}] [${it.event}] [${it.component}/${it.operation}] PID=${it.pid} Thread=${it.thread} ${it.detail}".trim()
    }
}

@Composable
fun ExecutionHistoryTab(events: List<LogKeeper.ExecutionEvent>) {
    val ordered = remember(events) { events.sortedByDescending { it.timestampMillis } }
    if (ordered.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "No structured execution history yet. Activity lifecycle and explicitly instrumented operations will appear here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(ordered) { event ->
            OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(event.component + " / " + event.operation, fontWeight = FontWeight.SemiBold)
                        StatusPill(
                            text = event.event,
                            containerColor = if (event.event == "FAILED" || event.event == "OVERLAP") Color(0xFFFFEBEE) else Color(0xFFE8F5E9),
                            contentColor = if (event.event == "FAILED" || event.event == "OVERLAP") Color(0xFFC62828) else Color(0xFF2E7D32)
                        )
                    }
                    Text(
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(event.timestampMillis)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${event.process} • PID ${event.pid} • ${event.thread}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (event.detail.isNotBlank()) Text(event.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

fun getEventTypeColors(eventType: String): Pair<Color, Color> {
    return when (eventType) {
        "STARTED" -> Pair(Color(0xFFE8F5E9), Color(0xFF2E7D32))
        "STOPPED" -> Pair(Color(0xFFFFF3E0), Color(0xFFE65100))
        "DESTROYED" -> Pair(Color(0xFFFFEBEE), Color(0xFFC62828))
        "CONNECTED" -> Pair(Color(0xFFE3F2FD), Color(0xFF1565C0))
        "DISCONNECTED" -> Pair(Color(0xFFF5F5F5), Color(0xFF616161))
        "RESTARTED" -> Pair(Color(0xFFEDE7F6), Color(0xFF512DA8))
        "CRASH", "ERROR" -> Pair(Color(0xFFFFEBEE), Color(0xFFC62828))
        else -> Pair(Color(0xFFF5F5F5), Color(0xFF424242))
    }
}

fun loadRunningState(context: Context, diagnosticLogs: String): RunningRuntimeState {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    // 1. Check Processes
    val runningProcesses = am?.runningAppProcesses ?: emptyList()
    val mainProc = runningProcesses.firstOrNull { it.processName == context.packageName }
    val homeProc = runningProcesses.firstOrNull { it.processName == "${context.packageName}:home" }
    val heavyProc = runningProcesses.firstOrNull { it.processName == "${context.packageName}:heavy" }

    val processesList = listOf(
        ActiveProcessItem(
            name = "Main Process",
            isMain = true,
            isHeavy = false,
            pid = mainProc?.pid ?: 0,
            importance = mainProc?.let { formatImportance(it.importance) } ?: "Not Running",
            isRunning = mainProc != null
        ),
        ActiveProcessItem(
            name = "Home Process",
            isMain = false,
            isHeavy = false,
            pid = homeProc?.pid ?: 0,
            importance = homeProc?.let { formatImportance(it.importance) } ?: "Stopped / Idle",
            isRunning = homeProc != null
        ),
        ActiveProcessItem(
            name = "Heavy Process",
            isMain = false,
            isHeavy = true,
            pid = heavyProc?.pid ?: 0,
            importance = heavyProc?.let { formatImportance(it.importance) } ?: "Stopped / Idle",
            isRunning = heavyProc != null
        )
    )

    // 2. Check Active Services
    val activeServicesList = mutableListOf<ActiveServiceItem>()
    try {
        val services = am?.getRunningServices(Int.MAX_VALUE) ?: emptyList()
        val appServices = services.filter { it.service.packageName == context.packageName }
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

        for (s in appServices) {
            val shortName = s.service.shortClassName.removePrefix(".")
            val isMain = s.process == context.packageName
            val uptimeMs = if (s.activeSince > 0) (nowElapsed - s.activeSince).coerceAtLeast(0L) else 0L
            val startWallMs = if (uptimeMs > 0) nowWall - uptimeMs else 0L
            val startTimeStr = if (startWallMs > 0) timeFormat.format(Date(startWallMs)) else "Active"
            val durationStr = if (uptimeMs > 0) formatDuration(uptimeMs) else "< 1s"

            activeServicesList.add(
                ActiveServiceItem(
                    shortName = shortName,
                    fullName = s.service.className,
                    processName = s.process,
                    isMainProcess = isMain,
                    isForeground = s.foreground,
                    started = s.started,
                    activeSinceMs = s.activeSince,
                    startTimeFormatted = startTimeStr,
                    durationFormatted = durationStr
                )
            )
        }
    } catch (_: Exception) {}

    // 3. Parse Lifecycle Events from diagnostic log
    val eventsList = mutableListOf<LifecycleEventItem>()
    val lineRegex = Regex("""^\[(.*?)\]\s+\[(.*?)\]\s+\[(.*?)\]\s+\[(.*?)\]\s+(.*)$""")
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    val lastStartTimes = mutableMapOf<String, Long>()

    val lines = diagnosticLogs.lines()
    for (line in lines) {
        if (line.isBlank() || line.startsWith("[... logs truncated ...]")) continue
        val match = lineRegex.find(line.trim()) ?: continue
        val timestamp = match.groupValues[1]
        val process = match.groupValues[2]
        val thread = match.groupValues[3]
        val rawComponent = match.groupValues[4]
        val message = match.groupValues[5]

        val isMain = !process.endsWith(":heavy")
        val isCrash = rawComponent == "CRASH" || message.contains("[CRASH]")
        val isError = rawComponent.startsWith("ERROR") || message.contains("[ERROR")

        var component = rawComponent
        if (component.startsWith("ERROR:")) {
            component = component.removePrefix("ERROR:")
        }

        val eventType: String
        var durationText: String? = null
        val eventTime = try { sdf.parse(timestamp)?.time } catch (_: Exception) { null }

        when {
            isCrash -> eventType = "CRASH"
            isError -> eventType = "ERROR"
            component == "Lifecycle" -> {
                when {
                    message.contains("started", ignoreCase = true) -> {
                        eventType = "STARTED"
                        val compName = message.substringAfter("started:").trim()
                        if (eventTime != null && compName.isNotEmpty()) {
                            lastStartTimes[compName] = eventTime
                        }
                    }
                    message.contains("stopped", ignoreCase = true) -> {
                        eventType = "STOPPED"
                        val compName = message.substringAfter("stopped:").trim()
                        if (eventTime != null && compName.isNotEmpty()) {
                            val start = lastStartTimes[compName]
                            if (start != null && eventTime >= start) {
                                durationText = formatDuration(eventTime - start)
                            }
                        }
                    }
                    message.contains("destroyed", ignoreCase = true) -> {
                        eventType = "DESTROYED"
                        val compName = message.substringAfter("destroyed:").trim()
                        if (eventTime != null && compName.isNotEmpty()) {
                            val start = lastStartTimes[compName]
                            if (start != null && eventTime >= start) {
                                durationText = formatDuration(eventTime - start)
                            }
                            lastStartTimes.remove(compName)
                        }
                    }
                    else -> eventType = "LIFECYCLE"
                }
            }
            component == "Notification" -> {
                when {
                    message.contains("connected", ignoreCase = true) -> {
                        eventType = "CONNECTED"
                        if (eventTime != null) lastStartTimes["NotificationListener"] = eventTime
                    }
                    message.contains("disconnected", ignoreCase = true) -> {
                        eventType = "DISCONNECTED"
                        if (eventTime != null) {
                            val start = lastStartTimes["NotificationListener"]
                            if (start != null && eventTime >= start) {
                                durationText = formatDuration(eventTime - start)
                            }
                        }
                    }
                    else -> eventType = "NOTIFICATION"
                }
            }
            component == "LogKeeper" && message.contains("Initialized logger", ignoreCase = true) -> {
                eventType = "STARTED"
            }
            component == "StartupDiagnostics" -> {
                eventType = "STARTED"
            }
            message.contains("restarted", ignoreCase = true) || message.contains("restart", ignoreCase = true) -> {
                eventType = "RESTARTED"
            }
            message.contains("start", ignoreCase = true) -> {
                eventType = "STARTED"
            }
            message.contains("stop", ignoreCase = true) -> {
                eventType = "STOPPED"
            }
            else -> continue
        }

        eventsList.add(
            LifecycleEventItem(
                timestamp = timestamp.substringAfter(" ").substringBeforeLast("."),
                process = when {
                    isMain -> "Main"
                    process.endsWith(":home") -> "Home"
                    else -> "Heavy"
                },
                isMainProcess = isMain,
                thread = thread,
                component = component,
                eventType = eventType,
                message = message,
                durationText = durationText
            )
        )
    }

    return RunningRuntimeState(
        processes = processesList,
        activeServices = activeServicesList,
        lifecycleEvents = eventsList.asReversed()
    )
}

fun formatRunningSummary(state: RunningRuntimeState): String {
    val sb = StringBuilder()
    sb.append("=== RUNTIME PROCESSES ===\n")
    for (p in state.processes) {
        val status = if (p.isRunning) "RUNNING (PID: ${p.pid}, ${p.importance})" else "STOPPED / IDLE"
        sb.append("- ${p.name}: $status\n")
    }
    sb.append("\n=== ACTIVE SERVICES (${state.activeServices.size}) ===\n")
    if (state.activeServices.isEmpty()) {
        sb.append("No active background services.\n")
    } else {
        for (s in state.activeServices) {
            val fg = if (s.isForeground) "Foreground" else "Background"
            val proc = if (s.isMainProcess) "Main" else "Heavy"
            sb.append("- ${s.shortName} [$proc] [$fg] | Started: ${s.startTimeFormatted} | Uptime: ${s.durationFormatted}\n")
        }
    }
    sb.append("\n=== RECENT LIFECYCLE EVENTS (${state.lifecycleEvents.size}) ===\n")
    if (state.lifecycleEvents.isEmpty()) {
        sb.append("No lifecycle events recorded.\n")
    } else {
        for (e in state.lifecycleEvents.take(50)) {
            val dur = if (e.durationText != null) " (Duration: ${e.durationText})" else ""
            sb.append("[${e.timestamp}] [${e.process}] [${e.eventType}] [${e.component}] ${e.message}$dur\n")
        }
    }
    return sb.toString()
}

fun formatImportance(importance: Int): String {
    return when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "Foreground"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "Foreground Service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "Visible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "Perceptible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE -> "Cant Save State"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "Service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "Cached"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "Terminated"
        else -> "Priority: $importance"
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0s"
    val seconds = (ms / 1000) % 60
    val minutes = (ms / (1000 * 60)) % 60
    val hours = (ms / (1000 * 60 * 60))
    val days = (ms / (1000 * 60 * 60 * 24))

    return when {
        days > 0 -> "${days}d ${hours % 24}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

