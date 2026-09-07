package ru.pashaapps.appfleet.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessManagerTest {
    @TempDir Path temporaryDirectory;

    @Test void sameNamedExecutableInAnotherDirectoryIsNotAnEligibleProcessTarget() throws Exception {
        Path installed = temporaryDirectory.resolve("installed").resolve("Widget.exe");
        Path other = temporaryDirectory.resolve("other").resolve("Widget.exe");
        Files.createDirectories(installed.getParent());
        Files.createDirectories(other.getParent());
        Files.writeString(installed, "installed");
        Files.writeString(other, "other");

        assertTrue(ProcessManager.matchesVerifiedExecutable(installed, installed, Set.of("widget.exe")));
        assertFalse(ProcessManager.matchesVerifiedExecutable(other, installed, Set.of("widget.exe")));
    }

    @Test void verifiedPathMustAlsoHaveAnAllowedExecutableName() throws Exception {
        Path installed = temporaryDirectory.resolve("Widget.exe");
        Files.writeString(installed, "installed");

        assertFalse(ProcessManager.matchesVerifiedExecutable(installed, installed, Set.of("runtime.exe")));
    }
}
