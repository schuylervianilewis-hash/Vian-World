package com.example.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturePermissionChecksTest {
    @Test
    fun preAndroid13UsesAppNotificationSettingWithoutRuntimePrompt() {
        assertFalse(FeaturePermissionChecks.needsRuntimePermission(32, false))
        assertTrue(FeaturePermissionChecks.hasNotificationPermission(true, true))
        assertFalse(FeaturePermissionChecks.hasNotificationPermission(true, false))
    }

    @Test
    fun android13AndLaterRequiresRuntimeGrantAndEnabledNotifications() {
        assertTrue(FeaturePermissionChecks.needsRuntimePermission(33, false))
        assertFalse(FeaturePermissionChecks.needsRuntimePermission(33, true))
        assertFalse(FeaturePermissionChecks.hasNotificationPermission(false, true))
        assertFalse(FeaturePermissionChecks.hasNotificationPermission(true, false))
        assertTrue(FeaturePermissionChecks.hasNotificationPermission(true, true))
    }
}
