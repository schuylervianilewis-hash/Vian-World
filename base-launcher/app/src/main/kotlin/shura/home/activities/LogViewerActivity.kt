package shura.home.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.ensureBackgroundThread
import shura.home.R
import shura.home.adapters.LogEntriesAdapter
import shura.home.databinding.ActivityLogViewerBinding
import shura.home.extensions.config
import shura.home.helpers.LogCatcher

class LogViewerActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityLogViewerBinding::inflate)
    private val adapter = LogEntriesAdapter()
    private var allEntries = listOf<LogCatcher.LogEntry>()
    private var currentFilter = TimeFilter.ALL

    private enum class TimeFilter(val durationMillis: Long) {
        SIX_HOURS(6L * 3600L * 1000L),
        TWELVE_HOURS(12L * 3600L * 1000L),
        TWENTY_FOUR_HOURS(24L * 3600L * 1000L),
        ALL(Long.MAX_VALUE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(
            padTopSystem = listOf(binding.logViewerRoot),
            padBottomSystem = listOf(binding.logViewerRecycler)
        )
        setupTopBarActions()
        setupTimeFilterTabs()
        setupRecyclerView()
        loadLogs()
    }

    override fun onResume() {
        super.onResume()
        binding.logViewerMasterSwitch.isChecked = config.logKeeperEnabled
        loadLogs()
    }

    private fun setupTopBarActions() {
        binding.logViewerBackBtn.setOnClickListener {
            finish()
        }

        binding.logViewerMasterSwitch.isChecked = config.logKeeperEnabled
        binding.logViewerMasterSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.logKeeperEnabled = isChecked
            if (isChecked) {
                toast(R.string.logging_enabled)
                loadLogs()
            } else {
                toast(R.string.logging_disabled)
            }
        }

        binding.logViewerCopyBtn.setOnClickListener {
            copyCurrentLogsToClipboard()
        }

        binding.logViewerDownloadBtn.setOnClickListener {
            dumpLogsToDownloadFolder()
        }
    }

    private fun setupTimeFilterTabs() {
        binding.tabFilter6h.setOnClickListener { selectTimeFilter(TimeFilter.SIX_HOURS) }
        binding.tabFilter12h.setOnClickListener { selectTimeFilter(TimeFilter.TWELVE_HOURS) }
        binding.tabFilter24h.setOnClickListener { selectTimeFilter(TimeFilter.TWENTY_FOUR_HOURS) }
        binding.tabFilterAll.setOnClickListener { selectTimeFilter(TimeFilter.ALL) }
        updateTabIndicators()
    }

    private fun selectTimeFilter(filter: TimeFilter) {
        currentFilter = filter
        updateTabIndicators()
        applyFilter()
    }

    private fun updateTabIndicators() {
        binding.tabIndicator6h.visibility = if (currentFilter == TimeFilter.SIX_HOURS) View.VISIBLE else View.INVISIBLE
        binding.tabIndicator12h.visibility = if (currentFilter == TimeFilter.TWELVE_HOURS) View.VISIBLE else View.INVISIBLE
        binding.tabIndicator24h.visibility = if (currentFilter == TimeFilter.TWENTY_FOUR_HOURS) View.VISIBLE else View.INVISIBLE
        binding.tabIndicatorAll.visibility = if (currentFilter == TimeFilter.ALL) View.VISIBLE else View.INVISIBLE
    }

    private fun setupRecyclerView() {
        binding.logViewerRecycler.layoutManager = LinearLayoutManager(this)
        binding.logViewerRecycler.adapter = adapter
    }

    private fun loadLogs() {
        ensureBackgroundThread {
            allEntries = LogCatcher.getLogEntries()
            runOnUiThread {
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val now = System.currentTimeMillis()
        val filtered = if (currentFilter == TimeFilter.ALL) {
            allEntries
        } else {
            val cutoff = now - currentFilter.durationMillis
            allEntries.filter { it.timestampMillis >= cutoff }
        }

        adapter.setEntries(filtered)
        val isEmpty = filtered.isEmpty()
        binding.logViewerPlaceholder.beVisibleIf(isEmpty)
        binding.logViewerRecycler.beVisibleIf(!isEmpty)
    }

    private fun copyCurrentLogsToClipboard() {
        val now = System.currentTimeMillis()
        val filtered = if (currentFilter == TimeFilter.ALL) {
            allEntries
        } else {
            val cutoff = now - currentFilter.durationMillis
            allEntries.filter { it.timestampMillis >= cutoff }
        }

        if (filtered.isEmpty()) {
            toast(R.string.no_logs_yet)
            return
        }

        val textToCopy = buildString {
            for (entry in filtered) {
                append(entry.timestampDisplay).append(" [").append(entry.tag).append("] ").append(entry.message).append("\n")
                if (!entry.throwableDetails.isNullOrBlank()) {
                    append(entry.throwableDetails).append("\n")
                }
            }
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Vian Logs", textToCopy)
        clipboard.setPrimaryClip(clip)
        toast(R.string.logs_copied)
    }

    private fun dumpLogsToDownloadFolder() {
        ensureBackgroundThread {
            val success = LogCatcher.dumpLogsToDownloadFolder(this)
            runOnUiThread {
                if (success) {
                    toast(R.string.logs_dumped)
                } else {
                    toast(R.string.no_logs_yet)
                }
            }
        }
    }
}
