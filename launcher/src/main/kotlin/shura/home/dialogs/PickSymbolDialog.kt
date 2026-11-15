package shura.home.dialogs

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.setupDialogStuff
import shura.home.R
import shura.home.databinding.DialogPickSymbolBinding

class PickSymbolDialog(
    val activity: Activity,
    val onSymbolSelected: (Bitmap) -> Unit
) {
    private var dialog: AlertDialog? = null

    // Curated high-utility Material vector glyphs available in the application
    private val symbolDrawables = listOf(
        R.drawable.ic_folder_vector,
        R.drawable.ic_settings_gear_vector,
        R.drawable.ic_lock_vector,
        R.drawable.ic_lock_open_vector,
        R.drawable.ic_apps_vector,
        R.drawable.ic_widget_vector,
        R.drawable.ic_popup_widget_glyph,
        R.drawable.ic_cloud_download_vector,
        R.drawable.ic_download_vector,
        R.drawable.ic_copy_vector,
        R.drawable.ic_share_vector,
        R.drawable.ic_touch_vector,
        R.drawable.ic_double_tap_vector,
        R.drawable.ic_swipe_up_vector,
        R.drawable.ic_swipe_down_vector,
        R.drawable.ic_vibrate_vector,
        org.fossify.commons.R.drawable.ic_star_vector,
        org.fossify.commons.R.drawable.ic_star_outline_vector,
        org.fossify.commons.R.drawable.ic_phone_vector,
        org.fossify.commons.R.drawable.ic_mail_vector,
        org.fossify.commons.R.drawable.ic_camera_vector,
        org.fossify.commons.R.drawable.ic_person_vector,
        org.fossify.commons.R.drawable.ic_people_vector,
        org.fossify.commons.R.drawable.ic_play_vector,
        org.fossify.commons.R.drawable.ic_pause_vector,
        org.fossify.commons.R.drawable.ic_place_vector,
        org.fossify.commons.R.drawable.ic_search_vector,
        org.fossify.commons.R.drawable.ic_pin_vector,
        org.fossify.commons.R.drawable.ic_plus_vector,
        org.fossify.commons.R.drawable.ic_save_vector,
        org.fossify.commons.R.drawable.ic_undo_vector,
        org.fossify.commons.R.drawable.ic_redo_vector,
        org.fossify.commons.R.drawable.ic_send_vector,
        org.fossify.commons.R.drawable.ic_sms_vector,
        org.fossify.commons.R.drawable.ic_print_vector,
        org.fossify.commons.R.drawable.ic_question_mark_vector
    )

    init {
        val binding = DialogPickSymbolBinding.inflate(activity.layoutInflater)
        val gridLayout = GridLayoutManager(activity, 4)
        binding.dialogSymbolsGrid.layoutManager = gridLayout
        binding.dialogSymbolsGrid.adapter = SymbolsAdapter()

        activity.getAlertDialogBuilder()
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.choose_from_symbols) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }

    private inner class SymbolsAdapter : RecyclerView.Adapter<SymbolsAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_symbol_glyph, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val resId = symbolDrawables[position]
            val drawable = ContextCompat.getDrawable(activity, resId)?.mutate()
            if (drawable != null) {
                drawable.setTint(activity.getProperPrimaryColor())
                holder.icon.setImageDrawable(drawable)
            }

            holder.itemView.setOnClickListener {
                showStyleSelectionDialog(resId)
            }
        }

        override fun getItemCount() = symbolDrawables.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val icon: ImageView = itemView.findViewById(R.id.symbol_item_icon)
        }
    }

    private fun showStyleSelectionDialog(drawableResId: Int) {
        val options = arrayOf(
            activity.getString(R.string.symbol_style_bubble),
            activity.getString(R.string.symbol_style_clean)
        )

        activity.getAlertDialogBuilder()
            .setTitle(R.string.symbol_style_dialog_title)
            .setItems(options) { _, which ->
                val withBubble = which == 0
                val bitmap = renderSymbolToIconBitmap(drawableResId, withBubble)
                onSymbolSelected(bitmap)
                dialog?.dismiss()
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun renderSymbolToIconBitmap(drawableResId: Int, withBubble: Boolean): Bitmap {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (withBubble) {
            // Draw soft squircle background plate in subtle dark neutral (#1E2229)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1E2229")
                style = Paint.Style.FILL
            }
            val cornerRadius = size * 0.28f
            val bgRect = android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat())
            canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)

            // Draw subtle outline
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#33FFFFFF")
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            val strokeRect = android.graphics.RectF(1.5f, 1.5f, size - 1.5f, size - 1.5f)
            canvas.drawRoundRect(strokeRect, cornerRadius, cornerRadius, strokePaint)
        }

        // Draw the vector glyph centered with dynamic primary color
        val drawable: Drawable? = ContextCompat.getDrawable(activity, drawableResId)?.mutate()
        if (drawable != null) {
            drawable.setTint(activity.getProperPrimaryColor())
            // When without bubble, make the symbol prominent (~70% of area)
            val glyphSize = if (withBubble) (size * 0.54f).toInt() else (size * 0.72f).toInt()
            val left = (size - glyphSize) / 2
            val top = (size - glyphSize) / 2
            drawable.setBounds(left, top, left + glyphSize, top + glyphSize)
            drawable.draw(canvas)
        }

        return bitmap
    }
}
