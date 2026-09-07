package ru.pashaapps.appfleet.service;

import org.junit.jupiter.api.Test;
import ru.pashaapps.appfleet.domain.AppFleetManifest;
import ru.pashaapps.appfleet.domain.PackageType;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinimumAppFleetVersionTest {
    @Test void permitsEqualAndNewerRunningVersions() {
        AppFleetManifest manifest = manifest("1.4.0");

        assertDoesNotThrow(() -> MinimumAppFleetVersion.requireSupported("1.4.0", manifest));
        assertDoesNotThrow(() -> MinimumAppFleetVersion.requireSupported("1.4.1", manifest));
    }

    @Test void blocksManagedApplicationWhenRunningAppFleetIsTooOld() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> MinimumAppFleetVersion.requireSupported("1.3.9", manifest("1.4.0")));

        assertTrue(error.getMessage().contains("Сначала обновите AppFleet"));
    }

    private static AppFleetManifest manifest(String minimum) {
        return new AppFleetManifest(1, UUID.randomUUID(), "Example", "Example", "1.0.0", "https://github.com/example/example",
                "windows", "x64", new AppFleetManifest.Installer(PackageType.INNO, "Example.exe", "Example.exe.sha256", List.of(), null),
                null, List.of(), minimum);
    }
}
