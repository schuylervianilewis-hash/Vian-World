package shura.home.helpers

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.ConcurrentHashMap
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.SecurityDialog
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import shura.home.R
import shura.home.extensions.config
import shura.home.extensions.lockedAppsDB
import shura.home.models.LockedApp

object AppLockManager {

    private val lockedPackages = ConcurrentHashMap.newKeySet<String>()
    private val unlockedSessions = ConcurrentHashMap<String, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        ensureBackgroundThread {
            try {
                val packages = context.lockedAppsDB.getAllLockedPackages()
                lockedPackages.addAll(packages)
                isInitialized = true
            } catch (e: Exception) {
                // Ignore initial error, will retry on next operation
            }
        }
    }

    fun reload(context: Context) {
        ensureBackgroundThread {
            try {
                val packages = context.lockedAppsDB.getAllLockedPackages()
                lockedPackages.clear()
                lockedPackages.addAll(packages)
                isInitialized = true
                LogCatcher.log("AppLock", "Reloaded ${packages.size} locked packages into cache")
            } catch (e: Exception) {
                LogCatcher.log("AppLock", "Failed to reload locked packages", e)
            }
        }
    }

    fun isAppLocked(packageName: String): Boolean {
        return lockedPackages.contains(packageName)
    }

    fun isAppUnlockedSessionActive(packageName: String, timeoutSec: Int): Boolean {
        val lastUnlocked = unlockedSessions[packageName] ?: return false
        if (timeoutSec == APP_LOCK_TIMEOUT_IMMEDIATELY) {
            // Immediate relock: single use
            unlockedSessions.remove(packageName)
            return false
        }
        if (timeoutSec == APP_LOCK_TIMEOUT_SCREEN_OFF) {
            return true
        }
        val elapsedMs = System.currentTimeMillis() - lastUnlocked
        val timeoutMs = timeoutSec * 1000L
        return elapsedMs < timeoutMs
    }

    fun recordUnlock(packageName: String) {
        unlockedSessions[packageName] = System.currentTimeMillis()
    }

    fun clearSessions() {
        unlockedSessions.clear()
    }

    fun lockApp(
        context: Context,
        packageName: String,
        title: String,
        callback: (() -> Unit)? = null
    ) {
        lockedPackages.add(packageName)
        ensureBackgroundThread {
            context.lockedAppsDB.insertLockedApp(
                LockedApp(
                    packageName = packageName,
                    title = title,
                    lockedAt = System.currentTimeMillis()
                )
            )
            mainHandler.post { callback?.invoke() }
        }
    }

    fun unlockApp(
        context: Context,
        packageName: String,
        callback: (() -> Unit)? = null
    ) {
        lockedPackages.remove(packageName)
        unlockedSessions.remove(packageName)
        ensureBackgroundThread {
            context.lockedAppsDB.deleteLockedApp(packageName)
            mainHandler.post { callback?.invoke() }
        }
    }

    fun canLaunchWithoutAuth(context: Context, packageName: String): Boolean {
        if (!context.config.isAppLockEnabled) return true
        if (!isAppLocked(packageName)) return true
        return isAppUnlockedSessionActive(packageName, context.config.appLockTimeout)
    }

    fun authenticateAndLaunch(
        activity: FragmentActivity,
        packageName: String,
        appTitle: String,
        onError: (() -> Unit)? = null,
        onSuccess: () -> Unit
    ) {
        val config = activity.config
        val customPasswordHash = activity.baseConfig.appPasswordHash

        // If user set up a custom password/PIN in Fossify settings, use SecurityDialog
        if (customPasswordHash.isNotEmpty() && activity is BaseSimpleActivity) {
            SecurityDialog(
                activity = activity,
                requiredHash = customPasswordHash,
                showTabIndex = activity.baseConfig.appProtectionType
            ) { _, _, success ->
                if (success) {
                    recordUnlock(packageName)
                    onSuccess()
                } else {
                    onError?.invoke()
                }
            }
            return
        }

        // Otherwise, use AndroidX BiometricPrompt supporting Biometrics + Device Lock (PIN / Pattern / Password)
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    LogCatcher.log("AppLock", "Authentication succeeded for $packageName")
                    recordUnlock(packageName)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    LogCatcher.log("AppLock", "Authentication error ($errorCode) for $packageName")
                    onError?.invoke()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    LogCatcher.log("AppLock", "Authentication failed for $packageName")
                }
            }
        )

        val promptTitle = activity.getString(R.string.unlock_app_title, appTitle)
        val promptSubtitle = activity.getString(R.string.unlock_app_subtitle)

        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(promptTitle)
            .setSubtitle(promptSubtitle)
            .setAllowedAuthenticators(authenticators)
            .build()

        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            // Fallback for devices where BiometricPrompt encounters configuration mismatch
            if (activity is BaseSimpleActivity && customPasswordHash.isNotEmpty()) {
                SecurityDialog(
                    activity = activity,
                    requiredHash = customPasswordHash,
                    showTabIndex = activity.baseConfig.appProtectionType
                ) { _, _, success ->
                    if (success) {
                        recordUnlock(packageName)
                        onSuccess()
                    } else {
                        onError?.invoke()
                    }
                }
            } else {
                // If device has no credentials setup at all, allow launch
                onSuccess()
            }
        }
    }
}
