package ru.pashaapps.appfleet.install;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandardInstallationAwaiterTest {
    @Test
    void acceptsEquivalentReleaseAndRegistrySemVerVersions() {
        assertTrue(StandardInstallationAwaiter.matchesExpectedVersion("1.5.0", "v1.5.0"));
    }

    @Test
    void rejectsAnOlderOrMalformedRegistryVersion() {
        assertFalse(StandardInstallationAwaiter.matchesExpectedVersion("1.4.0", "v1.5.0"));
        assertFalse(StandardInstallationAwaiter.matchesExpectedVersion("latest", "v1.5.0"));
    }
}
