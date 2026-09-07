package ru.pashaapps.appfleet.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pashaapps.appfleet.domain.PackageType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileVerificationServiceTest {
    @TempDir Path temporaryDirectory;

    @Test void reportsChecksumAndUnsignedSignatureIndependentlyForInno() throws Exception {
        Path installer = payload("setup.exe", "hello");
        Path checksum = checksumFor(installer, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");

        FileVerificationResult result = service(AuthenticodeStatus.NOT_SIGNED).verify(installer, PackageType.INNO, checksum);

        assertEquals(ChecksumVerificationStatus.VERIFIED, result.checksum());
        assertEquals(SignatureVerificationStatus.NOT_SIGNED, result.signature());
        assertTrue(result.permitsInstallation());
        assertTrue(result.hasWarning());
        assertTrue(result.summary().contains("SHA-256: проверен"));
    }

    @Test void verifiesMsiAuthenticodeInsteadOfBypassingIt() throws Exception {
        Path installer = payload("setup.msi", "payload");

        FileVerificationResult result = service(AuthenticodeStatus.VALID).verify(installer, PackageType.MSI, null);

        assertEquals(ChecksumVerificationStatus.NOT_PUBLISHED, result.checksum());
        assertEquals(SignatureVerificationStatus.VALID, result.signature());
        assertTrue(result.hasWarning(), "Отсутствие опубликованного SHA-256 должно быть видно отдельно от действительной подписи");
    }

    @Test void blocksOnlyAnExplicitlyInvalidSignature() throws Exception {
        Path installer = payload("setup.exe", "payload");

        FileVerificationResult result = service(AuthenticodeStatus.INVALID).verify(installer, PackageType.EXE, null);

        assertEquals(SignatureVerificationStatus.INVALID, result.signature());
        assertFalse(result.permitsInstallation());
    }

    @Test void neverClaimsChecksumVerificationForAnUnsignedGenericExeWithoutChecksumAsset() throws Exception {
        Path installer = payload("generic.exe", "payload");

        FileVerificationResult result = service(AuthenticodeStatus.NOT_SIGNED).verify(installer, PackageType.EXE, null);

        assertEquals(ChecksumVerificationStatus.NOT_PUBLISHED, result.checksum());
        assertEquals(SignatureVerificationStatus.NOT_SIGNED, result.signature());
        assertTrue(result.permitsInstallation());
        assertTrue(result.summary().contains("SHA-256: не опубликован"));
        assertFalse(result.summary().contains("SHA-256: проверен"));
    }

    @Test void distinguishesUnavailableAndFailedSignatureVerificationFromInvalidSignature() throws Exception {
        Path installer = payload("setup.exe", "payload");

        FileVerificationResult unavailable = service(AuthenticodeStatus.UNAVAILABLE).verify(installer, PackageType.EXE, null);
        FileVerificationResult failed = new FileVerificationService(new ChecksumVerifier(), path -> { throw new IOException("PowerShell unavailable"); })
                .verify(installer, PackageType.EXE, null);

        assertEquals(SignatureVerificationStatus.VERIFIER_UNAVAILABLE, unavailable.signature());
        assertEquals(SignatureVerificationStatus.CHECK_FAILED, failed.signature());
        assertTrue(unavailable.permitsInstallation());
        assertTrue(failed.permitsInstallation());
        assertTrue(failed.summary().contains("PowerShell unavailable"));
    }

    @Test void doesNotAskForAuthenticodeForZipButStillChecksPublishedChecksum() throws Exception {
        Path archive = payload("app.zip", "hello");
        Path checksum = checksumFor(archive, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");

        FileVerificationResult result = new FileVerificationService(new ChecksumVerifier(), path -> { throw new AssertionError("ZIP must not invoke Authenticode"); })
                .verify(archive, PackageType.ZIP, checksum);

        assertEquals(ChecksumVerificationStatus.VERIFIED, result.checksum());
        assertEquals(SignatureVerificationStatus.NOT_APPLICABLE, result.signature());
        assertFalse(result.hasWarning());
    }

    @Test void rejectsChecksumMismatchBeforeAnySignatureConclusion() throws Exception {
        Path installer = payload("setup.exe", "payload");
        Path checksum = checksumFor(installer, "0000000000000000000000000000000000000000000000000000000000000000");

        assertThrows(IOException.class, () -> service(AuthenticodeStatus.VALID).verify(installer, PackageType.EXE, checksum));
    }

    private FileVerificationService service(AuthenticodeStatus status) {
        return new FileVerificationService(new ChecksumVerifier(), path -> status);
    }

    private Path payload(String name, String contents) throws IOException {
        Path file = temporaryDirectory.resolve(name);
        Files.writeString(file, contents);
        return file;
    }

    private Path checksumFor(Path payload, String hash) throws IOException {
        Path checksum = temporaryDirectory.resolve(payload.getFileName() + ".sha256");
        Files.writeString(checksum, hash + "  " + payload.getFileName());
        return checksum;
    }
}
