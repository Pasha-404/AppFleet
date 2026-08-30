package ru.pashaapps.appfleet.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ChecksumVerifierTest {
    @TempDir Path temporaryDirectory;
    @Test void parsesExactSha256AssetAndChecksContent() throws IOException {
        Path payload = temporaryDirectory.resolve("App-Setup-1.0.0-x64.exe");
        Files.writeString(payload, "hello");
        String sha256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
        Path checksum = temporaryDirectory.resolve("checksum.txt");
        Files.writeString(checksum, sha256 + "  " + payload.getFileName());
        ChecksumVerifier verifier = new ChecksumVerifier();
        assertEquals(sha256, verifier.parseSha256Asset(checksum, payload.getFileName().toString()));
        assertTrue(verifier.matches(payload, sha256));
    }
    @Test void refusesChecksumForAnotherFile() throws IOException {
        Path checksum = temporaryDirectory.resolve("checksum.txt");
        Files.writeString(checksum, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824  Other.exe");
        assertThrows(IOException.class, () -> new ChecksumVerifier().parseSha256Asset(checksum, "App.exe"));
    }
}

