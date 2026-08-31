package ru.pashaapps.appfleet.persistence;

import ru.pashaapps.appfleet.domain.AppFleetManifest;
import ru.pashaapps.appfleet.domain.GithubRelease;

import java.time.Instant;
import java.util.List;

/** Last verified public release data, retained only to keep the UI useful during a temporary GitHub outage. */
public record ReleaseCache(int schemaVersion, List<Entry> entries) {
    public ReleaseCache {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static ReleaseCache empty() { return new ReleaseCache(1, List.of()); }

    public record Entry(String repositoryKey, GithubRelease release, AppFleetManifest manifest, Instant checkedAt) {
        public Entry {
            if (repositoryKey == null || repositoryKey.isBlank() || release == null || checkedAt == null) {
                throw new IllegalArgumentException("Некорректная запись кэша релиза");
            }
        }
    }
}
