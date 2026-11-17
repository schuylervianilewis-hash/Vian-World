package com.example.feature.sidebar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import net.kollnig.missioncontrol.data.InternetPauseManager

/** Binds the global Internet Pause state to any Sidebar grid tile. */
object InternetPauseTileBinder {
    fun bind(context: Context, root: View, icon: ImageView) {
        val appContext = context.applicationContext

        fun refresh() {
            val active = InternetPauseManager.isActive(appContext)
            val applied = active && InternetPauseManager.isApplied(appContext)
            icon.setImageResource(
                if (active) net.kollnig.missioncontrol.R.drawable.ic_block_dashboard
                else net.kollnig.missioncontrol.R.drawable.ic_shield_check
            )
            icon.setColorFilter(
                when {
                    applied -> Color.parseColor("#FFFF7777")
                    active -> Color.parseColor("#FFFFC857")
                    else -> Color.WHITE
                }
            )

            val density = appContext.resources.displayMetrics.density
            root.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(Color.parseColor(when {
                    applied -> "#332B1111"
                    active -> "#332B2411"
                    else -> "#22FFFFFF"
                }))
                setStroke(
                    density.toInt().coerceAtLeast(1),
                    Color.parseColor(when {
                        applied -> "#99FF7777"
                        active -> "#99FFC857"
                        else -> "#44FFFFFF"
                    })
                )
            }
            root.contentDescription = when {
                applied -> "Internet Pause is on. Eligible apps are blocked. Tap to restore internet."
                active -> "Internet Pause is being applied. Tap to cancel and restore normal firewall rules."
                else -> "Internet Pause is off. Tap to pause internet for eligible apps."
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == InternetPauseManager.ACTION_STATE_CHANGED)
                    refresh()
            }
        }
        val filter = IntentFilter(InternetPauseManager.ACTION_STATE_CHANGED)
        var registered = false

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                if (!registered) {
                    ContextCompat.registerReceiver(
                        appContext,
                        receiver,
                        filter,
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                    registered = true
                }
                refresh()
            }

            override fun onViewDetachedFromWindow(view: View) {
                if (registered) {
                    try {
                        appContext.unregisterReceiver(receiver)
                    } catch (_: IllegalArgumentException) {
                    }
                    registered = false
                }
            }
        })
        refresh()
    }
}
