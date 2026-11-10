package shura.home.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class WallpaperCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var sourceBitmap: Bitmap? = null
    private val matrix = Matrix()
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val scrimPaint = Paint().apply {
        color = Color.parseColor("#80000000")
        style = Paint.Style.FILL
    }
    private val frameBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }

    private val cropFrame = RectF()
    private var lastTouch = PointF()
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
            clampMatrix()
            invalidate()
            return true
        }
    })

    fun setSourceBitmap(bitmap: Bitmap) {
        this.sourceBitmap = bitmap
        initMatrix()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateCropFrame(w, h)
        initMatrix()
    }

    private fun calculateCropFrame(viewWidth: Int, viewHeight: Int) {
        val dm = resources.displayMetrics
        val screenAspect = dm.widthPixels.toFloat() / dm.heightPixels.toFloat()

        val margin = 24f * dm.density
        val availWidth = viewWidth - (margin * 2)
        val availHeight = viewHeight - (margin * 2)

        var frameW = availWidth
        var frameH = frameW / screenAspect

        if (frameH > availHeight) {
            frameH = availHeight
            frameW = frameH * screenAspect
        }

        val left = (viewWidth - frameW) / 2f
        val top = (viewHeight - frameH) / 2f
        cropFrame.set(left, top, left + frameW, top + frameH)
    }

    private fun initMatrix() {
        val bitmap = sourceBitmap ?: return
        if (cropFrame.width() <= 0 || cropFrame.height() <= 0) return

        matrix.reset()
        val scale = max(
            cropFrame.width() / bitmap.width.toFloat(),
            cropFrame.height() / bitmap.height.toFloat()
        )
        matrix.postScale(scale, scale)

        // Center on crop frame
        val scaledWidth = bitmap.width * scale
        val scaledHeight = bitmap.height * scale
        val dx = cropFrame.left + (cropFrame.width() - scaledWidth) / 2f
        val dy = cropFrame.top + (cropFrame.height() - scaledHeight) / 2f
        matrix.postTranslate(dx, dy)
        clampMatrix()
    }

    private fun clampMatrix() {
        val bitmap = sourceBitmap ?: return
        if (cropFrame.width() <= 0) return

        val srcRect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        val dstRect = RectF()
        matrix.mapRect(dstRect, srcRect)

        var dx = 0f
        var dy = 0f

        if (dstRect.width() < cropFrame.width()) {
            val scale = cropFrame.width() / dstRect.width()
            matrix.postScale(scale, scale, cropFrame.centerX(), cropFrame.centerY())
            matrix.mapRect(dstRect, srcRect)
        }
        if (dstRect.height() < cropFrame.height()) {
            val scale = cropFrame.height() / dstRect.height()
            matrix.postScale(scale, scale, cropFrame.centerX(), cropFrame.centerY())
            matrix.mapRect(dstRect, srcRect)
        }

        if (dstRect.left > cropFrame.left) {
            dx = cropFrame.left - dstRect.left
        } else if (dstRect.right < cropFrame.right) {
            dx = cropFrame.right - dstRect.right
        }

        if (dstRect.top > cropFrame.top) {
            dy = cropFrame.top - dstRect.top
        } else if (dstRect.bottom < cropFrame.bottom) {
            dy = cropFrame.bottom - dstRect.bottom
        }

        matrix.postTranslate(dx, dy)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch.set(event.x, event.y)
                isDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && !scaleDetector.isInProgress) {
                    val dx = event.x - lastTouch.x
                    val dy = event.y - lastTouch.y
                    matrix.postTranslate(dx, dy)
                    clampMatrix()
                    invalidate()
                    lastTouch.set(event.x, event.y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = sourceBitmap ?: return

        // 1. Draw transformed bitmap
        canvas.drawBitmap(bitmap, matrix, drawPaint)

        // 2. Draw 4 dark scrim regions outside the crop frame
        // Top
        canvas.drawRect(0f, 0f, width.toFloat(), cropFrame.top, scrimPaint)
        // Bottom
        canvas.drawRect(0f, cropFrame.bottom, width.toFloat(), height.toFloat(), scrimPaint)
        // Left
        canvas.drawRect(0f, cropFrame.top, cropFrame.left, cropFrame.bottom, scrimPaint)
        // Right
        canvas.drawRect(cropFrame.right, cropFrame.top, width.toFloat(), cropFrame.bottom, scrimPaint)

        // 3. Draw white framing border
        canvas.drawRect(cropFrame, frameBorderPaint)
    }

    fun cropVisibleBitmap(targetWidth: Int, targetHeight: Int): Bitmap? {
        val bitmap = sourceBitmap ?: return null
        val inverse = Matrix()
        if (!matrix.invert(inverse)) return null

        val mappedCropRect = RectF()
        inverse.mapRect(mappedCropRect, cropFrame)

        val cropX = max(0, mappedCropRect.left.toInt())
        val cropY = max(0, mappedCropRect.top.toInt())
        val cropW = min(bitmap.width - cropX, mappedCropRect.width().toInt())
        val cropH = min(bitmap.height - cropY, mappedCropRect.height().toInt())

        if (cropW <= 0 || cropH <= 0) return null

        val cropped = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
        return Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
    }
}
