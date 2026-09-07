package ru.pashaapps.appfleet.install;

import ru.pashaapps.appfleet.domain.PackageType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Launches only a downloaded local file as separated ProcessBuilder arguments. */
public final class ExternalInstallerRunner {
    private final DirectInstallerLauncher directLauncher;
    private final ElevatedInstallerLauncher elevatedLauncher;

    public ExternalInstallerRunner() {
        this(command -> {
            try {
                return new ProcessBuilder(command).inheritIO().start().waitFor();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Ожидание установщика прервано", interrupted);
            }
        }, new WindowsElevatedInstallerLauncher());
    }

    ExternalInstallerRunner(DirectInstallerLauncher directLauncher, ElevatedInstallerLauncher elevatedLauncher) {
        this.directLauncher = directLauncher;
        this.elevatedLauncher = elevatedLauncher;
    }

    public InstallerExit run(Path file, PackageType type, List<String> manifestArguments) throws IOException {
        List<String> command = commandFor(file, type, manifestArguments);
        try {
            int exit = directLauncher.launchAndWait(command);
            return type == PackageType.MSI ? InstallerExit.forMsi(exit) : InstallerExit.forGeneric(exit);
        } catch (IOException launchFailure) {
            if (type != PackageType.EXE || !isElevationRequired(launchFailure)) throw launchFailure;
            return InstallerExit.forGeneric(elevatedLauncher.launchAndWait(file));
        }
    }

    static boolean isElevationRequired(IOException failure) {
        String text = failure.getMessage();
        return text != null && text.matches("(?is).*\\b(?:error[= ]*)?740\\b.*");
    }

    static List<String> commandFor(Path file, PackageType type, List<String> manifestArguments) throws IOException {
        Path localFile = file.toAbsolutePath().normalize();
        if (!java.nio.file.Files.isRegularFile(localFile)) throw new IOException("Файл установщика не найден");
        List<String> command = new ArrayList<>();
        if (type == PackageType.MSI) command.addAll(List.of("msiexec.exe", "/i", localFile.toString(), "/passive", "/norestart"));
        else {
            command.add(localFile.toString());
            if (type == PackageType.INNO) command.addAll(manifestArguments);
            // Generic EXE is deliberately interactive: no guessed silent arguments.
        }
        return List.copyOf(command);
    }

    @FunctionalInterface
    interface DirectInstallerLauncher {
        int launchAndWait(List<String> command) throws IOException;
    }
}
