package ru.pashaapps.appfleet.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Gives the coordinator a rollback handle until the portable app has been launched successfully. */
public final class ManagedZipInstall {
    private final Path workingDirectory;
    private final Path backupDirectory;
    ManagedZipInstall(Path workingDirectory, Path backupDirectory) { this.workingDirectory = workingDirectory; this.backupDirectory = backupDirectory; }
    public Path workingDirectory() { return workingDirectory; }
    public void completeSuccessfully() throws IOException { if (backupDirectory != null) SafeFiles.deleteTree(backupDirectory, backupDirectory.getParent()); }
    public void rollback() throws IOException {
        if (backupDirectory == null) return;
        SafeFiles.deleteTree(workingDirectory, workingDirectory.getParent());
        Files.move(backupDirectory, workingDirectory);
    }
}

