package eu.faircode.netguard;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.preference.PreferenceManager;

/** Shared Main-process entry point for all integrated Shield controls. */
public final class ShieldControl {
    private ShieldControl() {}

    public static boolean isEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("enabled", false);
    }

    public static void setEnabled(Context context, boolean enabled, String source) {
        Context app = context.getApplicationContext();
        cancelResumeAlarm(app);
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean("enabled", enabled).apply();
        if (enabled) ServiceSinkhole.start(source, app, true);
        else ServiceSinkhole.stop(source, app, false);
        WidgetMain.updateWidgets(app);
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
