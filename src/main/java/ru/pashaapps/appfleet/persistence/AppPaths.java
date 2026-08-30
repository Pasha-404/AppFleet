package ru.pashaapps.appfleet.persistence;

import java.nio.file.Path;
import java.util.Map;

/** All persistent and temporary paths are derived from explicitly bounded roots. */
public record AppPaths(Path settingsDirectory, Path cacheDirectory, Path temporaryRoot, Path programDirectory) {
    public static AppPaths forCurrentUser(Map<String, String> environment, Path userHome, Path tempDirectory) {
        Path roaming = Path.of(environment.getOrDefault("APPDATA", userHome.resolve("AppData/Roaming").toString()));
        Path local = Path.of(environment.getOrDefault("LOCALAPPDATA", userHome.resolve("AppData/Local").toString()));
        return new AppPaths(roaming.resolve("PashaApps/AppFleet"), local.resolve("PashaApps/AppFleet"), tempDirectory.resolve("AppFleet"), local.resolve("Programs/PashaApps/AppFleet"));
    }
    public Path repositoriesFile() { return settingsDirectory.resolve("repositories.json"); }
    public Path settingsFile() { return settingsDirectory.resolve("settings.json"); }
    public Path operationsFile() { return settingsDirectory.resolve("operations.json"); }
    public Path logDirectory() { return cacheDirectory.resolve("logs"); }
}

