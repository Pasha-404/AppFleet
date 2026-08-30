package ru.pashaapps.appfleet.github;

import java.time.Instant;
import java.util.Optional;

public final class GithubApiException extends RuntimeException {
    private final int statusCode;
    private final Instant retryAt;
    public GithubApiException(String message, int statusCode, Instant retryAt) {
        super(message);
        this.statusCode = statusCode;
        this.retryAt = retryAt;
    }
    public GithubApiException(String message, Throwable cause) { super(message, cause); this.statusCode = 0; this.retryAt = null; }
    public int statusCode() { return statusCode; }
    public Optional<Instant> retryAt() { return Optional.ofNullable(retryAt); }
}

