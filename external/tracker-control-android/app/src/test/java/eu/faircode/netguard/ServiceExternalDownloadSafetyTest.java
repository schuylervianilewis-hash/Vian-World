package eu.faircode.netguard;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ServiceExternalDownloadSafetyTest {
    @Test
    public void boundedCopyAcceptsAFileExactlyAtTheLimit() throws Exception {
        byte[] input = new byte[]{1, 2, 3, 4};
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        long copied = ServiceExternal.copyBounded(
                new ByteArrayInputStream(input), output, input.length);

        assertEquals(input.length, copied);
        assertArrayEquals(input, output.toByteArray());
    }

    @Test
    public void boundedCopyRejectsTheFirstByteBeyondTheLimit() throws Exception {
        byte[] input = new byte[]{1, 2, 3, 4, 5};
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try {
            ServiceExternal.copyBounded(new ByteArrayInputStream(input), output, 4L);
            fail("Expected oversized hosts file to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("maximum size"));
        }

        // The caller deletes the temporary file on failure; the copy routine
        // itself must never write bytes beyond the configured ceiling.
        assertArrayEquals(new byte[]{1, 2, 3, 4}, output.toByteArray());
    }

    @Test
    public void downloadThrottleAllowsFirstAttemptAndBlocksRapidRepeats() {
        assertTrue(ServiceExternal.isDownloadAllowed(10_000L, 0L));
        assertFalse(ServiceExternal.isDownloadAllowed(20_000L, 10_000L));
        assertTrue(ServiceExternal.isDownloadAllowed(
                10_000L + ServiceExternal.MIN_DOWNLOAD_INTERVAL_MS, 10_000L));
    }

    @Test
    public void downloadThrottleRecognizesElapsedRealtimeResetAfterReboot() {
        assertTrue(ServiceExternal.isDownloadAllowed(500L, 80_000L));
    }
}
