package ru.pashaapps.appfleet.service;

import ru.pashaapps.appfleet.install.RunningApplication;

import java.util.List;
import java.util.UUID;

/** Exact verified process identities awaiting a one-time force-close decision. */
public record ForceCloseContinuation(UUID id, String applicationName, List<RunningApplication> processes) {
    public ForceCloseContinuation { processes = List.copyOf(processes); }
}
