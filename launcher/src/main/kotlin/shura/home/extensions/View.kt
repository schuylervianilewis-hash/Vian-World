package shura.home.extensions

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import org.fossify.commons.extensions.getProperBackgroundColor
import shura.home.R

fun View.animateScale(
    from: Float,
    to: Float,
    duration: Long,
) = animate()
    .scaleX(to)
    .scaleY(to)
    .setDuration(duration)
    .setInterpolator(AccelerateDecelerateInterpolator())
    .withStartAction {
        scaleX = from
        scaleY = from
    }

fun View.setupDrawerBackground() {
    if (context.config.drawerUseCustomWallpaper && shura.home.helpers.WallpaperHelper.hasCustomDrawerWallpaper(context)) {
        val bitmap = shura.home.helpers.WallpaperHelper.loadDrawerWallpaperBitmap(context)
        if (bitmap != null) {
            val drawable = android.graphics.drawable.BitmapDrawable(resources, bitmap).apply {
                // Apply subtle 30% black color filter so icons/labels remain 100% readable
                setColorFilter(Color.parseColor("#4D000000"), android.graphics.PorterDuff.Mode.SRC_ATOP)
            }
            background = drawable
            return
        }
    }
    setBackgroundColor(Color.BLACK)
}