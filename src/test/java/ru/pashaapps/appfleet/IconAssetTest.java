package ru.pashaapps.appfleet;

import org.junit.jupiter.api.Test;

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

    private static int unsignedShort(byte[] value, int offset) {
        return Byte.toUnsignedInt(value[offset]) | Byte.toUnsignedInt(value[offset + 1]) << 8;
    }
}
