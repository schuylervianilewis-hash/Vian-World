package com.example.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogKeeperSanitizeTest {

    @Test
    fun ordinaryTextUsesFastPathUnchanged() {
        val message = "Floating handle attached successfully"
        assertEquals(message, LogKeeper.sanitize(message))
    }

    @Test
    fun credentialValuesAreRedactedWhenHintsArePresent() {
        val sanitized = LogKeeper.sanitize(
            "https://example.test/callback?token=topsecret Authorization: Bearer abc.def.ghi"
        )
        assertTrue(sanitized.contains("token=[REDACTED]"))
        assertTrue(!sanitized.contains("topsecret"))
        assertTrue(!sanitized.contains("abc.def.ghi"))
    }
}
