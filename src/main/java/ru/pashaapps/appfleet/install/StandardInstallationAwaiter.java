package ru.pashaapps.appfleet.install;

import ru.pashaapps.appfleet.domain.SemVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Waits for a standard installer whose bootstrap process exited before its child installer finished. */
public final class StandardInstallationAwaiter {
    private final WindowsRegistryDetector registry;

    public StandardInstallationAwaiter(WindowsRegistryDetector registry) {
        this.registry = registry;
    }

    public InstalledApplication await(UUID appId, String expectedVersion, Duration timeout) throws IOException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Optional<InstalledApplication> candidate = registry.findStandardApplication(appId);
            if (candidate.filter(installed -> matchesExpectedVersion(installed.version(), expectedVersion))
                    .filter(installed -> Files.isRegularFile(installed.executable())).isPresent()) {
                return candidate.orElseThrow();
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Ожидание регистрации установленного приложения прервано", interrupted);
            }
        }
        throw new IOException("Установщик завершил работу, но AppFleet не обнаружил установленную версию " + expectedVersion + " в течение " + timeout.toSeconds() + " с");
    }

    static boolean matchesExpectedVersion(String installedVersion, String expectedVersion) {
        return SemVersion.tryParse(installedVersion).isPresent()
                && SemVersion.tryParse(expectedVersion).isPresent()
                && SemVersion.parse(installedVersion).equals(SemVersion.parse(expectedVersion));
    }
}
