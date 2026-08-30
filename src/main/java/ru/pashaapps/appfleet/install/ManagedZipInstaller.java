package ru.pashaapps.appfleet.install;

import ru.pashaapps.appfleet.domain.RepositoryId;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Portable ZIP install with Zip Slip rejection, capacity preflight and a reversible adjacent swap. */
public final class ManagedZipInstaller {
    private final Path managedRoot;
    public ManagedZipInstaller(Path managedRoot) { this.managedRoot = managedRoot.toAbsolutePath().normalize(); }
    public ManagedZipInstall install(Path archive, RepositoryId repository, CancellationToken cancellation) throws IOException {
        Path parent = managedRoot.resolve(repository.owner()).normalize();
        Path target = parent.resolve(repository.repository()).normalize();
        if (!target.startsWith(managedRoot) || target.equals(managedRoot)) throw new IOException("Некорректный каталог управляемого приложения");
        Files.createDirectories(parent);
        Path staged = parent.resolve("." + repository.repository() + ".new-" + UUID.randomUUID()).normalize();
        Path backup = parent.resolve("." + repository.repository() + ".backup-" + UUID.randomUUID()).normalize();
        try {
            extractSafely(archive, staged, cancellation);
            Path old = Files.exists(target) ? backup : null;
            if (old != null) Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE);
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException swapFailure) {
                if (old != null && !Files.exists(target)) Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE);
                throw swapFailure;
            }
            return new ManagedZipInstall(target, old);
        } catch (Exception failure) {
            SafeFiles.deleteTree(staged, parent);
            throw failure;
        }
    }
    private static void extractSafely(Path archive, Path staged, CancellationToken cancellation) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            long uncompressed = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) { ZipEntry entry = entries.nextElement(); validateEntry(entry, staged); if (entry.getSize() > 0) uncompressed = Math.addExact(uncompressed, entry.getSize()); }
            FileStore store = Files.getFileStore(staged.getParent());
            if (uncompressed > store.getUsableSpace()) throw new IOException("Недостаточно места для распаковки ZIP");
            Files.createDirectories(staged);
            entries = zip.entries();
            while (entries.hasMoreElements()) {
                cancellation.throwIfCancelled();
                ZipEntry entry = entries.nextElement();
                Path destination = staged.resolve(entry.getName()).normalize();
                if (entry.isDirectory()) Files.createDirectories(destination);
                else {
                    Files.createDirectories(destination.getParent());
                    try (InputStream input = zip.getInputStream(entry); var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) { input.transferTo(output); }
                }
            }
        }
    }
    private static void validateEntry(ZipEntry entry, Path staged) throws IOException {
        Path output = staged.resolve(entry.getName()).normalize();
        if (entry.getName().isBlank() || Path.of(entry.getName()).isAbsolute() || !output.startsWith(staged)) throw new IOException("ZIP содержит небезопасный путь: " + entry.getName());
    }
}
