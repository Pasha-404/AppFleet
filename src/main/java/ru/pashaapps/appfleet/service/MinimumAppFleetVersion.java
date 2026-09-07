package ru.pashaapps.appfleet.service;

import ru.pashaapps.appfleet.domain.AppFleetManifest;
import ru.pashaapps.appfleet.domain.SemVersion;

/** Compatibility rule for managed applications. Self-update intentionally bypasses this rule. */
final class MinimumAppFleetVersion {
    private MinimumAppFleetVersion() { }

    static void requireSupported(String runningVersion, AppFleetManifest manifest) {
        if (manifest == null || manifest.minimumAppFleetVersion() == null || manifest.minimumAppFleetVersion().isBlank()) {
            return;
        }
        SemVersion running = SemVersion.tryParse(runningVersion)
                .orElseThrow(() -> new IllegalStateException("Не удалось определить SemVer-версию запущенного AppFleet"));
        SemVersion required = SemVersion.parse(manifest.minimumAppFleetVersion());
        if (running.compareTo(required) < 0) {
            throw new IllegalStateException("Для этого приложения требуется AppFleet " + required.normalized()
                    + " или новее. Сначала обновите AppFleet.");
        }
    }
}
