package ru.pashaapps.appfleet.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pashaapps.appfleet.AppFleetObjectMapper;
import ru.pashaapps.appfleet.BuildInfo;
import ru.pashaapps.appfleet.persistence.AppPaths;
import ru.pashaapps.appfleet.persistence.AtomicJsonStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfUpdateServiceTest {
    @TempDir Path temporaryDirectory;

    @Test void startsTheInstallerWithTheExplicitSelfUpdateFlag() {
        List<String> command = SelfUpdateService.commandFor(Path.of("C:\\Temp\\AppFleet-Setup.exe"));

        assertEquals(List.of("C:\\Temp\\AppFleet-Setup.exe", "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS", "/APPFLEETSELFUPDATE"), command);
        assertFalse(command.contains("/RESTARTAPPLICATIONS"));
    }

    @Test void installerRestartsOnlyAnExplicitSelfUpdateInSilentMode() throws IOException {
        String installerScript = Files.readString(Path.of("installer", "AppFleet.iss"));

        assertTrue(installerScript.contains("Flags: nowait; Check: IsAppFleetSelfUpdate"));
        assertTrue(installerScript.contains("Flags: nowait postinstall skipifsilent; Check: not IsAppFleetSelfUpdate"));
        assertTrue(installerScript.contains("CompareText(ParamStr(Index), '/APPFLEETSELFUPDATE') = 0"));
        assertTrue(installerScript.contains("Result := HasCloseApplications and HasLegacyRestart"));
    }

    @Test void installerDeclaresTheStandardOptionalDesktopShortcutTask() throws IOException {
        String installerScript = Files.readString(Path.of("installer", "AppFleet.iss"));

        assertTrue(installerScript.contains("UsePreviousTasks=yes"));
        assertTrue(installerScript.contains("Name: \"desktopicon\"; Description: \"Создать ярлык на рабочем столе\""));
        assertTrue(installerScript.contains("Tasks: desktopicon"));
    }

    @Test void unconfirmedPreviousAttemptIsClearedSoTheUserCanRetry() throws IOException {
        AppPaths paths = new AppPaths(temporaryDirectory.resolve("roaming"), temporaryDirectory.resolve("local"), temporaryDirectory.resolve("temp"), temporaryDirectory.resolve("programs"));
        AtomicJsonStore<SelfUpdateMarker> store = new AtomicJsonStore<>(AppFleetObjectMapper.create(), SelfUpdateMarker.class, paths.cacheDirectory().resolve("self-update.json"));
        Path operation = paths.temporaryRoot().resolve("self-update-" + UUID.randomUUID());
        Files.createDirectories(operation);
        Files.writeString(operation.resolve("installer.exe"), "payload");
        store.write(new SelfUpdateMarker("1.1.0", 1, operation.toString(), Instant.now()));

        new SelfUpdateService(new BuildInfo("1.0.0", "https://github.com/Pasha-404/AppFleet", "5dce5095-1140-4ceb-8a85-99e029c73468"), paths, AppFleetObjectMapper.create()).recoverAfterLaunch();

        assertTrue(store.read().isEmpty(), "marker прошлой попытки не должен блокировать повторную попытку");
        assertFalse(Files.exists(operation), "должен удаляться только каталог, подтверждённый marker-ом операции");
    }

    @Test void recoveryNeverDeletesAnUntrustedMarkerPath() throws IOException {
        AppPaths paths = new AppPaths(temporaryDirectory.resolve("roaming"), temporaryDirectory.resolve("local"), temporaryDirectory.resolve("temp"), temporaryDirectory.resolve("programs"));
        AtomicJsonStore<SelfUpdateMarker> store = new AtomicJsonStore<>(AppFleetObjectMapper.create(), SelfUpdateMarker.class, paths.cacheDirectory().resolve("self-update.json"));
        Path unrelated = temporaryDirectory.resolve("unrelated");
        Files.createDirectories(unrelated);
        Files.writeString(unrelated.resolve("keep.txt"), "keep");
        store.write(new SelfUpdateMarker("1.1.0", 1, unrelated.toString(), Instant.now()));

        new SelfUpdateService(new BuildInfo("1.0.0", "https://github.com/Pasha-404/AppFleet", "5dce5095-1140-4ceb-8a85-99e029c73468"), paths, AppFleetObjectMapper.create()).recoverAfterLaunch();

        assertTrue(Files.exists(unrelated.resolve("keep.txt")));
        assertTrue(store.read().isEmpty(), "недоверенный marker всё равно не должен блокировать новую попытку");
    }
}
