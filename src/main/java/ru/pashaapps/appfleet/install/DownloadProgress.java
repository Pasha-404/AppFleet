package ru.pashaapps.appfleet.install;

@FunctionalInterface
public interface DownloadProgress {
    DownloadProgress NONE = (received, total) -> { };
    void update(long receivedBytes, long totalBytes);

    /** Signals a terminal byte count even if normal display updates were coalesced. */
    default void completed(long receivedBytes, long totalBytes) { }
}
