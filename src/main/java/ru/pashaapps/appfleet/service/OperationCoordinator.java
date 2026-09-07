package ru.pashaapps.appfleet.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A small process-local gate for mutating operations.
 *
 * <p>Repository checks may run independently, but an application install and
 * an AppFleet self-update must never prepare or launch external installers at
 * the same time. The lease is deliberately explicit so the caller owns the
 * complete lifetime of the operation, including queued work.</p>
 */
public final class OperationCoordinator {
    private ActiveOperation active;

    public synchronized Optional<Lease> tryAcquire(OperationKind kind, String target) {
        if (active != null) {
            return Optional.empty();
        }
        UUID id = UUID.randomUUID();
        active = new ActiveOperation(id, kind, target, Instant.now());
        return Optional.of(new Lease(this, id));
    }

    public synchronized Optional<ActiveOperation> activeOperation() {
        return Optional.ofNullable(active);
    }

    private synchronized void release(UUID id) {
        if (active != null && active.id().equals(id)) {
            active = null;
        }
    }

    public enum OperationKind {
        APPLICATION_INSTALL,
        SELF_UPDATE
    }

    public record ActiveOperation(UUID id, OperationKind kind, String target, Instant startedAt) { }

    public static final class Lease implements AutoCloseable {
        private final OperationCoordinator coordinator;
        private final UUID id;
        private boolean closed;

        private Lease(OperationCoordinator coordinator, UUID id) {
            this.coordinator = coordinator;
            this.id = id;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                coordinator.release(id);
            }
        }
    }
}
