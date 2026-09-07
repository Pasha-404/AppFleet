package ru.pashaapps.appfleet.install;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Limits display work while preserving the most recent and the terminal download state. */
public final class CoalescingDownloadProgress implements DownloadProgress {
    private static final Duration DEFAULT_MINIMUM_INTERVAL = Duration.ofMillis(100);

    private final DownloadProgress downstream;
    private final long minimumIntervalNanos;
    private final LongSupplier nanoClock;
    private long lastPublishedAt = Long.MIN_VALUE;
    private long lastReceived = Long.MIN_VALUE;
    private long lastTotal = Long.MIN_VALUE;

    public CoalescingDownloadProgress(DownloadProgress downstream) {
        this(downstream, DEFAULT_MINIMUM_INTERVAL, System::nanoTime);
    }

    CoalescingDownloadProgress(DownloadProgress downstream, Duration minimumInterval, LongSupplier nanoClock) {
        this.downstream = Objects.requireNonNull(downstream, "downstream");
        this.minimumIntervalNanos = Objects.requireNonNull(minimumInterval, "minimumInterval").toNanos();
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (minimumIntervalNanos < 0) throw new IllegalArgumentException("minimumInterval must not be negative");
    }

    @Override public synchronized void update(long receivedBytes, long totalBytes) {
        long now = nanoClock.getAsLong();
        if (lastPublishedAt == Long.MIN_VALUE || now - lastPublishedAt >= minimumIntervalNanos) publish(receivedBytes, totalBytes, now);
    }

    @Override public synchronized void completed(long receivedBytes, long totalBytes) {
        publish(receivedBytes, totalBytes, nanoClock.getAsLong());
    }

    private void publish(long receivedBytes, long totalBytes, long now) {
        if (receivedBytes == lastReceived && totalBytes == lastTotal) return;
        downstream.update(receivedBytes, totalBytes);
        lastPublishedAt = now;
        lastReceived = receivedBytes;
        lastTotal = totalBytes;
    }
}
