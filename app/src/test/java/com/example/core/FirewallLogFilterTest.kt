package com.example.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirewallLogFilterTest {
    @Test
    fun identifiesFirewallBridgeEntriesAndStackTraces() {
        assertTrue(FirewallLogFilter.isFirewallLogEntry("[app] [Firewall] [ServiceSinkhole] VPN started"))
        assertTrue(FirewallLogFilter.isFirewallLogEntry("at eu.faircode.netguard.ServiceSinkhole.onCreate(ServiceSinkhole.java:1)"))
        assertTrue(FirewallLogFilter.isFirewallLogEntry("at net.kollnig.missioncontrol.ActivityMain.onCreate(ActivityMain.java:1)"))
        assertFalse(FirewallLogFilter.isFirewallLogEntry("[app] [HandleService] Handles active"))
    }

    @Test
    fun filtersFirewallBlocksAndPreservesMultilineStackTraces() {
        val raw = """
            [2026-11-17 12:00:00.000] [app] [main] [Firewall] [ServiceSinkhole] VPN start failed
            java.lang.IllegalStateException: failed
            at eu.faircode.netguard.ServiceSinkhole.start(ServiceSinkhole.java:1)

            [2026-11-17 12:00:01.000] [app] [main] [HandleService] Handles active
        """.trimIndent()
        val filtered = FirewallLogFilter.filter(raw, true)
        assertTrue(filtered.contains("VPN start failed"))
        assertTrue(filtered.contains("IllegalStateException"))
        assertFalse(filtered.contains("Handles active"))
        assertEquals(raw, FirewallLogFilter.filter(raw, false))
    }
}
