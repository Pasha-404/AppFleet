package ru.pashaapps.appfleet.install;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read-only exact-key detector: it never guesses from an uninstall display name. */
public final class WindowsRegistryDetector {
    private static final List<String> REQUIRED = List.of("SchemaVersion", "AppId", "Name", "TechnicalName", "Version", "InstallLocation", "Executable", "ProcessName", "RepositoryUrl", "InstallerType", "InstalledBy");
    public Optional<InstalledApplication> findStandardApplication(UUID appId) throws IOException {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return Optional.empty();
        String key = "HKCU\\Software\\PashaApps\\" + appId;
        Process process = new ProcessBuilder("reg.exe", "query", key).redirectErrorStream(true).start();
        try {
            int code = process.waitFor();
            if (code != 0) return Optional.empty();
            Map<String, String> values = parseQueryOutput(new String(process.getInputStream().readAllBytes(), Charset.defaultCharset()));
            if (!REQUIRED.stream().allMatch(values::containsKey) || !appId.toString().equalsIgnoreCase(values.get("AppId"))) return Optional.empty();
            return Optional.of(new InstalledApplication(appId, values.get("Name"), values.get("TechnicalName"), values.get("Version"), Path.of(values.get("InstallLocation")), Path.of(values.get("Executable")), values.get("ProcessName"), values.get("RepositoryUrl"), values.get("InstallerType"), values.get("InstalledBy")));
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Проверка реестра прервана", interrupted); }
    }
    static Map<String, String> parseQueryOutput(String output) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : output.lines().toList()) {
            String trimmed = line.trim();
            String[] parts = trimmed.split("\\s{2,}", 3);
            if (parts.length == 3 && parts[1].equalsIgnoreCase("REG_SZ")) values.put(parts[0], parts[2]);
        }
        return values;
    }
}

