package shura.home.views

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.provider.AlarmClock
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.ViewConfiguration
import shura.home.activities.MainActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

@SuppressLint("ViewConstructor")
class BuiltInClockWidgetView(context: Context) : MyAppWidgetHostView(context) {

    private val timeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val timeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val dateStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val dateFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private var timeText = ""
    private var dateText = ""
    private var isReceiverRegistered = false

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            updateTime()
            postInvalidate()
        }
    }

    init {
        setWillNotDraw(false)
        updateTime()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (windowVisibility == VISIBLE) {
            registerTimeReceiver()
            updateTime()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unregisterTimeReceiver()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            updateTime()
            registerTimeReceiver()
            postInvalidate()
        } else {
            unregisterTimeReceiver()
        }
    }

    private fun registerTimeReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                timeReceiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isReceiverRegistered = true
        }
    }

    private fun unregisterTimeReceiver() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(timeReceiver)
            } catch (ignored: Exception) {
            }
            isReceiverRegistered = false
        }
    }

    fun updateTime() {
        val now = Calendar.getInstance().time
        val is24Hour = DateFormat.is24HourFormat(context)
        val timeFormat = if (is24Hour) "HH:mm" else "h:mm"
        timeText = SimpleDateFormat(timeFormat, Locale.getDefault()).format(now)

        val skeleton = "EEEE, MMMM d"
        val pattern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
        } else {
            "EEEE, MMMM d"
        }
        dateText = SimpleDateFormat(pattern, Locale.getDefault()).format(now)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawClockContent(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
    }

    private fun drawClockContent(canvas: Canvas) {
        if (width <= 0 || height <= 0) return

        val centerX = width / 2f

        var timeTextSize = (height * 0.44f).coerceIn(24f, 130f)
        var dateTextSize = (height * 0.16f).coerceIn(12f, 40f)

        // Ensure time text does not exceed available width
        timeFillPaint.textSize = timeTextSize
        val maxTimeWidth = width * 0.88f
        val measuredTimeWidth = timeFillPaint.measureText(timeText)
        if (measuredTimeWidth > maxTimeWidth && measuredTimeWidth > 0f) {
            val scale = maxTimeWidth / measuredTimeWidth
            timeTextSize = max(18f, timeTextSize * scale)
        }

        // Ensure date text does not exceed available width
        dateFillPaint.textSize = dateTextSize
        val maxDateWidth = width * 0.90f
        val measuredDateWidth = dateFillPaint.measureText(dateText)
        if (measuredDateWidth > maxDateWidth && measuredDateWidth > 0f) {
            val scale = maxDateWidth / measuredDateWidth
            dateTextSize = max(10f, dateTextSize * scale)
        }

        val timeStrokeWidth = (timeTextSize * 0.12f).coerceIn(2f, 16f)
        val dateStrokeWidth = (dateTextSize * 0.14f).coerceIn(1.5f, 6f)

        timeStrokePaint.textSize = timeTextSize
        timeStrokePaint.strokeWidth = timeStrokeWidth

        timeFillPaint.textSize = timeTextSize

        dateStrokePaint.textSize = dateTextSize
        dateStrokePaint.strokeWidth = dateStrokeWidth

        dateFillPaint.textSize = dateTextSize

        val timeY = height * 0.52f
        val dateY = height * 0.78f

        // Draw time: high-contrast outline first, then fill
        canvas.drawText(timeText, centerX, timeY, timeStrokePaint)
        canvas.drawText(timeText, centerX, timeY, timeFillPaint)

        // Draw date: high-contrast outline first, then fill
        canvas.drawText(dateText, centerX, dateY, dateStrokePaint)
        canvas.drawText(dateText, centerX, dateY, dateFillPaint)
    }

    override fun performClick(): Boolean {
        super.performClick()
        launchClockApp()
        return true
    }

    private fun launchClockApp() {
        val pm = context.packageManager

        // 1. Primary target requested: Version 1.2.8go (com.android.deskclock.go)
        val deskClockGoIntent = pm.getLaunchIntentForPackage("com.android.deskclock.go")
        if (deskClockGoIntent != null) {
            try {
                deskClockGoIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(deskClockGoIntent)
                return
            } catch (e: Exception) {
                (context as? MainActivity)?.logKeeper?.log("BuiltInClockWidgetView", "Failed to launch com.android.deskclock.go", e)
            }
        }

        // 2. Standard system clock packages fallback
        val fallbackPackages = listOf(
            "com.google.android.deskclock",
            "com.android.deskclock",
            "com.sec.android.app.clockpackage",
            "com.oneplus.deskclock",
            "com.xiaomi.deskclock",
            "com.coloros.alarmclock"
        )
        for (pkg in fallbackPackages) {
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return
                } catch (e: Exception) {
                    (context as? MainActivity)?.logKeeper?.log("BuiltInClockWidgetView", "Failed to launch $pkg", e)
                }
            }
        }

        // 3. System AlarmClock action intent
        try {
            val alarmIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (alarmIntent.resolveActivity(pm) != null) {
                context.startActivity(alarmIntent)
                return
            }
        } catch (e: Exception) {
            (context as? MainActivity)?.logKeeper?.log("BuiltInClockWidgetView", "Failed to resolve ACTION_SHOW_ALARMS", e)
        }
    }
}
