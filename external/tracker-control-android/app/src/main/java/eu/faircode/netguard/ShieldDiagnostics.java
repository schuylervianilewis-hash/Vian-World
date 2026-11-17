package eu.faircode.netguard;

import android.util.Log;

/** Narrow diagnostics boundary between the Firewall library and its host application. */
public final class ShieldDiagnostics {
    public interface Sink { void write(String component, String message, Throwable error); }
    private static final String TAG = "ShieldDiagnostics";
    private static volatile Sink sink;
    private ShieldDiagnostics() {}
    public static void setSink(Sink value) { sink = value; }
    public static void log(String component, String message) { dispatch(component, message, null); }
    public static void error(String component, String message, Throwable error) { dispatch(component, message, error); }
    private static void dispatch(String component, String message, Throwable error) {
        String c = component == null ? "Unknown" : component;
        String m = message == null ? "" : message;
        if (error == null) Log.i(TAG, "[" + c + "] " + m);
        else Log.e(TAG, "[" + c + "] " + m, error);
        Sink current = sink;
        if (current != null) {
            try { current.write(c, m, error); }
            catch (Throwable bridgeError) { Log.e(TAG, "Host diagnostics sink failed", bridgeError); }
        }
    }
}
