package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.example.core.CallRecorderManager

/**
 * CallStateReceiver: BroadcastReceiver for phone state changes.
 * Feeds call state changes to CallRecorderManager when active.
 */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            val state = when (stateStr) {
                TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
                else -> -1
            }
            if (state != -1) {
                val label = when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> "RINGING"
                    TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
                    TelephonyManager.CALL_STATE_IDLE -> "IDLE"
                    else -> "UNKNOWN"
                }
                com.example.core.LogKeeper.log(context, "CallStateReceiver", "Phone state broadcast received: " + label)
                CallRecorderManager.getInstance(context).onCallStateReceived(state)
            }
        }
    }
}
