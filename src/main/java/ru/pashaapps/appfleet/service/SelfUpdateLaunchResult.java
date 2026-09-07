package ru.pashaapps.appfleet.service;

import ru.pashaapps.appfleet.install.FileVerificationResult;

/** Evidence collected before AppFleet hands control to its external Inno Setup updater. */
public record SelfUpdateLaunchResult(FileVerificationResult verification) {
    public SelfUpdateLaunchResult {
        if (verification == null) throw new IllegalArgumentException("verification is required");
    }
}
