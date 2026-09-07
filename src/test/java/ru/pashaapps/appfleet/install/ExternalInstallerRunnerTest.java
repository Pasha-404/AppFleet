package ru.pashaapps.appfleet.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pashaapps.appfleet.domain.PackageType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Test void genericElevationRequiredFallsBackToTheExplicitWindowsUacLauncher() throws IOException {
        Path installer = temporaryDirectory.resolve("ThirdParty.exe");
        Files.writeString(installer, "fixture");
        AtomicBoolean elevated = new AtomicBoolean();
        ExternalInstallerRunner runner = new ExternalInstallerRunner(
                command -> { throw new IOException("CreateProcess error=740, The requested operation requires elevation"); },
                file -> { elevated.set(true); assertEquals(installer, file); return 0; });

        InstallerExit exit = runner.run(installer, PackageType.EXE, List.of());

        assertTrue(elevated.get());
        assertTrue(exit.successful());
    }

    @Test void userCancellingUacIsReportedAndNeverTurnsIntoInstallSuccess() throws IOException {
        Path installer = temporaryDirectory.resolve("ThirdParty.exe");
        Files.writeString(installer, "fixture");
        ExternalInstallerRunner runner = new ExternalInstallerRunner(
                command -> { throw new IOException("CreateProcess error=740"); },
                file -> { throw new UacCancelledException(); });

        assertThrows(UacCancelledException.class, () -> runner.run(installer, PackageType.EXE, List.of()));
    }

    @Test void elevationFallbackIsLimitedToGenericExePackages() throws IOException {
        Path installer = temporaryDirectory.resolve("Setup.msi");
        Files.writeString(installer, "fixture");
        AtomicBoolean elevated = new AtomicBoolean();
        ExternalInstallerRunner runner = new ExternalInstallerRunner(
                command -> { throw new IOException("CreateProcess error=740"); },
                file -> { elevated.set(true); return 0; });

        assertThrows(IOException.class, () -> runner.run(installer, PackageType.MSI, List.of()));
        assertFalse(elevated.get());
    }
}
