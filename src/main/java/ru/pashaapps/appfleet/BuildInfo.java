package ru.pashaapps.appfleet;

import java.io.IOException;
import java.util.Properties;

/** Build-time metadata is generated from the single Gradle version source. */
public record BuildInfo(String version, String repositoryUrl, String appId) {
    public static BuildInfo load() {
        try (var input = BuildInfo.class.getClassLoader().getResourceAsStream("appfleet-build.properties")) {
            Properties properties = new Properties();
            if (input != null) properties.load(input);
            return new BuildInfo(properties.getProperty("version", "0.0.0-dev"), properties.getProperty("repositoryUrl", "https://github.com/Pasha-404/AppFleet"), properties.getProperty("appId", "5dce5095-1140-4ceb-8a85-99e029c73468"));
        } catch (IOException ignored) { return new BuildInfo("0.0.0-dev", "https://github.com/Pasha-404/AppFleet", "5dce5095-1140-4ceb-8a85-99e029c73468"); }
    }
}

