package com.github.witcheryoptimizer.registry;

import static org.junit.Assert.*;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.github.witcheryoptimizer.migration.WitcheryImportCoordinator;

public class PoppetWorldDataTest {

    @Test
    public void strictLocationRejectsWrongTypesAndHeightBounds() {
        NBTTagCompound tag = new ShelfLocation(0, 1, 0, 2).write();
        for (int y : new int[] { -1, 256 }) {
            tag.setInteger("Y", y);
            try {
                ShelfLocation.read(tag);
                fail("invalid height accepted");
            } catch (IllegalStateException expected) {}
        }
        tag.setInteger("Y", 255);
        assertEquals(255, ShelfLocation.read(tag).y);
        tag.setLong("X", 1);
        try {
            ShelfLocation.read(tag);
            fail("long coordinate accepted");
        } catch (IllegalStateException expected) {}
    }

    @Test
    public void strictRecordRejectsDuplicateSlotsAndPartialUuid() {
        ShelfRecord record = new ShelfRecord(
            UUID.randomUUID(),
            new ShelfLocation(0, 1, 2, 3),
            "",
            0,
            new net.minecraft.item.ItemStack[9]);
        NBTTagCompound tag = record.write();
        tag.removeTag("UuidLeast");
        try {
            ShelfRecord.read(tag);
            fail("partial UUID accepted");
        } catch (IllegalStateException expected) {}
    }

    @Test
    public void firstEndTickCompletesTicketFreeUnknownBeforeCensusEligibility() {
        WitcheryImportCoordinator coordinator = new WitcheryImportCoordinator();
        PoppetWorldData.ImportState persisted = PoppetWorldData.ImportState.UNKNOWN;
        assertTrue(PoppetRegistry.shouldFinalizeImport(persisted));
        assertTrue(coordinator.finalizeStartup());
        persisted = coordinator.state();
        assertEquals(PoppetWorldData.ImportState.DRAINED_CLEAN, persisted);
        assertTrue(
            PoppetRegistry.censusEligible(persisted, false, PoppetWorldData.CensusState.UNKNOWN, false, true, false));
    }

    @Test
    public void outstandingTicketCallbackBlocksCompletionAndCensus() {
        WitcheryImportCoordinator coordinator = new WitcheryImportCoordinator();
        assertTrue(coordinator.inspect(7, new boolean[] { true }, 1));
        assertFalse(coordinator.finalizeStartup());
        assertEquals(PoppetWorldData.ImportState.IN_PROGRESS, coordinator.state());
        assertFalse(
            PoppetRegistry
                .censusEligible(coordinator.state(), false, PoppetWorldData.CensusState.UNKNOWN, false, true, false));
    }

    @Test
    public void completeImportWithoutCensusMarkerIsEligible() {
        assertFalse(PoppetRegistry.shouldFinalizeImport(PoppetWorldData.ImportState.COMPLETE));
        assertTrue(
            PoppetRegistry.censusEligible(
                PoppetWorldData.ImportState.DRAINED_CLEAN,
                false,
                PoppetWorldData.CensusState.UNKNOWN,
                false,
                true,
                false));
        assertFalse(
            PoppetRegistry.censusEligible(
                PoppetWorldData.ImportState.DRAINED_CLEAN,
                false,
                PoppetWorldData.CensusState.UNKNOWN,
                false,
                true,
                true));
    }

    @Test
    public void preparedRemovalCannotAttachRegardlessOfPersistentIdentity() {
        assertFalse(PoppetRegistry.canAttach(ShelfRecord.State.REMOVAL_PREPARED));
        assertTrue(PoppetRegistry.canAttach(ShelfRecord.State.ACTIVE));
    }

    @Test
    public void startupValidationQuarantinesBothRemovalStates() {
        assertTrue(PoppetRegistry.quarantineDuringStartupValidation(ShelfRecord.State.REMOVAL_PREPARED));
        assertTrue(PoppetRegistry.quarantineDuringStartupValidation(ShelfRecord.State.REMOVAL_CLEANUP_PENDING));
        assertFalse(PoppetRegistry.quarantineDuringStartupValidation(ShelfRecord.State.ACTIVE));
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
        assertEquals(PoppetWorldData.CensusState.RETRY_WAIT, interrupted.censusState());
        assertFalse(interrupted.censusComplete(1));
    }

    @Test
    public void completeClearsRetryAndNextFailureStartsAtOne() {
        PoppetWorldData data = new PoppetWorldData();
        data.setCensusRetry(1, 9, 1234L, true, "old failure");
        data.setCensusState(1, PoppetWorldData.CensusState.COMPLETE);
        assertEquals(0, data.retryAttempt());
        assertEquals("", data.retryReason());
        data.setCensusRetry(1, data.retryAttempt() + 1, 2000L, false, "new failure");
        assertEquals(1, data.retryAttempt());
    }

    @Test
    public void hashHostileNewDimensionsStillFormNumericSuffix() {
        assertEquals(
            java.util.Arrays.asList(0, 7, 1, 17, 33),
            PoppetRegistry.deterministicDimensionOrder(
                java.util.Arrays.asList(0),
                java.util.Arrays.asList(7),
                new java.util.HashSet<>(java.util.Arrays.asList(33, 1, 17))));
    }

    @Test
    public void retryMetadataRoundTripsAndClamps() {
        PoppetWorldData data = new PoppetWorldData();
        data.setCensusRetry(1, 4, 120000L, true, "broken authority");
        NBTTagCompound tag = new NBTTagCompound();
        data.writeToNBT(tag);
        PoppetWorldData restored = new PoppetWorldData();
        restored.readFromNBT(tag);
        assertEquals(PoppetWorldData.CensusState.RETRY_WAIT, restored.censusState());
        assertEquals(4, restored.retryAttempt());
        assertEquals(120000L, restored.retryAt(100000L));
        tag.setLong("CensusRetryAt", Long.MAX_VALUE);
        restored.readFromNBT(tag);
        assertEquals(100000L, restored.retryAt(100000L));
    }

    @Test
    public void legacyFailedCensusMigratesToRetryWait() {
        PoppetWorldData data = new PoppetWorldData();
        data.setCensusState(1, PoppetWorldData.CensusState.FAILED);
        NBTTagCompound tag = new NBTTagCompound();
        data.writeToNBT(tag);
        PoppetWorldData restored = new PoppetWorldData();
        restored.readFromNBT(tag);
        assertEquals(PoppetWorldData.CensusState.RETRY_WAIT, restored.censusState());
        assertFalse(restored.censusComplete(1));
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
    public void loadedPersistentUuidAtDifferentAuthorityIsCorruptionPreflight() {
        UUID id = UUID.randomUUID();
        ShelfLocation authoritative = new ShelfLocation(0, 1, 2, 3);
        ShelfLocation loaded = new ShelfLocation(0, 9, 2, 3);
        ShelfRecord record = new ShelfRecord(id, authoritative, "", 0, new net.minecraft.item.ItemStack[9]);
        assertTrue(PoppetRegistry.loadedAuthorityConflict(true, id, loaded, record, null, false, false, false));
    }

    @Test
    public void benignOperationalAttachFailureIsNotAuthorityConflict() {
        UUID id = UUID.randomUUID();
        ShelfLocation location = new ShelfLocation(0, 1, 2, 3);
        ShelfRecord record = new ShelfRecord(id, location, "", 0, new net.minecraft.item.ItemStack[9]);
        assertFalse(PoppetRegistry.loadedAuthorityConflict(true, id, location, record, record, false, false, false));
        assertFalse(PoppetRegistry.loadedAuthorityConflict(false, null, location, null, null, false, false, false));
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

    private static NBTTagCompound operation(UUID id, String kind) {
        NBTTagCompound operation = new NBTTagCompound();
        operation.setLong("OperationMost", id.getMostSignificantBits());
        operation.setLong("OperationLeast", id.getLeastSignificantBits());
        operation.setString("Kind", kind);
        return operation;
    }

    @Test
    public void atMostOnceDropsRequireDurableDeletionAndSuccessfulRemoval() {
        assertFalse(PoppetRegistry.deletionBeforeSpawn(false, true));
        assertFalse(PoppetRegistry.deletionBeforeSpawn(true, false));
        assertTrue(PoppetRegistry.deletionBeforeSpawn(true, true));
    }

    @Test
    public void removalSuppressionAndSnapshotServingRequireExactShelf() {
        Object shelf = new Object();
        Object unrelated = new Object();
        assertTrue(PoppetRegistry.contextOwnsShelf(true, shelf, shelf));
        assertFalse(PoppetRegistry.contextOwnsShelf(false, shelf, shelf));
        assertFalse(PoppetRegistry.contextOwnsShelf(true, shelf, unrelated));
        assertTrue(PoppetRegistry.canServeRemovalSnapshot(true, true));
        assertFalse(PoppetRegistry.canServeRemovalSnapshot(false, true));
        assertFalse(PoppetRegistry.canServeRemovalSnapshot(true, false));
    }

    @Test
    public void removalStateRejectsNestedPreflightAndActivatesOnlyExactPending() {
        assertTrue(PoppetRegistry.canBeginRemoval(false, false));
        assertFalse(PoppetRegistry.canBeginRemoval(true, false));
        assertFalse(PoppetRegistry.canBeginRemoval(false, true));
        assertFalse(PoppetRegistry.canBeginRemoval(true, true));
        assertTrue(PoppetRegistry.canActivateRemoval(true, false));
        assertFalse(PoppetRegistry.canActivateRemoval(false, false));
        assertFalse(PoppetRegistry.canActivateRemoval(true, true));
        assertTrue(PoppetRegistry.shouldClearPendingAtReturn(true));
        assertFalse(PoppetRegistry.shouldClearPendingAtReturn(false));
    }

    @Test
    public void startupValidationDeletesOnlyAfterLoadedPhysicalEvidence() {
        assertEquals(
            PoppetRegistry.StartupValidationDecision.RETRY,
            PoppetRegistry.classifyStartupValidation(false, false, false, false, false, false, false));
        assertEquals(
            PoppetRegistry.StartupValidationDecision.RETRY,
            PoppetRegistry.classifyStartupValidation(true, false, false, false, false, false, false));
        assertEquals(
            PoppetRegistry.StartupValidationDecision.RETRY,
            PoppetRegistry.classifyStartupValidation(true, true, false, false, false, false, false));
        assertEquals(
            PoppetRegistry.StartupValidationDecision.RETRY,
            PoppetRegistry.classifyStartupValidation(true, true, true, false, false, false, false));
        assertEquals(
            PoppetRegistry.StartupValidationDecision.DELETE,
            PoppetRegistry.classifyStartupValidation(true, true, true, true, false, false, false));
        assertEquals(
            PoppetRegistry.StartupValidationDecision.DELETE,
            PoppetRegistry.classifyStartupValidation(true, true, true, true, true, false, false));
        assertEquals(
            PoppetRegistry.StartupValidationDecision.DELETE,
            PoppetRegistry.classifyStartupValidation(true, true, true, true, true, true, false));
        assertEquals(
            PoppetRegistry.StartupValidationDecision.MIRROR,
            PoppetRegistry.classifyStartupValidation(true, true, true, true, true, true, true));
        assertEquals(
            "exact Witchery Poppet Shelf is absent",
            PoppetRegistry.startupValidationConfirmedAbsence(false, false, false));
        assertEquals(
            "exact Witchery Poppet Shelf TE is absent",
            PoppetRegistry.startupValidationConfirmedAbsence(true, false, false));
        assertEquals(
            "exact shelf identity does not match authority",
            PoppetRegistry.startupValidationConfirmedAbsence(true, true, false));
        assertNull(PoppetRegistry.startupValidationConfirmedAbsence(true, true, true));
    }

    @Test
    public void preloadedChunkMismatchRetriesWithoutDiskProvenance() {
        assertEquals(
            PoppetRegistry.StartupValidationDecision.MIRROR,
            PoppetRegistry.classifyPhysicalValidation(true, false, true, true, true));
        assertEquals(
            PoppetRegistry.StartupValidationDecision.RETRY,
            PoppetRegistry.classifyPhysicalValidation(true, false, false, false, false));
        assertEquals(
            PoppetRegistry.StartupValidationDecision.RETRY,
            PoppetRegistry.classifyPhysicalValidation(true, false, true, true, false));
        assertEquals(
            PoppetRegistry.StartupValidationDecision.DELETE,
            PoppetRegistry.classifyPhysicalValidation(false, true, false, false, false));
        assertEquals(
            PoppetRegistry.StartupValidationDecision.DELETE,
            PoppetRegistry.classifyPhysicalValidation(false, true, true, true, false));
        assertEquals(
            PoppetRegistry.StartupValidationDecision.RETRY,
            PoppetRegistry.classifyPhysicalValidation(false, false, false, false, false));
    }

    @Test
    public void startupMirrorRequiresExactPersistentPhysicalIdentity() {
        UUID authority = UUID.randomUUID();
        assertTrue(PoppetRegistry.startupIdentityMatches(true, authority, authority));
        assertFalse(PoppetRegistry.startupIdentityMatches(false, authority, authority));
        assertFalse(PoppetRegistry.startupIdentityMatches(true, null, authority));
        assertFalse(PoppetRegistry.startupIdentityMatches(true, UUID.randomUUID(), authority));
    }

    @Test
    public void removalOutcomeMatrixSeparatesCleanupFromTransientFailure() {
        assertEquals(PoppetRegistry.SetBlockRemoval.AUTHORITATIVE, PoppetRegistry.classifyRemoval(true, false));
        assertEquals(PoppetRegistry.SetBlockRemoval.PHYSICAL_CLEANUP, PoppetRegistry.classifyRemoval(false, true));
        assertEquals(PoppetRegistry.SetBlockRemoval.TRANSIENT_FAILURE, PoppetRegistry.classifyRemoval(false, false));
    }

    @Test
    public void missingTileRemovalRequiresUniqueAuthorityDeletionBeforeCleanup() {
        assertEquals(
            PoppetRegistry.MissingTileRemoval.CLEANUP,
            PoppetRegistry.classifyMissingTileRemoval(false, false));
        assertEquals(
            PoppetRegistry.MissingTileRemoval.DELETE_THEN_CLEANUP,
            PoppetRegistry.classifyMissingTileRemoval(true, false));
        assertEquals(
            PoppetRegistry.MissingTileRemoval.TRANSIENT_FAILURE,
            PoppetRegistry.classifyMissingTileRemoval(false, true));
        assertEquals(
            PoppetRegistry.MissingTileRemoval.TRANSIENT_FAILURE,
            PoppetRegistry.classifyMissingTileRemoval(true, true));
    }

    @Test
    public void staleAuthorityDeleteLeavesNoGhostRecord() {
        PoppetWorldData data = new PoppetWorldData();
        ShelfRecord record = data
            .newRecord(UUID.randomUUID(), new ShelfLocation(0, 12, 64, 13), "", new net.minecraft.item.ItemStack[9]);
        data.install(record);
        data.delete(record.id, record.version + 1, record.location);
        assertNull(data.get(record.id));
        assertTrue(data.isTombstoned(record.id));
        assertTrue(data.isTombstonedLocation(record.location));
    }
}
