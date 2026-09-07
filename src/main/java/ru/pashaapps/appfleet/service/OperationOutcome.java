package ru.pashaapps.appfleet.service;

/** Typed terminal or paused outcome used by the UI instead of parsing localized text. */
public enum OperationOutcome {
    COMPLETED,
    FAILED,
    CANCELLED,
    FORCE_CLOSE_CONFIRMATION_REQUIRED
}
