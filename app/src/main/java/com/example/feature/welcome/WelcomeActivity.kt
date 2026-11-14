package com.example.feature.welcome

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.example.MainActivity
import com.example.SettingsActivity
import com.example.core.HandleManager
import com.example.core.HandleService
import com.example.feature.settings.PermissionManagerScreen
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WelcomeActivity: Permission / Setup entry page of Settings running in the Heavy process (:heavy).
 *
 * Characteristics:
 * 1. Resident strictly in the :heavy process; keeps Main process lightweight.
 * 2. Provides active state tracking to prevent duplicate launches from repeated IPC requests.
 * 3. Preserves exact reference UI, wording, permission checks, and behavior.
 * 4. Terminates cleanly upon exit without leaving background services or leaking memory.
 */
class WelcomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                WelcomeScreen(
                    onContinue = {
                        // The floating handle cannot work without overlay permission.
                        // Keep Welcome as the next-launch entry point until this required step is granted.
                        if (!Settings.canDrawOverlays(this@WelcomeActivity)) {
                            Toast.makeText(
                                this@WelcomeActivity,
                                "Allow Display Over Other Apps to finish Sidebar setup.",
                                Toast.LENGTH_LONG
                            ).show()
                            return@WelcomeScreen
                        }

                        com.example.core.LogKeeper.log(this@WelcomeActivity, "WelcomeActivity", "Overlay permission granted; completing setup")
                        val prefs = getSharedPreferences(HandleManager.PREFS_NAME, Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("setup_completed", true).commit()
                        HandleService.startIfConfigured(this@WelcomeActivity)
                        startActivity(Intent(this@WelcomeActivity, SettingsActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isActive.set(true)
    }

    override fun onPause() {
        super.onPause()
        isActive.set(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        isActive.set(false)
    }

    companion object {
        private val isActive = AtomicBoolean(false)

        val isWelcomeActive: Boolean
            get() = isActive.get()

        internal fun setActiveForTesting(active: Boolean) {
            isActive.set(active)
        }
    }
}

@Composable
fun WelcomeScreen(onContinue: (() -> Unit)? = null) {
    PermissionManagerScreen(
        onContinue = { onContinue?.invoke() },
        isFirstLaunch = true
    )
}
