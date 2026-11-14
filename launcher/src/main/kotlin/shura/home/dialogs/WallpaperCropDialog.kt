package shura.home.dialogs

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.view.Window
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import shura.home.R
import shura.home.databinding.DialogWallpaperCropBinding
import shura.home.helpers.LogCatcher
import shura.home.helpers.WallpaperHelper
import kotlin.math.max

class WallpaperCropDialog(
    val activity: Activity,
    val imageUri: Uri,
    val onComplete: (() -> Unit)? = null
) {
    private val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val binding = DialogWallpaperCropBinding.inflate(activity.layoutInflater)
        dialog.setContentView(binding.root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        ensureBackgroundThread {
            try {
                // Decode bounds first to prevent OOM
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                var stream = activity.contentResolver.openInputStream(imageUri)
                BitmapFactory.decodeStream(stream, null, options)
                stream?.close()

                val dm = activity.resources.displayMetrics
                val maxDim = max(dm.widthPixels, dm.heightPixels)
                var sampleSize = 1
                while (options.outWidth / (sampleSize * 2) >= maxDim && options.outHeight / (sampleSize * 2) >= maxDim) {
                    sampleSize *= 2
                }

                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                stream = activity.contentResolver.openInputStream(imageUri)
                val loadedBitmap = BitmapFactory.decodeStream(stream, null, decodeOptions)
                stream?.close()

                if (loadedBitmap != null) {
                    // Extract wallpaper palette preview
                    val paletteColors = extractColorsFromBitmap(loadedBitmap)

                    activity.runOnUiThread {
                        binding.wallpaperCropView.setSourceBitmap(loadedBitmap)

                        // Update palette preview dots
                        if (paletteColors.isNotEmpty()) {
                            binding.wallpaperPalettePreviewHolder.beVisibleIf(true)
                            binding.paletteDot1.background.mutate().setColorFilter(paletteColors[0], PorterDuff.Mode.SRC_IN)
                            if (paletteColors.size > 1) {
                                binding.paletteDot2.background.mutate().setColorFilter(paletteColors[1], PorterDuff.Mode.SRC_IN)
                            }
                            if (paletteColors.size > 2) {
                                binding.paletteDot3.background.mutate().setColorFilter(paletteColors[2], PorterDuff.Mode.SRC_IN)
                            }
                        } else {
                            binding.wallpaperPalettePreviewHolder.beVisibleIf(false)
                        }

                        binding.btnApplyWallpaperCrop.setOnClickListener {
                            val targetW = dm.widthPixels
                            val targetH = dm.heightPixels
                            val cropped = binding.wallpaperCropView.cropVisibleBitmap(targetW, targetH)
                            if (cropped != null) {
                                dialog.dismiss()
                                WallpaperHelper.showTargetAndApplyCroppedBitmap(activity, cropped, onComplete)
                            } else {
                                activity.toast(R.string.wallpaper_apply_failed)
                            }
                        }
                    }
                } else {
                    activity.runOnUiThread {
                        activity.toast(R.string.wallpaper_apply_failed)
                        dialog.dismiss()
                    }
                }
            } catch (e: Exception) {
                LogCatcher.log("WallpaperCropDialog", "Failed to load image for framing", e)
                activity.runOnUiThread {
                    activity.toast(R.string.wallpaper_apply_failed)
                    dialog.dismiss()
                }
            }
        }
    }

    fun show() {
        dialog.show()
    }

    private fun extractColorsFromBitmap(bitmap: Bitmap): List<Int> {
        val colors = mutableListOf<Int>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                val wpColors = android.app.WallpaperColors.fromBitmap(bitmap)
                colors.add(wpColors.primaryColor.toArgb())
                wpColors.secondaryColor?.let { colors.add(it.toArgb()) }
                wpColors.tertiaryColor?.let { colors.add(it.toArgb()) }
                return colors
            } catch (e: Exception) {
                // fallback to sampling below
            }
        }

        // Lightweight sampling fallback
        val w = bitmap.width
        val h = bitmap.height
        colors.add(bitmap.getPixel(w / 2, h / 2))
        colors.add(bitmap.getPixel(w / 4, h / 4))
        colors.add(bitmap.getPixel(3 * w / 4, 3 * h / 4))
        return colors
    }
}
