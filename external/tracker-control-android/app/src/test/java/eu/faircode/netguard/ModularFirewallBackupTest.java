package eu.faircode.netguard;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ModularFirewallBackupTest {
    private JSONObject validPayload() throws Exception {
        JSONObject packageFlags = new JSONObject()
                .put("apply", new JSONObject())
                .put("tracker_protect", new JSONObject())
                .put("tracker_essential", new JSONObject())
                .put("notify", new JSONObject());
        JSONObject blocklists = new JSONObject()
                .put("internetPackages", new JSONArray())
                .put("trackerPackages", new JSONArray())
                .put("trackerSubsets", new JSONObject());
        return new JSONObject()
                .put("schemaVersion", 1)
                .put("application", new JSONObject())
                .put("packageFlags", packageFlags)
                .put("domains", new JSONArray())
                .put("forwarding", new JSONArray())
                .put("blocklists", blocklists);
    }

    @Test
    public void acceptsAnEmptySafeFirewallSnapshot() throws Exception {
        ModularFirewallBackup.validate(validPayload());
    }

    @Test
    public void rejectsWireGuardPreferences() throws Exception {
        JSONObject payload = validPayload();
        payload.getJSONObject("application").put(
                "wg_enabled",
                new JSONObject().put("type", "boolean").put("value", true)
        );

        try {
            ModularFirewallBackup.validate(payload);
            fail("WireGuard preference must not be accepted by the backup adapter");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unsupported Firewall setting"));
        }
    }

    @Test
    public void rejectsCredentialLikeNonAllowlistedPreferences() throws Exception {
        JSONObject payload = validPayload();
        payload.getJSONObject("application").put(
                "socks5_password",
                new JSONObject().put("type", "string").put("value", "secret")
        );

        try {
            ModularFirewallBackup.validate(payload);
            fail("Non-allowlisted credential must not be accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unsupported Firewall setting"));
        }
    }

    @Test
    public void rejectsMalformedDomainEntries() throws Exception {
        JSONObject payload = validPayload();
        payload.getJSONArray("domains").put("not a valid hostname");

        try {
            ModularFirewallBackup.validate(payload);
            fail("Malformed domain must not be accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("domain"));
        }
    }
}
