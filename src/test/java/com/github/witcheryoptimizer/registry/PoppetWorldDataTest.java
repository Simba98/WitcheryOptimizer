package com.github.witcheryoptimizer.registry;

import static org.junit.Assert.*;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class PoppetWorldDataTest {

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

    private static NBTTagCompound operation(UUID id, String kind) {
        NBTTagCompound operation = new NBTTagCompound();
        operation.setLong("OperationMost", id.getMostSignificantBits());
        operation.setLong("OperationLeast", id.getLeastSignificantBits());
        operation.setString("Kind", kind);
        return operation;
    }
}
