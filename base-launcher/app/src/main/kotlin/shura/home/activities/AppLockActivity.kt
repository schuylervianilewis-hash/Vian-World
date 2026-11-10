package shura.home.activities

import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import shura.home.R
import shura.home.adapters.LockedAppsAdapter
import shura.home.databinding.ActivityAppLockBinding
import shura.home.dialogs.AddAppDialog
import shura.home.extensions.config
import shura.home.extensions.launchersDB
import shura.home.extensions.lockedAppsDB
import shura.home.helpers.APP_LOCK_TIMEOUT_1_MIN
import shura.home.helpers.APP_LOCK_TIMEOUT_5_MIN
import shura.home.helpers.APP_LOCK_TIMEOUT_IMMEDIATELY
import shura.home.helpers.APP_LOCK_TIMEOUT_SCREEN_OFF
import shura.home.helpers.AppLockManager
import shura.home.models.LockedApp

class AppLockActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityAppLockBinding::inflate)
    private var isAuthenticated = false
    private lateinit var lockedAppsAdapter: LockedAppsAdapter
    private val lockedAppsList = ArrayList<LockedApp>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        if (config.isAppLockEnabled && !isAuthenticated) {
            AppLockManager.authenticateAndLaunch(
                activity = this,
                packageName = packageName,
                appTitle = getString(R.string.app_lock)
            ) {
                isAuthenticated = true
                initActivity()
            }
        } else {
            isAuthenticated = true
            initActivity()
        }
    }

    private fun initActivity() {
        setupEdgeToEdge(padBottomSystem = listOf(binding.appLockNestedScrollview))
        setupTopAppBar(binding.appLockAppbar, NavigationIcon.Arrow)

        binding.appLockSecurityLabel.setTextColor(getProperPrimaryColor())
        binding.appLockAppsLabel.setTextColor(getProperPrimaryColor())

        setupMasterSwitch()
        setupTimeout()
        setupProtectHiddenApps()
        setupLockedAppsRecycler()
        setupAddAppButton()

        loadLockedApps()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.appLockAppbar, NavigationIcon.Arrow)
        if (isAuthenticated) {
            loadLockedApps()
        }
    }

    private fun setupMasterSwitch() {
        val isEnabled = config.isAppLockEnabled
        binding.appLockEnableSwitch.isChecked = isEnabled
        binding.appLockOptionsContainer.beVisibleIf(isEnabled)

        binding.appLockEnableHolder.setOnClickListener {
            binding.appLockEnableSwitch.toggle()
            config.isAppLockEnabled = binding.appLockEnableSwitch.isChecked
            val enabled = config.isAppLockEnabled
            binding.appLockOptionsContainer.beVisibleIf(enabled)
        }
    }

    private fun setupTimeout() {
        val currentTimeout = config.appLockTimeout
        binding.appLockTimeoutValue.text = getAppLockTimeoutText(currentTimeout)

        binding.appLockTimeoutHolder.setOnClickListener {
            val timeouts = listOf(
                RadioItem(APP_LOCK_TIMEOUT_IMMEDIATELY, getString(R.string.timeout_immediately)),
                RadioItem(APP_LOCK_TIMEOUT_1_MIN, getString(R.string.timeout_1_min)),
                RadioItem(APP_LOCK_TIMEOUT_5_MIN, getString(R.string.timeout_5_min)),
                RadioItem(APP_LOCK_TIMEOUT_SCREEN_OFF, getString(R.string.timeout_screen_off))
            )

            RadioGroupDialog(this, ArrayList(timeouts), config.appLockTimeout) { selected ->
                val newTimeout = selected as Int
                if (config.appLockTimeout != newTimeout) {
                    config.appLockTimeout = newTimeout
                    binding.appLockTimeoutValue.text = getAppLockTimeoutText(newTimeout)
                }
            }
        }
    }

    private fun getAppLockTimeoutText(timeout: Int): String {
        return when (timeout) {
            APP_LOCK_TIMEOUT_IMMEDIATELY -> getString(R.string.timeout_immediately)
            APP_LOCK_TIMEOUT_1_MIN -> getString(R.string.timeout_1_min)
            APP_LOCK_TIMEOUT_5_MIN -> getString(R.string.timeout_5_min)
            APP_LOCK_TIMEOUT_SCREEN_OFF -> getString(R.string.timeout_screen_off)
            else -> getString(R.string.timeout_immediately)
        }
    }

    private fun setupProtectHiddenApps() {
        binding.appLockProtectHiddenAppsSwitch.isChecked = config.appLockProtectHiddenApps
        binding.appLockProtectHiddenAppsHolder.setOnClickListener {
            binding.appLockProtectHiddenAppsSwitch.toggle()
            config.appLockProtectHiddenApps = binding.appLockProtectHiddenAppsSwitch.isChecked
        }
    }

    private fun setupLockedAppsRecycler() {
        lockedAppsAdapter = LockedAppsAdapter(this, lockedAppsList) { app ->
            AppLockManager.unlockApp(this, app.packageName) {
                toast(R.string.app_unlocked)
                loadLockedApps()
            }
        }

        binding.appLockRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@AppLockActivity)
            adapter = lockedAppsAdapter
        }
    }

    private fun setupAddAppButton() {
        binding.appLockAddBtn.setOnClickListener {
            ensureBackgroundThread {
                val allApps = launchersDB.getAppLaunchers()
                val lockedPkgs = lockedAppsDB.getAllLockedPackages().toSet()
                val availableApps = allApps.filter { it.packageName !in lockedPkgs }
                    .sortedBy { it.title.lowercase() }

                runOnUiThread {
                    if (availableApps.isEmpty()) {
                        toast(org.fossify.commons.R.string.no_app_found)
                    } else {
                        AddAppDialog(this@AppLockActivity, availableApps) { selected ->
                            AppLockManager.lockApp(this@AppLockActivity, selected.packageName, selected.title) {
                                toast(R.string.app_locked)
                                loadLockedApps()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadLockedApps() {
        ensureBackgroundThread {
            val apps = lockedAppsDB.getAllLockedApps().sortedBy { it.title.lowercase() }
            runOnUiThread {
                lockedAppsAdapter.updateItems(apps)
                if (apps.isEmpty()) {
                    binding.appLockEmptyPlaceholder.beVisible()
                    binding.appLockRecyclerView.beGone()
                } else {
                    binding.appLockEmptyPlaceholder.beGone()
                    binding.appLockRecyclerView.beVisible()
                }
            }
        }
    }
}
