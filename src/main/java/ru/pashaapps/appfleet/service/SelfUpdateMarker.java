package ru.pashaapps.appfleet.service;

import java.time.Instant;

public record SelfUpdateMarker(String targetVersion, int attempts, String operationDirectory, Instant startedAt) { }

