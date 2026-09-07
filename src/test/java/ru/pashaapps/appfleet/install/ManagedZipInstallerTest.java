package ru.pashaapps.appfleet.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pashaapps.appfleet.domain.RepositoryId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ManagedZipInstallerTest {
    @TempDir Path temporaryDirectory;
    @Test void replacesPortableDirectoryAndCanRollback() throws IOException {
        Path root = temporaryDirectory.resolve("managed");
        RepositoryId repository = new RepositoryId("owner", "tool");
        Path prior = root.resolve("owner/tool");
        Files.createDirectories(prior);
        Files.writeString(prior.resolve("version.txt"), "old");
        ManagedZipInstall install = new ManagedZipInstaller(root).install(zip(Map.of("version.txt", "new", "bin/tool.exe", "binary")), repository, CancellationToken.NEVER_CANCELLED);
        assertEquals("new", Files.readString(install.workingDirectory().resolve("version.txt")));
        assertEquals(install.workingDirectory().resolve("bin/tool.exe"), install.executable());
        install.rollback();
        assertEquals("old", Files.readString(prior.resolve("version.txt")));
    }

    @Test void rejectsArchiveWithoutAnExecutableBeforeReplacingExistingInstall() throws IOException {
        Path root = temporaryDirectory.resolve("managed");
        RepositoryId repository = new RepositoryId("owner", "tool");
        Path prior = root.resolve("owner/tool");
        Files.createDirectories(prior);
        Files.writeString(prior.resolve("version.txt"), "old");

        assertThrows(IOException.class, () -> new ManagedZipInstaller(root).install(zip(Map.of("version.txt", "new")), repository, CancellationToken.NEVER_CANCELLED));

        assertEquals("old", Files.readString(prior.resolve("version.txt")));
    }

    @Test void rejectsAmbiguousArchiveWithTwoExecutablesBeforeReplacingExistingInstall() throws IOException {
        Path root = temporaryDirectory.resolve("managed");
        RepositoryId repository = new RepositoryId("owner", "tool");
        Path prior = root.resolve("owner/tool");
        Files.createDirectories(prior);
        Files.writeString(prior.resolve("version.txt"), "old");

        assertThrows(IOException.class, () -> new ManagedZipInstaller(root).install(zip(Map.of("one.exe", "one", "two.exe", "two")), repository, CancellationToken.NEVER_CANCELLED));

        assertEquals("old", Files.readString(prior.resolve("version.txt")));
    }

    @Test void rollbackOfFirstInstallRemovesPublishedDirectory() throws IOException {
        Path root = temporaryDirectory.resolve("managed");
        RepositoryId repository = new RepositoryId("owner", "tool");

        ManagedZipInstall install = new ManagedZipInstaller(root).install(zip(Map.of("tool.exe", "binary")), repository, CancellationToken.NEVER_CANCELLED);
        install.rollback();

        assertFalse(Files.exists(root.resolve("owner/tool")));
    }

    @Test void successfulCompletionRemovesOnlyTheBackup() throws IOException {
        Path root = temporaryDirectory.resolve("managed");
        RepositoryId repository = new RepositoryId("owner", "tool");
        Path prior = root.resolve("owner/tool");
        Files.createDirectories(prior);
        Files.writeString(prior.resolve("version.txt"), "old");

        ManagedZipInstall install = new ManagedZipInstaller(root).install(zip(Map.of("tool.exe", "new")), repository, CancellationToken.NEVER_CANCELLED);
        install.completeSuccessfully();

        assertTrue(Files.isRegularFile(install.executable()));
        try (var entries = Files.list(root.resolve("owner"))) {
            assertEquals(List.of("tool"), entries.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }
    @Test void blocksZipSlipBeforeWritingOutsideManagedDirectory() throws IOException {
        Path archive = zip(Map.of("../outside.txt", "blocked"));
        assertThrows(IOException.class, () -> new ManagedZipInstaller(temporaryDirectory.resolve("managed")).install(archive, new RepositoryId("owner", "tool"), CancellationToken.NEVER_CANCELLED));
        assertFalse(Files.exists(temporaryDirectory.resolve("outside.txt")));
    }
    private Path zip(Map<String, String> contents) throws IOException {
        Path file = temporaryDirectory.resolve("payload-" + System.nanoTime() + ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, String> entry : contents.entrySet()) { output.putNextEntry(new ZipEntry(entry.getKey())); output.write(entry.getValue().getBytes()); output.closeEntry(); }
        }
        return file;
    }
}
