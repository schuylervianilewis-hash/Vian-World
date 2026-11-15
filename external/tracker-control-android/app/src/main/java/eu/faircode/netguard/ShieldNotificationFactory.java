/*
 * This file is part of the integrated TrackerControl / Shield module.
 *
 * It is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */
package eu.faircode.netguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.R;

/** Shared notification presentation for Sidebar HandleService and the VPN foreground service. */
public final class ShieldNotificationFactory {
    public static final int NOTIFICATION_ID = 1001;
    public static final String CHANNEL_ID = "sidebar_shield_control";
    private static final String STATE_PREFS = "sidebar_shield_notification_state";
    private static final String KEY_HANDLE_ACTIVE = "handle_service_active";
    private static final String KEY_SPEED_ENABLED = "speed_monitor_enabled";
    private static final String KEY_ICON = "small_icon";
    private static final String KEY_TITLE = "title";
    private static final String KEY_CONTENT = "content";
    private static boolean processInitialized;

    private ShieldNotificationFactory() {}

    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Sidebar and Shield", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps Sidebar controls and Shield protection available.");
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.enableLights(false);
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    public static void setHandleServiceActive(Context context, boolean active) {
        state(context).edit().putBoolean(KEY_HANDLE_ACTIVE, active).apply();
    }

    public static boolean isHandleServiceActive(Context context) {
        return state(context).getBoolean(KEY_HANDLE_ACTIVE, false);
    }

    public static Notification buildSidebarNotification(Context context, int icon, String title, String content, boolean speedEnabled) {
        state(context).edit().putBoolean(KEY_HANDLE_ACTIVE, true).putBoolean(KEY_SPEED_ENABLED, speedEnabled)
                .putInt(KEY_ICON, icon).putString(KEY_TITLE, title).putString(KEY_CONTENT, content).apply();
        return build(context, icon, title, content, speedEnabled);
    }

    public static Notification buildCurrentNotification(Context context, int fallbackIcon, String fallbackTitle, String fallbackContent) {
        SharedPreferences prefs = state(context);
        if (prefs.getBoolean(KEY_HANDLE_ACTIVE, false)) {
            return build(context, prefs.getInt(KEY_ICON, fallbackIcon), prefs.getString(KEY_TITLE, fallbackTitle),
                    prefs.getString(KEY_CONTENT, fallbackContent), prefs.getBoolean(KEY_SPEED_ENABLED, false));
        }
        return build(context, fallbackIcon, fallbackTitle, fallbackContent, false);
    }

    public static Notification buildShieldOnlyNotification(Context context, boolean enabled) {
        return build(context, R.drawable.ic_rocket_white, enabled ? "Shield enabled" : "Shield off",
                enabled ? "Tap to open Shield and check protection status" : "Protection is off • Tap to open Shield", false);
    }

    private static synchronized SharedPreferences state(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
        // The active-service flag is process-local state. Clear a stale value left
        // behind if Android killed the process without calling Service.onDestroy().
        if (!processInitialized) {
            prefs.edit().putBoolean(KEY_HANDLE_ACTIVE, false).apply();
            processInitialized = true;
        }
        return prefs;
    }

    private static Notification build(Context context, int icon, String title, String content, boolean speedEnabled) {
        createNotificationChannel(context);
        PendingIntent open = PendingIntent.getActivity(context, 1201, ShieldControl.createOpenShieldIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent pauseIntent = new Intent(WidgetAdmin.INTENT_PAUSE);
        pauseIntent.setPackage(context.getPackageName());
        PendingIntent pause = PendingIntent.getBroadcast(context, 1202, pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String pauseLabel = getPauseLabel(context);
        RemoteViews expanded = new RemoteViews(context.getPackageName(), R.layout.notification_shield_expanded);
        bind(expanded, title, content, pauseLabel, pause, open);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(icon > 0 ? icon : R.drawable.ic_rocket_white)
                .setContentTitle(title).setContentText(content).setContentIntent(open)
                .setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true).setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .setCustomBigContentView(expanded);

        if (!speedEnabled) {
            RemoteViews compact = new RemoteViews(context.getPackageName(), R.layout.notification_shield_compact);
            bind(compact, title, content, pauseLabel, pause, open);
            builder.setCustomContentView(compact);
        }
        return builder.build();
    }

    private static void bind(RemoteViews views, String title, String content, String pauseLabel, PendingIntent pause, PendingIntent open) {
        views.setTextViewText(R.id.notificationTitle, title);
        views.setTextViewText(R.id.notificationContent, content);
        views.setTextViewText(R.id.actionPause, pauseLabel);
        views.setTextViewText(R.id.actionOpen, "Open Shield");
        views.setContentDescription(R.id.actionPause, pauseLabel);
        views.setContentDescription(R.id.actionOpen, "Open Shield");
        views.setOnClickPendingIntent(R.id.actionPause, pause);
        views.setOnClickPendingIntent(R.id.actionOpen, open);
    }

    private static String getPauseLabel(Context context) {
        String raw = PreferenceManager.getDefaultSharedPreferences(context).getString("pause", "10");
        int minutes;
        try { minutes = Integer.parseInt(raw); } catch (Exception ignored) { minutes = 10; }
        return "Pause all (" + minutes + "m)";
    }
}
