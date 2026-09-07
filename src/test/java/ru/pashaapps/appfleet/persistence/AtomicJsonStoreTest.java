package ru.pashaapps.appfleet.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pashaapps.appfleet.AppFleetObjectMapper;
import ru.pashaapps.appfleet.domain.AppFleetManifest;
import ru.pashaapps.appfleet.domain.GithubRelease;
import ru.pashaapps.appfleet.domain.PackageType;
import ru.pashaapps.appfleet.domain.ReleaseAsset;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AtomicJsonStoreTest {
    @TempDir Path temporaryDirectory;
    @Test void readsTheLastKnownGoodBackupWhenPrimaryBecomesCorrupt() throws IOException {
        AtomicJsonStore<UserSettings> store = new AtomicJsonStore<>(AppFleetObjectMapper.create(), UserSettings.class, temporaryDirectory.resolve("settings.json"));
        store.write(new UserSettings(false, true, true));
        store.write(new UserSettings(true, false, false));
        Files.writeString(temporaryDirectory.resolve("settings.json"), "broken json");
        assertEquals(new UserSettings(false, true, true), store.read().orElseThrow());
        Files.delete(temporaryDirectory.resolve("settings.json.bak"));
        assertEquals(new UserSettings(false, true, true), new AtomicJsonStore<>(AppFleetObjectMapper.create(), UserSettings.class, temporaryDirectory.resolve("settings.json")).read().orElseThrow());
    }
    @Test void corruptPrimaryDoesNotReplaceTheLastKnownGoodBackupOnNextWrite() throws IOException {
        Path file = temporaryDirectory.resolve("settings.json");
        AtomicJsonStore<UserSettings> store = new AtomicJsonStore<>(AppFleetObjectMapper.create(), UserSettings.class, file);
        UserSettings first = new UserSettings(false, true, true);
        store.write(first);
        store.write(new UserSettings(true, false, false));
        Files.writeString(file, "broken json");

        store.write(new UserSettings(true, true, false));
        Files.writeString(file, "broken json again");

        assertEquals(first, store.read().orElseThrow());
    }
    @Test void deleteRemovesPrimaryAndBackupWithoutResurrection() throws IOException {
        Path file = temporaryDirectory.resolve("settings.json");
        AtomicJsonStore<UserSettings> store = new AtomicJsonStore<>(AppFleetObjectMapper.create(), UserSettings.class, file);
        store.write(new UserSettings(false, true, true));
        store.write(new UserSettings(true, false, false));

        store.delete();

        assertFalse(Files.exists(file));
        assertFalse(Files.exists(temporaryDirectory.resolve("settings.json.bak")));
        assertTrue(new AtomicJsonStore<>(AppFleetObjectMapper.create(), UserSettings.class, file).read().isEmpty());
    }
    @Test void nullPrimaryIsTreatedAsCorruptAndFallsBackToTheBackup() throws IOException {
        Path file = temporaryDirectory.resolve("settings.json");
        AtomicJsonStore<UserSettings> store = new AtomicJsonStore<>(AppFleetObjectMapper.create(), UserSettings.class, file);
        UserSettings expected = new UserSettings(false, true, true);
        store.write(expected);
        store.write(new UserSettings(true, false, false));
        Files.writeString(file, "null");

        assertEquals(expected, store.read().orElseThrow());
    }
    @Test void semanticallyMalformedRepositoryStateFallsBackToTheBackup() throws IOException {
        Path file = temporaryDirectory.resolve("repositories.json");
        AtomicJsonStore<RepositoriesDocument> store = new AtomicJsonStore<>(AppFleetObjectMapper.create(), RepositoriesDocument.class, file);
        RepositoriesDocument expected = new RepositoriesDocument(1, List.of(new RepositoryState(1, "Pasha-404", "sortit", "https://github.com/Pasha-404/sortit", null, null, null, null, null, null, null, null, null, null, null, null)));
        store.write(expected);
        store.write(new RepositoriesDocument(1, List.of()));
        Files.writeString(file, "{\"schemaVersion\":1,\"repositories\":[{\"schemaVersion\":1,\"owner\":\"Pasha-404\",\"repository\":\"sortit\",\"canonicalUrl\":\"https://github.com/other/project\"}]}" );

        assertEquals(expected, store.read().orElseThrow());
    }
    @Test void missingSettingsUseCompatibleDefaults() { assertEquals(UserSettings.defaults(), new UserSettings(null, null, null).normalized()); }
    @Test void persistsVerifiedStandardReleaseForOfflineFallback() throws IOException {
        AtomicJsonStore<ReleaseCache> store = new AtomicJsonStore<>(AppFleetObjectMapper.create(), ReleaseCache.class, temporaryDirectory.resolve("release-cache.json"));
        GithubRelease release = new GithubRelease(17, "v1.5.0", "SortIt", "", false, false, Instant.parse("2026-08-29T13:07:00Z"), URI.create("https://github.com/Pasha-404/sortit/releases/tag/v1.5.0"), List.of(new ReleaseAsset(18, "SortIt-Setup-1.5.0-x64.exe", 42, URI.create("https://example.test/SortIt-Setup-1.5.0-x64.exe"), "application/vnd.microsoft.portable-executable")));
        AppFleetManifest manifest = new AppFleetManifest(1, UUID.fromString("f238fccc-4f33-429d-b476-7cd286adb376"), "SortIt", "SortIt", "1.5.0", "https://github.com/Pasha-404/sortit", "windows", "x64", new AppFleetManifest.Installer(PackageType.INNO, "SortIt-Setup-1.5.0-x64.exe", "SortIt-Setup-1.5.0-x64.exe.sha256", List.of("/VERYSILENT"), "desktopicon"), new AppFleetManifest.Detection("HKCU\\Software\\PashaApps\\f238fccc-4f33-429d-b476-7cd286adb376", "Version", "Executable"), List.of("SortIt.exe"), "1.0.0");
        ReleaseCache expected = new ReleaseCache(1, List.of(new ReleaseCache.Entry("pasha-404/sortit", release, manifest, Instant.parse("2026-08-31T12:00:00Z"))));

        store.write(expected);

        assertEquals(expected, store.read().orElseThrow());
    }
}
