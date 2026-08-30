package ru.pashaapps.appfleet.install;

import java.nio.file.Path;
import java.util.UUID;

public record InstalledApplication(UUID appId, String name, String technicalName, String version, Path installLocation,
                                 Path executable, String processName, String repositoryUrl, String installerType,
                                 String installedBy) { }

