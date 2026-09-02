package ru.pashaapps.appfleet.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfUpdateServiceTest {
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

        assertTrue(installerScript.contains("Name: \"desktopicon\"; Description: \"Создать ярлык на рабочем столе\""));
        assertTrue(installerScript.contains("Tasks: desktopicon"));
    }
}
