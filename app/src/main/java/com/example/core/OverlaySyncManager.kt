package com.example.core

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object OverlaySyncManager {
    const val ACTION_SYNC_PREF = "com.example.ACTION_SYNC_PREF"
    const val EXTRA_KEY = "extra_key"
    const val EXTRA_VALUE = "extra_value"
    const val EXTRA_TYPE = "extra_type"
    const val EXTRA_PREF_NAME = "extra_pref_name"

    fun syncString(context: Context, key: String, value: String, prefName: String = "FloatingReaderPrefs") {
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        prefs.edit().putString(key, value).commit()
        sendSync(context, key, value, "STRING", prefName)
    }

    fun syncInt(context: Context, key: String, value: Int, prefName: String = "FloatingReaderPrefs") {
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        prefs.edit().putInt(key, value).commit()
        sendSync(context, key, value.toString(), "INT", prefName)
    }

    fun syncBoolean(context: Context, key: String, value: Boolean, prefName: String = "FloatingReaderPrefs") {
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(key, value).commit()
        sendSync(context, key, value.toString(), "BOOLEAN", prefName)
    }

    fun syncFloat(context: Context, key: String, value: Float, prefName: String = "FloatingReaderPrefs") {
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        prefs.edit().putFloat(key, value).commit()
        sendSync(context, key, value.toString(), "FLOAT", prefName)
    }

    fun syncStringSet(context: Context, key: String, set: Set<String>, prefName: String = "FloatingReaderPrefs") {
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(key, set).commit()
        sendSync(context, key, set.joinToString("|||"), "STRING_SET", prefName)
    }

    fun syncRemove(context: Context, key: String, prefName: String = "FloatingReaderPrefs") {
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        prefs.edit().remove(key).commit()
        sendSync(context, key, "", "REMOVE", prefName)
    }

    /**
     * Applies a bounded set of preference updates with one cross-process sync event.
     * Used by restore flows to avoid repeatedly detaching and reattaching overlay handles
     * while dozens of page keys are being restored.
     */
    fun syncBulk(
        context: Context,
        values: Map<String, Any>,
        removals: Set<String> = emptySet(),
        prefName: String = "FloatingReaderPrefs"
    ) {
        require(values.size + removals.size <= 10000) { "Too many preference updates in one batch" }
        require((values.keys + removals).all { it.isNotBlank() && it.length <= 512 }) {
            "Invalid preference key in bulk sync"
        }

        val localPrefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val localEditor = localPrefs.edit()
        values.forEach { (key, value) -> putValue(localEditor, key, value) }
        removals.forEach(localEditor::remove)
        check(localEditor.commit()) { "Could not apply bulk preference updates" }

        val syncDir = File(context.cacheDir, "sidebar_pref_sync")
        if (!syncDir.exists()) check(syncDir.mkdirs()) { "Could not create Sidebar sync directory" }
        syncDir.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > 24L * 60L * 60L * 1000L }
            ?.forEach { it.delete() }

        val encodedValues = JSONObject()
        values.forEach { (key, value) ->
            val encoded = JSONObject()
            when (value) {
                is String -> encoded.put("type", "STRING").put("value", value)
                is Int -> encoded.put("type", "INT").put("value", value)
                is Long -> encoded.put("type", "LONG").put("value", value)
                is Float -> encoded.put("type", "FLOAT").put("value", value.toDouble())
                is Boolean -> encoded.put("type", "BOOLEAN").put("value", value)
                is Set<*> -> encoded.put("type", "STRING_SET").put(
                    "value",
                    JSONArray().apply { value.filterIsInstance<String>().forEach(::put) }
                )
                else -> error("Unsupported bulk preference value for $key")
            }
            encodedValues.put(key, encoded)
        }
        val payload = JSONObject()
            .put("prefName", prefName)
            .put("values", encodedValues)
            .put("removals", JSONArray().apply { removals.sorted().forEach(::put) })
        val syncFile = File(syncDir, "sidebar-sync-${UUID.randomUUID()}.json")
        syncFile.writeText(payload.toString(), Charsets.UTF_8)

        val serviceIntent = Intent(context, HandleService::class.java).apply {
            action = ACTION_SYNC_PREF
            putExtra(EXTRA_KEY, "")
            putExtra(EXTRA_VALUE, "")
            putExtra(EXTRA_TYPE, "SYNC_BULK_FILE")
            putExtra(EXTRA_PREF_NAME, prefName)
            putExtra(EXTRA_BULK_FILE_PATH, syncFile.absolutePath)
        }
        val serviceStarted = runCatching { context.startService(serviceIntent) }
            .getOrNull() != null

        val broadcastIntent = Intent(ACTION_SYNC_PREF).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_KEY, "")
            putExtra(EXTRA_VALUE, "")
            putExtra(EXTRA_TYPE, "SYNC_BULK_FILE")
            putExtra(EXTRA_PREF_NAME, prefName)
            putExtra(EXTRA_BULK_FILE_PATH, syncFile.absolutePath)
            putExtra(EXTRA_BULK_FALLBACK, !serviceStarted)
        }
        runCatching { context.sendBroadcast(broadcastIntent) }
    }

    fun applyBulkFile(context: Context, prefName: String, path: String): Boolean {
        val syncDir = File(context.cacheDir, "sidebar_pref_sync").canonicalFile
        val file = File(path).canonicalFile
        require(file.parentFile == syncDir && file.isFile) { "Invalid bulk preference sync file" }
        val payload = JSONObject(file.readText(Charsets.UTF_8))
        require(payload.optString("prefName") == prefName) { "Bulk preference file target mismatch" }
        val values = payload.optJSONObject("values") ?: error("Bulk preference values are missing")
        require(values.length() <= 10000) { "Too many bulk preference values" }
        val editor = context.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit()
        val keys = values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            require(key.isNotBlank() && key.length <= 512) { "Invalid bulk preference key" }
            val item = values.getJSONObject(key)
            when (item.optString("type")) {
                "STRING" -> editor.putString(key, item.optString("value"))
                "INT" -> editor.putInt(key, item.getInt("value"))
                "LONG" -> editor.putLong(key, item.getLong("value"))
                "FLOAT" -> editor.putFloat(key, item.getDouble("value").toFloat())
                "BOOLEAN" -> editor.putBoolean(key, item.getBoolean("value"))
                "STRING_SET" -> {
                    val array = item.getJSONArray("value")
                    require(array.length() <= 5000) { "Bulk string set is too large" }
                    editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                }
                else -> error("Unsupported bulk preference type")
            }
        }
        val removals = payload.optJSONArray("removals") ?: JSONArray()
        require(removals.length() <= 10000) { "Too many bulk preference removals" }
        for (index in 0 until removals.length()) {
            val key = removals.getString(index)
            require(key.isNotBlank() && key.length <= 512) { "Invalid bulk preference removal key" }
            editor.remove(key)
        }
        return editor.commit()
    }

    private fun putValue(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> error("Unsupported bulk preference value for $key")
        }
    }

    const val EXTRA_BULK_FILE_PATH = "extra_bulk_file_path"
    const val EXTRA_BULK_FALLBACK = "extra_bulk_fallback"

    fun triggerReload(context: Context) {
        sendSync(context, "", "", "RELOAD_ALL", "FloatingReaderPrefs")
    }

    private fun sendSync(context: Context, key: String, value: String, type: String, prefName: String = "FloatingReaderPrefs") {
        // 1. Send via startService to HandleService if running
        try {
            val svcIntent = Intent(context, HandleService::class.java).apply {
                action = ACTION_SYNC_PREF
                putExtra(EXTRA_KEY, key)
                putExtra(EXTRA_VALUE, value)
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_PREF_NAME, prefName)
            }
            context.startService(svcIntent)
        } catch (e: Exception) {
            // Ignored if background start is restricted
        }

        // 2. Also send explicit broadcast with app package target
        try {
            val bIntent = Intent(ACTION_SYNC_PREF).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_KEY, key)
                putExtra(EXTRA_VALUE, value)
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_PREF_NAME, prefName)
            }
            context.sendBroadcast(bIntent)
        } catch (e: Exception) {
            // Ignored
        }
    }
}
