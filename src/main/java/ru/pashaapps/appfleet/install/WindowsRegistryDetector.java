package ru.pashaapps.appfleet.install;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinReg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only exact-key detector using JNA's Unicode Advapi32 binding.
 * Missing data is distinct from an unreadable registry, and an absent EXE is not an installation.
 */
public final class WindowsRegistryDetector implements InstallationDetector {
    private static final List<String> REQUIRED = List.of("SchemaVersion", "AppId", "Name", "TechnicalName", "Version", "InstallLocation", "Executable", "ProcessName", "RepositoryUrl", "InstallerType", "InstalledBy");
    private final RegistryValues values;

    public WindowsRegistryDetector() {
        this(new NativeRegistryValues());
    }

    WindowsRegistryDetector(RegistryValues values) {
        this.values = values;
    }

    @Override
    public Optional<InstalledApplication> findStandardApplication(UUID appId) throws IOException {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return Optional.empty();
        String key = "Software\\PashaApps\\" + appId;
        Optional<Map<String, String>> read = values.readStrings(key, REQUIRED);
        if (read.isEmpty()) return Optional.empty();
        Map<String, String> state = read.get();
        if (!REQUIRED.stream().allMatch(state::containsKey) || !appId.toString().equalsIgnoreCase(state.get("AppId"))) return Optional.empty();
        try {
            Path installLocation = Path.of(state.get("InstallLocation")).toAbsolutePath().normalize();
            Path executable = Path.of(state.get("Executable")).toAbsolutePath().normalize();
            if (!executable.startsWith(installLocation) || !Files.isRegularFile(executable)) return Optional.empty();
            return Optional.of(new InstalledApplication(appId, state.get("Name"), state.get("TechnicalName"), state.get("Version"), installLocation, executable,
                    state.get("ProcessName"), state.get("RepositoryUrl"), state.get("InstallerType"), state.get("InstalledBy")));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    interface RegistryValues {
        Optional<Map<String, String>> readStrings(String subKey, List<String> names) throws IOException;
    }

    private static final class NativeRegistryValues implements RegistryValues {
        @Override public Optional<Map<String, String>> readStrings(String subKey, List<String> names) throws IOException {
            try {
                if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, subKey)) return Optional.empty();
                Map<String, String> read = new LinkedHashMap<>();
                for (String name : names) {
                    String value = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, subKey, name);
                    if (value == null || value.isBlank()) return Optional.empty();
                    read.put(name, value);
                }
                return Optional.of(Map.copyOf(read));
            } catch (Win32Exception missing) {
                if (missing.getErrorCode() == WinError.ERROR_FILE_NOT_FOUND || missing.getErrorCode() == WinError.ERROR_PATH_NOT_FOUND) return Optional.empty();
                throw new IOException("Не удалось прочитать HKCU AppFleet: код Windows " + missing.getErrorCode(), missing);
            } catch (RuntimeException nativeFailure) {
                throw new IOException("Не удалось прочитать HKCU AppFleet", nativeFailure);
            }
        }
    }
}
