package ru.pashaapps.appfleet.service;

import ru.pashaapps.appfleet.install.FileVerificationResult;

public record OperationResult(boolean successful, boolean restartRequired, String message, ApplicationSnapshot updatedSnapshot,
                              OperationOutcome outcome, ForceCloseContinuation forceCloseContinuation, FileVerificationResult verification) {
    public OperationResult(boolean successful, boolean restartRequired, String message, ApplicationSnapshot updatedSnapshot) {
        this(successful, restartRequired, message, updatedSnapshot, successful ? OperationOutcome.COMPLETED : OperationOutcome.FAILED, null, null);
    }

    public static OperationResult cancelled(String message, ApplicationSnapshot snapshot) {
        return new OperationResult(false, false, message, snapshot, OperationOutcome.CANCELLED, null, null);
    }

    public static OperationResult forceCloseConfirmationRequired(ApplicationSnapshot snapshot, ForceCloseContinuation continuation) {
        return new OperationResult(false, false, "Требуется отдельное подтверждение принудительного завершения приложения", snapshot,
                OperationOutcome.FORCE_CLOSE_CONFIRMATION_REQUIRED, continuation, null);
    }

    public boolean requiresForceCloseConfirmation() {
        return outcome == OperationOutcome.FORCE_CLOSE_CONFIRMATION_REQUIRED;
    }
}
