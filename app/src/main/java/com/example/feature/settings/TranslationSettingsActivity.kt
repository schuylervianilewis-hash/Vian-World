package com.example.feature.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.SettingsActivity

/**
 * Compatibility redirect for older Translation Settings intents.
 * The only settings UI now lives in SettingsActivity's Dictionary & Translation page.
 */
class TranslationSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("start_route", ROUTE_DICTIONARY_SETTINGS)
            }
        )
        finish()
    }
}
