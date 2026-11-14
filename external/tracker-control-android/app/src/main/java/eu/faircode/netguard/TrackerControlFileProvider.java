package eu.faircode.netguard;

import androidx.core.content.FileProvider;

/**
 * Dedicated provider class so the Firewall module can declare its own
 * authority and path configuration alongside the host app's FileProvider.
 */
public final class TrackerControlFileProvider extends FileProvider {
}
