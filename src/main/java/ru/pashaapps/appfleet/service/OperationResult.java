package ru.pashaapps.appfleet.service;

public record OperationResult(boolean successful, boolean restartRequired, String message, ApplicationSnapshot updatedSnapshot) { }

