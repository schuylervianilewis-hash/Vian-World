package shura.home.activities

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import shura.home.services.NotificationBadgeService
import androidx.activity.result.contract.ActivityResultContracts
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.launchMoreAppsFromUsIntent
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isTiramisuPlus
import org.fossify.commons.models.FAQItem
import org.fossify.commons.models.RadioItem
import shura.home.BuildConfig
import shura.home.R
import shura.home.databinding.ActivitySettingsBinding
import shura.home.extensions.config
import shura.home.extensions.lockedAppsDB
import shura.home.helpers.BackupHelper
import shura.home.helpers.MAX_COLUMN_COUNT
import shura.home.helpers.MAX_ROW_COUNT
import shura.home.helpers.MIN_COLUMN_COUNT
import shura.home.helpers.MIN_ROW_COUNT
import shura.home.receivers.LockDeviceAdminReceiver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class SettingsActivity : SimpleActivity() {

    private val binding by viewBinding(ActivitySettingsBinding::inflate)

    private val createBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            ensureBackgroundThread {
                try {
                    val success = contentResolver.openOutputStream(uri)?.use { outputStream ->
                        BackupHelper.exportBackup(this, outputStream)
                    } ?: false

                    runOnUiThread {
                        if (success) {
                            toast(R.string.backup_created_successfully)
                        } else {
                            toast(R.string.backup_failed)
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        toast(R.string.backup_failed)
                    }
                }
            }
        }
    }

    private val restoreBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            ConfirmationDialog(
                activity = this,
                message = "",
                messageId = R.string.restore_confirm_message,
                positive = org.fossify.commons.R.string.yes,
                negative = org.fossify.commons.R.string.no
            ) {
                toast(R.string.restoring)
                ensureBackgroundThread {
                    try {
                        val success = contentResolver.openInputStream(uri)?.use { inputStream ->
                            BackupHelper.importBackup(this, inputStream)
                        } ?: false

                        runOnUiThread {
                            if (success) {
                                toast(R.string.backup_restored_successfully)
                                recreate()
                            } else {
                                toast(R.string.restore_failed)
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            toast(R.string.restore_failed)
                        }
                    }
                }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomSystem = listOf(binding.settingsNestedScrollview))
        setupMaterialScrollListener(binding.settingsNestedScrollview, binding.settingsAppbar)
        setupOptionsMenu()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.settingsAppbar, NavigationIcon.Arrow)
        refreshMenuItems()

        setupCustomizeColors()
        setupSetWallpaper()
        setupResetLockWallpaper()
        setupUseEnglish()
        setupLanguage()
        setupHomeRowCount()
        setupHomeColumnCount()
        setupDockColumnCount()
        setupShowHomeAppLabels()
        setupNotificationBadges()
        setupDrawerColumnCount()
        setupDrawerWallpaper()
        setupDrawerSearchBar()
        setupOpenKeyboardOnAppDrawer()
        setupCloseAppDrawerOnOtherAppOpen()
        setupShowDrawerAppLabels()
        setupGesturesAndActions()
        setupAppLock()
        setupManageHiddenIcons()
        setupViewAppLogs()
        setupBackupAndRestore()
        setupAbout()
        setupSidebarSetup()
        updateTextColors(binding.settingsHolder)

        arrayOf(
            binding.settingsColorCustomizationSectionLabel,
            binding.settingsHomeScreenLabel,
            binding.settingsDrawerSettingsLabel,
            binding.settingsGeneralSettingsLabel,
            binding.settingsPrivacySecurityLabel,
            binding.settingsBackupRestoreLabel
        ).forEach {
            it.setTextColor(getProperPrimaryColor())
        }
    }

    private fun setupOptionsMenu() {
        binding.settingsToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.about -> launchAbout()
                R.id.more_apps_from_us -> launchMoreAppsFromUsIntent()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun refreshMenuItems() {
        binding.settingsToolbar.menu.apply {
            findItem(R.id.more_apps_from_us).isVisible =
                !resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)
        }
    }

    private val pickWallpaperLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            shura.home.helpers.WallpaperHelper.showWallpaperTargetDialog(this, uri) {
                setupDrawerWallpaper()
            }
        }
    }

    private fun setupSetWallpaper() {
        binding.settingsSetWallpaperHolder.setOnClickListener {
            pickWallpaperLauncher.launch("image/*")
        }
    }

    private fun setupDrawerWallpaper() {
        val hasCustom = config.drawerUseCustomWallpaper && shura.home.helpers.WallpaperHelper.hasCustomDrawerWallpaper(this)
        binding.settingsDrawerWallpaper.text = if (hasCustom) {
            getString(R.string.drawer_bg_custom_wallpaper)
        } else {
            getString(R.string.drawer_bg_pure_black)
        }

        binding.settingsDrawerWallpaperHolder.setOnClickListener {
            val options = arrayOf(
                getString(R.string.drawer_bg_pure_black),
                getString(R.string.drawer_bg_custom_wallpaper)
            )

            getAlertDialogBuilder()
                .setTitle(R.string.drawer_wallpaper)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            shura.home.helpers.WallpaperHelper.removeDrawerWallpaper(this)
                            setupDrawerWallpaper()
                        }
                        1 -> {
                            pickWallpaperLauncher.launch("image/*")
                        }
                    }
                }
                .setNegativeButton(org.fossify.commons.R.string.cancel, null)
                .show()
        }
    }

    private fun setupCustomizeColors() {
        // Update palette preview capsule
        val primaryColor = getProperPrimaryColor()
        binding.settingsPaletteDotPrimary.background.mutate().setColorFilter(primaryColor, android.graphics.PorterDuff.Mode.SRC_IN)

        var secondaryColor = primaryColor
        var tertiaryColor = primaryColor

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            try {
                val wallpaperManager = android.app.WallpaperManager.getInstance(applicationContext)
                val wpColors = wallpaperManager.getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
                if (wpColors != null) {
                    secondaryColor = wpColors.secondaryColor?.toArgb() ?: primaryColor
                    tertiaryColor = wpColors.tertiaryColor?.toArgb() ?: wpColors.primaryColor.toArgb()
                }
            } catch (ignored: Exception) {}
        }

        binding.settingsPaletteDotSecondary.background.mutate().setColorFilter(secondaryColor, android.graphics.PorterDuff.Mode.SRC_IN)
        binding.settingsPaletteDotTertiary.background.mutate().setColorFilter(tertiaryColor, android.graphics.PorterDuff.Mode.SRC_IN)

        binding.settingsColorCustomizationHolder.setOnClickListener {
            startCustomizationActivity()
        }
    }

    private fun setupResetLockWallpaper() {
        binding.settingsResetLockWallpaperHolder.setOnClickListener {
            ConfirmationDialog(
                activity = this,
                message = "",
                messageId = R.string.reset_lock_screen_wallpaper,
                positive = org.fossify.commons.R.string.yes,
                negative = org.fossify.commons.R.string.no
            ) {
                shura.home.helpers.WallpaperHelper.resetLockScreenWallpaper(this) {
                    setupCustomizeColors()
                }
            }
        }
    }

    private fun setupUseEnglish() {
        binding.settingsUseEnglishHolder.beVisibleIf(
            beVisible = (config.wasUseEnglishToggled || Locale.getDefault().language != "en")
                    && !isTiramisuPlus()
        )

        binding.settingsUseEnglish.isChecked = config.useEnglish
        binding.settingsUseEnglishHolder.setOnClickListener {
            binding.settingsUseEnglish.toggle()
            config.useEnglish = binding.settingsUseEnglish.isChecked
            exitProcess(0)
        }
    }

    private fun setupGesturesAndActions() {
        binding.settingsGesturesAndActionsHolder.setOnClickListener {
            Intent(this, GesturesActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    private fun setupSidebarSetup() {
        binding.settingsSidebarSetupHolder.setOnClickListener {
            val intent = Intent().setClassName(packageName, "com.example.MainActivity")
                .putExtra("force_welcome", true)
            startActivity(intent)
        }
    }

    private fun setupAbout() {
        binding.settingsAboutHolder.setOnClickListener {
            launchAbout()
        }
    }

    private fun setupOpenKeyboardOnAppDrawer() {
        binding.settingsOpenKeyboardOnAppDrawerHolder.beVisibleIf(config.showSearchBar)
        binding.settingsOpenKeyboardOnAppDrawer.isChecked = config.autoShowKeyboardInAppDrawer
        binding.settingsOpenKeyboardOnAppDrawerHolder.setOnClickListener {
            binding.settingsOpenKeyboardOnAppDrawer.toggle()
            config.autoShowKeyboardInAppDrawer = binding.settingsOpenKeyboardOnAppDrawer.isChecked
        }
    }

    private fun setupCloseAppDrawerOnOtherAppOpen() {
        binding.settingsCloseAppDrawerOnOtherApp.isChecked = config.closeAppDrawer
        binding.settingsCloseAppDrawerOnOtherAppHolder.setOnClickListener {
            binding.settingsCloseAppDrawerOnOtherApp.toggle()
            config.closeAppDrawer = binding.settingsCloseAppDrawerOnOtherApp.isChecked
        }
    }

    private fun setupDrawerColumnCount() {
        val currentColumnCount = config.drawerColumnCount
        binding.settingsDrawerColumnCount.text = currentColumnCount.toString()
        binding.settingsDrawerColumnCountHolder.setOnClickListener {
            val items = ArrayList<RadioItem>()
            for (i in 1..MAX_COLUMN_COUNT) {
                items.add(
                    RadioItem(
                        id = i,
                        title = resources.getQuantityString(
                            org.fossify.commons.R.plurals.column_counts, i, i
                        )
                    )
                )
            }

            RadioGroupDialog(this, items, currentColumnCount) {
                val newColumnCount = it as Int
                if (currentColumnCount != newColumnCount) {
                    config.drawerColumnCount = newColumnCount
                    setupDrawerColumnCount()
                }
            }
        }
    }

    private fun setupDrawerSearchBar() {
        val showSearchBar = config.showSearchBar
        binding.settingsShowSearchBar.isChecked = showSearchBar
        binding.settingsDrawerSearchHolder.setOnClickListener {
            binding.settingsShowSearchBar.toggle()
            config.showSearchBar = binding.settingsShowSearchBar.isChecked
            binding.settingsOpenKeyboardOnAppDrawerHolder.beVisibleIf(config.showSearchBar)
        }
    }

    private fun setupShowDrawerAppLabels() {
        binding.settingsShowDrawerAppLabels.isChecked = config.showDrawerAppLabels
        binding.settingsShowDrawerAppLabelsHolder.setOnClickListener {
            binding.settingsShowDrawerAppLabels.toggle()
            config.showDrawerAppLabels = binding.settingsShowDrawerAppLabels.isChecked
        }
    }

    private fun setupHomeRowCount() {
        val currentRowCount = config.homeRowCount
        binding.settingsHomeScreenRowCount.text = currentRowCount.toString()
        binding.settingsHomeScreenRowCountHolder.setOnClickListener {
            val items = ArrayList<RadioItem>()
            for (i in MIN_ROW_COUNT..MAX_ROW_COUNT) {
                items.add(
                    RadioItem(
                        id = i,
                        title = resources.getQuantityString(
                            org.fossify.commons.R.plurals.row_counts, i, i
                        )
                    )
                )
            }

            RadioGroupDialog(this, items, currentRowCount) {
                val newRowCount = it as Int
                if (currentRowCount != newRowCount) {
                    config.homeRowCount = newRowCount
                    setupHomeRowCount()
                }
            }
        }
    }

    private fun setupHomeColumnCount() {
        val currentColumnCount = config.homeColumnCount
        binding.settingsHomeScreenColumnCount.text = currentColumnCount.toString()
        binding.settingsHomeScreenColumnCountHolder.setOnClickListener {
            val items = ArrayList<RadioItem>()
            for (i in MIN_COLUMN_COUNT..MAX_COLUMN_COUNT) {
                items.add(
                    RadioItem(
                        id = i,
                        title = resources.getQuantityString(
                            org.fossify.commons.R.plurals.column_counts, i, i
                        )
                    )
                )
            }

            RadioGroupDialog(this, items, currentColumnCount) {
                val newColumnCount = it as Int
                if (currentColumnCount != newColumnCount) {
                    config.homeColumnCount = newColumnCount
                    setupHomeColumnCount()
                }
            }
        }
    }

    private fun setupDockColumnCount() {
        val currentColumnCount = config.dockColumnCount
        binding.settingsDockColumnCount.text = currentColumnCount.toString()
        binding.settingsDockColumnCountHolder.setOnClickListener {
            val items = ArrayList<RadioItem>()
            for (i in MIN_COLUMN_COUNT..MAX_COLUMN_COUNT) {
                items.add(
                    RadioItem(
                        id = i,
                        title = resources.getQuantityString(
                            org.fossify.commons.R.plurals.column_counts, i, i
                        )
                    )
                )
            }

            RadioGroupDialog(this, items, currentColumnCount) {
                val newColumnCount = it as Int
                if (currentColumnCount != newColumnCount) {
                    config.dockColumnCount = newColumnCount
                    setupDockColumnCount()
                }
            }
        }
    }

    private fun setupShowHomeAppLabels() {
        binding.settingsShowHomeAppLabels.isChecked = config.showHomeAppLabels
        binding.settingsShowHomeAppLabelsHolder.setOnClickListener {
            binding.settingsShowHomeAppLabels.toggle()
            config.showHomeAppLabels = binding.settingsShowHomeAppLabels.isChecked
        }
    }

    private fun setupNotificationBadges() {
        val hasAccess = NotificationBadgeService.isNotificationAccessGranted(this)
        binding.settingsNotificationBadges.isChecked = config.showNotificationBadges && hasAccess
        binding.settingsNotificationBadgesHolder.setOnClickListener {
            val isCurrentlyChecked = binding.settingsNotificationBadges.isChecked
            val targetState = !isCurrentlyChecked
            if (targetState) {
                if (!NotificationBadgeService.isNotificationAccessGranted(this)) {
                    try {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    } catch (e: Exception) {
                        toast(org.fossify.commons.R.string.unknown_error_occurred)
                    }
                }
                config.showNotificationBadges = true
                binding.settingsNotificationBadges.isChecked = true
            } else {
                config.showNotificationBadges = false
                binding.settingsNotificationBadges.isChecked = false
            }
        }
    }

    @SuppressLint("NewApi")
    private fun setupLanguage() {
        binding.settingsLanguage.text = Locale.getDefault().displayLanguage
        binding.settingsLanguageHolder.beVisibleIf(isTiramisuPlus())
        binding.settingsLanguageHolder.setOnClickListener {
            launchChangeAppLanguageIntent()
        }
    }

    private fun setupManageHiddenIcons() {
        binding.settingsManageHiddenIconsHolder.setOnClickListener {
            startActivity(Intent(this, HiddenIconsActivity::class.java))
        }
    }

    private fun setupAppLock() {
        val isEnabled = config.isAppLockEnabled
        ensureBackgroundThread {
            val lockedCount = lockedAppsDB.getAllLockedPackages().size
            runOnUiThread {
                binding.settingsAppLockDesc.text = if (isEnabled) {
                    if (lockedCount > 0) {
                        getString(R.string.app_lock_enabled_count, lockedCount)
                    } else {
                        getString(R.string.app_lock_status_enabled)
                    }
                } else {
                    getString(R.string.app_lock_status_disabled)
                }
            }
        }

        binding.settingsAppLockHolder.setOnClickListener {
            startActivity(Intent(this, AppLockActivity::class.java))
        }
    }

    private fun setupViewAppLogs() {
        binding.settingsViewAppLogsHolder.setOnClickListener {
            val intent = Intent().setClassName(packageName, "com.example.LogKeeperActivity").apply {
                putExtra("extra_launch_source", "SOURCE_SETTINGS")
            }
            startActivity(intent)
        }
        // Long press triggers a deliberate test crash, so the crash-logging
        // pipeline can be verified without waiting for a real bug. Debug/testing
        // aid only — safe to remove once the log keeper is trusted.
        binding.settingsViewAppLogsHolder.setOnLongClickListener {
            throw TestCrashException("Test crash triggered from Settings (long press on View app logs)")
        }
    }

    private fun setupBackupAndRestore() {
        binding.settingsBackupHolder.setOnClickListener {
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            createBackupLauncher.launch("vian_launcher_backup_$dateStr.json")
        }

        binding.settingsRestoreHolder.setOnClickListener {
            restoreBackupLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        }
    }

    /** Thrown only by the deliberate test-crash trigger above, to exercise the log keeper's crash handler on demand. */
    private class TestCrashException(message: String) : Exception(message)

    private fun launchAbout() {
        val licenses = 0L
        val faqItems = ArrayList<FAQItem>()

        faqItems.add(
            FAQItem(
                title = getString(R.string.faq_about_app_title),
                text = getString(R.string.faq_about_app_text)
            )
        )
        faqItems.add(
            FAQItem(
                title = getString(R.string.faq_fork_title),
                text = getString(R.string.faq_fork_text)
            )
        )

        startAboutActivity(
            appNameId = R.string.app_name,
            licenseMask = licenses,
            versionName = "${BuildConfig.VERSION_NAME} (Viabhron Series)",
            faqItems = faqItems,
            showFAQBeforeMail = false
        )
    }
}
