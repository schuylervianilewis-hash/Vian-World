package shura.home.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import shura.home.R
import kotlin.math.max

object IconDecorator {

    /**
     * Decorates an application icon for archived apps according to standard Android 15 / Pixel launcher UX:
     * 1. Draws the base icon with dimmed alpha (~55%)
     * 2. Overlays a prominent cloud download glyph centered directly OVER the icon with a dark disc
     */
    fun decorateArchivedIcon(context: Context, original: Drawable): Drawable {
        val width = max(original.intrinsicWidth, 192)
        val height = max(original.intrinsicHeight, 192)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Draw base icon with ~55% alpha (140/255) to signify offloaded state
        original.setBounds(0, 0, width, height)
        val prevAlpha = original.alpha
        original.alpha = 140
        original.draw(canvas)
        original.alpha = prevAlpha

        // 2. Overlay prominent, floating Cloud Run-inspired glyph centered directly OVER the icon (no grey bubble)
        val centerX = width / 2f
        val centerY = height / 2f
        val glyphSize = width * 0.54f
        val glyphHalf = glyphSize / 2f

        val downloadDrawable = ContextCompat.getDrawable(context, R.drawable.ic_cloud_download_vector)?.mutate()
        if (downloadDrawable != null) {
            // Soft shadow offset for crisp boundary separation on light icons
            val shadowOffset = max(width * 0.016f, 2f)
            downloadDrawable.setBounds(
                (centerX - glyphHalf).toInt(),
                (centerY - glyphHalf + shadowOffset).toInt(),
                (centerX + glyphHalf).toInt(),
                (centerY + glyphHalf + shadowOffset).toInt()
            )
            downloadDrawable.colorFilter = PorterDuffColorFilter(Color.parseColor("#B3000000"), PorterDuff.Mode.SRC_IN)
            downloadDrawable.draw(canvas)

            // Crisp solid white glyph directly centered
            downloadDrawable.setBounds(
                (centerX - glyphHalf).toInt(),
                (centerY - glyphHalf).toInt(),
                (centerX + glyphHalf).toInt(),
                (centerY + glyphHalf).toInt()
            )
            downloadDrawable.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            downloadDrawable.draw(canvas)
        }

        return BitmapDrawable(context.resources, bitmap)
    }

    /**
     * Decorates an application icon for locked apps:
     * 1. Draws the base icon dimmed to ~55% alpha (140/255)
     * 2. Overlays a prominent, floating architectural lock glyph directly OVER the icon (no grey bubble)
     */
    fun decorateLockedIcon(context: Context, original: Drawable): Drawable {
        val width = max(original.intrinsicWidth, 192)
        val height = max(original.intrinsicHeight, 192)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Draw base icon dimmed to ~55% alpha (140/255)
        original.setBounds(0, 0, width, height)
        val prevAlpha = original.alpha
        original.alpha = 140
        original.draw(canvas)
        original.alpha = prevAlpha

        // 2. Overlay prominent, floating architectural lock glyph centered directly OVER the icon (no grey bubble)
        val centerX = width / 2f
        val centerY = height / 2f
        val glyphSize = width * 0.54f
        val glyphHalf = glyphSize / 2f

        val lockDrawable = ContextCompat.getDrawable(context, R.drawable.ic_lock_vector)?.mutate()
        if (lockDrawable != null) {
            // Soft shadow offset for crisp boundary separation on light icons
            val shadowOffset = max(width * 0.016f, 2f)
            lockDrawable.setBounds(
                (centerX - glyphHalf).toInt(),
                (centerY - glyphHalf + shadowOffset).toInt(),
                (centerX + glyphHalf).toInt(),
                (centerY + glyphHalf + shadowOffset).toInt()
            )
            lockDrawable.colorFilter = PorterDuffColorFilter(Color.parseColor("#B3000000"), PorterDuff.Mode.SRC_IN)
            lockDrawable.draw(canvas)

            // Crisp solid white glyph directly centered
            lockDrawable.setBounds(
                (centerX - glyphHalf).toInt(),
                (centerY - glyphHalf).toInt(),
                (centerX + glyphHalf).toInt(),
                (centerY + glyphHalf).toInt()
            )
            lockDrawable.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            lockDrawable.draw(canvas)
        }

        return BitmapDrawable(context.resources, bitmap)
    }
}
