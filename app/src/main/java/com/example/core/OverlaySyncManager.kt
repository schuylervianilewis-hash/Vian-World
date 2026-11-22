package com.example.core

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle

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
        require(values.size + removals.size <= 5000) { "Too many preference updates in one batch" }
        require((values.keys + removals).all { it.isNotBlank() && it.length <= 512 }) {
            "Invalid preference key in bulk sync"
        }

        val localPrefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val localEditor = localPrefs.edit()
        values.forEach { (key, value) -> putValue(localEditor, key, value) }
        removals.forEach(localEditor::remove)
        check(localEditor.commit()) { "Could not apply bulk preference updates" }

        val bundle = Bundle()
        values.forEach { (key, value) ->
            when (value) {
                is String -> bundle.putString(key, value)
                is Int -> bundle.putInt(key, value)
                is Long -> bundle.putLong(key, value)
                is Float -> bundle.putFloat(key, value)
                is Boolean -> bundle.putBoolean(key, value)
                is Set<*> -> bundle.putStringArrayList(key, ArrayList(value.filterIsInstance<String>()))
                else -> error("Unsupported bulk preference value for $key")
            }
        }
        val removeList = ArrayList(removals)

        val serviceIntent = Intent(context, HandleService::class.java).apply {
            action = ACTION_SYNC_PREF
            putExtra(EXTRA_KEY, "")
            putExtra(EXTRA_VALUE, "")
            putExtra(EXTRA_TYPE, "SYNC_BULK")
            putExtra(EXTRA_PREF_NAME, prefName)
            putExtra(EXTRA_BULK_VALUES, bundle)
            putStringArrayListExtra(EXTRA_BULK_REMOVE_KEYS, removeList)
        }
        runCatching { context.startService(serviceIntent) }

        val broadcastIntent = Intent(ACTION_SYNC_PREF).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_KEY, "")
            putExtra(EXTRA_VALUE, "")
            putExtra(EXTRA_TYPE, "SYNC_BULK")
            putExtra(EXTRA_PREF_NAME, prefName)
            putExtra(EXTRA_BULK_VALUES, bundle)
            putStringArrayListExtra(EXTRA_BULK_REMOVE_KEYS, removeList)
        }
        runCatching { context.sendBroadcast(broadcastIntent) }
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

    const val EXTRA_BULK_VALUES = "extra_bulk_values"
    const val EXTRA_BULK_REMOVE_KEYS = "extra_bulk_remove_keys"

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
