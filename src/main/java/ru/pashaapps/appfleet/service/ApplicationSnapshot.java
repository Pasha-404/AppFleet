package ru.pashaapps.appfleet.service;

import ru.pashaapps.appfleet.domain.AppFleetManifest;
import ru.pashaapps.appfleet.domain.GithubRelease;
import ru.pashaapps.appfleet.domain.ReleaseAsset;
import ru.pashaapps.appfleet.domain.RepositoryId;
import ru.pashaapps.appfleet.persistence.RepositoryState;

import java.util.List;

/** Immutable row state transferred from a worker thread to the JavaFX UI. */
public record ApplicationSnapshot(RepositoryId repository, RepositoryState persisted, AppStatus status, String displayName,
                                  String installedVersion, GithubRelease release, ReleaseAsset selectedAsset,
                                  List<ReleaseAsset> choiceCandidates, AppFleetManifest manifest, String message) {
    public ApplicationSnapshot { choiceCandidates = choiceCandidates == null ? List.of() : List.copyOf(choiceCandidates); }
    public String latestVersion() { return release == null ? "—" : release.tagName(); }
    public String assetName() { return selectedAsset == null ? "—" : selectedAsset.name(); }
}

