package ru.pashaapps.appfleet.service;

import org.junit.jupiter.api.Test;
import ru.pashaapps.appfleet.install.InstalledApplication;
import ru.pashaapps.appfleet.persistence.RepositoryState;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InstalledApplicationStateMergeTest {
    @Test void recordsTheDetectedExecutableForNativeCardIconLookup() {
        RepositoryState original = new RepositoryState(1, "Pasha-404", "LocalDrop", "https://github.com/Pasha-404/LocalDrop", "INNO", "X64", Set.of("setup"),
                "2.2.0", 123L, "INNO", null, null, Set.of(), "etag", Instant.parse("2026-09-03T10:00:00Z"), "Успешно");
        InstalledApplication detected = new InstalledApplication(UUID.fromString("11111111-2222-4333-8444-555555555555"), "LocalDrop", "LocalDrop", "2.3.0",
                Path.of("C:\\Users\\User\\AppData\\Local\\Programs\\PashaApps\\LocalDrop"), Path.of("C:\\Users\\User\\AppData\\Local\\Programs\\PashaApps\\LocalDrop\\LocalDrop.exe"), "LocalDrop.exe", "https://github.com/Pasha-404/LocalDrop", "inno", "installer");

        RepositoryState merged = AppFleetService.mergeDetectedStandard(original, detected);

        assertEquals("2.3.0", merged.installedVersion());
        assertEquals(detected.installLocation().toString(), merged.installLocation());
        assertEquals(detected.executable().toString(), merged.executable());
        assertEquals(Set.of("LocalDrop.exe"), merged.processNames());
        assertEquals("etag", merged.releaseEtag());
    }
}
