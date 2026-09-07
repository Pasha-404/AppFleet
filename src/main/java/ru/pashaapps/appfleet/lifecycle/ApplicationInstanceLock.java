package ru.pashaapps.appfleet.lifecycle;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** Per-user lock that prevents two AppFleet processes from mutating the same state files. */
public final class ApplicationInstanceLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    private ApplicationInstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static Optional<ApplicationInstanceLock> tryAcquire(Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        Files.createDirectories(normalized.getParent());
        FileChannel channel = FileChannel.open(normalized, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return Optional.empty();
            }
            return Optional.of(new ApplicationInstanceLock(channel, lock));
        } catch (OverlappingFileLockException alreadyLockedInThisJvm) {
            channel.close();
            return Optional.empty();
        } catch (IOException failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } finally {
            channel.close();
        }
    }
}
