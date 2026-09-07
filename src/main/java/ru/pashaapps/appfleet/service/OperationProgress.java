package ru.pashaapps.appfleet.service;

@FunctionalInterface
public interface OperationProgress {
    OperationProgress NONE = phase -> { };

    void phaseChanged(OperationPhase phase);
}
