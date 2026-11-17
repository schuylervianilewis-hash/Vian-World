package com.example.core

/** Identifies Firewall/Shield bridge records and Firewall stack traces. */
object FirewallLogFilter {
    const val EMPTY_STATE_MESSAGE = "No Firewall logs found."

    fun isFirewallLogEntry(block: String): Boolean =
        block.contains("[Firewall]") ||
            block.contains("[ERROR:Firewall]") ||
            block.contains("eu.faircode.netguard.") ||
            block.contains("net.kollnig.missioncontrol.")

    fun filter(rawLogs: String, enabled: Boolean): String {
        if (!enabled) return rawLogs
        if (rawLogs.isBlank() || rawLogs.startsWith("No records in ")) return EMPTY_STATE_MESSAGE
        val matches = FloatingMiniAppLogFilter.splitIntoBlocks(rawLogs).filter(::isFirewallLogEntry)
        return if (matches.isEmpty()) EMPTY_STATE_MESSAGE else matches.joinToString("\n\n")
    }
}
