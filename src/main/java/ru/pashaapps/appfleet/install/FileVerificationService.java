package ru.pashaapps.appfleet.install;

import ru.pashaapps.appfleet.domain.PackageType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Checks checksum and Authenticode independently for every executable installer format. */
public final class FileVerificationService {
    private final ChecksumVerifier checksums;
    private final SignatureInspector signatures;

    public FileVerificationService() {
        this(new ChecksumVerifier(), new AuthenticodeVerifier()::verify);
    }

    FileVerificationService(ChecksumVerifier checksums, SignatureInspector signatures) {
        this.checksums = Objects.requireNonNull(checksums, "checksums");
        this.signatures = Objects.requireNonNull(signatures, "signatures");
    }

    public FileVerificationResult verify(Path payload, PackageType packageType, Path checksumFile) throws IOException {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(packageType, "packageType");
        ChecksumVerificationStatus checksum = verifyChecksum(payload, checksumFile);
        if (packageType == PackageType.ZIP) {
            return new FileVerificationResult(checksum, SignatureVerificationStatus.NOT_APPLICABLE, "");
        }
        try {
            AuthenticodeStatus raw = signatures.verify(payload);
            return new FileVerificationResult(checksum, map(raw), "");
        } catch (IOException verifierFailure) {
            return new FileVerificationResult(checksum, SignatureVerificationStatus.CHECK_FAILED, verifierFailure.getMessage());
        }
    }

    private ChecksumVerificationStatus verifyChecksum(Path payload, Path checksumFile) throws IOException {
        if (checksumFile == null) return ChecksumVerificationStatus.NOT_PUBLISHED;
        String expected = checksums.parseSha256Asset(checksumFile, payload.getFileName().toString());
        if (!checksums.matches(payload, expected)) throw new IOException("SHA-256 скачанного файла не совпал");
        return ChecksumVerificationStatus.VERIFIED;
    }

    private static SignatureVerificationStatus map(AuthenticodeStatus status) {
        return switch (status) {
            case VALID -> SignatureVerificationStatus.VALID;
            case NOT_SIGNED -> SignatureVerificationStatus.NOT_SIGNED;
            case INVALID -> SignatureVerificationStatus.INVALID;
            case UNAVAILABLE -> SignatureVerificationStatus.VERIFIER_UNAVAILABLE;
        };
    }

    @FunctionalInterface
    interface SignatureInspector {
        AuthenticodeStatus verify(Path file) throws IOException;
    }
}
