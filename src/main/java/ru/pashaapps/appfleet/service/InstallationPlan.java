package ru.pashaapps.appfleet.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import ru.pashaapps.appfleet.persistence.UserSettings;

/** Immutable, user-confirmed input for one installation attempt. */
public record InstallationPlan(UUID operationId, ApplicationSnapshot snapshot, OperationPreview preview,
                               OperationRequest request, UserSettings settings, Instant confirmedAt) {
    public InstallationPlan {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        if (!request.confirmed()) {
            throw new IllegalArgumentException("План установки может быть создан только после подтверждения пользователя");
        }
    }
}
