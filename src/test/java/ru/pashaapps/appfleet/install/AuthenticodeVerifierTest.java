package ru.pashaapps.appfleet.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthenticodeVerifierTest {
    @TempDir Path temporaryDirectory;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void recognizesAnUnsignedExecutableWithoutTreatingItAsAnInvalidSignature() throws IOException {
        Path unsignedExecutable = temporaryDirectory.resolve("unsigned.exe");
        Files.writeString(unsignedExecutable, "This fixture has no Authenticode signature.");

        assertEquals(AuthenticodeStatus.NOT_SIGNED, new AuthenticodeVerifier().verify(unsignedExecutable));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void treatsPowerShellSyntaxInTheFilenameAsALiteralUnsignedPath() throws IOException {
        Path unicodeDirectory = Files.createDirectories(temporaryDirectory.resolve("папка [AppFleet]"));
        Path unsignedExecutable = unicodeDirectory.resolve("unsigned'; Write-Output APPFLEET_REVIEW_CANARY.exe");
        Files.writeString(unsignedExecutable, "This fixture has no Authenticode signature.");

        assertEquals(AuthenticodeStatus.NOT_SIGNED, new AuthenticodeVerifier().verify(unsignedExecutable));
    }

    @Test
    void passesTheTargetOnlyThroughTheChildProcessEnvironment() {
        Path executable = Path.of("folder", "name; Write-Output APPFLEET_REVIEW_CANARY.exe");
        ProcessBuilder command = AuthenticodeVerifier.commandFor(executable);
        String literalPath = executable.toAbsolutePath().normalize().toString();

        assertEquals(literalPath, command.environment().get(AuthenticodeVerifier.TARGET_PATH_ENVIRONMENT_VARIABLE));
        assertFalse(command.command().contains(literalPath));
    }

    @Test
    void distinguishesVerifierFailureFromAnInvalidSignature() throws IOException {
        assertThrows(IOException.class, () -> AuthenticodeVerifier.statusFrom(1, "NotSigned"));
        assertThrows(IOException.class, () -> AuthenticodeVerifier.statusFrom(0, "unexpected output"));
        assertEquals(AuthenticodeStatus.INVALID, AuthenticodeVerifier.statusFrom(0, "HashMismatch"));
    }
}
