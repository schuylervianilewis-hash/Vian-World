package shura.home.adapters

import android.app.Activity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import shura.home.databinding.ItemLockedAppBinding
import shura.home.extensions.getDrawableForPackageName
import shura.home.models.LockedApp

class LockedAppsAdapter(
    private val activity: Activity,
    private val items: ArrayList<LockedApp>,
    private val onUnlockClicked: (LockedApp) -> Unit
) : RecyclerView.Adapter<LockedAppsAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemLockedAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLockedAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val binding = holder.binding

        binding.lockedAppTitle.text = item.title
        binding.lockedAppSubtitle.text = item.packageName
        binding.lockedAppIcon.setImageDrawable(activity.getDrawableForPackageName(item.packageName))

        binding.lockedAppUnlockBtn.setOnClickListener {
            onUnlockClicked(item)
        }
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<LockedApp>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
