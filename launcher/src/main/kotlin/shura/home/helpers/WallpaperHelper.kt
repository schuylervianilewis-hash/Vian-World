package shura.home.helpers

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import shura.home.R
import shura.home.extensions.config
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object WallpaperHelper {

    private const val DRAWER_WALLPAPER_FILE = "drawer_wallpaper.png"

    fun getDrawerWallpaperFile(context: Context): File {
        return File(context.filesDir, DRAWER_WALLPAPER_FILE)
    }

    fun hasCustomDrawerWallpaper(context: Context): Boolean {
        return getDrawerWallpaperFile(context).exists()
    }

    fun showTargetAndApplyCroppedBitmap(
        activity: Activity,
        croppedBitmap: Bitmap,
        onComplete: (() -> Unit)? = null
    ) {
        val options = arrayOf(
            activity.getString(R.string.wallpaper_target_home),
            activity.getString(R.string.wallpaper_target_lock),
            activity.getString(R.string.wallpaper_target_both),
            activity.getString(R.string.wallpaper_target_drawer)
        )

        activity.getAlertDialogBuilder()
            .setTitle(R.string.set_wallpaper)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> applyCroppedBitmapToSystem(activity, croppedBitmap, WallpaperManager.FLAG_SYSTEM, onComplete)
                    1 -> applyCroppedBitmapToSystem(activity, croppedBitmap, WallpaperManager.FLAG_LOCK, onComplete)
                    2 -> applyCroppedBitmapToSystem(
                        activity,
                        croppedBitmap,
                        WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
                        onComplete
                    )
                    3 -> applyCroppedBitmapToDrawer(activity, croppedBitmap, onComplete)
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    fun applyCroppedBitmapToSystem(
        activity: Activity,
        bitmap: Bitmap,
        whichFlag: Int,
        onComplete: (() -> Unit)? = null
    ) {
        ensureBackgroundThread {
            try {
                val wallpaperManager = WallpaperManager.getInstance(activity.applicationContext)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    wallpaperManager.setBitmap(bitmap, null, true, whichFlag)
                } else {
                    wallpaperManager.setBitmap(bitmap)
                }

                activity.runOnUiThread {
                    activity.toast(R.string.wallpaper_applied_successfully)
                    onComplete?.invoke()
                }
            } catch (e: Exception) {
                LogCatcher.log("WallpaperHelper", "Failed to apply cropped system wallpaper", e)
                activity.runOnUiThread {
                    activity.toast(R.string.wallpaper_apply_failed)
                }
            }
        }
    }

    fun applyCroppedBitmapToDrawer(
        activity: Activity,
        bitmap: Bitmap,
        onComplete: (() -> Unit)? = null
    ) {
        ensureBackgroundThread {
            try {
                val file = getDrawerWallpaperFile(activity)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }

                activity.config.drawerUseCustomWallpaper = true

                activity.runOnUiThread {
                    activity.toast(R.string.wallpaper_applied_successfully)
                    onComplete?.invoke()
                }
            } catch (e: Exception) {
                LogCatcher.log("WallpaperHelper", "Failed to apply cropped drawer wallpaper", e)
                activity.runOnUiThread {
                    activity.toast(R.string.wallpaper_apply_failed)
                }
            }
        }
    }

    fun resetLockScreenWallpaper(activity: Activity, onComplete: (() -> Unit)? = null) {
        ensureBackgroundThread {
            try {
                val wallpaperManager = WallpaperManager.getInstance(activity.applicationContext)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    wallpaperManager.clear(WallpaperManager.FLAG_LOCK)
                } else {
                    wallpaperManager.clear()
                }

                activity.runOnUiThread {
                    activity.toast(R.string.lock_wallpaper_reset_success)
                    onComplete?.invoke()
                }
            } catch (e: Exception) {
                LogCatcher.log("WallpaperHelper", "Failed to reset lock screen wallpaper", e)
                activity.runOnUiThread {
                    activity.toast(R.string.wallpaper_apply_failed)
                }
            }
        }
    }

    fun showWallpaperTargetDialog(
        activity: Activity,
        imageUri: Uri,
        onComplete: (() -> Unit)? = null
    ) {
        // Open the interactive WallpaperCropDialog first so the user can frame/fit the area
        shura.home.dialogs.WallpaperCropDialog(activity, imageUri, onComplete).show()
    }

    fun applySystemWallpaper(
        activity: Activity,
        imageUri: Uri,
        whichFlag: Int,
        onComplete: (() -> Unit)? = null
    ) {
        ensureBackgroundThread {
            try {
                val wallpaperManager = WallpaperManager.getInstance(activity.applicationContext)
                var stream: InputStream? = activity.contentResolver.openInputStream(imageUri)
                if (stream != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wallpaperManager.setStream(stream, null, true, whichFlag)
                    } else {
                        wallpaperManager.setStream(stream)
                    }
                    stream.close()

                    activity.runOnUiThread {
                        activity.toast(R.string.wallpaper_applied_successfully)
                        onComplete?.invoke()
                    }
                } else {
                    activity.runOnUiThread {
                        activity.toast(R.string.wallpaper_apply_failed)
                    }
                }
            } catch (e: Exception) {
                LogCatcher.log("WallpaperHelper", "Failed to apply system wallpaper", e)
                activity.runOnUiThread {
                    activity.toast(R.string.wallpaper_apply_failed)
                }
            }
        }
    }

    fun applyDrawerWallpaper(
        activity: Activity,
        imageUri: Uri,
        onComplete: (() -> Unit)? = null
    ) {
        ensureBackgroundThread {
            try {
                val displayMetrics = activity.resources.displayMetrics
                val targetWidth = displayMetrics.widthPixels
                val targetHeight = displayMetrics.heightPixels

                val inputStream = activity.contentResolver.openInputStream(imageUri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap != null) {
                    // Downsample to fit device bounds without consuming extra memory
                    val scaled = if (bitmap.width > targetWidth || bitmap.height > targetHeight) {
                        Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                    } else {
                        bitmap
                    }

                    val file = getDrawerWallpaperFile(activity)
                    FileOutputStream(file).use { out ->
                        scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
                    }

                    activity.config.drawerUseCustomWallpaper = true

                    activity.runOnUiThread {
                        activity.toast(R.string.wallpaper_applied_successfully)
                        onComplete?.invoke()
                    }
                } else {
                    activity.runOnUiThread {
                        activity.toast(R.string.wallpaper_apply_failed)
                    }
                }
            } catch (e: Exception) {
                LogCatcher.log("WallpaperHelper", "Failed to apply drawer wallpaper", e)
                activity.runOnUiThread {
                    activity.toast(R.string.wallpaper_apply_failed)
                }
            }
        }
    }

    fun removeDrawerWallpaper(context: Context) {
        val file = getDrawerWallpaperFile(context)
        if (file.exists()) {
            file.delete()
        }
        context.config.drawerUseCustomWallpaper = false
    }

    fun loadDrawerWallpaperBitmap(context: Context): Bitmap? {
        val file = getDrawerWallpaperFile(context)
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            null
        }
    }
}
