/*
 * Modular, allowlisted Firewall backup adapter.
 *
 * This class intentionally does not read WireGuard profile storage, wg_route,
 * wg_enabled, wg_config, active profile IDs, or any WireGuard preference.
 */
package eu.faircode.netguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.data.BlockingMode;
import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.PackageUids;
import net.kollnig.missioncontrol.data.PausedApps;
import net.kollnig.missioncontrol.data.TrackerBlocklist;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ModularFirewallBackup {
    private static final String APPLY = "apply";
    private static final String TRACKER_PROTECT = "tracker_protect";
    private static final String TRACKER_ESSENTIAL = "tracker_essential";
    private static final String NOTIFY = "notify";

    private static final Set<String> APPLICATION_KEYS = new HashSet<>(Arrays.asList(
            "blocking_mode", "log_logcat", "disable_on_call", "pause",
            "update_check", "reload_onconnectivity", "tcp_mss_clamp",
            "doh_enabled", "doh_endpoint", "doh_dns_fallback", "block_dot",
            "domain_based_blocking", "include_system_vpn", "hosts_auto_update",
            "use_hosts", "rcode", "log_app", "handover", "vpn4", "vpn6",
            "dns", "dns2", "validate", "ttl", "socks5_enabled", "socks5_addr",
            "socks5_port", "pcap_record_size", "pcap_file_size", "show_resolved",
            "loglevel", "ip6"
    ));

    private static final Set<String> PACKAGE_STORES = new LinkedHashSet<>(Arrays.asList(
            APPLY, TRACKER_PROTECT, TRACKER_ESSENTIAL, NOTIFY
    ));

    private ModularFirewallBackup() {
    }

    public static void validate(JSONObject payload) throws JSONException {
        if (payload.optInt("schemaVersion", -1) != 1)
            throw new IllegalArgumentException("Unsupported Firewall backup schema");
        JSONObject application = payload.optJSONObject("application");
        JSONObject packageFlags = payload.optJSONObject("packageFlags");
        JSONArray domains = payload.optJSONArray("domains");
        JSONArray forwarding = payload.optJSONArray("forwarding");
        JSONObject blocklists = payload.optJSONObject("blocklists");
        if (application == null || packageFlags == null || domains == null ||
                forwarding == null || blocklists == null)
            throw new IllegalArgumentException("Firewall backup is incomplete");

        for (java.util.Iterator<String> it = application.keys(); it.hasNext();) {
            String key = it.next();
            if (!APPLICATION_KEYS.contains(key) || key.startsWith("wg") || "enabled".equals(key))
                throw new IllegalArgumentException("Unsupported Firewall setting: " + key);
            JSONObject typed = application.optJSONObject(key);
            if (typed == null)
                throw new IllegalArgumentException("Invalid Firewall setting value");
            String type = typed.optString("type");
            if (!Arrays.asList("boolean", "integer", "long", "string").contains(type))
                throw new IllegalArgumentException("Invalid Firewall setting type");
        }

        for (String store : PACKAGE_STORES) {
            JSONObject values = packageFlags.optJSONObject(store);
            if (values == null)
                throw new IllegalArgumentException("Missing Firewall app rule store: " + store);
            for (java.util.Iterator<String> it = values.keys(); it.hasNext();) {
                String packageName = it.next();
                if (!isPackageKey(packageName) || !(values.opt(packageName) instanceof Boolean))
                    throw new IllegalArgumentException("Invalid Firewall app rule");
            }
        }

        if (domains.length() > 10000 || forwarding.length() > 1000)
            throw new IllegalArgumentException("Firewall backup contains too many records");
        for (int i = 0; i < domains.length(); i++) {
            String domain = domains.optString(i, "");
            if (domain.length() > 253 || TextUtils.isEmpty(domain))
                throw new IllegalArgumentException("Invalid Firewall domain entry");
        }
        for (int i = 0; i < forwarding.length(); i++) {
            JSONObject forward = forwarding.optJSONObject(i);
            if (forward == null || !isPackageKey(forward.optString("package")))
                throw new IllegalArgumentException("Invalid forwarding rule");
            if (forward.optInt("protocol", -1) < 0 || forward.optInt("protocol", -1) > 255 ||
                    forward.optInt("destinationPort", 0) < 1 || forward.optInt("destinationPort", 0) > 65535 ||
                    forward.optInt("remotePort", 0) < 1 || forward.optInt("remotePort", 0) > 65535)
                throw new IllegalArgumentException("Invalid forwarding port");
        }
    }

    public static JSONObject export(Context context) throws JSONException {
        JSONObject root = new JSONObject();
        JSONObject application = new JSONObject();
        SharedPreferences defaults = PreferenceManager.getDefaultSharedPreferences(context);
        Map<String, ?> all = defaults.getAll();
        for (String key : APPLICATION_KEYS) {
            if (!all.containsKey(key))
                continue;
            Object value = all.get(key);
            JSONObject typed = encodeValue(value);
            if (typed != null)
                application.put(key, typed);
        }
        root.put("schemaVersion", 1);
        root.put("application", application);

        JSONObject packageFlags = new JSONObject();
        // Start with the full persistent per-package values, then overlay the
        // pre-pause values for packages currently paused on this device. The
        // pause snapshot store itself is never exported.
        Map<String, Object> safeApply = new java.util.HashMap<>();
        for (Map.Entry<String, ?> entry :
                context.getSharedPreferences(APPLY, Context.MODE_PRIVATE).getAll().entrySet())
            safeApply.put(entry.getKey(), entry.getValue());
        safeApply.putAll(PausedApps.applyValuesWithoutPauses(context));
        packageFlags.put(APPLY, exportPackageFlags(context, safeApply));
        packageFlags.put(TRACKER_PROTECT, exportPackageFlags(
                context, context.getSharedPreferences(TRACKER_PROTECT, Context.MODE_PRIVATE).getAll()));
        packageFlags.put(TRACKER_ESSENTIAL, exportPackageFlags(
                context, context.getSharedPreferences(TRACKER_ESSENTIAL, Context.MODE_PRIVATE).getAll()));
        packageFlags.put(NOTIFY, exportPackageFlags(
                context, context.getSharedPreferences(NOTIFY, Context.MODE_PRIVATE).getAll()));
        root.put("packageFlags", packageFlags);

        JSONArray domains = new JSONArray();
        for (String domain : DomainWhitelist.getInstance(context).getDomains())
            domains.put(domain);
        root.put("domains", domains);

        root.put("blocklists", exportBlocklists(context));
        root.put("forwarding", exportForwarding(context));
        return root;
    }

    public static void restore(Context context, JSONObject payload) throws JSONException {
        validate(payload);

        SharedPreferences defaults = PreferenceManager.getDefaultSharedPreferences(context);
        JSONObject application = payload.getJSONObject("application");
        SharedPreferences.Editor appEditor = defaults.edit();
        for (java.util.Iterator<String> it = application.keys(); it.hasNext();) {
            String key = it.next();
            JSONObject typed = application.getJSONObject(key);
            Object value = typed.get("value");
            switch (typed.getString("type")) {
                case "boolean":
                    appEditor.putBoolean(key, (Boolean) value);
                    break;
                case "integer":
                    appEditor.putInt(key, ((Number) value).intValue());
                    break;
                case "long":
                    appEditor.putLong(key, ((Number) value).longValue());
                    break;
                case "string":
                    appEditor.putString(key, String.valueOf(value));
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported Firewall setting type");
            }
        }
        if (!appEditor.commit())
            throw new IllegalStateException("Could not restore Firewall settings");

        JSONObject packageFlags = payload.getJSONObject("packageFlags");
        restorePackageFlags(context, APPLY, packageFlags.getJSONObject(APPLY));
        restorePackageFlags(context, TRACKER_PROTECT, packageFlags.getJSONObject(TRACKER_PROTECT));
        restorePackageFlags(context, TRACKER_ESSENTIAL, packageFlags.getJSONObject(TRACKER_ESSENTIAL));
        restorePackageFlags(context, NOTIFY, packageFlags.getJSONObject(NOTIFY));

        JSONArray domains = payload.getJSONArray("domains");
        Set<String> domainSet = new LinkedHashSet<>();
        for (int i = 0; i < domains.length(); i++)
            domainSet.add(domains.getString(i));
        DomainWhitelist.getInstance(context).replaceDomains(domainSet);

        restoreBlocklists(context, payload.getJSONObject("blocklists"));
        restoreForwarding(context, payload.getJSONArray("forwarding"));

        TrackerBlocklist.getInstance(context).loadSettings(context);
        InternetBlocklist.getInstance(context).loadSettings(context);
        BlockingMode.applyMode(context);
        DatabaseHelper.clearCache();
        ServiceSinkhole.reload("modular backup restore", context, false);
    }

    private static JSONObject encodeValue(Object value) throws JSONException {
        if (value instanceof Boolean)
            return new JSONObject().put("type", "boolean").put("value", value);
        if (value instanceof Integer)
            return new JSONObject().put("type", "integer").put("value", value);
        if (value instanceof Long)
            return new JSONObject().put("type", "long").put("value", value);
        if (value instanceof String)
            return new JSONObject().put("type", "string").put("value", value);
        return null;
    }

    private static JSONObject exportPackageFlags(Context context, Map<String, ?> values) throws JSONException {
        JSONObject output = new JSONObject();
        PackageManager pm = context.getPackageManager();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!(entry.getValue() instanceof Boolean))
                continue;
            String key = entry.getKey();
            if (TextUtils.isEmpty(key))
                continue;
            try {
                int uid = Integer.parseInt(key);
                String[] packages = Util.getPackagesForUid(pm, uid);
                if (packages != null)
                    for (String packageName : packages)
                        if (isPackageKey(packageName))
                            output.put(packageName, entry.getValue());
            } catch (NumberFormatException ignored) {
                if (isPackageKey(key))
                    output.put(key, entry.getValue());
            }
        }
        return output;
    }

    private static void restorePackageFlags(Context context, String store, JSONObject values) {
        SharedPreferences prefs = context.getSharedPreferences(store, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        for (java.util.Iterator<String> it = values.keys(); it.hasNext();) {
            String packageName = it.next();
            if (!isPackageKey(packageName))
                continue;
            // These stores are package-keyed in the app's existing XML import contract.
            editor.putBoolean(packageName, values.optBoolean(packageName, false));
        }
        if (!editor.commit())
            throw new IllegalStateException("Could not restore Firewall app rules: " + store);
    }

    private static JSONObject exportBlocklists(Context context) throws JSONException {
        SharedPreferences prefs = context.getSharedPreferences(TrackerBlocklist.PREF_BLOCKLIST, Context.MODE_PRIVATE);
        JSONObject result = new JSONObject();
        result.put("internetPackages", exportUidSet(context,
                prefs.getStringSet(InternetBlocklist.SHARED_PREFS_INTERNET_BLOCKLIST_APPS_KEY, Collections.emptySet())));
        Set<String> trackerUids = prefs.getStringSet(
                TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY, Collections.emptySet());
        JSONArray trackerPackages = exportUidSet(context, trackerUids);
        result.put("trackerPackages", trackerPackages);

        JSONObject subsets = new JSONObject();
        PackageManager pm = context.getPackageManager();
        for (String storedUid : trackerUids) {
            Set<String> packages = new LinkedHashSet<>();
            try {
                int uid = Integer.parseInt(storedUid);
                String[] resolved = Util.getPackagesForUid(pm, uid);
                if (resolved != null)
                    packages.addAll(Arrays.asList(resolved));
            } catch (NumberFormatException ignored) {
                if (isPackageKey(storedUid))
                    packages.add(storedUid);
            }
            Set<String> subset = prefs.getStringSet(
                    TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + storedUid, null);
            if (subset != null)
                for (String packageName : packages)
                    if (isPackageKey(packageName))
                        subsets.put(packageName, new JSONArray(subset));
        }
        result.put("trackerSubsets", subsets);
        return result;
    }

    private static JSONArray exportUidSet(Context context, Set<String> values) {
        JSONArray result = new JSONArray();
        Set<String> packages = new LinkedHashSet<>();
        PackageManager pm = context.getPackageManager();
        for (String value : values) {
            try {
                int uid = Integer.parseInt(value);
                String[] resolved = Util.getPackagesForUid(pm, uid);
                if (resolved != null)
                    for (String packageName : resolved)
                        if (isPackageKey(packageName))
                            packages.add(packageName);
            } catch (NumberFormatException ignored) {
                if (isPackageKey(value))
                    packages.add(value);
            }
        }
        for (String packageName : packages)
            result.put(packageName);
        return result;
    }

    private static void restoreBlocklists(Context context, JSONObject blocklists) throws JSONException {
        SharedPreferences prefs = context.getSharedPreferences(TrackerBlocklist.PREF_BLOCKLIST, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putStringSet(InternetBlocklist.SHARED_PREFS_INTERNET_BLOCKLIST_APPS_KEY,
                resolvePackageSet(context, blocklists.getJSONArray("internetPackages")));
        editor.putStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY,
                resolvePackageSet(context, blocklists.getJSONArray("trackerPackages")));

        JSONObject subsets = blocklists.optJSONObject("trackerSubsets");
        if (subsets != null) {
            for (java.util.Iterator<String> it = subsets.keys(); it.hasNext();) {
                String packageName = it.next();
                Integer uid = resolveUid(context, packageName);
                String storageKey = TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" +
                        (uid == null ? packageName : Integer.toString(uid));
                JSONArray values = subsets.getJSONArray(packageName);
                Set<String> subset = new LinkedHashSet<>();
                for (int i = 0; i < values.length(); i++)
                    subset.add(values.getString(i));
                editor.putStringSet(storageKey, subset);
            }
        }
        if (!editor.commit())
            throw new IllegalStateException("Could not restore Firewall blocklist settings");
    }

    private static Set<String> resolvePackageSet(Context context, JSONArray packages) {
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < packages.length(); i++) {
            String packageName = packages.optString(i, "");
            if (isPackageKey(packageName)) {
                Integer uid = resolveUid(context, packageName);
                result.add(uid == null ? packageName : Integer.toString(uid));
            }
        }
        return result;
    }

    private static Integer resolveUid(Context context, String packageName) {
        if ("root".equals(packageName)) return 0;
        if ("mediaserver".equals(packageName)) return 1013;
        if ("nobody".equals(packageName)) return 9999;
        return PackageUids.resolve(context, packageName);
    }

    private static JSONArray exportForwarding(Context context) throws JSONException {
        JSONArray result = new JSONArray();
        try (Cursor cursor = DatabaseHelper.getInstance(context).getForwarding()) {
            int protocol = cursor.getColumnIndexOrThrow("protocol");
            int destinationPort = cursor.getColumnIndexOrThrow("dport");
            int remoteAddress = cursor.getColumnIndexOrThrow("raddr");
            int remotePort = cursor.getColumnIndexOrThrow("rport");
            int remoteUid = cursor.getColumnIndexOrThrow("ruid");
            while (cursor.moveToNext()) {
                String[] packages;
                int uid = cursor.getInt(remoteUid);
                if (uid == 0)
                    packages = new String[] { "root" };
                else if (uid == 1013)
                    packages = new String[] { "mediaserver" };
                else if (uid == 9999)
                    packages = new String[] { "nobody" };
                else
                    packages = Util.getPackagesForUid(context.getPackageManager(), uid);
                if (packages == null)
                    continue;
                for (String packageName : packages) {
                    if (!isPackageKey(packageName))
                        continue;
                    result.put(new JSONObject()
                            .put("package", packageName)
                            .put("protocol", cursor.getInt(protocol))
                            .put("destinationPort", cursor.getInt(destinationPort))
                            .put("remoteAddress", cursor.getString(remoteAddress))
                            .put("remotePort", cursor.getInt(remotePort)));
                }
            }
        }
        return result;
    }

    private static void restoreForwarding(Context context, JSONArray records) throws JSONException {
        List<Forward> forwards = new ArrayList<>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject item = records.getJSONObject(i);
            String packageName = item.getString("package");
            Integer uid = resolveUid(context, packageName);
            if (uid == null)
                continue; // The destination app is not installed on this device.
            Forward forward = new Forward();
            forward.protocol = item.getInt("protocol");
            forward.dport = item.getInt("destinationPort");
            forward.raddr = item.getString("remoteAddress");
            forward.rport = item.getInt("remotePort");
            forward.ruid = uid;
            forwards.add(forward);
        }
        DatabaseHelper.getInstance(context).replaceForwards(forwards);
    }

    private static boolean isPackageKey(String value) {
        if (TextUtils.isEmpty(value) || value.length() > 255)
            return false;
        if ("root".equals(value) || "mediaserver".equals(value) || "nobody".equals(value))
            return true;
        return value.matches("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$");
    }
}
