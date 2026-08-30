package ru.pashaapps.appfleet.ui;

import java.nio.charset.Charset;

/** Reads the Windows user theme at startup; JavaFX then receives matching light/dark styling. */
final class WindowsTheme {
    private WindowsTheme() { }
    static boolean isDark() {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return false;
        try {
            Process process = new ProcessBuilder("reg.exe", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme").redirectErrorStream(true).start();
            if (process.waitFor() != 0) return false;
            return !new String(process.getInputStream().readAllBytes(), Charset.defaultCharset()).matches("(?s).*REG_DWORD\\s+0x0.*");
        } catch (Exception ignored) { return false; }
    }
}

