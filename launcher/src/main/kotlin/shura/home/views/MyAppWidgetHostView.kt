package shura.home.views

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import shura.home.R
import kotlin.math.abs

open class MyAppWidgetHostView(context: Context) : AppWidgetHostView(context) {
    private var longPressHandler = Handler(Looper.getMainLooper())
    private var actionDownCoords = PointF()
    private var currentCoords = PointF()
    private var actionDownMS = 0L
    private val moveGestureThreshold = resources.getDimension(R.dimen.move_gesture_threshold).toInt() / 4

    var hasLongPressed = false
    var isDragging = false
    var ignoreTouches = false
    var longPressListener: ((x: Float, y: Float) -> Unit)? = null
    var dragListener: ((event: MotionEvent, isUp: Boolean) -> Unit)? = null
    var onIgnoreInterceptedListener: (() -> Unit)? = null

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        resetTouches()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            resetTouches()
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (ignoreTouches) {
            onIgnoreInterceptedListener?.invoke()
            return true
        }
        if (event == null) {
            return super.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                actionDownCoords.set(event.rawX, event.rawY)
                currentCoords.set(event.rawX, event.rawY)
                actionDownMS = System.currentTimeMillis()
                hasLongPressed = false
                isDragging = false
                resetTouches()
                longPressHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                currentCoords.set(event.rawX, event.rawY)
                if (hasLongPressed) {
                    if (hasFingerMoved(event.rawX, event.rawY)) {
                        isDragging = true
                        dragListener?.invoke(event, false)
                    }
                } else if (hasFingerMoved(event.rawX, event.rawY)) {
                    resetTouches()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val wasDragging = isDragging
                val wasLongPressed = hasLongPressed
                resetTouches()
                if (wasDragging) {
                    dragListener?.invoke(event, true)
                    isDragging = false
                    hasLongPressed = false
                } else if (!wasLongPressed) {
                    hasLongPressed = false
                    val duration = System.currentTimeMillis() - actionDownMS
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    val dx = abs(event.rawX - actionDownCoords.x)
                    val dy = abs(event.rawY - actionDownCoords.y)
                    if (duration < 500 && dx < slop && dy < slop) {
                        performClick()
                    }
                } else {
                    hasLongPressed = false
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                resetTouches()
                if (isDragging) {
                    dragListener?.invoke(event, true)
                    isDragging = false
                }
                hasLongPressed = false
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent?): Boolean {
        if (ignoreTouches || event == null) {
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                actionDownCoords.set(event.rawX, event.rawY)
                currentCoords.set(event.rawX, event.rawY)
                actionDownMS = System.currentTimeMillis()
                hasLongPressed = false
                isDragging = false
                resetTouches()
                longPressHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                currentCoords.set(event.rawX, event.rawY)
                if (hasLongPressed) {
                    if (hasFingerMoved(event.rawX, event.rawY)) {
                        isDragging = true
                        dragListener?.invoke(event, false)
                        return true
                    }
                } else if (hasFingerMoved(event.rawX, event.rawY)) {
                    resetTouches()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetTouches()
                if (isDragging) {
                    dragListener?.invoke(event, true)
                    isDragging = false
                    hasLongPressed = false
                    return true
                }
                hasLongPressed = false
            }
        }

        return false
    }

    private val longPressRunnable = Runnable {
        if (!hasFingerMoved(currentCoords.x, currentCoords.y)) {
            longPressHandler.removeCallbacksAndMessages(null)
            hasLongPressed = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            longPressListener?.invoke(actionDownCoords.x, actionDownCoords.y)
        }
    }

    fun resetTouches() {
        longPressHandler.removeCallbacksAndMessages(null)
    }

    private fun hasFingerMoved(x: Float, y: Float) =
        ((abs(actionDownCoords.x - x) > moveGestureThreshold) || (abs(actionDownCoords.y - y) > moveGestureThreshold))
}
