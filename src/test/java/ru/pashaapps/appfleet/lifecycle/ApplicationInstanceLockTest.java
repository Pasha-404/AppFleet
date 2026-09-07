package ru.pashaapps.appfleet.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationInstanceLockTest {
    @TempDir Path temporaryDirectory;

    @Test void secondAcquisitionIsRejectedUntilTheFirstLockIsReleased() throws Exception {
        Path lockFile = temporaryDirectory.resolve("appfleet.instance.lock");
        ApplicationInstanceLock first = ApplicationInstanceLock.tryAcquire(lockFile).orElseThrow();
        try {
            assertFalse(ApplicationInstanceLock.tryAcquire(lockFile).isPresent());
        } finally {
            first.close();
        }

        try (ApplicationInstanceLock next = ApplicationInstanceLock.tryAcquire(lockFile).orElseThrow()) {
            assertTrue(lockFile.toFile().isFile());
        }
    }
}
