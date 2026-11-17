/*
 * This file is part of the integrated TrackerControl / Shield module.
 *
 * It is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */
package eu.faircode.netguard;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.data.InternetPauseManager;

/** Shared Main-process entry point for all integrated Shield controls. */
public final class ShieldControl {
    private ShieldControl() {}

    public static boolean isEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("enabled", false);
    }

    public static void setEnabled(Context context, boolean enabled, String source) {
        Context app = context.getApplicationContext();
        ShieldDiagnostics.log("ShieldControl", "setEnabled requested enabled=" + enabled + " source=" + source);
        boolean pauseInternalRequest = source != null && source.startsWith("internet_pause");
        if (!enabled && !pauseInternalRequest && InternetPauseManager.isActive(app)) {
            ShieldDiagnostics.log("ShieldControl", "Ignoring firewall disable while Internet Pause is active");
            return;
        }
        cancelResumeAlarm(app);
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean("enabled", enabled).apply();
        if (enabled) ServiceSinkhole.start(source, app, true);
        else ServiceSinkhole.stop(source, app, false);
        WidgetMain.updateWidgets(app);
        ShieldDiagnostics.log("ShieldControl", "Firewall state request dispatched; enabled=" + enabled);
    }

    public static Intent createOpenShieldIntent(Context context) {
        return new Intent(context, ActivityMain.class)
                .putExtra(ActivityMain.EXTRA_OPEN_SHIELD, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private static void cancelResumeAlarm(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        Intent intent = new Intent(WidgetAdmin.INTENT_ON);
        intent.setPackage(context.getPackageName());
        PendingIntent pending = PendingIntentCompat.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        manager.cancel(pending);
    }
}
