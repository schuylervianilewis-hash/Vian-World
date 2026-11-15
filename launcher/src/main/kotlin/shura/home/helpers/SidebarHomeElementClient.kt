package shura.home.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import androidx.core.graphics.drawable.toDrawable

data class SidebarHomeElementInfo(
    val id: String,
    val label: String,
    val icon: Bitmap?,
    val isActive: Boolean,
    val glowColor: Int
)

/**
 * One-shot bridge to Sidebar's resident main process. The launcher calls this only when
 * loading/resuming its home screen or when adding an element; it does not subscribe or poll.
 */
object SidebarHomeElementClient {
    private const val AUTHORITY_SUFFIX = ".sidebar.elements"
    private const val METHOD_GET_HOME_ELEMENTS = "get_home_elements"
    private const val KEY_ELEMENT_IDS = "element_ids"
    private const val KEY_ELEMENTS = "elements"
    private const val KEY_ID = "id"
    private const val KEY_LABEL = "label"
    private const val KEY_ICON = "icon"
    private const val KEY_ACTIVE = "active"
    private const val KEY_GLOW_COLOR = "glow_color"

    @Suppress("DEPRECATION")
    fun load(context: Context, elementIds: List<String>): Map<String, SidebarHomeElementInfo> {
        val ids = ArrayList(elementIds.filter { it.isNotBlank() }.distinct())
        if (ids.isEmpty()) return emptyMap()

        return try {
            val response = context.contentResolver.call(
                Uri.parse("content://" + context.packageName + AUTHORITY_SUFFIX),
                METHOD_GET_HOME_ELEMENTS,
                null,
                Bundle().apply { putStringArrayList(KEY_ELEMENT_IDS, ids) }
            ) ?: return emptyMap()

            val entries = response.getParcelableArrayList<Bundle>(KEY_ELEMENTS) ?: return emptyMap()
            entries.mapNotNull { entry ->
                val id = entry.getString(KEY_ID) ?: return@mapNotNull null
                SidebarHomeElementInfo(
                    id = id,
                    label = entry.getString(KEY_LABEL).orEmpty(),
                    icon = entry.getParcelable(KEY_ICON),
                    isActive = entry.getBoolean(KEY_ACTIVE, false),
                    glowColor = entry.getInt(KEY_GLOW_COLOR, 0)
                ).let { id to it }
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

/** Draws a soft state halo behind an active Sidebar icon without altering its source image. */
object SidebarHomeElementIconDecorator {
    fun createDrawable(
        context: Context,
        source: Bitmap?,
        isActive: Boolean,
        glowColor: Int
    ): Drawable? {
        source ?: return null
        if (!isActive || glowColor == 0) return source.toDrawable(context.resources)

        val width = source.width.coerceAtLeast(1)
        val height = source.height.coerceAtLeast(1)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val diameter = minOf(width, height).toFloat()
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = glowColor
            alpha = 185
            maskFilter = BlurMaskFilter(diameter * 0.22f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(width / 2f, height / 2f, diameter * 0.30f, glowPaint)
        canvas.drawBitmap(source, null, Rect(0, 0, width, height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return BitmapDrawable(context.resources, output)
    }
}
