/*
 * Internet Pause adds a temporary, service-owned enforcement overlay.
 * Permanent per-app preferences and the persistent InternetBlocklist are never
 * rewritten, so disabling the overlay restores the exact underlying policy.
 */
package net.kollnig.missioncontrol.data;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import eu.faircode.netguard.Rule;
import eu.faircode.netguard.ServiceSinkhole;
import eu.faircode.netguard.ShieldControl;

/** Coordinates the durable Internet Pause request and its temporary VPN overlay. */
public final class InternetPauseManager {
    public static final String ACTION_STATE_CHANGED =
            "net.kollnig.missioncontrol.action.INTERNET_PAUSE_STATE_CHANGED";
    static final String METHOD_GET_STATE = "get_internet_pause_state";
    static final String METHOD_SET_PAUSED = "set_internet_pause";
    static final String METHOD_PREPARE_VPN = "prepare_internet_pause_vpn";
    static final String METHOD_CANCEL_PREPARATION = "cancel_internet_pause_preparation";
    static final String KEY_REQUESTED_PAUSED = "requested_paused";
    static final String KEY_SUCCESS = "success";
    static final String KEY_VPN_ESTABLISHED = "vpn_established";
    static final String PREFS_NAME = "internet_pause";
    static final String KEY_ACTIVE = "active";
    static final String KEY_APPLIED = "applied";
    static final String KEY_PREPARED = "prepared";
    static final String KEY_PREVIOUS_FIREWALL_ENABLED = "previous_firewall_enabled";
    static final String KEY_PREPARED_AT = "prepared_at";
    private static final long PREPARATION_TIMEOUT_MS = 60_000L;
    private static final Object LOCK = new Object();

    private InternetPauseManager() {
    }

    /** Internal stop requests are allowed to unwind the pause preparation itself. */
    public static boolean isInternalPauseSource(String source) {
        return source != null && source.startsWith("internet_pause");
    }

    /** A failed native recovery must be allowed to tear down the unusable VPN. */
    public static boolean isRecoveryStopSource(String source) {
        return "native recovery exhausted".equals(source);
    }

    /** Prevent user/background stop paths from dropping enforcement while paused. */
    public static boolean shouldBlockFirewallStop(boolean pauseActive, String source) {
        return pauseActive && !isInternalPauseSource(source) && !isRecoveryStopSource(source);
    }

    /** Read desired/applied VPN state from the provider in the default process. */
    private static Bundle queryServiceState(Context context) {
        Context appContext = context.getApplicationContext();
        Uri uri = Uri.parse("content://" + appContext.getPackageName() + ".internetpause.state");
        try {
            return appContext.getContentResolver().call(uri, METHOD_GET_STATE, null, null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static boolean isActive(Context context) {
        Bundle state = queryServiceState(context);
        return state != null && state.getBoolean(KEY_ACTIVE, false);
    }

    public static boolean isApplied(Context context) {
        Bundle state = queryServiceState(context);
        return state != null && state.getBoolean(KEY_ACTIVE, false)
                && state.getBoolean(KEY_APPLIED, false)
                && state.getBoolean(KEY_VPN_ESTABLISHED, false);
    }

    public static boolean isRecovering(Context context) {
        Bundle state = queryServiceState(context);
        return state != null && state.getBoolean(KEY_ACTIVE, false)
                && !state.getBoolean(KEY_APPLIED, false);
    }

    public static boolean isVpnReady(Context context) {
        Bundle state = queryServiceState(context);
        return VpnService.prepare(context.getApplicationContext()) == null
                && state != null && state.getBoolean(KEY_VPN_ESTABLISHED, false);
    }

    /** Route state changes through the default-process provider; never write from :heavy. */
    public static boolean setPaused(Context context, boolean paused) {
        if (context == null)
            return false;
        Context appContext = context.getApplicationContext();
        Bundle extras = new Bundle();
        extras.putBoolean(KEY_REQUESTED_PAUSED, paused);
        Uri uri = Uri.parse("content://" + appContext.getPackageName() + ".internetpause.state");
        try {
            Bundle result = appContext.getContentResolver().call(uri, METHOD_SET_PAUSED, null, extras);
            return result != null && result.getBoolean(KEY_SUCCESS, false);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Ask the default process to prepare the firewall VPN for a pause request. */
    public static boolean prepareVpnForPause(Context context) {
        if (context == null)
            return false;
        Context appContext = context.getApplicationContext();
        if (VpnService.prepare(appContext) != null)
            return false;
        Uri uri = Uri.parse("content://" + appContext.getPackageName() + ".internetpause.state");
        try {
            Bundle result = appContext.getContentResolver().call(uri, METHOD_PREPARE_VPN, null, null);
            return result != null && result.getBoolean(KEY_SUCCESS, false);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Cancel a not-yet-active pause and restore the firewall's previous enabled state. */
    public static boolean cancelPausePreparation(Context context) {
        if (context == null)
            return false;
        Context appContext = context.getApplicationContext();
        Uri uri = Uri.parse("content://" + appContext.getPackageName() + ".internetpause.state");
        try {
            Bundle result = appContext.getContentResolver().call(uri, METHOD_CANCEL_PREPARATION, null, null);
            return result != null && result.getBoolean(KEY_SUCCESS, false);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Provider entry point; runs in the default process with ServiceSinkhole. */
    static Bundle handleProviderCall(Context context, String method, Bundle extras) {
        Context appContext = context.getApplicationContext();
        if (METHOD_GET_STATE.equals(method)) {
            recoverStalePreparation(appContext);
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean vpnEstablished = ServiceSinkhole.isVpnEstablished();
            Bundle result = new Bundle();
            result.putBoolean(KEY_ACTIVE, prefs.getBoolean(KEY_ACTIVE, false));
            result.putBoolean(KEY_APPLIED, prefs.getBoolean(KEY_ACTIVE, false)
                    && prefs.getBoolean(KEY_APPLIED, false) && vpnEstablished);
            result.putBoolean(KEY_VPN_ESTABLISHED, vpnEstablished);
            return result;
        }
        if (METHOD_PREPARE_VPN.equals(method)) {
            Bundle result = new Bundle();
            result.putBoolean(KEY_SUCCESS, prepareVpnInServiceProcess(appContext));
            return result;
        }
        if (METHOD_CANCEL_PREPARATION.equals(method)) {
            Bundle result = new Bundle();
            result.putBoolean(KEY_SUCCESS, cancelPreparationInServiceProcess(appContext));
            return result;
        }
        if (METHOD_SET_PAUSED.equals(method)) {
            boolean paused = extras != null && extras.getBoolean(KEY_REQUESTED_PAUSED, false);
            Bundle result = new Bundle();
            result.putBoolean(KEY_SUCCESS, setPausedInServiceProcess(appContext, paused));
            return result;
        }
        return null;
    }

    private static boolean prepareVpnInServiceProcess(Context appContext) {
        if (VpnService.prepare(appContext) != null)
            return false;
        synchronized (LOCK) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean active = prefs.getBoolean(KEY_ACTIVE, false);
            boolean prepared = prefs.getBoolean(KEY_PREPARED, false);
            if (!active && !prepared) {
                boolean previousFirewallEnabled = ShieldControl.isEnabled(appContext);
                if (!prefs.edit()
                        .putBoolean(KEY_PREPARED, true)
                        .putBoolean(KEY_PREVIOUS_FIREWALL_ENABLED, previousFirewallEnabled)
                        .putLong(KEY_PREPARED_AT, System.currentTimeMillis())
                        .commit())
                    return false;
            }
            if (!ShieldControl.isEnabled(appContext) || !ServiceSinkhole.isVpnEstablished())
                ShieldControl.setEnabled(appContext, true, "internet_pause_prepare");
            return true;
        }
    }

    private static boolean cancelPreparationInServiceProcess(Context appContext) {
        synchronized (LOCK) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_ACTIVE, false))
                return false;
            InternetBlocklist.getInstance(appContext).clearTemporaryBlockedUids();
            boolean restored = restorePreparedFirewallState(appContext, prefs, "internet_pause_cancel");
            notifyStateChanged(appContext);
            return restored;
        }
    }

    private static void recoverStalePreparation(Context appContext) {
        synchronized (LOCK) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_ACTIVE, false) || !prefs.getBoolean(KEY_PREPARED, false))
                return;
            long preparedAt = prefs.getLong(KEY_PREPARED_AT, 0L);
            if (preparedAt <= 0L || System.currentTimeMillis() - preparedAt < PREPARATION_TIMEOUT_MS)
                return;
            InternetBlocklist.getInstance(appContext).clearTemporaryBlockedUids();
            restorePreparedFirewallState(appContext, prefs, "internet_pause_prepare_timeout");
            notifyStateChanged(appContext);
        }
    }

    private static boolean restorePreparedFirewallState(Context appContext,
            SharedPreferences prefs, String source) {
        if (!prefs.getBoolean(KEY_PREPARED, false))
            return true;
        boolean previousFirewallEnabled = prefs.getBoolean(KEY_PREVIOUS_FIREWALL_ENABLED, true);
        if (!previousFirewallEnabled && ShieldControl.isEnabled(appContext))
            ShieldControl.setEnabled(appContext, false, source);
        return prefs.edit()
                .remove(KEY_PREPARED)
                .remove(KEY_PREVIOUS_FIREWALL_ENABLED)
                .remove(KEY_PREPARED_AT)
                .commit();
    }

    static boolean shouldRestoreFirewallOff(boolean prepared, boolean previousFirewallEnabled) {
        return prepared && !previousFirewallEnabled;
    }

    private static boolean setPausedInServiceProcess(Context appContext, boolean paused) {
        synchronized (LOCK) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean wasActive = prefs.getBoolean(KEY_ACTIVE, false);
            boolean wasApplied = prefs.getBoolean(KEY_APPLIED, false);
            boolean wasPrepared = prefs.getBoolean(KEY_PREPARED, false);

            if (paused) {
                if (VpnService.prepare(appContext) != null || !ServiceSinkhole.isVpnEstablished())
                    return false;
                if (!wasActive && !wasPrepared) {
                    boolean previousFirewallEnabled = ShieldControl.isEnabled(appContext);
                    if (!prefs.edit()
                            .putBoolean(KEY_PREPARED, true)
                            .putBoolean(KEY_PREVIOUS_FIREWALL_ENABLED, previousFirewallEnabled)
                            .putLong(KEY_PREPARED_AT, System.currentTimeMillis())
                            .commit())
                        return false;
                    wasPrepared = true;
                }
                if (wasActive && wasApplied && ServiceSinkhole.isVpnEstablished())
                    return true;
                if (!prefs.edit().putBoolean(KEY_ACTIVE, true).putBoolean(KEY_APPLIED, false).commit())
                    return false;
                notifyStateChanged(appContext);
                ServiceSinkhole.reload("internet pause enabled", appContext, false);
                return true;
            }

            boolean previousFirewallEnabled = prefs.getBoolean(KEY_PREVIOUS_FIREWALL_ENABLED, true);
            if (!wasActive && !wasPrepared) {
                InternetBlocklist.getInstance(appContext).clearTemporaryBlockedUids();
                if (wasApplied && !prefs.edit().putBoolean(KEY_APPLIED, false).commit())
                    return false;
                return true;
            }

            if (!prefs.edit().putBoolean(KEY_ACTIVE, false).putBoolean(KEY_APPLIED, false).commit())
                return false;
            InternetBlocklist.getInstance(appContext).clearTemporaryBlockedUids();
            notifyStateChanged(appContext);

            if (shouldRestoreFirewallOff(wasPrepared, previousFirewallEnabled)) {
                if (ShieldControl.isEnabled(appContext))
                    ShieldControl.setEnabled(appContext, false, "internet_pause_restore");
            } else if (ServiceSinkhole.isVpnEstablished()) {
                ServiceSinkhole.reload("internet pause disabled", appContext, false);
            }

            if (wasPrepared && !restorePreparedFirewallState(appContext, prefs, "internet_pause_restore"))
                return false;
            return true;
        }
    }

    /**
     * Overlay package-backed app rules, including ordinary system apps, in the
     * service's fresh inventory. Bypassed apps are temporarily included in the
     * TUN, then blocked before any direct/WireGuard forwarding occurs.
     */
    public static void applyToRules(Context context, List<Rule> rules) {
        Context appContext = context.getApplicationContext();
        InternetBlocklist blocklist = InternetBlocklist.getInstance(appContext);
        boolean pauseActive = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ACTIVE, false);
        if (!pauseActive || rules == null || rules.isEmpty()) {
            blocklist.clearTemporaryBlockedUids();
            return;
        }

        // A UID shared with a non-package daemon or a critical platform
        // identity must stay outside the pause overlay. UID enforcement is
        // shared by every package in that UID, so protecting only one Rule
        // would not be sufficient.
        Set<Integer> protectedUids = new HashSet<>();
        for (Rule rule : rules) {
            if (rule == null || rule.uid < 0)
                continue;
            if (!rule.pkg || InternetPermissionPolicy.isProtectedIdentity(
                    appContext, rule.packageName, rule.uid))
                protectedUids.add(rule.uid);
        }

        Set<Integer> temporaryBlockedUids = new HashSet<>();
        for (Rule rule : rules) {
            if (rule == null || rule.uid < 0)
                continue;
            boolean protectedIdentity = InternetPermissionPolicy.isProtectedIdentity(
                    appContext, rule.packageName, rule.uid);
            if (!rule.pkg || !isEligibleUid(rule.uid, protectedUids) || protectedIdentity) {
                // Critical identities and synthetic daemon rows are explicitly
                // kept out of the pause VPN. This is a runtime Rule mutation;
                // no permanent per-app preference is changed.
                rule.apply = false;
                continue;
            }
            if (!isEligiblePackageRule(rule.pkg, rule.system, protectedIdentity))
                continue;

            // Include ordinary preinstalled/system packages even when the
            // user's normal "include system apps in VPN" setting is off. The
            // temporary UID block is enforced before direct/WireGuard egress.
            rule.apply = true;
            temporaryBlockedUids.add(rule.uid);
        }
        blocklist.setTemporaryBlockedUids(temporaryBlockedUids);
    }

    static boolean isEligibleUserRule(boolean packageRule, boolean systemRule,
            boolean protectedIdentity) {
        // System classification is not an exclusion: package-backed system
        // apps are blockable. Only non-package and protected identities are
        // exempt. Keep the parameter to make the distinction explicit at call
        // sites and preserve a focused pure-JVM test seam.
        return packageRule && !protectedIdentity;
    }

    /** System packages must enter the TUN while pause is active, regardless of normal preference. */
    public static boolean shouldIncludeSystemApps(boolean includeSystemPreference, boolean pauseActive) {
        return includeSystemPreference || pauseActive;
    }

    /** Read pause state directly in the VPN service process without a provider round-trip. */
    public static boolean isPauseEnforcementRequested(Context context) {
        return context != null && context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ACTIVE, false);
    }

    static boolean isEligibleUid(int uid, Set<Integer> protectedUids) {
        return uid >= 0 && (protectedUids == null || !protectedUids.contains(uid));
    }

    /** Called only after the service has rebuilt the VPN successfully. */
    public static void markApplied(Context context) {
        Context appContext = context.getApplicationContext();
        boolean applied = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ACTIVE, false) && ServiceSinkhole.isVpnEstablished();
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_APPLIED, applied).commit();
        notifyStateChanged(appContext);
    }

    /** Retain the requested state, but never claim that a stopped VPN is enforcing it. */
    public static void markNotApplied(Context context) {
        if (context == null)
            return;
        Context appContext = context.getApplicationContext();
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_APPLIED, false).commit();
        notifyStateChanged(appContext);
    }

    private static void notifyStateChanged(Context context) {
        Intent intent = new Intent(ACTION_STATE_CHANGED).setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }
}
