package shura.home.adapters

import android.app.Activity
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.normalizeString
import shura.home.databinding.ItemSelectAppBinding
import shura.home.models.AppLauncher

class SelectAppAdapter(
    val activity: Activity,
    private val allApps: List<AppLauncher>,
    val onAppClicked: (AppLauncher) -> Unit,
) : RecyclerView.Adapter<SelectAppAdapter.ViewHolder>() {

    private var displayedApps = ArrayList(allApps)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSelectAppBinding.inflate(activity.layoutInflater, parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = displayedApps[position]
        holder.bind(app)
    }

    override fun getItemCount() = displayedApps.size

    fun filter(query: String, onFilterComplete: ((count: Int) -> Unit)? = null) {
        val trimmed = query.trim()
        displayedApps = if (trimmed.isEmpty()) {
            ArrayList(allApps)
        } else {
            ArrayList(allApps.filter {
                it.title.normalizeString().contains(trimmed, ignoreCase = true)
            })
        }
        notifyDataSetChanged()
        onFilterComplete?.invoke(displayedApps.size)
    }

    inner class ViewHolder(val binding: ItemSelectAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AppLauncher) {
            binding.selectAppIcon.setImageDrawable(app.drawable)
            binding.selectAppTitle.text = app.title
            binding.selectAppSubtitle.visibility = android.view.View.GONE
            binding.root.setOnClickListener {
                onAppClicked(app)
            }
        }
    }
}
