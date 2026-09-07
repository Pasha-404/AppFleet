package ru.pashaapps.appfleet.github;

import java.time.Instant;
import java.util.Optional;

public final class GithubApiException extends RuntimeException {
    public enum Kind { RATE_LIMIT, TRANSIENT, PERMANENT }
    private final int statusCode;
    private final Instant retryAt;
    private final Kind kind;
    public GithubApiException(String message, int statusCode, Instant retryAt) {
        this(message, statusCode, retryAt, statusCode >= 500 ? Kind.TRANSIENT : Kind.PERMANENT);
    }
    public GithubApiException(String message, int statusCode, Instant retryAt, Kind kind) {
        super(message);
        this.statusCode = statusCode;
        this.retryAt = retryAt;
        this.kind = kind;
    }
    public GithubApiException(String message, Throwable cause) { super(message, cause); this.statusCode = 0; this.retryAt = null; this.kind = Kind.TRANSIENT; }
    public int statusCode() { return statusCode; }
    public Optional<Instant> retryAt() { return Optional.ofNullable(retryAt); }
    public Kind kind() { return kind; }
}
