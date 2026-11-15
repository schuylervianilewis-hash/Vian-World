package shura.home.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import shura.home.extensions.getDrawableForPackageName
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

object CustomIconManager {
    private const val ICONS_DIR = "custom_icons"
    const val MAX_ICON_SIZE = 192

    private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9._]+$")

    fun isValidPackageName(packageName: String): Boolean {
        return packageName.length in 1..128 && PACKAGE_NAME_REGEX.matches(packageName) && !packageName.contains("..")
    }

    private fun getIconsDir(context: Context): File {
        val dir = File(context.filesDir, ICONS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getIconFile(context: Context, packageName: String): File {
        val sanitized = packageName.filter { it.isLetterOrDigit() || it == '.' || it == '_' }
        val safeName = "${sanitized.ifEmpty { "unknown" }}.png"
        return File(getIconsDir(context), safeName)
    }

    fun hasCustomIcon(context: Context, packageName: String): Boolean {
        return getIconFile(context, packageName).exists()
    }

    fun getCustomIcon(context: Context, packageName: String): Drawable? {
        val cacheKey = "custom_icon_$packageName"
        val cached = IconCache.getDrawable(cacheKey)
        if (cached != null) {
            return cached
        }

        val file = getIconFile(context, packageName)
        if (!file.exists()) {
            return null
        }

        return try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                val drawable = BitmapDrawable(context.resources, bitmap)
                IconCache.putDrawable(cacheKey, drawable)
                drawable
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun saveCustomIcon(context: Context, packageName: String, bitmap: Bitmap) {
        val scaled = downsampleBitmap(bitmap, MAX_ICON_SIZE)
        val file = getIconFile(context, packageName)
        try {
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val drawable = BitmapDrawable(context.resources, scaled)
            val cacheKey = "custom_icon_$packageName"
            IconCache.putDrawable(cacheKey, drawable)
            // Also refresh in-memory launchers cache
            IconCache.launchers.forEach { launcher ->
                if (launcher.packageName == packageName) {
                    launcher.drawable = drawable
                    IconCache.putDrawable(launcher.getLauncherIdentifier(), drawable)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun removeCustomIcon(context: Context, packageName: String) {
        val file = getIconFile(context, packageName)
        if (file.exists()) {
            file.delete()
        }
        // Evict from IconCache
        IconCache.removeDrawable("custom_icon_$packageName")
        IconCache.launchers.forEach { launcher ->
            if (launcher.packageName == packageName) {
                IconCache.removeDrawable(launcher.getLauncherIdentifier())
                val defaultDrawable = context.getDrawableForPackageName(packageName)
                launcher.drawable = defaultDrawable
                if (defaultDrawable != null) {
                    IconCache.putDrawable(launcher.getLauncherIdentifier(), defaultDrawable)
                }
            }
        }
    }

    fun downsampleBitmap(bitmap: Bitmap, maxDimension: Int = MAX_ICON_SIZE): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) {
            return bitmap
        }

        val ratio = min(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
        val targetWidth = max(1, (width * ratio).toInt())
        val targetHeight = max(1, (height * ratio).toInt())

        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    fun loadAndDownsampleFromUri(context: Context, uri: Uri, targetSize: Int = MAX_ICON_SIZE): Bitmap? {
        return try {
            // First decode with inJustDecodeBounds to check dimensions
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }

            var inSampleSize = 1
            if (options.outHeight > targetSize || options.outWidth > targetSize) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / inSampleSize >= targetSize && halfWidth / inSampleSize >= targetSize) {
                    inSampleSize *= 2
                }
            }

            // Decode with inSampleSize
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val decodedBitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            } ?: return null

            downsampleBitmap(decodedBitmap, targetSize)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
