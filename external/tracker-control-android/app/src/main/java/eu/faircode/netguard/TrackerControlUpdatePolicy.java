package eu.faircode.netguard;

/**
 * Compares an upstream TrackerControl release with the version embedded in
 * this host application. The host application's own version code is unrelated.
 */
public final class TrackerControlUpdatePolicy {
    private TrackerControlUpdatePolicy() {
    }

    public static boolean isUpdateAvailable(long bundledVersionCode, long latestReleaseVersionCode) {
        return latestReleaseVersionCode > bundledVersionCode;
    }
}
