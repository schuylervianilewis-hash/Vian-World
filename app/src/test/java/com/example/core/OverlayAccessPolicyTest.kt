package com.example.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayAccessPolicyTest {
    @Test fun packagesAreAllowedUnlessExplicitlyExcluded() {
        val excluded = setOf("com.example.blocked")
        assertTrue(OverlayAccessPolicy.isPackageAllowed("com.example.allowed", excluded))
        assertFalse(OverlayAccessPolicy.isPackageAllowed("com.example.blocked", excluded))
    }
    @Test fun newlyInstalledPackagesAreAllowedByDefault() {
        assertTrue(OverlayAccessPolicy.isPackageAllowed("com.example.newapp", emptySet()))
    }
    @Test fun disablingAllowOverlaysAddsOnlyThatPackage() {
        assertEquals(
            setOf("com.example.existing", "com.example.new"),
            OverlayAccessPolicy.updatedExcludedPackages(setOf("com.example.existing"), "com.example.new", false)
        )
    }
    @Test fun enablingAllowOverlaysRemovesOnlyThatPackage() {
        assertEquals(
            setOf("com.example.keep"),
            OverlayAccessPolicy.updatedExcludedPackages(setOf("com.example.keep", "com.example.allow"), "com.example.allow", true)
        )
    }
}
