package ru.pashaapps.appfleet.domain;

import java.util.List;
import java.util.UUID;

public record AppFleetManifest(int schemaVersion, UUID appId, String name, String technicalName, String version,
                               String repositoryUrl, String platform, String architecture, Installer installer,
                               Detection detection, List<String> processNames, String minimumAppFleetVersion) {
    public record Installer(PackageType type, String assetName, String sha256AssetName, List<String> silentArgs, String desktopShortcutTask) {
        public Installer { silentArgs = List.copyOf(silentArgs); }
    }
    public record Detection(String registryKey, String versionValue, String executableValue) { }
}
