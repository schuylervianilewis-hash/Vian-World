package com.example.feature.sidebar

import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import net.kollnig.missioncontrol.data.InternetPauseManager

/** Handles VPN consent and service readiness for the Internet Pause grid toggle. */
class InternetPauseToggleActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private var enableFlowStarted = false
    private var preparationStarted = false
    private var pauseRequestSubmitted = false
    private var progressDialog: AlertDialog? = null

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
                    showProgress("Applying temporary internet block…")
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pauseRequestSubmitted)
                    InternetPauseManager.setPaused(this@InternetPauseToggleActivity, false)
                else if (preparationStarted)
                    InternetPauseManager.cancelPausePreparation(this@InternetPauseToggleActivity)
                finishWithMessage("Internet Pause cancelled")
            }
        })

        if (savedInstanceState?.getBoolean(STATE_ENABLE_FLOW, false) == true) {
            enableFlowStarted = true
            preparationStarted = savedInstanceState.getBoolean(STATE_PREPARATION_STARTED, false)
            pauseRequestSubmitted = savedInstanceState.getBoolean(STATE_PAUSE_REQUEST_SUBMITTED, false)
            deadline = savedInstanceState.getLong(STATE_DEADLINE, 0L)
            if (preparationStarted) {
                if (deadline <= SystemClock.elapsedRealtime())
                    deadline = SystemClock.elapsedRealtime() + PREPARATION_TIMEOUT_MS
                showProgress(if (pauseRequestSubmitted) "Applying temporary internet block…" else "Starting firewall…")
                handler.post(pollRunnable)
            }
            return
        }

        if (InternetPauseManager.isActive(this)) {
            val restored = InternetPauseManager.setPaused(this, false)
            finishWithMessage(
                if (restored) "Internet restored" else "Could not restore the previous firewall state"
            )
            return
        }

        enableFlowStarted = true
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

        preparationStarted = true
        showProgress("Starting firewall…")
        deadline = SystemClock.elapsedRealtime() + PREPARATION_TIMEOUT_MS
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    private fun showProgress(message: String) {
        if (progressDialog?.isShowing == true) {
            progressDialog?.setMessage(message)
            return
        }
        progressDialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Internet Pause")
            .setMessage(message)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun finishWithMessage(message: String) {
        handler.removeCallbacks(pollRunnable)
        progressDialog?.dismiss()
        progressDialog = null
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_ENABLE_FLOW, enableFlowStarted && !isFinishing)
        outState.putBoolean(STATE_PREPARATION_STARTED, preparationStarted)
        outState.putBoolean(STATE_PAUSE_REQUEST_SUBMITTED, pauseRequestSubmitted)
        outState.putLong(STATE_DEADLINE, deadline)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        progressDialog?.dismiss()
        progressDialog = null
        super.onDestroy()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 350L
        const val PREPARATION_TIMEOUT_MS = 45_000L
        const val STATE_ENABLE_FLOW = "internet_pause_enable_flow"
        const val STATE_PREPARATION_STARTED = "internet_pause_preparation_started"
        const val STATE_PAUSE_REQUEST_SUBMITTED = "internet_pause_request_submitted"
        const val STATE_DEADLINE = "internet_pause_deadline"
    }
}
