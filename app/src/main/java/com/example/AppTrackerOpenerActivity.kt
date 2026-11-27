package com.example

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/**
 * AppTrackerOpenerActivity: Lightweight, on-demand sequential loop presenter for force-stopping apps.
 *
 * Strictly adheres to user directive:
 * "No clicking. Only loop. User will click."
 *
 * Sequentially brings up each app's App Info settings page. When the user finishes and returns,
 * onResume() advances to the next app in the queue until the list is exhausted.
 */
class AppTrackerOpenerActivity : Activity() {
    private var packageNames = arrayListOf<String>()
    private var currentIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageNames = intent.getStringArrayListExtra("packages") ?: arrayListOf()
        if (packageNames.isEmpty()) {
            finish()
            return
        }
    }

    override fun onResume() {
        super.onResume()
        if (packageNames.isEmpty() || isFinishing || isDestroyed) {
            return
        }
        // Advance to next app whenever we resume (initial start or return from previous Settings page)
        openNext()
    }

    private fun openNext() {
        // Iterate rather than recurse: a long list of stale/unavailable packages must not
        // grow the call stack. Returning from each App Info screen re-enters this method.
        while (currentIndex < packageNames.size) {
            val pkg = packageNames[currentIndex++]
            val appInfo = try {
                packageManager.getApplicationInfo(pkg, 0)
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                continue
            } catch (_: SecurityException) {
                continue
            }

            // A previous manual Force stop can mark the package stopped before the next
            // item is reached (for example if duplicate/stale candidates are supplied).
            if ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0) {
                continue
            }

            try {
                val appInfoIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$pkg")
                }
                startActivity(appInfoIntent)
                return
            } catch (_: Exception) {
                // This package's settings page is unavailable; continue to the next candidate.
            }
        }
        finish()
    }
}
