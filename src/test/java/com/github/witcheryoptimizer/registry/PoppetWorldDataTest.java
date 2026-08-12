package com.github.witcheryoptimizer.registry;

import static org.junit.Assert.*;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.github.witcheryoptimizer.migration.WitcheryImportCoordinator;

public class PoppetWorldDataTest {

    @Test
    public void firstEndTickCompletesTicketFreeUnknownBeforeCensusEligibility() {
        WitcheryImportCoordinator coordinator = new WitcheryImportCoordinator();
        PoppetWorldData.ImportState persisted = PoppetWorldData.ImportState.UNKNOWN;
        assertTrue(PoppetRegistry.shouldFinalizeImport(persisted));
        assertTrue(coordinator.finalizeStartup());
        persisted = coordinator.state();
        assertEquals(PoppetWorldData.ImportState.COMPLETE, persisted);
        assertTrue(PoppetRegistry.censusEligible(persisted, false, PoppetWorldData.CensusState.UNKNOWN, false, false));
    }

    @Test
    public void outstandingTicketCallbackBlocksCompletionAndCensus() {
        WitcheryImportCoordinator coordinator = new WitcheryImportCoordinator();
        assertTrue(coordinator.inspect(7, new boolean[] { true }, 1));
        assertFalse(coordinator.finalizeStartup());
        assertEquals(PoppetWorldData.ImportState.IN_PROGRESS, coordinator.state());
        assertFalse(
            PoppetRegistry
                .censusEligible(coordinator.state(), false, PoppetWorldData.CensusState.UNKNOWN, false, false));
    }

    @Test
    public void completeImportWithoutCensusMarkerIsEligible() {
        assertFalse(PoppetRegistry.shouldFinalizeImport(PoppetWorldData.ImportState.COMPLETE));
        assertTrue(
            PoppetRegistry.censusEligible(
                PoppetWorldData.ImportState.COMPLETE,
                false,
                PoppetWorldData.CensusState.UNKNOWN,
                false,
                false));
        assertFalse(
            PoppetRegistry.censusEligible(
                PoppetWorldData.ImportState.COMPLETE,
                false,
                PoppetWorldData.CensusState.UNKNOWN,
                false,
                true));
    }

    @Test
    public void preparedRemovalCannotAttachRegardlessOfPersistentIdentity() {
        assertFalse(PoppetRegistry.canAttach(ShelfRecord.State.REMOVAL_PREPARED));
        assertTrue(PoppetRegistry.canAttach(ShelfRecord.State.ACTIVE));
    }

    @Test
    public void journalPutAndDeleteReplayAreIdempotent() {
        PoppetWorldData data = new PoppetWorldData();
        UUID shelf = UUID.randomUUID();
        ShelfRecord record = new ShelfRecord(
            shelf,
            new ShelfLocation(7, 1, 2, 3),
            "",
            0,
            new net.minecraft.item.ItemStack[9]);
        record.version = 2;
        NBTTagCompound put = operation(UUID.randomUUID(), "PUT");
        put.setTag("Record", record.write());
        data.applyJournal(put);
        data.applyJournal(put);
        assertEquals(2, data.get(shelf).version);

        NBTTagCompound delete = operation(UUID.randomUUID(), "DELETE");
        delete.setLong("ShelfMost", shelf.getMostSignificantBits());
        delete.setLong("ShelfLeast", shelf.getLeastSignificantBits());
        delete.setLong("Generation", 3);
        data.applyJournal(delete);
        data.applyJournal(put);
        assertNull(data.get(shelf));
        assertTrue(data.isTombstoned(shelf));
    }

    @Test
    public void nbtRoundTripPreservesTombstoneAndFullRecord() {
        PoppetWorldData data = new PoppetWorldData();
        UUID shelf = UUID.randomUUID();
        data.install(
            data.newRecord(shelf, new ShelfLocation(31, -4, 70, 9), "named", new net.minecraft.item.ItemStack[9]));
        ShelfLocation location = new ShelfLocation(31, -4, 70, 9);
        data.delete(shelf, 4, location);
        NBTTagCompound root = new NBTTagCompound();
        data.writeToNBT(root);
        PoppetWorldData restored = new PoppetWorldData();
        restored.readFromNBT(root);
        assertNull(restored.get(shelf));
        assertTrue(restored.isTombstoned(shelf));
        assertTrue(restored.isTombstoned(shelf));
    }

    @Test
    public void newerPutClearsOlderTombstoneAcrossRoundTrip() {
        PoppetWorldData data = new PoppetWorldData();
        UUID id = UUID.randomUUID();
        ShelfRecord record = new ShelfRecord(
            id,
            new ShelfLocation(2, 3, 4, 5),
            "",
            0,
            new net.minecraft.item.ItemStack[9]);
        record.version = 1;
        data.install(record);
        data.delete(id, 2, record.location);
        record.version = 3;
        data.install(record);
        assertNotNull(data.get(id));
        assertFalse(data.isTombstoned(id));
        NBTTagCompound tag = new NBTTagCompound();
        data.writeToNBT(tag);
        PoppetWorldData restored = new PoppetWorldData();
        restored.readFromNBT(tag);
        assertNotNull(restored.get(id));
        assertFalse(restored.isTombstoned(id));
    }

    @Test
    public void importStateTransitionsPersist() {
        PoppetWorldData data = new PoppetWorldData();
        data.setImportState(PoppetWorldData.ImportState.IN_PROGRESS);
        NBTTagCompound tag = new NBTTagCompound();
        data.writeToNBT(tag);
        PoppetWorldData restored = new PoppetWorldData();
        restored.readFromNBT(tag);
        assertEquals(PoppetWorldData.ImportState.IN_PROGRESS, restored.importState());
        restored.setImportState(PoppetWorldData.ImportState.FAILED);
        assertEquals(PoppetWorldData.ImportState.FAILED, restored.importState());
    }

    @Test
    public void removalRollbackRequiresExactOriginalShelf() {
        assertTrue(PoppetRegistry.shouldRestoreRemoval(true, true));
        assertFalse(PoppetRegistry.shouldRestoreRemoval(false, true));
        assertFalse(PoppetRegistry.shouldRestoreRemoval(true, false));
        assertFalse(PoppetRegistry.shouldRestoreRemoval(false, false));
    }

    @Test
    public void staleDeleteCannotOverrideNewerRecord() {
        PoppetWorldData data = new PoppetWorldData();
        UUID id = UUID.randomUUID();
        ShelfRecord record = new ShelfRecord(
            id,
            new ShelfLocation(4, 1, 2, 3),
            "",
            0,
            new net.minecraft.item.ItemStack[9]);
        record.version = 3;
        data.install(record);
        data.delete(id, 2, record.location);
        assertNotNull(data.get(id));
        assertFalse(data.isTombstoned(id));
        data.delete(id, 3, record.location);
        assertNull(data.get(id));
        assertTrue(data.isTombstoned(id));
    }

    @Test
    public void rejectsMissingAndSchemaOneOptimizerData() {
        for (int schema : new int[] { -1, 1 }) {
            NBTTagCompound tag = new NBTTagCompound();
            if (schema >= 0) tag.setInteger("Schema", schema);
            try {
                new PoppetWorldData().readFromNBT(tag);
                fail("legacy optimizer data must be rejected");
            } catch (IllegalStateException expected) {
                assertTrue(
                    expected.getMessage()
                        .contains("v0.1/schema-1"));
            }
        }
    }

    @Test
    public void journalStatesRoundTripWithoutDroppingInventory() {
        PoppetWorldData data = new PoppetWorldData();
        ShelfRecord record = data
            .newRecord(UUID.randomUUID(), new ShelfLocation(8, 1, 64, 2), "named", new net.minecraft.item.ItemStack[9]);
        record.state = ShelfRecord.State.REMOVAL_PREPARED;
        record.removalTransaction = UUID.randomUUID();
        record.writebackPending = true;
        data.install(record);
        NBTTagCompound tag = new NBTTagCompound();
        data.writeToNBT(tag);
        PoppetWorldData restored = new PoppetWorldData();
        restored.readFromNBT(tag);
        ShelfRecord copy = restored.get(record.id);
        assertEquals(ShelfRecord.State.REMOVAL_PREPARED, copy.state);
        assertTrue(copy.writebackPending);
    }

    @Test
    public void legacyCompleteWithoutCensusProofRemainsIncomplete() {
        PoppetWorldData data = new PoppetWorldData();
        data.setImportState(PoppetWorldData.ImportState.COMPLETE);
        NBTTagCompound tag = new NBTTagCompound();
        data.writeToNBT(tag);
        tag.removeTag("CensusState");
        tag.removeTag("CensusVersion");
        PoppetWorldData restored = new PoppetWorldData();
        restored.readFromNBT(tag);
        assertEquals(PoppetWorldData.ImportState.COMPLETE, restored.importState());
        assertFalse(restored.censusComplete(1));
    }

    @Test
    public void censusProofAndInterruptedStateRoundTrip() {
        PoppetWorldData data = new PoppetWorldData();
        data.setCensusState(1, PoppetWorldData.CensusState.COMPLETE);
        NBTTagCompound tag = new NBTTagCompound();
        data.writeToNBT(tag);
        PoppetWorldData restored = new PoppetWorldData();
        restored.readFromNBT(tag);
        assertTrue(restored.censusComplete(1));
        restored.setCensusState(1, PoppetWorldData.CensusState.IN_PROGRESS);
        restored.writeToNBT(tag);
        PoppetWorldData interrupted = new PoppetWorldData();
        interrupted.readFromNBT(tag);
        assertEquals(PoppetWorldData.CensusState.IN_PROGRESS, interrupted.censusState());
        assertFalse(interrupted.censusComplete(1));
    }

    @Test
    public void terminalRemovalReplayTombstonesAndCannotResurrect() {
        PoppetWorldData data = new PoppetWorldData();
        ShelfRecord record = data
            .newRecord(UUID.randomUUID(), new ShelfLocation(0, 2, 3, 4), "", new net.minecraft.item.ItemStack[9]);
        data.install(record);
        NBTTagCompound delete = new NBTTagCompound();
        delete.setString("Kind", "DELETE");
        delete.setLong("ShelfMost", record.id.getMostSignificantBits());
        delete.setLong("ShelfLeast", record.id.getLeastSignificantBits());
        delete.setLong("Generation", 3);
        delete.setTag("Location", record.location.write());
        data.applyJournal(delete);
        data.applyJournal(delete);
        assertNull(data.get(record.id));
        assertTrue(data.isTombstonedLocation(record.location));
        data.install(record);
        assertNull(data.get(record.id));
    }

    @Test
    public void invalidRecordAndImportStatesFailClearly() {
        PoppetWorldData data = new PoppetWorldData();
        ShelfRecord record = data
            .newRecord(UUID.randomUUID(), new ShelfLocation(0, 1, 2, 3), "", new net.minecraft.item.ItemStack[9]);
        data.install(record);
        NBTTagCompound tag = new NBTTagCompound();
        data.writeToNBT(tag);
        tag.setString("WitcheryImportState", "BOGUS");
        try {
            new PoppetWorldData().readFromNBT(tag);
            fail("invalid import state");
        } catch (IllegalStateException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("WitcheryImportState"));
        }
        data.writeToNBT(tag);
        tag.getTagList("Shelves", 10)
            .getCompoundTagAt(0)
            .setString("State", "BOGUS");
        try {
            new PoppetWorldData().readFromNBT(tag);
            fail("invalid record state");
        } catch (IllegalStateException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("shelf transaction state"));
        }
    }

    @Test
    public void removalOutcomeUsesPhysicalResultNotBooleanReturn() {
        assertEquals(
            PoppetRegistry.RemovalOutcome.COMMIT_AND_UNLOCK,
            PoppetRegistry.removalOutcome(false, false, true));
        assertEquals(
            PoppetRegistry.RemovalOutcome.AWAIT_DURABLE_RECONCILIATION,
            PoppetRegistry.removalOutcome(true, false, true));
        assertEquals(
            PoppetRegistry.RemovalOutcome.RESTORE_EXACT_ORIGINAL,
            PoppetRegistry.removalOutcome(true, true, false));
        assertEquals(
            PoppetRegistry.RemovalOutcome.AWAIT_DURABLE_RECONCILIATION,
            PoppetRegistry.removalOutcome(true, true, true));
    }

    @Test
    public void dynamicDimensionAllowancePreservesNumericRestriction() {
        java.util.List<Integer> dimensions = java.util.Arrays.asList(0, -1, 1, 7, 42);
        assertEquals(new java.util.HashSet<>(dimensions), PoppetRegistry.allowedDimensions(dimensions, false, 42));
        assertEquals(
            new java.util.HashSet<>(java.util.Arrays.asList(0, -1, 1, 42)),
            PoppetRegistry.allowedDimensions(dimensions, true, 42));
    }

    @Test
    public void writebackRequiresMatchingReloadedActiveVersion() {
        assertTrue(PoppetRegistry.shouldConfirmWriteback(true, true, 7, 7));
        assertFalse(PoppetRegistry.shouldConfirmWriteback(true, true, -1, 7));
        assertFalse(PoppetRegistry.shouldConfirmWriteback(true, true, 6, 7));
        assertFalse(PoppetRegistry.shouldConfirmWriteback(false, true, 7, 7));
        assertFalse(PoppetRegistry.shouldConfirmWriteback(true, false, 7, 7));
    }

    @Test
    public void deterministicDimensionsUseLoadedPrefixPersistedThenSortedDiscovery() {
        java.util.List<Integer> order = PoppetRegistry.deterministicDimensionOrder(
            java.util.Arrays.asList(0, -1, 1, 180),
            java.util.Arrays.asList(7, 180, 9),
            java.util.Arrays.asList(42, 3, 7, 181));
        assertEquals(java.util.Arrays.asList(0, -1, 1, 180, 7, 9, 3, 42, 181), order);
    }

    @Test
    public void duplicateAuthoritativeLocationIsRejectedOnInstallAndReplay() {
        PoppetWorldData data = new PoppetWorldData();
        ShelfLocation location = new ShelfLocation(0, 1, 2, 3);
        ShelfRecord first = data.newRecord(UUID.randomUUID(), location, "", new net.minecraft.item.ItemStack[9]);
        ShelfRecord second = data.newRecord(UUID.randomUUID(), location, "", new net.minecraft.item.ItemStack[9]);
        data.install(first);
        try {
            data.install(second);
            fail("duplicate location");
        } catch (IllegalStateException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("Duplicate"));
        }
        NBTTagCompound put = new NBTTagCompound();
        put.setString("Kind", "PUT");
        put.setTag("Record", second.write());
        try {
            data.applyJournal(put);
            fail("duplicate replay");
        } catch (IllegalStateException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("Duplicate"));
        }
    }

    @Test
    public void censusExistingLocationRequiresMatchingUuidOrExactLegacyContents() throws Exception {
        ShelfRecord existing = new ShelfRecord(
            UUID.randomUUID(),
            new ShelfLocation(0, 1, 2, 3),
            "same",
            0,
            new net.minecraft.item.ItemStack[9]);
        NBTTagCompound tile = new NBTTagCompound();
        tile.setString("CustomName", "same");
        tile.setTag("Items", new net.minecraft.nbt.NBTTagList());
        assertTrue(PoppetRegistry.censusIdentityMatches(existing, existing.id, tile));
        assertFalse(PoppetRegistry.censusIdentityMatches(existing, UUID.randomUUID(), tile));
        assertTrue(PoppetRegistry.censusIdentityMatches(existing, null, tile));
        tile.setString("CustomName", "different");
        assertFalse(PoppetRegistry.censusIdentityMatches(existing, null, tile));
    }

    @Test
    public void preparedRecoveryCoversAllCrashBoundariesAndReplayDecision() {
        assertEquals(
            PoppetRegistry.RemovalRecovery.RESTORE,
            PoppetRegistry.removalRecovery(true, true, false, false, false));
        assertEquals(
            PoppetRegistry.RemovalRecovery.DELETE,
            PoppetRegistry.removalRecovery(false, false, true, true, true));
        assertEquals(
            PoppetRegistry.RemovalRecovery.CLEANUP_PENDING,
            PoppetRegistry.removalRecovery(true, true, true, true, true));
        assertEquals(
            PoppetRegistry.RemovalRecovery.UNRESOLVED,
            PoppetRegistry.removalRecovery(false, false, true, true, false));
        assertEquals(
            PoppetRegistry.RemovalRecovery.RESTORE,
            PoppetRegistry.removalRecovery(true, true, true, false, false));
        assertEquals(
            PoppetRegistry.RemovalRecovery.RESTORE,
            PoppetRegistry.removalRecovery(true, true, false, false, false));
    }

    @Test
    public void preparedTransactionEvidenceRoundTripsAndLegacyPreparedFailsClosed() {
        ShelfRecord record = new ShelfRecord(
            UUID.randomUUID(),
            new ShelfLocation(0, 4, 5, 6),
            "",
            0,
            new net.minecraft.item.ItemStack[9]);
        record.state = ShelfRecord.State.REMOVAL_PREPARED;
        record.removalTransaction = UUID.randomUUID();
        record.removalDropsStarted = true;
        ShelfRecord restored = ShelfRecord.read(record.write());
        assertEquals(record.removalTransaction, restored.removalTransaction);
        assertTrue(restored.removalDropsStarted);

        NBTTagCompound legacy = record.write();
        legacy.removeTag("RemovalTransactionMost");
        legacy.removeTag("RemovalTransactionLeast");
        try {
            ShelfRecord.read(legacy);
            fail("prepared state without transaction identity must fail closed");
        } catch (IllegalStateException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("transaction identity"));
        }
    }

    @Test
    public void unresolvedPreparedRecordsBlockCensusCompletion() {
        PoppetWorldData data = new PoppetWorldData();
        ShelfRecord record = data
            .newRecord(UUID.randomUUID(), new ShelfLocation(0, 7, 8, 9), "", new net.minecraft.item.ItemStack[9]);
        record.state = ShelfRecord.State.REMOVAL_PREPARED;
        record.removalTransaction = UUID.randomUUID();
        data.install(record);
        assertTrue(data.hasPreparedRemovals());
        data.delete(record.id, record.version + 1, record.location);
        assertFalse(data.hasPreparedRemovals());
    }

    @Test
    public void recoveredPreparedRemovalInvalidatesPriorCensusProof() {
        assertTrue(PoppetRegistry.requiresRemovalCensus(true, true));
        assertFalse(PoppetRegistry.requiresRemovalCensus(true, false));
        assertFalse(PoppetRegistry.requiresRemovalCensus(false, true));
    }

    @Test
    public void terminalRemovalPersistsCommittedTransactionForDropUnlock() {
        PoppetWorldData data = new PoppetWorldData();
        UUID transaction = UUID.randomUUID();
        ShelfRecord record = data
            .newRecord(UUID.randomUUID(), new ShelfLocation(0, 1, 2, 3), "", new net.minecraft.item.ItemStack[9]);
        record.state = ShelfRecord.State.REMOVAL_PREPARED;
        record.removalTransaction = transaction;
        data.install(record);
        data.delete(record.id, 2, record.location, transaction);
        assertTrue(data.isCommittedRemoval(transaction));
        NBTTagCompound root = new NBTTagCompound();
        data.writeToNBT(root);
        PoppetWorldData restored = new PoppetWorldData();
        restored.readFromNBT(root);
        assertTrue(restored.isCommittedRemoval(transaction));
    }

    @Test
    public void removalDropTagParsingLocksOnlyCompleteOptimizerTransactions() {
        NBTTagCompound ordinary = new NBTTagCompound();
        assertNull(PoppetRegistry.removalDropTransaction(ordinary));
        UUID transaction = UUID.randomUUID();
        NBTTagCompound tagged = new NBTTagCompound();
        tagged.setLong("WORemovalMost", transaction.getMostSignificantBits());
        tagged.setLong("WORemovalLeast", transaction.getLeastSignificantBits());
        tagged.setInteger("WODropOrdinal", 0);
        assertEquals(transaction, PoppetRegistry.removalDropTransaction(tagged));
        tagged.removeTag("WODropOrdinal");
        assertEquals(new UUID(0, 0), PoppetRegistry.removalDropTransaction(tagged));
    }

    @Test
    public void cleanupPendingRoundTripsAndBlocksCompletionUntilDeleted() {
        PoppetWorldData data = new PoppetWorldData();
        ShelfRecord record = data
            .newRecord(UUID.randomUUID(), new ShelfLocation(0, 9, 10, 11), "", new net.minecraft.item.ItemStack[9]);
        record.state = ShelfRecord.State.REMOVAL_CLEANUP_PENDING;
        record.removalTransaction = UUID.randomUUID();
        data.install(record);
        NBTTagCompound root = new NBTTagCompound();
        data.writeToNBT(root);
        PoppetWorldData restored = new PoppetWorldData();
        restored.readFromNBT(root);
        assertTrue(restored.hasCleanupPendingRemovals());
        restored.delete(record.id, 2, record.location, record.removalTransaction);
        assertFalse(restored.hasCleanupPendingRemovals());
    }

    @Test
    public void cleanupAuthorizationRequiresExactWorldLocationShelfTransactionAndTile() {
        ShelfLocation location = new ShelfLocation(7, 1, 2, 3);
        UUID shelf = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        assertTrue(
            PoppetRegistry
                .cleanupContextMatches(location, shelf, transaction, 7, 1, 2, 3, shelf, transaction, true, true));
        assertFalse(
            PoppetRegistry
                .cleanupContextMatches(location, shelf, transaction, 7, 2, 2, 3, shelf, transaction, true, true));
        assertFalse(
            PoppetRegistry
                .cleanupContextMatches(location, shelf, transaction, 7, 1, 2, 3, shelf, transaction, false, true));
        assertFalse(
            PoppetRegistry.cleanupContextMatches(
                location,
                shelf,
                transaction,
                7,
                1,
                2,
                3,
                UUID.randomUUID(),
                transaction,
                true,
                true));
        assertFalse(
            PoppetRegistry
                .cleanupContextMatches(location, shelf, transaction, 7, 1, 2, 3, shelf, UUID.randomUUID(), true, true));
        assertFalse(
            PoppetRegistry
                .cleanupContextMatches(location, shelf, transaction, 7, 1, 2, 3, shelf, transaction, true, false));
    }

    @Test
    public void cleanupAuthorizationDoesNotChangeOrdinaryRemovalDecision() {
        assertEquals(
            PoppetRegistry.RemovalOutcome.COMMIT_AND_UNLOCK,
            PoppetRegistry.removalOutcome(false, false, true));
        assertEquals(
            PoppetRegistry.RemovalRecovery.CLEANUP_PENDING,
            PoppetRegistry.removalRecovery(true, true, true, true, true));
    }

    @Test
    public void existingRemovalTransactionAlwaysDeniesReuse() {
        assertTrue(PoppetRegistry.allowNewRemoval(false));
        assertFalse(PoppetRegistry.allowNewRemoval(true));
        assertFalse(PoppetRegistry.shouldClearStaleAtTick(false));
        assertTrue(PoppetRegistry.shouldClearStaleAtTick(true));
        ShelfLocation same = new ShelfLocation(0, 1, 2, 3);
        ShelfLocation different = new ShelfLocation(0, 2, 2, 3);
        assertTrue(PoppetRegistry.transactionContextMatches(true, same, same));
        assertFalse(PoppetRegistry.transactionContextMatches(true, same, different));
        assertFalse(PoppetRegistry.transactionContextMatches(false, same, same));
    }

    @Test
    public void crossWorldSpawnAndFinishContextsCannotMatch() {
        ShelfLocation target = new ShelfLocation(7, 4, 5, 6);
        assertFalse(PoppetRegistry.transactionContextMatches(false, target, target));
        assertFalse(PoppetRegistry.transactionContextMatches(true, target, new ShelfLocation(8, 4, 5, 6)));
        assertTrue(PoppetRegistry.requiresRemovalCensus(true, true));
    }

    private static NBTTagCompound operation(UUID id, String kind) {
        NBTTagCompound operation = new NBTTagCompound();
        operation.setLong("OperationMost", id.getMostSignificantBits());
        operation.setLong("OperationLeast", id.getLeastSignificantBits());
        operation.setString("Kind", kind);
        return operation;
    }
}
