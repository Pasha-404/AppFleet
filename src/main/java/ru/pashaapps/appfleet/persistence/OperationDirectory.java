package ru.pashaapps.appfleet.persistence;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

/** A private, operation-owned directory directly below AppFleet's bounded temporary root. */
public final class OperationDirectory implements AutoCloseable {
    private final Path root;
    private final Path directory;
    private final String prefix;
    private boolean closed;

    private OperationDirectory(Path root, Path directory, String prefix) {
        this.root = root;
        this.directory = directory;
        this.prefix = prefix;
    }

    public static OperationDirectory create(Path temporaryRoot, String prefix) throws IOException {
        validatePrefix(prefix);
        Path root = temporaryRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        // The durable marker later proves ownership with this UUID; Files.createTempDirectory
        // uses an implementation-specific suffix and cannot provide that proof.
        Path directory = root.resolve(prefix + UUID.randomUUID()).toAbsolutePath().normalize();
        Files.createDirectory(directory);
        if (!isOwned(root, directory, prefix)) {
            throw new IOException("Не удалось создать ограниченный временный каталог операции");
        }
        return new OperationDirectory(root, directory, prefix);
    }

    public Path path() { return directory; }

    /**
     * Transfers cleanup responsibility to a durable workflow.  This is used only
     * for the self-update installer, whose downloaded files must outlive the
     * current AppFleet process.
     */
    public synchronized void detach() {
        closed = true;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        deleteOwned(root, directory, prefix);
    }

    /** Deletes a recovered operation only when its durable marker proves the exact owned UUID directory. */
    public static boolean deleteRecovered(Path temporaryRoot, String serializedDirectory, String prefix) throws IOException {
        validatePrefix(prefix);
        if (serializedDirectory == null || serializedDirectory.isBlank()) return false;
        Path root = temporaryRoot.toAbsolutePath().normalize();
        final Path candidate;
        try {
            candidate = Path.of(serializedDirectory).toAbsolutePath().normalize();
        } catch (RuntimeException malformed) {
            return false;
        }
        if (!isOwned(root, candidate, prefix)) return false;
        deleteOwned(root, candidate, prefix);
        return true;
    }

    private static void deleteOwned(Path root, Path directory, String prefix) throws IOException {
        if (!isOwned(root, directory, prefix) || !Files.exists(directory)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path folder, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(folder);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isOwned(Path root, Path directory, String prefix) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        if (!normalizedRoot.equals(normalizedDirectory.getParent())) return false;
        String name = normalizedDirectory.getFileName().toString();
        if (!name.startsWith(prefix)) return false;
        try {
            UUID.fromString(name.substring(prefix.length()));
            return true;
        } catch (IllegalArgumentException malformedId) {
            return false;
        }
    }

    private static void validatePrefix(String prefix) {
        if (prefix == null || !prefix.matches("[a-z0-9-]+-") || prefix.contains("..")) {
            throw new IllegalArgumentException("Некорректный префикс временной операции");
        }
    }
}
