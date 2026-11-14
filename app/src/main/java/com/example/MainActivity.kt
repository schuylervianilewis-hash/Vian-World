package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.core.HandleManager
import com.example.core.HandleService

/**
 * Compatibility entry point retained for older launcher integrations.
 * The actual first-run UI is hosted by WelcomeActivity in :heavy.
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FORCE_WELCOME = "force_welcome"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val setupCompleted = getSharedPreferences(
            HandleManager.PREFS_NAME,
            MODE_PRIVATE
        ).getBoolean("setup_completed", false)
        val forceWelcome = intent.getBooleanExtra(EXTRA_FORCE_WELCOME, false)

        val destination = if (setupCompleted && !forceWelcome) {
            HandleService.startIfConfigured(this)
            SettingsActivity::class.java
        } else {
            com.example.feature.welcome.WelcomeActivity::class.java
        }

        startActivity(Intent(this, destination))
        finish()
    }
}
