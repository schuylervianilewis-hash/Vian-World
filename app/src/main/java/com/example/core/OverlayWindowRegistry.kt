package com.example.core

import android.content.Context
import android.view.View
import android.view.WindowManager

/**
 * Direct WindowManager overlay registry for visual owners outside FloatingWindowManager.
 * Views are detached during exclusion without destroying feature state, then restored.
 */
object OverlayWindowRegistry {
    private data class Entry(
        val manager: WindowManager,
        val view: View,
        val params: WindowManager.LayoutParams,
        var attached: Boolean = false,
        var restore: Boolean = true
    )
    private val entries = LinkedHashMap<View, Entry>()
    private var suppressed = false

    @Synchronized
    fun addView(context: Context, manager: WindowManager, view: View,
        params: WindowManager.LayoutParams): Boolean {
        val entry = entries[view] ?: Entry(manager, view, params).also { entries[view] = it }
        entry.restore = true
        if (suppressed || OverlayAccessPolicy.isForegroundExcluded(context)) {
            suppressed = true
            return true
        }
        if (entry.attached || view.isAttachedToWindow) {
            entry.attached = true
            return true
        }
        return try {
            entry.manager.addView(entry.view, entry.params)
            entry.attached = true
            true
        } catch (error: Throwable) {
            if (view.isAttachedToWindow) {
                entry.attached = true
                true
            } else {
                entries.remove(view)
                LogKeeper.logError(context, "OverlayWindowRegistry",
                    "Unable to attach ${view.javaClass.simpleName}", error)
                false
            }
        }
    }

    @Synchronized
    fun removeView(manager: WindowManager?, view: View) {
        val entry = entries.remove(view)
        val actualManager = entry?.manager ?: manager
        val attached = (entry?.attached == true) || view.isAttachedToWindow
        if (attached && actualManager != null) {
            try { actualManager.removeViewImmediate(view) } catch (_: Throwable) {}
        }
    }

    @Synchronized
    fun setSuppressed(value: Boolean) {
        if (suppressed == value && entries.values.none {
                if (value) it.attached else it.restore && !it.attached
            }) return
        suppressed = value
        entries.values.toList().forEach { entry ->
            if (value) {
                if (entry.attached || entry.view.isAttachedToWindow) {
                    entry.restore = true
                    try { entry.manager.removeViewImmediate(entry.view) } catch (_: Throwable) {}
                    entry.attached = false
                }
            } else if (entry.restore) {
                try {
                    if (!entry.view.isAttachedToWindow) entry.manager.addView(entry.view, entry.params)
                    entry.attached = true
                    entry.restore = false
                } catch (error: Throwable) {
                    LogKeeper.logError(entry.view.context, "OverlayWindowRegistry",
                        "Unable to restore ${entry.view.javaClass.simpleName}", error)
                }
            }
        }
    }
}
