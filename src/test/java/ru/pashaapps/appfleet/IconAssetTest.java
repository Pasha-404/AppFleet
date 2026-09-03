package ru.pashaapps.appfleet;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IconAssetTest {
    @Test
    void windowsIconContainsMultipleResolutions() throws Exception {
        byte[] icon = Files.readAllBytes(Path.of("assets", "AppFleet.ico"));
        assertTrue(icon.length > 1024);
        assertEquals(0, unsignedShort(icon, 0));
        assertEquals(1, unsignedShort(icon, 2));
        assertTrue(unsignedShort(icon, 4) >= 7);
    }

    @Test
    void windowIconResourceIsAPngAtNativeResolution() throws Exception {
        try (InputStream stream = AppFleetApplication.class.getResourceAsStream("/appfleet-window-icon.png")) {
            assertTrue(stream != null, "Window icon resource is missing");
            byte[] icon = stream.readAllBytes();
            assertTrue(icon.length > 1024);
            assertEquals(0x89504E47, unsignedInt(icon, 0));
            assertEquals(256, unsignedInt(icon, 16));
            assertEquals(256, unsignedInt(icon, 20));
        }
    }

    private static int unsignedShort(byte[] value, int offset) {
        return Byte.toUnsignedInt(value[offset]) | Byte.toUnsignedInt(value[offset + 1]) << 8;
    }

    private static int unsignedInt(byte[] value, int offset) {
        return Byte.toUnsignedInt(value[offset]) << 24
                | Byte.toUnsignedInt(value[offset + 1]) << 16
                | Byte.toUnsignedInt(value[offset + 2]) << 8
                | Byte.toUnsignedInt(value[offset + 3]);
    }
}
