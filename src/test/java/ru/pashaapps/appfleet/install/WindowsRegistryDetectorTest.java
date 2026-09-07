package ru.pashaapps.appfleet.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsRegistryDetectorTest {
    @TempDir Path temporaryDirectory;

    @Test void preservesUnicodeValuesFromTheRegistryAdapter() throws IOException {
        UUID appId = UUID.randomUUID();
        Path install = temporaryDirectory.resolve("Папка приложения");
        Path executable = install.resolve("Программа.exe");
        Files.createDirectories(install);
        Files.writeString(executable, "test");
        Map<String, String> values = standardValues(appId, install, executable, "Тест");

        WindowsRegistryDetector detector = new WindowsRegistryDetector((subKey, names) -> Optional.of(values));

        InstalledApplication found = detector.findStandardApplication(appId).orElseThrow();
        assertEquals(executable.toAbsolutePath().normalize(), found.executable());
        assertEquals("Тест", found.name());
    }

    @Test void missingOrInvalidExecutableIsNotReportedAsInstalled() throws IOException {
        UUID appId = UUID.randomUUID();
        Path install = temporaryDirectory.resolve("app");
        Files.createDirectories(install);
        Map<String, String> values = standardValues(appId, install, install.resolve("missing.exe"), "Test");

        assertTrue(new WindowsRegistryDetector((subKey, names) -> Optional.of(values)).findStandardApplication(appId).isEmpty());
    }

    private static Map<String, String> standardValues(UUID appId, Path install, Path executable, String name) {
        return Map.ofEntries(
                Map.entry("SchemaVersion", "1"), Map.entry("AppId", appId.toString()), Map.entry("Name", name), Map.entry("TechnicalName", "Test"),
                Map.entry("Version", "1.0.0"), Map.entry("InstallLocation", install.toString()), Map.entry("Executable", executable.toString()),
                Map.entry("ProcessName", executable.getFileName().toString()), Map.entry("RepositoryUrl", "https://github.com/example/test"),
                Map.entry("InstallerType", "inno"), Map.entry("InstalledBy", "AppFleet"));
    }
}
