package ru.pashaapps.appfleet.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationDirectoryTest {
    @TempDir Path temporaryDirectory;

    @Test void closeRemovesOnlyTheDirectoryCreatedForThisOperation() throws IOException {
        Path unrelated = temporaryDirectory.resolve("user-files");
        Files.createDirectories(unrelated);
        Files.writeString(unrelated.resolve("keep.txt"), "keep");
        OperationDirectory operation = OperationDirectory.create(temporaryDirectory, "operation-");
        Files.writeString(operation.path().resolve("download.exe"), "payload");

        operation.close();

        assertFalse(Files.exists(operation.path()));
        assertTrue(Files.exists(unrelated.resolve("keep.txt")));
    }

    @Test void recoveryRefusesAPathOutsideTheOwnedTemporaryRoot() throws IOException {
        Path temporaryRoot = temporaryDirectory.resolve("appfleet-temp");
        Path outside = temporaryDirectory.resolve("outside-operation");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("keep.txt"), "keep");

        assertFalse(OperationDirectory.deleteRecovered(temporaryRoot, outside.toString(), "self-update-"));

        assertTrue(Files.exists(outside.resolve("keep.txt")));
    }

    @Test void recoveryDeletesOnlyAValidUuidOperationDirectory() throws IOException {
        Path owned = temporaryDirectory.resolve("self-update-" + UUID.randomUUID());
        Files.createDirectories(owned);
        Files.writeString(owned.resolve("installer.exe"), "payload");

        assertTrue(OperationDirectory.deleteRecovered(temporaryDirectory, owned.toString(), "self-update-"));

        assertFalse(Files.exists(owned));
    }
}
