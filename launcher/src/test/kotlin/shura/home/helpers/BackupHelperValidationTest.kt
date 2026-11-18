package shura.home.helpers

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupHelperValidationTest {
    private fun item(id: Long) = JSONObject()
        .put("id", id)
        .put("type", 0)
        .put("left", 0)
        .put("right", 1)
        .put("top", 0)
        .put("bottom", 1)
        .put("page", 0)
        .put("package_name", "com.example.app")
        .put("activity_name", "")
        .put("class_name", "")
        .put("title", "Example")

    @Test
    fun rejectsDuplicatePersistentItemIdsBeforeRestore() {
        val root = JSONObject()
            .put("grid_items", JSONArray().put(item(7L)).put(item(7L)))
            .put("config", JSONObject())

        val failure = runCatching { BackupHelper.validateBackupPayload(root) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun acceptsEmptyGridAndLegacyOptionalCollections() {
        val root = JSONObject()
            .put("grid_items", JSONArray())
            .put("config", JSONObject())

        BackupHelper.validateBackupPayload(root)
    }

    @Test
    fun validatesFolderParentIdentifiers() {
        val invalid = item(8L).put("parent_id", -4L)
        val root = JSONObject()
            .put("grid_items", JSONArray().put(invalid))
            .put("config", JSONObject())

        val failure = runCatching { BackupHelper.validateBackupPayload(root) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
