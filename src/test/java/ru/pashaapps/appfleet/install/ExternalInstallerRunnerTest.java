package ru.pashaapps.appfleet.install;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalInstallerRunnerTest {
    @Test void mapsWindowsInstallerRestartCodesWithoutRestartingWindows() {
        InstallerExit exit = InstallerExit.forMsi(3010);
        assertTrue(exit.successful());
        assertTrue(exit.restartRequired());
        assertEquals(3010, exit.code());
    }
}
