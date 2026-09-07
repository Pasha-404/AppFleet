package ru.pashaapps.appfleet.install;

/** Immutable evidence from the verification phase, suitable for UI and operation journal text. */
public record FileVerificationResult(ChecksumVerificationStatus checksum, SignatureVerificationStatus signature, String signatureDetail) {
    public FileVerificationResult {
        if (checksum == null || signature == null) throw new IllegalArgumentException("Verification statuses are required");
        signatureDetail = signatureDetail == null ? "" : signatureDetail.strip();
    }

    public boolean permitsInstallation() { return signature != SignatureVerificationStatus.INVALID; }

    public boolean hasWarning() {
        return checksum != ChecksumVerificationStatus.VERIFIED
                || signature == SignatureVerificationStatus.NOT_SIGNED
                || signature == SignatureVerificationStatus.VERIFIER_UNAVAILABLE
                || signature == SignatureVerificationStatus.CHECK_FAILED;
    }

    public String summary() {
        String result = "SHA-256: " + checksum.display() + "; цифровая подпись: " + signature.display();
        return signatureDetail.isBlank() ? result : result + " (" + signatureDetail + ")";
    }
}
