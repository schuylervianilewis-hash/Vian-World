package shura.home.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import shura.home.databinding.ItemLogEntryBinding
import shura.home.helpers.LogCatcher

class LogEntriesAdapter(
    private var entries: List<LogCatcher.LogEntry> = emptyList()
) : RecyclerView.Adapter<LogEntriesAdapter.LogViewHolder>() {

    private val expandedPositions = mutableSetOf<Int>()

    fun setEntries(newEntries: List<LogCatcher.LogEntry>) {
        entries = newEntries
        expandedPositions.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogEntryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(entries[position], position)
    }

    override fun getItemCount(): Int = entries.size

    inner class LogViewHolder(private val binding: ItemLogEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: LogCatcher.LogEntry, position: Int) {
            binding.logItemTimestamp.text = entry.timestampDisplay
            binding.logItemTag.text = entry.tag
            binding.logItemMessage.text = entry.message

            val hasStackTrace = !entry.throwableDetails.isNullOrBlank()
            val isExpanded = expandedPositions.contains(position)

            if (hasStackTrace) {
                binding.logItemStackTrace.text = entry.throwableDetails
                binding.logItemStackTrace.visibility = if (isExpanded) View.VISIBLE else View.GONE
                binding.root.setOnClickListener {
                    if (isExpanded) {
                        expandedPositions.remove(position)
                        binding.logItemStackTrace.visibility = View.GONE
                    } else {
                        expandedPositions.add(position)
                        binding.logItemStackTrace.visibility = View.VISIBLE
                    }
                }
            } else {
                binding.logItemStackTrace.visibility = View.GONE
                binding.root.setOnClickListener(null)
            }
        }
    }
}
