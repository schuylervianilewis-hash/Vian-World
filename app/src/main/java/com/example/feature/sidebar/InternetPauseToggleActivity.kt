package com.example.feature.sidebar

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import net.kollnig.missioncontrol.data.InternetPauseManager

/** Handles VPN consent and service readiness for the Internet Pause grid toggle. */
class InternetPauseToggleActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private var pauseRequestSubmitted = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (isFinishing)
                return

            if (InternetPauseManager.isVpnReady(this@InternetPauseToggleActivity)) {
                if (!pauseRequestSubmitted) {
                    if (!InternetPauseManager.setPaused(this@InternetPauseToggleActivity, true)) {
                        InternetPauseManager.cancelPausePreparation(this@InternetPauseToggleActivity)
                        finishWithMessage("Internet Pause could not be activated")
                        return
                    }
                    pauseRequestSubmitted = true
                }

                if (InternetPauseManager.isApplied(this@InternetPauseToggleActivity)) {
                    finishWithMessage("Internet Pause is ON")
                    return
                }
            }

            if (SystemClock.elapsedRealtime() >= deadline) {
                if (pauseRequestSubmitted)
                    InternetPauseManager.setPaused(this@InternetPauseToggleActivity, false)
                else
                    InternetPauseManager.cancelPausePreparation(this@InternetPauseToggleActivity)
                finishWithMessage("Firewall did not become ready. Internet Pause was not confirmed.")
                return
            }

            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (VpnService.prepare(this) == null) {
            beginPreparation()
        } else {
            InternetPauseManager.cancelPausePreparation(this)
            finishWithMessage("VPN permission is required for Internet Pause")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (InternetPauseManager.isActive(this)) {
            val restored = InternetPauseManager.setPaused(this, false)
            finishWithMessage(
                if (restored) "Internet restored" else "Could not restore the previous firewall state"
            )
            return
        }

        val consentIntent: Intent? = VpnService.prepare(this)
        if (consentIntent != null) {
            try {
                vpnConsentLauncher.launch(consentIntent)
            } catch (_: Exception) {
                finishWithMessage("Unable to request VPN permission")
            }
        } else {
            beginPreparation()
        }
    }

    private fun beginPreparation() {
        if (!InternetPauseManager.prepareVpnForPause(this)) {
            InternetPauseManager.cancelPausePreparation(this)
            finishWithMessage("Firewall could not be started for Internet Pause")
            return
        }

        Toast.makeText(this, "Starting firewall for Internet Pause…", Toast.LENGTH_LONG).show()
        deadline = SystemClock.elapsedRealtime() + PREPARATION_TIMEOUT_MS
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    private fun finishWithMessage(message: String) {
        handler.removeCallbacks(pollRunnable)
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 350L
        const val PREPARATION_TIMEOUT_MS = 45_000L
    }
}
