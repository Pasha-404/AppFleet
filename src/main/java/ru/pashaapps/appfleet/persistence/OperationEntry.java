package ru.pashaapps.appfleet.persistence;

import java.time.Instant;

public record OperationEntry(Instant occurredAt, String repository, String operation, String result, String message, String technicalDetails) { }

