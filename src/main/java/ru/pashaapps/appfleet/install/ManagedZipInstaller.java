package ru.pashaapps.appfleet.install;

import ru.pashaapps.appfleet.domain.RepositoryId;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Portable ZIP install as a reversible transaction:
 * private staging → verify one launchable EXE → adjacent swap → durable state → backup cleanup.
 */
public final class ManagedZipInstaller {
    private final Path managedRoot;

    public ManagedZipInstaller(Path managedRoot) {
        this.managedRoot = managedRoot.toAbsolutePath().normalize();
    }

    public ManagedZipInstall install(Path archive, RepositoryId repository, CancellationToken cancellation) throws IOException {
        Path parent = managedRoot.resolve(repository.owner()).normalize();
        Path target = parent.resolve(repository.repository()).normalize();
        if (!parent.startsWith(managedRoot) || !target.startsWith(parent) || target.equals(parent)) {
            throw new IOException("Некорректный каталог управляемого приложения");
        }
        Files.createDirectories(parent);

        Path staged = parent.resolve("." + repository.repository() + ".new-" + UUID.randomUUID()).normalize();
        Path backup = parent.resolve("." + repository.repository() + ".backup-" + UUID.randomUUID()).normalize();
        if (!staged.getParent().equals(parent) || !backup.getParent().equals(parent)) {
            throw new IOException("Некорректный временный каталог ZIP-установки");
        }

        boolean oldMoved = false;
        boolean newPublished = false;
        try {
            // CREATE_NEW semantics prove this directory belongs to this transaction.
            Files.createDirectory(staged);
            extractSafely(archive, staged, cancellation);
            Path executable = findOnlyExecutable(staged);
            Path executableRelative = staged.relativize(executable);

            Path old = Files.exists(target) ? backup : null;
            if (old != null) {
                moveAdjacent(target, backup);
                oldMoved = true;
            }
            moveAdjacent(staged, target);
            newPublished = true;
            return new ManagedZipInstall(target, old, executableRelative);
        } catch (Exception failure) {
            // A failed first installation must not leave a published new directory either.
            if (newPublished) SafeFiles.deleteTree(target, parent);
            if (oldMoved && !Files.exists(target) && Files.exists(backup)) moveAdjacent(backup, target);
            SafeFiles.deleteTree(staged, parent);
            if (failure instanceof IOException io) throw io;
            throw new IOException("Не удалось безопасно установить ZIP-архив", failure);
        }
    }

    private static Path findOnlyExecutable(Path staged) throws IOException {
        List<Path> executables;
        try (var files = Files.walk(staged)) {
            executables = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".exe"))
                    .limit(2)
                    .toList();
        }
        if (executables.isEmpty()) throw new IOException("ZIP-архив не содержит запускаемый EXE-файл");
        if (executables.size() > 1) throw new IOException("ZIP-архив содержит несколько EXE-файлов; невозможно безопасно выбрать главный");
        Path executable = executables.getFirst();
        if (Files.size(executable) == 0) throw new IOException("ZIP-архив содержит пустой EXE-файл");
        return executable;
    }

    private static void extractSafely(Path archive, Path staged, CancellationToken cancellation) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            long uncompressed = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                validateEntry(entry, staged);
                if (entry.getSize() > 0) uncompressed = Math.addExact(uncompressed, entry.getSize());
            }
            FileStore store = Files.getFileStore(staged);
            if (uncompressed > store.getUsableSpace()) throw new IOException("Недостаточно места для распаковки ZIP");

            entries = zip.entries();
            while (entries.hasMoreElements()) {
                cancellation.throwIfCancelled();
                ZipEntry entry = entries.nextElement();
                Path destination = staged.resolve(entry.getName()).normalize();
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    try (InputStream input = zip.getInputStream(entry);
                         var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                        input.transferTo(output);
                    }
                }
            }
        }
    }

    private static void validateEntry(ZipEntry entry, Path staged) throws IOException {
        Path output = staged.resolve(entry.getName()).normalize();
        if (entry.getName().isBlank() || Path.of(entry.getName()).isAbsolute() || !output.startsWith(staged)) {
            throw new IOException("ZIP содержит небезопасный путь: " + entry.getName());
        }
    }

    static void moveAdjacent(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(source, destination);
        }
    }
}
