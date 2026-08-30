package ru.pashaapps.appfleet.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.pashaapps.appfleet.persistence.AtomicJsonStore;
import ru.pashaapps.appfleet.persistence.OperationEntry;
import ru.pashaapps.appfleet.persistence.OperationsDocument;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class OperationJournal {
    private static final Logger log = LoggerFactory.getLogger(OperationJournal.class);
    private static final int MAX_ENTRIES = 1_000;
    private final AtomicJsonStore<OperationsDocument> store;
    private final List<OperationEntry> entries;
    public OperationJournal(AtomicJsonStore<OperationsDocument> store) {
        this.store = store;
        this.entries = new ArrayList<>(store.read().orElseGet(OperationsDocument::empty).operations());
    }
    public synchronized void write(String repository, String operation, String result, String message, Throwable technical) {
        String detail = technical == null ? "" : stackTrace(technical);
        entries.addFirst(new OperationEntry(Instant.now(), repository, operation, result, message, detail));
        while (entries.size() > MAX_ENTRIES) entries.removeLast();
        try { store.write(new OperationsDocument(1, entries)); } catch (IOException failure) { log.error("Не удалось сохранить журнал операций", failure); }
        if (technical == null) log.info("{} {}: {}", operation, repository, message); else log.error("{} {}: {}", operation, repository, message, technical);
    }
    public synchronized List<OperationEntry> entries() { return List.copyOf(entries); }
    private static String stackTrace(Throwable failure) { java.io.StringWriter output = new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(output)); return output.toString(); }
}

