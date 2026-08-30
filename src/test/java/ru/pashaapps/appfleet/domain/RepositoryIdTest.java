package ru.pashaapps.appfleet.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryIdTest {
    @Test void normalizesEverySupportedGithubReleaseUrl() {
        assertEquals("Pasha-404/sortit", RepositoryId.fromGithubUrl("https://github.com/Pasha-404/sortit/releases/tag/v1.5.0").slug());
        assertEquals("Pasha-404/sortit", RepositoryId.fromGithubUrl("https://github.com/Pasha-404/sortit/releases/").slug());
    }
    @Test void rejectsUntrustedOrUnsupportedUrls() {
        assertThrows(IllegalArgumentException.class, () -> RepositoryId.fromGithubUrl("http://github.com/a/b"));
        assertThrows(IllegalArgumentException.class, () -> RepositoryId.fromGithubUrl("https://evil.example/a/b"));
        assertThrows(IllegalArgumentException.class, () -> RepositoryId.fromGithubUrl("https://github.com/a/b/issues"));
    }
}

