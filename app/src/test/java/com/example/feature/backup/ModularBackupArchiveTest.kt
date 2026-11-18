package com.example.feature.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModularBackupArchiveTest {
    @Test
    fun roundTripsIndependentlySelectedModules() {
        val output = ByteArrayOutputStream()
        ModularBackupArchive.write(
            output,
            "com.example.vianworld.test",
            "test-version",
            42L,
            "debug",
            mapOf(
                ModularBackupArchive.MODULE_LAUNCHER to JSONObject().put("schema_version", 2),
                ModularBackupArchive.MODULE_SIDEBAR to JSONObject().put("schemaVersion", 1)
            )
        )

        val parsed = ModularBackupArchive.read(ByteArrayInputStream(output.toByteArray()), "com.example.vianworld.test")

        assertEquals("com.example.vianworld.test", parsed.applicationId)
        assertEquals("test-version", parsed.appVersion)
        assertEquals(42L, parsed.versionCode)
        assertEquals("debug", parsed.buildType)
        assertEquals(setOf("launcher", "sidebar"), parsed.modules.keys)
        assertEquals(2, parsed.modules.getValue("launcher").getInt("schema_version"))
        assertEquals(1, parsed.moduleSchemaVersions.getValue("sidebar"))
    }

    @Test
    fun rejectsUnexpectedZipPathBeforeReturningPayloads() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("../outside.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }

        val failure = runCatching {
            ModularBackupArchive.read(ByteArrayInputStream(output.toByteArray()))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun rejectsArchiveWithoutRequiredManifest() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("launcher/launcher.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }

        val failure = runCatching {
            ModularBackupArchive.read(ByteArrayInputStream(output.toByteArray()))
        }.exceptionOrNull()

        assertTrue(failure != null)
    }
}
