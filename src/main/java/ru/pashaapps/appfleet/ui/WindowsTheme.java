package ru.pashaapps.appfleet.ui;

import java.nio.charset.StandardCharsets;

/** Reads the Windows user theme at startup; JavaFX then receives matching light/dark styling. */
final class WindowsTheme {
    private WindowsTheme() { }
    static boolean isDark() {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return false;
        try {
            Process process = new ProcessBuilder("reg.exe", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme").redirectErrorStream(true).start();
            if (process.waitFor() != 0) return false;
            return isDarkFromRegistryOutput(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception ignored) { return false; }
    }

    static boolean isDarkFromRegistryOutput(String output) {
        return output != null && output.matches("(?s).*REG_DWORD\\s+0x0.*");
    }
}
