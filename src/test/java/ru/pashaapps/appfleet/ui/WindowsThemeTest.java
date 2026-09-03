package ru.pashaapps.appfleet.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsThemeTest {
    @Test void usesDarkStylesheetWhenWindowsAppsUseDarkTheme() {
        assertTrue(WindowsTheme.isDarkFromRegistryOutput("AppsUseLightTheme    REG_DWORD    0x0"));
    }

    @Test void usesLightStylesheetWhenWindowsAppsUseLightTheme() {
        assertFalse(WindowsTheme.isDarkFromRegistryOutput("AppsUseLightTheme    REG_DWORD    0x1"));
    }

    @Test void treatsMissingOrMalformedRegistryValueAsLightTheme() {
        assertFalse(WindowsTheme.isDarkFromRegistryOutput(null));
        assertFalse(WindowsTheme.isDarkFromRegistryOutput("AppsUseLightTheme    REG_SZ    dark"));
    }
}
