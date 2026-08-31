package ru.pashaapps.appfleet.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import ru.pashaapps.appfleet.AppFleetObjectMapper;
import ru.pashaapps.appfleet.domain.PackageType;
import ru.pashaapps.appfleet.install.CancellationToken;
import ru.pashaapps.appfleet.install.ExternalInstallerRunner;
import ru.pashaapps.appfleet.install.WindowsRegistryDetector;
import ru.pashaapps.appfleet.persistence.AppPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Deliberately opt-in: it changes the current user's SortIt installation and needs public GitHub access.
 * It proves the complete manifest → installer → registry-detection path required by the specification.
 */
@Tag("live-install")
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "runLiveInstallTests", matches = "true")
class LiveSortItInstallerIntegrationTest {
    private static final UUID SORTIT_APP_ID = UUID.fromString("f238fccc-4f33-429d-b476-7cd286adb376");
    private static final String SORTIT_REPOSITORY = "https://github.com/Pasha-404/sortit";
    private static final Duration INSTALLER_TIMEOUT = Duration.ofSeconds(90);

    @TempDir Path temporaryDirectory;

    @Test
    void uninstallsAndReinstallsSortItThroughAppFleetService() throws Exception {
        WindowsRegistryDetector detector = new WindowsRegistryDetector();
        Optional<ru.pashaapps.appfleet.install.InstalledApplication> existing = detector.findStandardApplication(SORTIT_APP_ID);
        assertTrue(existing.isPresent(), "SortIt 1.5.0 must be installed before this explicitly destructive test");
        Path uninstaller = existing.orElseThrow().installLocation().resolve("unins000.exe");
        assertTrue(Files.isRegularFile(uninstaller), "The standard SortIt uninstaller must be present");

        new ExternalInstallerRunner().run(uninstaller, PackageType.INNO, java.util.List.of("/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS"));
        await(() -> detector.findStandardApplication(SORTIT_APP_ID).isEmpty(), "SortIt was not removed before the integration installation");

        AppPaths paths = new AppPaths(temporaryDirectory.resolve("roaming"), temporaryDirectory.resolve("local"), temporaryDirectory.resolve("temporary"), temporaryDirectory.resolve("programs"));
        try (AppFleetService service = new AppFleetService(paths, AppFleetObjectMapper.create(), "test")) {
            ApplicationSnapshot added = service.addRepository(SORTIT_REPOSITORY).get();
            assertEquals(AppStatus.NOT_INSTALLED, added.status());

            OperationResult result = service.installOrUpdate(added.repository(), new OperationRequest(true, true, false), CancellationToken.NEVER_CANCELLED, ru.pashaapps.appfleet.install.DownloadProgress.NONE).get();

            assertTrue(result.successful(), result.message());
            assertEquals(AppStatus.UP_TO_DATE, result.updatedSnapshot().status());
            assertTrue(detector.findStandardApplication(SORTIT_APP_ID).isPresent());
        }
    }

    private static void await(CheckedCondition condition, String failureMessage) throws Exception {
        Instant deadline = Instant.now().plus(INSTALLER_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.matches()) return;
            Thread.sleep(250);
        }
        fail(failureMessage);
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean matches() throws Exception;
    }
}
