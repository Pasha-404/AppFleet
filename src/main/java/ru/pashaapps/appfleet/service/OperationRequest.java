package ru.pashaapps.appfleet.service;

/** Consent is explicit; a service call with confirmed=false cannot download or launch any installer. */
public record OperationRequest(boolean confirmed, boolean closeRunningApplications, boolean forceCloseIfNeeded) {
    public static OperationRequest cancelled() { return new OperationRequest(false, false, false); }
}

