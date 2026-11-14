package com.example.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Exposes the Heavy-process-owned setup flag to the launcher process.
 * The process that writes setup_completed also owns this provider's preference reads.
 */
class SidebarSetupStateProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_IS_SETUP_COMPLETED) return null

        val appContext = context ?: return null
        val completed = appContext.getSharedPreferences(
            HandleManager.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        ).getBoolean(SETUP_COMPLETED_KEY, false)

        return Bundle().apply { putBoolean(KEY_SETUP_COMPLETED, completed) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val METHOD_IS_SETUP_COMPLETED = "is_setup_completed"
        const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val SETUP_COMPLETED_KEY = "setup_completed"
    }
}
