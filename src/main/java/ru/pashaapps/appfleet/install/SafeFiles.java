package ru.pashaapps.appfleet.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

final class SafeFiles {
    private SafeFiles() { }
    static void deleteTree(Path target, Path allowedParent) throws IOException {
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path normalizedParent = allowedParent.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedParent) || normalizedTarget.equals(normalizedParent) || !Files.exists(normalizedTarget)) return;
        Files.walkFileTree(normalizedTarget, new SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE; }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException { if (error != null) throw error; Files.delete(directory); return java.nio.file.FileVisitResult.CONTINUE; }
        });
    }
}

