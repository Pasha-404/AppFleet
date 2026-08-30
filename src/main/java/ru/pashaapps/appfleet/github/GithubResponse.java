package ru.pashaapps.appfleet.github;

import java.util.Optional;

public record GithubResponse<T>(T body, String etag, boolean notModified) {
    public static <T> GithubResponse<T> notModified(String etag) { return new GithubResponse<>(null, etag, true); }
    public static <T> GithubResponse<T> success(T body, String etag) { return new GithubResponse<>(body, etag, false); }
    public Optional<String> etagValue() { return Optional.ofNullable(etag); }
}

