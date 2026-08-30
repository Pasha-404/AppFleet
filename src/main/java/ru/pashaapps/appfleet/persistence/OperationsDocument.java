package ru.pashaapps.appfleet.persistence;

import java.util.List;

public record OperationsDocument(int schemaVersion, List<OperationEntry> operations) {
    public OperationsDocument { operations = operations == null ? List.of() : List.copyOf(operations); }
    public static OperationsDocument empty() { return new OperationsDocument(1, List.of()); }
}
