package ru.pashaapps.appfleet.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pashaapps.appfleet.domain.PackageType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExternalInstallerRunnerTest {
    @TempDir Path temporaryDirectory;

    @Test void mapsWindowsInstallerRestartCodesWithoutRestartingWindows() {
        InstallerExit exit = InstallerExit.forMsi(3010);
        assertTrue(exit.successful());
        assertTrue(exit.restartRequired());
        assertEquals(3010, exit.code());
    }

    @Test void preservesEachApprovedInnoArgumentAsASeparateProcessArgument() throws IOException {
        Path installer = temporaryDirectory.resolve("Setup.exe");
        Files.writeString(installer, "fixture");

        assertEquals(List.of(installer.toAbsolutePath().toString(), "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART"),
                ExternalInstallerRunner.commandFor(installer, PackageType.INNO, List.of("/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART")));
    }
}
