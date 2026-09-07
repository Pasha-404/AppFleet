package ru.pashaapps.appfleet.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Rollback handle kept until the repository state has been persisted. */
public final class ManagedZipInstall {
    private final Path workingDirectory;
    private final Path backupDirectory;
    private final Path executableRelativePath;

    ManagedZipInstall(Path workingDirectory, Path backupDirectory, Path executableRelativePath) {
        this.workingDirectory = workingDirectory;
        this.backupDirectory = backupDirectory;
        this.executableRelativePath = executableRelativePath;
    }

    public Path workingDirectory() { return workingDirectory; }

    /** The sole EXE verified in staging, now resolved under the published directory. */
    public Path executable() {
        Path executable = workingDirectory.resolve(executableRelativePath).normalize();
        if (!executable.startsWith(workingDirectory)) throw new IllegalStateException("Некорректный путь EXE ZIP-установки");
        return executable;
    }

    /** The durable state now points to the new directory, so the obsolete backup may be removed. */
    public void completeSuccessfully() throws IOException {
        if (backupDirectory != null) SafeFiles.deleteTree(backupDirectory, backupDirectory.getParent());
    }

    /** Restores the last known-good version, including when this was the first installation. */
    public void rollback() throws IOException {
        SafeFiles.deleteTree(workingDirectory, workingDirectory.getParent());
        if (backupDirectory != null && Files.exists(backupDirectory)) {
            ManagedZipInstaller.moveAdjacent(backupDirectory, workingDirectory);
        }
    }
}
