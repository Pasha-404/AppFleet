package ru.pashaapps.appfleet.install;

@FunctionalInterface
public interface CancellationToken {
    CancellationToken NEVER_CANCELLED = () -> false;
    boolean isCancelled();
    default void throwIfCancelled() { if (isCancelled()) throw new OperationCancelledException(); }
}

