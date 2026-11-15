/*
 * This file is from NetGuard.
 *
 * NetGuard is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NetGuard is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright © 2015–2020 Marcel Bokhorst (M66B)
 * Copyright © 2019–2026 Konrad Kollnig
 */

package eu.faircode.netguard;

import android.app.IntentService;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ServiceExternal extends IntentService {
    private static final String TAG = "TrackerControl.External";
    private static final String ACTION_DOWNLOAD_HOSTS_FILE = "net.kollnig.missioncontrol.DOWNLOAD_HOSTS_FILE";
    static final long MAX_HOSTS_FILE_BYTES = 64L * 1024L * 1024L;
    static final long MIN_DOWNLOAD_INTERVAL_MS = 60_000L;
    private static final String PREF_LAST_DOWNLOAD_ATTEMPT_ELAPSED = "hosts_last_download_attempt_elapsed";

    // am startservice -a net.kollnig.missioncontrol.DOWNLOAD_HOSTS_FILE

    public ServiceExternal() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        try {
            startForeground(ServiceSinkhole.NOTIFY_EXTERNAL, getForegroundNotification(this));

            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);

            if (ACTION_DOWNLOAD_HOSTS_FILE.equals(intent.getAction())) {
                final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

                String hosts_url = prefs.getString("hosts_url_new", null);
                File tmp = new File(getFilesDir(), "hosts.tmp");
                File hosts = new File(getFilesDir(), "hosts.txt");

                URLConnection connection = null;
                try {
                    long nowElapsed = SystemClock.elapsedRealtime();
                    long lastAttemptElapsed = prefs.getLong(PREF_LAST_DOWNLOAD_ATTEMPT_ELAPSED, 0L);
                    if (!isDownloadAllowed(nowElapsed, lastAttemptElapsed)) {
                        Log.w(TAG, "Ignoring hosts download request received inside the rate-limit window");
                        return;
                    }
                    if (!prefs.edit().putLong(PREF_LAST_DOWNLOAD_ATTEMPT_ELAPSED, nowElapsed).commit())
                        throw new IOException("Could not persist hosts download rate limit");

                    if (hosts_url == null || hosts_url.trim().isEmpty())
                        throw new IOException("No hosts URL is configured");
                    URL url = new URL(hosts_url);
                    if (!"https".equalsIgnoreCase(url.getProtocol()))
                        throw new IOException("Only HTTPS hosts URLs are supported");
                    connection = url.openConnection();
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(15000);
                    connection.connect();

                    if (connection instanceof HttpURLConnection) {
                        HttpURLConnection httpConnection = (HttpURLConnection) connection;
                        if (httpConnection.getResponseCode() != HttpURLConnection.HTTP_OK)
                            throw new IOException(httpConnection.getResponseCode() + " " + httpConnection.getResponseMessage());
                    }

                    int contentLength = connection.getContentLength();
                    Log.i(TAG, "Content length=" + contentLength);
                    if (contentLength > MAX_HOSTS_FILE_BYTES)
                        throw new IOException("Hosts file exceeds maximum size of " + MAX_HOSTS_FILE_BYTES + " bytes");

                    long size;
                    try (InputStream in = connection.getInputStream();
                         OutputStream out = new FileOutputStream(tmp)) {
                        size = copyBounded(in, out, MAX_HOSTS_FILE_BYTES);
                        out.flush();
                    }

                    Log.i(TAG, "Downloaded size=" + size);
                    replaceHostsFile(tmp, hosts);

                    String last = SimpleDateFormat.getDateTimeInstance().format(new Date().getTime());
                    prefs.edit().putString("hosts_last_download", last).apply();
                    ServiceSinkhole.reload("hosts file download", this, false);

                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    if (tmp.exists() && !tmp.delete())
                        Log.w(TAG, "Could not delete incomplete hosts temporary file");
                } finally {
                    if (connection instanceof HttpURLConnection)
                        ((HttpURLConnection) connection).disconnect();
                }
            }
        } finally {
            stopForeground(true);
        }
    }

    static boolean isDownloadAllowed(long nowElapsedMs, long lastAttemptElapsedMs) {
        // elapsedRealtime resets at reboot. A lower current value therefore
        // means the persisted timestamp belongs to the previous boot.
        return lastAttemptElapsedMs <= 0L
                || nowElapsedMs < lastAttemptElapsedMs
                || nowElapsedMs - lastAttemptElapsedMs >= MIN_DOWNLOAD_INTERVAL_MS;
    }

    static long copyBounded(InputStream input, OutputStream output, long maxBytes) throws IOException {
        if (maxBytes < 0L)
            throw new IllegalArgumentException("maxBytes must not be negative");

        byte[] buffer = new byte[8192];
        long size = 0L;
        int bytes;
        while ((bytes = input.read(buffer)) != -1) {
            if (size > maxBytes - bytes)
                throw new IOException("Hosts file exceeds maximum size of " + maxBytes + " bytes");
            output.write(buffer, 0, bytes);
            size += bytes;
        }
        return size;
    }

    /**
     * Replace the active hosts file only after a complete download exists.
     * Prefer an atomic same-directory replacement; retain a non-atomic
     * replacement fallback for filesystems that do not support ATOMIC_MOVE.
     */
    static void replaceHostsFile(File temporary, File target) throws IOException {
        try {
            Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Notification getForegroundNotification(Context context) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "foreground");
        builder.setSmallIcon(R.drawable.ic_hourglass_empty_white_24dp);
        builder.setPriority(NotificationCompat.PRIORITY_MIN);
        builder.setCategory(NotificationCompat.CATEGORY_STATUS);
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        builder.setContentTitle(context.getString(R.string.app_name));
        return builder.build();
    }
}
