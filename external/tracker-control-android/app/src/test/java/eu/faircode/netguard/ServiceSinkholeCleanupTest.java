package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ServiceSinkholeCleanupTest {
    @Test
    public void cleanupContinuesAfterAnEarlierReleaseThrows() {
        List<String> completed = new ArrayList<>();

        ServiceSinkhole.runCleanupSteps(
                new String[]{"native tunnel", "VPN descriptor", "UID state", "DNS proxy"},
                new Runnable[]{
                        () -> {
                            completed.add("native tunnel");
                            throw new IllegalStateException("injected native teardown failure");
                        },
                        () -> completed.add("VPN descriptor"),
                        () -> completed.add("UID state"),
                        () -> completed.add("DNS proxy")
                });

        assertEquals(4, completed.size());
        assertEquals("native tunnel", completed.get(0));
        assertEquals("VPN descriptor", completed.get(1));
        assertEquals("UID state", completed.get(2));
        assertEquals("DNS proxy", completed.get(3));
    }

    @Test
    public void cleanupAttemptsEveryReleaseEvenWhenSeveralFail() {
        List<Integer> completed = new ArrayList<>();

        ServiceSinkhole.runCleanupSteps(
                new String[]{"first", "second", "third"},
                new Runnable[]{
                        () -> {
                            completed.add(1);
                            throw new AssertionError("first failure");
                        },
                        () -> {
                            completed.add(2);
                            throw new IllegalStateException("second failure");
                        },
                        () -> completed.add(3)
                });

        assertEquals(3, completed.size());
        assertTrue(completed.contains(1));
        assertTrue(completed.contains(2));
        assertTrue(completed.contains(3));
    }
}
