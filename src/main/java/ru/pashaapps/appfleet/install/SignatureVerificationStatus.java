package ru.pashaapps.appfleet.install;

/** Authenticode outcome, kept independent from checksum integrity. */
public enum SignatureVerificationStatus {
    VALID("действительна"),
    NOT_SIGNED("отсутствует"),
    INVALID("недействительна"),
    NOT_APPLICABLE("не применяется"),
    VERIFIER_UNAVAILABLE("не проверена: средство недоступно"),
    CHECK_FAILED("не проверена: ошибка средства");

    private final String display;

    SignatureVerificationStatus(String display) { this.display = display; }

    public String display() { return display; }
}
