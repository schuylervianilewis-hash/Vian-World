package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.FloatingMiniAppLogFilter
import com.example.core.LogKeeper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogKeeperDisplayEntry(
    val key: String,
    val timestamp: String,
    val process: String,
    val thread: String,
    val component: String,
    val level: String,
    val message: String,
    val detail: String
)

/** Parse legacy log blocks for display; keep multiline stack traces attached to their event. */
fun parseLogKeeperDisplayEntries(raw: String): List<LogKeeperDisplayEntry> {
    if (raw.isBlank() || raw.startsWith("No records") || raw.startsWith("No matching logs")) return emptyList()
    return FloatingMiniAppLogFilter.splitIntoBlocks(raw).mapNotNull { block ->
        val lines = block.lines()
        val first = lines.firstOrNull()?.trim().orEmpty()
        if (first.isBlank()) return@mapNotNull null

        if (block.contains("UNCAUGHT EXCEPTION CRASH REPORT")) {
            fun field(name: String): String = lines.firstOrNull { it.startsWith("$name:") }
                ?.substringAfter(":")?.trim().orEmpty()
            val timestamp = field("Timestamp")
            val process = field("Process")
            val thread = field("Thread")
            val exception = field("Exception")
            val message = field("Message").ifBlank { exception.ifBlank { "Uncaught exception" } }
            val detailStart = lines.indexOfFirst { it.startsWith("StackTrace:") }
            val detail = if (detailStart >= 0) lines.drop(detailStart + 1)
                .takeWhile { !it.contains("====") }.joinToString("\n").trim() else ""
            return@mapNotNull LogKeeperDisplayEntry(
                "crash:$timestamp:$process:$exception", timestamp, process, thread,
                "CRASH", "FATAL", message, detail
            )
        }

        if (!first.startsWith("[")) return@mapNotNull null
        val fields = ArrayList<String>(4)
        var cursor = 0
        repeat(4) {
            if (cursor >= first.length || first[cursor] != '[') return@repeat
            val end = first.indexOf(']', cursor + 1)
            if (end < 0) return@repeat
            fields.add(first.substring(cursor + 1, end))
            cursor = end + 1
            while (cursor < first.length && first[cursor] == ' ') cursor++
        }
        if (fields.size < 4) return@mapNotNull null
        val component = fields[3]
        val message = first.substring(cursor).trim()
        val detail = lines.drop(1).joinToString("\n").trim()
        val level = when {
            component.startsWith("ERROR:", true) || component.contains("CRASH", true) -> "ERROR"
            message.contains("WARN", true) -> "WARN"
            else -> "INFO"
        }
        val key = "${fields[0]}:${fields[1]}:${component}:${message.hashCode()}"
        LogKeeperDisplayEntry(
            key, fields[0], fields[1], fields[2], component.removePrefix("ERROR:"),
            level, message.ifBlank { "(no message)" }, detail
        )
    }
}

@Composable
fun LogKeeperLogList(raw: String, emptyMessage: String, modifier: Modifier = Modifier) {
    val entries = remember(raw) { parseLogKeeperDisplayEntries(raw) }
    if (entries.isEmpty()) {
        Box(modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(
        modifier = modifier.testTag("log_entry_list"),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        items(entries.asReversed(), key = { it.key }) { entry -> LogKeeperLogCard(entry) }
    }
}

@Composable
private fun LogKeeperLogCard(entry: LogKeeperDisplayEntry) {
    val context = LocalContext.current
    var expanded by remember(entry.key) { mutableStateOf(false) }
    val (container, foreground) = when (entry.level) {
        "FATAL", "ERROR" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        "WARN" -> Color(0xFFFFE0B2) to Color(0xFFE65100)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f) to MaterialTheme.colorScheme.onSurface
    }
    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.timestamp, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = foreground.copy(alpha = 0.78f))
                    Text(entry.component, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(shape = RoundedCornerShape(5.dp), color = foreground.copy(alpha = 0.12f)) {
                    Text(entry.level, color = foreground, fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(entry.message, color = foreground, fontSize = 12.sp)
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text("${entry.process} • ${entry.thread}", color = foreground.copy(alpha = 0.78f), fontSize = 10.sp)
                if (entry.detail.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(entry.detail, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = foreground, lineHeight = 13.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("LogKeeperEntry", buildString {
                            appendLine("[${entry.timestamp}] [${entry.process}] [${entry.thread}] [${entry.component}] ${entry.message}")
                            if (entry.detail.isNotBlank()) append(entry.detail)
                        }))
                        Toast.makeText(context, "Log entry copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy", fontSize = 11.sp) }
                }
            }
        }
    }
}

@Composable
fun ActiveSubsystemsCard(events: List<LogKeeper.ExecutionEvent>, expanded: Boolean, onToggle: () -> Unit) {
    val active = remember(events) {
        events.groupBy { it.id }.values.mapNotNull { group ->
            val latest = group.filter { it.event != "OVERLAP" }.maxByOrNull { it.timestampMillis }
            latest?.takeIf { it.event == "STARTED" }
        }.sortedBy { it.component + it.operation }
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = CircleShape, color = if (active.isEmpty()) Color(0xFF757575) else Color(0xFF4CAF50), modifier = Modifier.size(8.dp)) {}
                    Text("Active Subsystems (${active.size})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                }
                Text(if (expanded) "Hide ▲" else "Show ▼", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                if (active.isEmpty()) {
                    Text("No tracked operations currently active.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                } else active.forEach { event ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${event.component} / ${event.operation}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("${event.process} • PID ${event.pid}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(event.timestampMillis)), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
