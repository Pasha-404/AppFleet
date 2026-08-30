package ru.pashaapps.appfleet.install;

public final class OperationCancelledException extends RuntimeException {
    public OperationCancelledException() { super("Операция отменена пользователем"); }
}

