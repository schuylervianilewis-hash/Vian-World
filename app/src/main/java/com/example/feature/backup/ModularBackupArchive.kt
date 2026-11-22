package com.example.feature.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Versioned, bounded ZIP container for independently-restorable Vian World modules.
 *
 * Only known JSON payload paths are accepted. The archive is validated in full before
 * a caller receives payloads, so no restore adapter can write while ZIP parsing is in
 * progress.
 */
object ModularBackupArchive {
    const val FORMAT_NAME = "vian-world-modular-backup"
    const val FORMAT_VERSION = 1

    const val MODULE_LAUNCHER = "launcher"
    const val MODULE_FIREWALL = "firewall"
    const val MODULE_SIDEBAR = "sidebar"

    private const val MANIFEST_PATH = "manifest.json"
    private const val CHECKSUMS_PATH = "checksums.json"
    private const val MAX_ENTRIES = 8
    private const val MAX_ENTRY_BYTES = 24 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64 * 1024 * 1024
    private const val MAX_COMPRESSION_RATIO = 200L
    private const val COMPRESSION_RATIO_SLACK_BYTES = 1024L * 1024L

    private val modulePaths = linkedMapOf(
        MODULE_LAUNCHER to "launcher/launcher.json",
        MODULE_FIREWALL to "firewall/firewall.json",
        MODULE_SIDEBAR to "sidebar/sidebar.json"
    )
    private val currentModuleSchemas = mapOf(
        MODULE_LAUNCHER to 2,
        MODULE_FIREWALL to 1,
        MODULE_SIDEBAR to 2
    )

    data class Parsed(
        val applicationId: String,
        val appVersion: String,
        val versionCode: Long,
        val buildType: String,
        val createdAt: Long,
        val modules: Map<String, JSONObject>,
        val moduleSchemaVersions: Map<String, Int>
    )

    fun write(
        output: OutputStream,
        applicationId: String,
        appVersion: String,
        versionCode: Long,
        buildType: String,
        payloads: Map<String, JSONObject>
    ) {
        require(applicationId.isNotBlank()) { "Application ID must not be blank" }
        require(payloads.isNotEmpty()) { "Select at least one backup module" }
        require(payloads.keys.all { it in modulePaths }) { "Unknown backup module" }

        val encodedPayloads = linkedMapOf<String, ByteArray>()
        payloads.toSortedMap().forEach { (moduleId, json) ->
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            require(bytes.isNotEmpty() && bytes.size <= MAX_ENTRY_BYTES) {
                "Invalid or oversized $moduleId backup payload"
            }
            encodedPayloads[modulePaths.getValue(moduleId)] = bytes
        }

        require(encodedPayloads.values.sumOf { it.size.toLong() } <= MAX_TOTAL_BYTES) {
            "Selected backup modules exceed the archive size limit"
        }
        val checksums = JSONObject()
        encodedPayloads.forEach { (path, bytes) -> checksums.put(path, sha256(bytes)) }

        val modulesJson = JSONArray()
        payloads.keys.sorted().forEach { moduleId ->
            modulesJson.put(
                JSONObject()
                    .put("id", moduleId)
                    .put("schemaVersion", currentModuleSchemas.getValue(moduleId))
                    .put("path", modulePaths.getValue(moduleId))
            )
        }

        val manifest = JSONObject()
            .put("format", FORMAT_NAME)
            .put("formatVersion", FORMAT_VERSION)
            .put("applicationId", applicationId)
            .put("appVersion", appVersion)
            .put("versionCode", versionCode)
            .put("buildType", buildType)
            .put("createdAt", System.currentTimeMillis())
            .put("modules", modulesJson)

        ZipOutputStream(output).use { zip ->
            putEntry(zip, MANIFEST_PATH, manifest.toString().toByteArray(Charsets.UTF_8))
            encodedPayloads.forEach { (path, bytes) -> putEntry(zip, path, bytes) }
            putEntry(zip, CHECKSUMS_PATH, checksums.toString().toByteArray(Charsets.UTF_8))
            zip.finish()
        }
    }

    fun read(input: InputStream, expectedApplicationId: String): Parsed {
        val entries = linkedMapOf<String, ByteArray>()
        var totalBytes = 0

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "Directory entries are not allowed in a backup" }
                require(isSafePath(entry.name)) { "Unsafe ZIP entry path" }
                require(entry.name in allowedPaths()) { "Unexpected backup entry: ${entry.name}" }
                require(!entries.containsKey(entry.name)) { "Duplicate ZIP entry: ${entry.name}" }
                require(entries.size < MAX_ENTRIES) { "Too many ZIP entries" }
                if (entry.size >= 0L)
                    require(entry.size <= MAX_ENTRY_BYTES.toLong()) { "ZIP entry exceeds size limit" }

                val bytes = readBounded(zip, MAX_ENTRY_BYTES)
                totalBytes += bytes.size
                require(totalBytes <= MAX_TOTAL_BYTES) { "Backup archive is too large" }
                zip.closeEntry()
                val compressedSize = entry.compressedSize
                if (compressedSize > 0L) {
                    val allowedExpandedSize =
                        compressedSize * MAX_COMPRESSION_RATIO + COMPRESSION_RATIO_SLACK_BYTES
                    require(bytes.size.toLong() <= allowedExpandedSize) {
                        "ZIP entry compression ratio is too high"
                    }
                }
                entries[entry.name] = bytes
            }
        }

        val manifestBytes = entries[MANIFEST_PATH] ?: error("Backup manifest is missing")
        val checksumBytes = entries[CHECKSUMS_PATH] ?: error("Backup checksums are missing")
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        require(manifest.optString("format") == FORMAT_NAME) { "This is not a Vian World backup" }
        val applicationId = manifest.optString("applicationId")
        require(applicationId == expectedApplicationId) {
            "This backup belongs to a different application: $applicationId"
        }
        val version = manifest.optInt("formatVersion", -1)
        require(version == FORMAT_VERSION) { "Unsupported backup format version: $version" }

        val moduleArray = manifest.optJSONArray("modules") ?: error("Backup module list is missing")
        require(moduleArray.length() in 1..modulePaths.size) { "Invalid backup module count" }

        val declared = linkedMapOf<String, Int>()
        val expectedPayloadPaths = linkedSetOf<String>()
        for (index in 0 until moduleArray.length()) {
            val item = moduleArray.optJSONObject(index) ?: error("Invalid module descriptor")
            val id = item.optString("id")
            require(id in modulePaths) { "Unknown module in backup: $id" }
            require(!declared.containsKey(id)) { "Duplicate module descriptor: $id" }
            val schemaVersion = item.optInt("schemaVersion", -1)
            require(schemaVersion in 1..currentModuleSchemas.getValue(id)) {
                "Unsupported $id module schema: $schemaVersion"
            }
            val path = item.optString("path")
            require(path == modulePaths[id]) { "Invalid payload path for $id" }
            declared[id] = schemaVersion
            expectedPayloadPaths.add(path)
        }

        val expectedEntries = expectedPayloadPaths + setOf(MANIFEST_PATH, CHECKSUMS_PATH)
        require(entries.keys == expectedEntries) { "Archive entries do not match the manifest" }

        val checksums = JSONObject(String(checksumBytes, Charsets.UTF_8))
        require(checksums.length() == expectedPayloadPaths.size) { "Invalid checksum list" }

        val payloads = linkedMapOf<String, JSONObject>()
        for ((id, schemaVersion) in declared) {
            val path = modulePaths.getValue(id)
            val bytes = entries[path] ?: error("Payload missing for $id")
            val expectedHash = checksums.optString(path)
            require(expectedHash.matches(Regex("^[a-f0-9]{64}$"))) { "Invalid checksum for $id" }
            require(constantTimeEquals(expectedHash, sha256(bytes))) { "Checksum mismatch for $id" }
            val payload = JSONObject(String(bytes, Charsets.UTF_8))
            val payloadSchema = when (id) {
                MODULE_LAUNCHER -> payload.optInt("schema_version", -1)
                else -> payload.optInt("schemaVersion", -1)
            }
            require(payloadSchema == schemaVersion) {
                "The $id payload schema does not match its manifest descriptor"
            }
            payloads[id] = payload
        }

        return Parsed(
            applicationId = applicationId,
            appVersion = manifest.optString("appVersion", "unknown"),
            versionCode = manifest.optLong("versionCode", -1L),
            buildType = manifest.optString("buildType", "unknown"),
            createdAt = manifest.optLong("createdAt", 0L),
            modules = payloads,
            moduleSchemaVersions = declared
        )
    }

    private fun allowedPaths(): Set<String> =
        modulePaths.values.toSet() + setOf(MANIFEST_PATH, CHECKSUMS_PATH)

    private fun isSafePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/") || path.startsWith("\\")) return false
        if (path.contains('\\')) return false
        val parts = path.split('/')
        return parts.none { it.isBlank() || it == "." || it == ".." } &&
            !path.contains(':')
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var count = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            require(count <= limit) { "ZIP entry exceeds size limit" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun putEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        if (left.length != right.length) return false
        var diff = 0
        for (index in left.indices) diff = diff or (left[index].code xor right[index].code)
        return diff == 0
    }
}
