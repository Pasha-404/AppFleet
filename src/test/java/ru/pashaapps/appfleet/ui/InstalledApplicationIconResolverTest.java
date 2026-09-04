package ru.pashaapps.appfleet.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstalledApplicationIconResolverTest {
    @Test void makesAStableFallbackMonogram() {
        assertEquals("MP", InstalledApplicationIconResolver.monogram("My Product"));
        assertEquals("S", InstalledApplicationIconResolver.monogram("SortIt"));
        assertEquals("A", InstalledApplicationIconResolver.monogram("  "));
    }

    @Test void doesNotAskTheWindowsShellForMissingExecutable() {
        assertTrue(InstalledApplicationIconResolver.loadSystemIcon(Path.of("missing-appfleet-test.exe")).isEmpty());
    }

    @Test void readsHighResolutionShellIconForAWindowsExecutable() {
        Path notepad = Path.of(System.getenv().getOrDefault("WINDIR", "C:\\Windows"), "System32", "notepad.exe");
        if (!Files.isRegularFile(notepad)) return;
        assertTrue(InstalledApplicationIconResolver.loadWindowsShellItemIcon(notepad)
                .map(image -> image.getWidth() >= 48 && image.getHeight() >= 48)
                .orElse(false));
    }
}
