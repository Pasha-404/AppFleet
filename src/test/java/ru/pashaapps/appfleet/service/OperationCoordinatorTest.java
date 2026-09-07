package ru.pashaapps.appfleet.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationCoordinatorTest {
    @Test void keepsOneMutatingOperationActiveUntilItsLeaseIsClosed() {
        OperationCoordinator coordinator = new OperationCoordinator();
        OperationCoordinator.Lease install = coordinator.tryAcquire(OperationCoordinator.OperationKind.APPLICATION_INSTALL, "owner/application").orElseThrow();

        assertEquals(OperationCoordinator.OperationKind.APPLICATION_INSTALL, coordinator.activeOperation().orElseThrow().kind());
        assertFalse(coordinator.tryAcquire(OperationCoordinator.OperationKind.SELF_UPDATE, "AppFleet 1.2.0").isPresent());

        install.close();

        try (OperationCoordinator.Lease update = coordinator.tryAcquire(OperationCoordinator.OperationKind.SELF_UPDATE, "AppFleet 1.2.0").orElseThrow()) {
            assertEquals(OperationCoordinator.OperationKind.SELF_UPDATE, coordinator.activeOperation().orElseThrow().kind());
        }
        assertTrue(coordinator.activeOperation().isEmpty());
    }
}
