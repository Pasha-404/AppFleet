package ru.pashaapps.appfleet.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthenticodeVerifierTest {
    @TempDir Path temporaryDirectory;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void recognizesAnUnsignedExecutableWithoutTreatingItAsAnInvalidSignature() throws IOException {
        Path unsignedExecutable = temporaryDirectory.resolve("unsigned.exe");
        Files.writeString(unsignedExecutable, "This fixture has no Authenticode signature.");

        assertEquals(AuthenticodeStatus.NOT_SIGNED, new AuthenticodeVerifier().verify(unsignedExecutable));
    }
}
