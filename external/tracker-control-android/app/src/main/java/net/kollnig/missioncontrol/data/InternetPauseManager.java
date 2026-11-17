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

    /** Provider entry point; runs in the default process with ServiceSinkhole. */
    static Bundle handleProviderCall(Context context, String method, Bundle extras) {
        Context appContext = context.getApplicationContext();
        if (METHOD_GET_STATE.equals(method)) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean vpnEstablished = ServiceSinkhole.isVpnEstablished();
            Bundle result = new Bundle();
            result.putBoolean(KEY_ACTIVE, prefs.getBoolean(KEY_ACTIVE, false));
            result.putBoolean(KEY_APPLIED, prefs.getBoolean(KEY_ACTIVE, false)
                    && prefs.getBoolean(KEY_APPLIED, false) && vpnEstablished);
            result.putBoolean(KEY_VPN_ESTABLISHED, vpnEstablished);
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

    private static boolean setPausedInServiceProcess(Context appContext, boolean paused) {
        synchronized (LOCK) {
            if (paused && (VpnService.prepare(appContext) != null || !ServiceSinkhole.isVpnEstablished()))
                return false;

            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean wasActive = prefs.getBoolean(KEY_ACTIVE, false);
            boolean wasApplied = prefs.getBoolean(KEY_APPLIED, false);
            if (wasActive == paused && !paused) {
                InternetBlocklist.getInstance(appContext).clearTemporaryBlockedUids();
                if (wasApplied)
                    prefs.edit().putBoolean(KEY_APPLIED, false).commit();
                return true;
            }
            if (wasActive && paused && wasApplied && ServiceSinkhole.isVpnEstablished())
                return true;

            if (!prefs.edit().putBoolean(KEY_ACTIVE, paused)
                    .putBoolean(KEY_APPLIED, false).commit())
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
        boolean pauseActive = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ACTIVE, false);
        if (!pauseActive || rules == null || rules.isEmpty()) {
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
