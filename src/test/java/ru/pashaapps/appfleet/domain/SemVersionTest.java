package ru.pashaapps.appfleet.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SemVersionTest {
    @Test void normalizesPrefixAndComparesNumerically() {
        assertEquals("1.10.0", SemVersion.parse("v1.10.0").normalized());
        assertTrue(SemVersion.parse("1.10.0").compareTo(SemVersion.parse("1.9.9")) > 0);
    }
    @Test void followsPrereleasePrecedence() {
        assertTrue(SemVersion.parse("1.0.0-alpha.1").compareTo(SemVersion.parse("1.0.0-alpha.beta")) < 0);
        assertTrue(SemVersion.parse("1.0.0").compareTo(SemVersion.parse("1.0.0-rc.1")) > 0);
    }
    @Test void rejectsAmbiguousVersions() { assertTrue(SemVersion.tryParse("release-1.0").isEmpty()); }
}

