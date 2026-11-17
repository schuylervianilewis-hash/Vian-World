/*
 * Internet Pause adds a temporary, service-owned enforcement overlay.
 * Permanent per-app preferences and the persistent InternetBlocklist are never
 * rewritten, so disabling the overlay restores the exact underlying policy.
 */
package net.kollnig.missioncontrol.data;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import eu.faircode.netguard.Rule;
import eu.faircode.netguard.ServiceSinkhole;

/** Coordinates the durable Internet Pause request and its temporary VPN overlay. */
public final class InternetPauseManager {
    public static final String ACTION_STATE_CHANGED =
            "net.kollnig.missioncontrol.action.INTERNET_PAUSE_STATE_CHANGED";
    static final String PREFS_NAME = "internet_pause";
    static final String KEY_ACTIVE = "active";
    static final String KEY_APPLIED = "applied";
    private static final Object LOCK = new Object();

    private InternetPauseManager() {
    }

    /** Whether the user has requested the global pause, including recovery periods. */
    public static boolean isActive(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ACTIVE, false);
    }

    /**
     * True only after the running VPN service has successfully applied the
     * temporary rule overlay. A persisted request alone is not enforcement.
     */
    public static boolean isApplied(Context context) {
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_ACTIVE, false)
                && prefs.getBoolean(KEY_APPLIED, false)
                && ServiceSinkhole.isVpnEstablished();
    }

    /** Requested but not currently enforced: the service is starting or recovering. */
    public static boolean isRecovering(Context context) {
        return isActive(context) && !isApplied(context);
    }

    /** VPN authorization and an established service are both required to activate. */
    public static boolean isVpnReady(Context context) {
        Context appContext = context.getApplicationContext();
        return VpnService.prepare(appContext) == null && ServiceSinkhole.isVpnEstablished();
    }

    /**
     * Request pause/resume. Activation is rejected until the VPN is authorized
     * and established. Resume is always allowed and clears the in-memory block
     * overlay immediately; permanent per-app state is untouched.
     */
    public static boolean setPaused(Context context, boolean paused) {
        if (context == null)
            return false;
        Context appContext = context.getApplicationContext();
        synchronized (LOCK) {
            if (paused && !isVpnReady(appContext))
                return false;

            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean wasActive = prefs.getBoolean(KEY_ACTIVE, false);
            boolean wasApplied = prefs.getBoolean(KEY_APPLIED, false);
            if (wasActive == paused && (!paused || (wasApplied && ServiceSinkhole.isVpnEstablished()))) {
                if (!paused)
                    InternetBlocklist.getInstance(appContext).clearTemporaryBlockedUids();
                return true;
            }

            if (!prefs.edit()
                    .putBoolean(KEY_ACTIVE, paused)
                    .putBoolean(KEY_APPLIED, false)
                    .commit())
                return false;

            if (!paused)
                InternetBlocklist.getInstance(appContext).clearTemporaryBlockedUids();

            notifyStateChanged(appContext);
            if (ServiceSinkhole.isVpnEstablished())
                ServiceSinkhole.reload("internet pause changed", appContext, false);
            return true;
        }
    }

    /**
     * Overlay only eligible user-app rules in the service's fresh inventory.
     * Bypassed apps are temporarily included in the TUN, then blocked by the
     * temporary UID set before any direct/WireGuard forwarding occurs.
     */
    public static void applyToRules(Context context, List<Rule> rules) {
        Context appContext = context.getApplicationContext();
        InternetBlocklist blocklist = InternetBlocklist.getInstance(appContext);
        if (!isActive(appContext) || rules == null || rules.isEmpty()) {
            blocklist.clearTemporaryBlockedUids();
            return;
        }

        Set<Integer> protectedUids = new HashSet<>();
        for (Rule rule : rules) {
            if (rule == null || rule.uid < 0)
                continue;
            if (!rule.pkg || rule.system || InternetPermissionPolicy.isProtectedIdentity(
                    appContext, rule.packageName, rule.uid))
                protectedUids.add(rule.uid);
        }

        Set<Integer> temporaryBlockedUids = new HashSet<>();
        for (Rule rule : rules) {
            if (rule == null || !isEligibleUid(rule.uid, protectedUids))
                continue;
            boolean protectedIdentity = InternetPermissionPolicy.isProtectedIdentity(
                    appContext, rule.packageName, rule.uid);
            if (!isEligibleUserRule(rule.pkg, rule.system, protectedIdentity))
                continue;

            // This changes only the in-memory Rule object used to build the
            // current VPN. The permanent "apply" preference is never written.
            rule.apply = true;
            temporaryBlockedUids.add(rule.uid);
        }
        blocklist.setTemporaryBlockedUids(temporaryBlockedUids);
    }

    static boolean isEligibleUserRule(boolean packageRule, boolean systemRule,
            boolean protectedIdentity) {
        return packageRule && !systemRule && !protectedIdentity;
    }

    static boolean isEligibleUid(int uid, Set<Integer> protectedUids) {
        return uid >= 0 && (protectedUids == null || !protectedUids.contains(uid));
    }

    /** Called only after the service has rebuilt the VPN successfully. */
    public static void markApplied(Context context) {
        Context appContext = context.getApplicationContext();
        boolean applied = isActive(appContext) && ServiceSinkhole.isVpnEstablished();
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
