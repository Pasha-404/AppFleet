package ru.pashaapps.appfleet.install;

@FunctionalInterface
public interface DownloadProgress {
    DownloadProgress NONE = (received, total) -> { };
    void update(long receivedBytes, long totalBytes);
}

