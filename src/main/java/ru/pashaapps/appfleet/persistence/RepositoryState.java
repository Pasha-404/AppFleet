package ru.pashaapps.appfleet.persistence;

import ru.pashaapps.appfleet.domain.Architecture;
import ru.pashaapps.appfleet.domain.PackageType;

import java.time.Instant;
import java.util.Set;

/** Persisted only after reliable discovery or a successful operation. */
public record RepositoryState(int schemaVersion, String owner, String repository, String canonicalUrl,
                              String selectedPackageType, String selectedArchitecture, Set<String> selectionTokens,
                              String installedVersion, Long installedAssetId, String installedPackageType,
                              String installLocation, String executable, Set<String> processNames,
                              String releaseEtag, Instant lastCheckedAt, String lastCheckResult) {
    public RepositoryState {
        selectionTokens = selectionTokens == null ? Set.of() : Set.copyOf(selectionTokens);
        processNames = processNames == null ? Set.of() : Set.copyOf(processNames);
    }
}

