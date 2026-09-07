package ru.pashaapps.appfleet.install;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/** Narrow read-only boundary for reconciling AppFleet state with Windows. */
@FunctionalInterface
public interface InstallationDetector {
    Optional<InstalledApplication> findStandardApplication(UUID appId) throws IOException;
}
