package com.github.witcheryoptimizer.registry;

import static org.junit.Assert.*;

import org.junit.Test;

public class AuthorityDecisionTest {

    @Test
    public void naturalIdentitylessBindsExistingForWalFirstCrashRecovery() {
        assertTrue(PoppetRegistry.mayBindExisting(null, false, false, false));
    }

    @Test
    public void authorizedIdentitylessAtOccupiedLocationCannotResurrectAuthority() {
        assertFalse(PoppetRegistry.mayBindExisting(null, false, false, true));
    }

    @Test
    public void transientUuidNeverBindsExisting() {
        assertFalse(PoppetRegistry.mayBindExisting(java.util.UUID.randomUUID(), false, true, false));
    }

    @Test
    public void persistentExactBindsExisting() {
        assertTrue(PoppetRegistry.mayBindExisting(java.util.UUID.randomUUID(), true, true, false));
    }

    @Test
    public void persistentMismatchDoesNotBind() {
        assertFalse(PoppetRegistry.mayBindExisting(java.util.UUID.randomUUID(), true, false, false));
    }

    @Test
    public void authorizedPlacementCannotReimportTombstonedPersistentCopy() {
        assertFalse(PoppetRegistry.mayCreateAuthority(java.util.UUID.randomUUID(), true));
    }

    @Test
    public void unknownPersistentIdentityCannotBootstrapOrCreate() {
        assertFalse(PoppetRegistry.mayCreateAuthority(java.util.UUID.randomUUID(), true));
    }

    @Test
    public void transientIdentityCannotCreateAuthority() {
        assertFalse(PoppetRegistry.mayCreateAuthority(java.util.UUID.randomUUID(), false));
    }

    @Test
    public void authorizedIdentitylessAtEmptyLocationMayCreate() {
        assertTrue(PoppetRegistry.mayCreateAuthority(null, false));
    }

    @Test
    public void snapshotCaptureReplacementIsDenied() {
        assertTrue(PoppetRegistry.denySnapshotReplacement(true, false));
        assertFalse(PoppetRegistry.prepareOrdinaryReplacement(true, false));
    }

    @Test
    public void snapshotRestoreDoesNotTombstone() {
        assertFalse(PoppetRegistry.denySnapshotReplacement(true, true));
        assertFalse(PoppetRegistry.prepareOrdinaryReplacement(true, true));
        assertFalse(PoppetRegistry.prepareOrdinaryReplacement(false, true));
    }

    @Test
    public void ordinaryReplacementPreparesWalRemoval() {
        assertFalse(PoppetRegistry.denySnapshotReplacement(false, false));
        assertTrue(PoppetRegistry.prepareOrdinaryReplacement(false, false));
    }

    @Test
    public void copiedKnownIdentityIsClearedOnRemoval() {
        assertTrue(PoppetRegistry.clearPhysicalOnRemoval(false, true, true));
    }

    @Test
    public void unknownPersistentIdentityIsClearedOnRemoval() {
        assertTrue(PoppetRegistry.clearPhysicalOnRemoval(false, true, false));
    }

    @Test
    public void ordinaryUnindexedShelfIsNotCleared() {
        assertFalse(PoppetRegistry.clearPhysicalOnRemoval(false, false, false));
    }

    @Test
    public void staleAtLocationWithMissingIdentityIsCleared() {
        assertTrue(PoppetRegistry.clearPhysicalOnRemoval(true, false, false));
    }

    @Test
    public void exactAtLocationIsNotCleared() {
        assertFalse(PoppetRegistry.clearPhysicalOnRemoval(true, true, true));
    }
}
