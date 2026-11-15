package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ServiceExternalFileReplacementTest {
    @Test
    public void successfulReplacementPublishesCompleteDownload() throws Exception {
        Path directory = Files.createTempDirectory("hosts-replace");
        try {
            File target = directory.resolve("hosts.txt").toFile();
            File temporary = directory.resolve("hosts.tmp").toFile();
            Files.write(target.toPath(), "old hosts".getBytes(StandardCharsets.UTF_8));
            Files.write(temporary.toPath(), "new hosts".getBytes(StandardCharsets.UTF_8));

            ServiceExternal.replaceHostsFile(temporary, target);

            assertEquals("new hosts", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
            assertFalse(temporary.exists());
        } finally {
            deleteTree(directory);
        }
    }

    @Test
    public void failedReplacementLeavesPreviousHostsFileUntouched() throws Exception {
        Path directory = Files.createTempDirectory("hosts-replace-fail");
        try {
            File target = directory.resolve("hosts.txt").toFile();
            File missingTemporary = directory.resolve("missing.tmp").toFile();
            Files.write(target.toPath(), "last good hosts".getBytes(StandardCharsets.UTF_8));

            assertThrows(IOException.class,
                    () -> ServiceExternal.replaceHostsFile(missingTemporary, target));

            assertEquals("last good hosts",
                    new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        } finally {
            deleteTree(directory);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
