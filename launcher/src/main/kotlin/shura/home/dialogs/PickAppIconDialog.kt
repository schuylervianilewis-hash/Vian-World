package shura.home.dialogs

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import shura.home.R
import shura.home.databinding.DialogPickAppIconBinding
import shura.home.databinding.ItemPickAppIconBinding
import shura.home.extensions.getDrawableForPackageName
import shura.home.helpers.CustomIconManager
import shura.home.helpers.IconCache
import shura.home.models.AppLauncher

class PickAppIconDialog(
    val activity: Activity,
    val callback: (Bitmap) -> Unit
) {
    init {
        val binding = DialogPickAppIconBinding.inflate(activity.layoutInflater)
        val allLaunchers = IconCache.launchers.ifEmpty {
            (activity as? shura.home.activities.MainActivity)?.getAllAppLaunchers() ?: emptyList()
        }.sortedBy { it.title.lowercase() }

        var displayedList = allLaunchers.toList()
        var alertDialog: AlertDialog? = null

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val itemBinding = ItemPickAppIconBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                return object : RecyclerView.ViewHolder(itemBinding.root) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = displayedList[position]
                val itemBinding = ItemPickAppIconBinding.bind(holder.itemView)
                itemBinding.appTitle.text = item.title

                val cachedDrawable = IconCache.getDrawable(item.getLauncherIdentifier())
                    ?: activity.getDrawableForPackageName(item.packageName)
                itemBinding.appIcon.setImageDrawable(cachedDrawable)

                itemBinding.root.setOnClickListener {
                    val drawable = itemBinding.appIcon.drawable
                    if (drawable != null) {
                        val bitmap = drawableToBitmap(drawable)
                        val scaled = CustomIconManager.downsampleBitmap(bitmap)
                        callback(scaled)
                    }
                    alertDialog?.dismiss()
                }
            }

            override fun getItemCount() = displayedList.size
        }

        binding.appsRecyclerView.layoutManager = LinearLayoutManager(activity)
        binding.appsRecyclerView.adapter = adapter

        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim()?.lowercase().orEmpty()
                displayedList = if (query.isEmpty()) {
                    allLaunchers
                } else {
                    allLaunchers.filter { it.title.lowercase().contains(query) }
                }
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        activity.getAlertDialogBuilder()
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.choose_from_apps) { dialog ->
                    alertDialog = dialog
                }
            }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }

        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else CustomIconManager.MAX_ICON_SIZE
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else CustomIconManager.MAX_ICON_SIZE
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
