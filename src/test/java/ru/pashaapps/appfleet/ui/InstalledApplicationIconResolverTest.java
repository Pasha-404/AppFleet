package ru.pashaapps.appfleet.ui;

import org.junit.jupiter.api.Test;

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
}
