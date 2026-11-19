package com.example

import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class LogKeeperFilterTest {
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Test
    fun subsystemPillsFilterWholeLogBlocks() {
        val raw = """
            [2026-11-20 09:00:00.000] [com.example] [main] [Firewall] firewall event
            [2026-11-20 09:00:01.000] [com.example] [main] [LogKeeper] logger event
        """.trimIndent()

        val firewall = filterLogText(raw, "All", "Firewall")
        assertTrue(firewall.contains("firewall event"))
        assertTrue(!firewall.contains("logger event"))

        val core = filterLogText(raw, "All", "Core")
        assertTrue(core.contains("logger event"))
        assertTrue(!core.contains("firewall event"))
    }

    @Test
    fun timePillsExcludeOlderEntries() {
        val now = formatter.parse("2026-11-20 10:00:00.000")!!.time
        val raw = """
            [2026-11-20 08:00:00.000] [com.example] [main] [Firewall] old event
            [2026-11-20 09:30:00.000] [com.example] [main] [Firewall] recent event
        """.trimIndent()

        val filtered = filterLogText(raw, "1h", "All", now)
        assertTrue(filtered.contains("recent event"))
        assertTrue(!filtered.contains("old event"))
    }
    @Test
    fun crashReportTimestampIsUsedByTimeFilters() {
        val now = formatter.parse("2026-11-20 10:00:00.000")!!.time
        val raw = """
            ================================================================
            === UNCAUGHT EXCEPTION CRASH REPORT ===
            Timestamp: 2026-11-20 09:45:00.000
            Process: com.example
            Thread: main
            Exception: java.lang.IllegalStateException
            ================================================================
        """.trimIndent()

        val filtered = filterLogText(raw, "1h", "Core", now)
        assertTrue(filtered.contains("UNCAUGHT EXCEPTION CRASH REPORT"))
    }

}
