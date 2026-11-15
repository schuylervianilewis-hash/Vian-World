package shura.home

import android.graphics.Color
import org.fossify.commons.FossifyApp
import shura.home.helpers.Config
import shura.home.helpers.LogCatcher

class VianApp : FossifyApp() {
    override fun onCreate() {
        super.onCreate()
        // Initialize LogCatcher at the absolute earliest point in the application lifecycle
        LogCatcher.init(this)

        initDefaultTheme()
    }

    private fun initDefaultTheme() {
        try {
            val config = Config(this)
            val prefs = config.sharedPrefs
            if (!prefs.getBoolean("vian_home_light_theme_applied", false)) {
                config.backgroundColor = Color.parseColor("#FAFAFA")
                config.textColor = Color.parseColor("#212121")
                val skyBlue = Color.parseColor("#0288D1")
                config.primaryColor = skyBlue
                config.accentColor = skyBlue
                config.appIconColor = skyBlue
                prefs.edit().putBoolean("vian_home_light_theme_applied", true).apply()
            }
        } catch (e: Exception) {
            // Ignore if theme initialization encounters an issue
        }
    }
}
