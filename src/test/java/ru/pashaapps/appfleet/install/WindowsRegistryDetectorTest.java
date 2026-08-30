package ru.pashaapps.appfleet.install;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WindowsRegistryDetectorTest {
    @Test void parsesOnlyStringValuesFromExactRegistryOutput() {
        Map<String, String> values = WindowsRegistryDetector.parseQueryOutput("HKCU\\Software\\PashaApps\\id\n    Version    REG_SZ    1.5.0\n    AppId    REG_SZ    id\n    Unrelated    REG_DWORD    0x1\n");
        assertEquals("1.5.0", values.get("Version"));
        assertFalse(values.containsKey("Unrelated"));
    }
}

