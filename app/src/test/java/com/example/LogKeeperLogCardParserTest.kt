package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogKeeperLogCardParserTest {
    @Test
    fun parsesStructuredLineAndKeepsContinuationLines() {
        val raw = "[2026-11-20 10:00:00.000] [com.example:heavy] [main] [ERROR:HeavyHost] Operation failed\njava.lang.IllegalStateException: broken"
        val entry = parseLogKeeperDisplayEntries(raw).single()
        assertEquals("HeavyHost", entry.component)
        assertEquals("ERROR", entry.level)
        assertEquals("Operation failed", entry.message)
        assertTrue(entry.detail.contains("IllegalStateException"))
        assertEquals("com.example:heavy", entry.process)
    }

    @Test
    fun parsesCrashReportBlock() {
        val raw = """
            ================================================================
            === UNCAUGHT EXCEPTION CRASH REPORT ===
            Timestamp: 2026-11-20 10:01:00.000
            Process: com.example
            Thread: main
            Exception: java.lang.IllegalStateException
            Message: bad state
            StackTrace:
            at com.example.Main.run(Main.kt:1)
            ================================================================
        """.trimIndent()
        val entry = parseLogKeeperDisplayEntries(raw).single()
        assertEquals("CRASH", entry.component)
        assertEquals("FATAL", entry.level)
        assertEquals("bad state", entry.message)
        assertTrue(entry.detail.contains("Main.run"))
    }

    @Test
    fun emptyLogTextProducesNoCards() {
        assertTrue(parseLogKeeperDisplayEntries("No records in LiteReader_Log.txt.").isEmpty())
        assertTrue(parseLogKeeperDisplayEntries("No matching logs found.").isEmpty())
    }
}
