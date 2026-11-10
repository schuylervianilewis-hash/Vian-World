package shura.home.adapters

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.normalizeString
import shura.home.databinding.ItemSelectAppBinding

data class ShortcutItem(
    val title: String,
    val appName: String,
    val icon: Drawable?,
    val activityInfo: ActivityInfo,
)

class SelectShortcutAdapter(
    val activity: Activity,
    private val allShortcuts: List<ShortcutItem>,
    val onShortcutClicked: (ShortcutItem) -> Unit,
) : RecyclerView.Adapter<SelectShortcutAdapter.ViewHolder>() {

    private var displayedShortcuts = ArrayList(allShortcuts)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSelectAppBinding.inflate(activity.layoutInflater, parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = displayedShortcuts[position]
        holder.bind(item)
    }

    override fun getItemCount() = displayedShortcuts.size

    fun filter(query: String, onFilterComplete: ((count: Int) -> Unit)? = null) {
        val trimmed = query.trim()
        displayedShortcuts = if (trimmed.isEmpty()) {
            ArrayList(allShortcuts)
        } else {
            ArrayList(allShortcuts.filter {
                it.title.normalizeString().contains(trimmed, ignoreCase = true) ||
                        it.appName.normalizeString().contains(trimmed, ignoreCase = true)
            })
        }
        notifyDataSetChanged()
        onFilterComplete?.invoke(displayedShortcuts.size)
    }

    inner class ViewHolder(val binding: ItemSelectAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ShortcutItem) {
            binding.selectAppIcon.setImageDrawable(item.icon)
            binding.selectAppTitle.text = item.title
            val hasSubtitle = item.appName.isNotEmpty() && item.appName != item.title
            binding.selectAppSubtitle.beVisibleIf(hasSubtitle)
            if (hasSubtitle) {
                binding.selectAppSubtitle.text = item.appName
            }
            binding.root.setOnClickListener {
                onShortcutClicked(item)
            }
        }
    }
}
