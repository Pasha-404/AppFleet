package ru.pashaapps.appfleet.install;

/** Whether AppFleet could compare the downloaded bytes with a published SHA-256 asset. */
public enum ChecksumVerificationStatus {
    VERIFIED("проверен"),
    NOT_PUBLISHED("не опубликован");

    private final String display;

    ChecksumVerificationStatus(String display) { this.display = display; }

    public String display() { return display; }
}
