package ru.pashaapps.appfleet.domain;

import java.net.URI;
import java.time.Instant;
import java.util.List;

public record GithubRelease(long id, String tagName, String name, String body, boolean draft, boolean prerelease,
                            Instant publishedAt, URI htmlUrl, List<ReleaseAsset> assets) {
    public GithubRelease {
        if (id <= 0 || tagName == null || tagName.isBlank() || assets == null) {
            throw new IllegalArgumentException("Некорректный релиз GitHub");
        }
        assets = List.copyOf(assets);
    }
    public boolean isStable() { return !draft && !prerelease; }
}

