package ru.pashaapps.appfleet.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** Atomic JSON writes with a last-known-good sibling backup. */
public final class AtomicJsonStore<T> {
    private static final Logger log = LoggerFactory.getLogger(AtomicJsonStore.class);
    private final ObjectMapper mapper;
    private final Class<T> type;
    private final Path file;

    public AtomicJsonStore(ObjectMapper mapper, Class<T> type, Path file) {
        this.mapper = mapper;
        this.type = type;
        this.file = file.toAbsolutePath().normalize();
    }

    public Optional<T> read() {
        Optional<T> primary = readOne(file);
        if (primary.isPresent()) return primary;
        Optional<T> backup = readOne(backupPath());
        if (backup.isPresent()) log.warn("Восстановлено состояние AppFleet из резервной копии {}", backupPath());
        return backup;
    }

    public void write(T value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            // A parse round-trip ensures a partially encoded write is never promoted.
            mapper.readValue(temporary.toFile(), type);
            if (Files.exists(file)) Files.copy(file, backupPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Optional<T> readOne(Path candidate) {
        if (!Files.isRegularFile(candidate)) return Optional.empty();
        try { return Optional.of(mapper.readValue(candidate.toFile(), type)); }
        catch (IOException malformed) { log.warn("Повреждён файл состояния {}: {}", candidate, malformed.getMessage()); return Optional.empty(); }
    }
    private Path backupPath() { return file.resolveSibling(file.getFileName() + ".bak"); }
}

