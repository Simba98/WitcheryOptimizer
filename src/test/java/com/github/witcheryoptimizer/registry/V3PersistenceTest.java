package com.github.witcheryoptimizer.registry;

import static org.junit.Assert.*;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Test;

public class V3PersistenceTest {

    private static ShelfRecord record(UUID id, int x) {
        net.minecraft.item.ItemStack[] a = new net.minecraft.item.ItemStack[9];
        ShelfRecord r = new ShelfRecord(id, new ShelfLocation(7, x, 64, 9), "named", 4, a);
        r.version = 6;
        return r;
    }

    private static NBTTagCompound root() {
        PoppetWorldData d = new PoppetWorldData();
        d.install(record(UUID.randomUUID(), 1));
        NBTTagCompound n = new NBTTagCompound();
        d.writeToNBT(n);
        return n;
    }

    private static void bad(NBTTagCompound n) {
        try {
            new PoppetWorldData().readFromNBT(n);
            fail();
        } catch (IllegalStateException expected) {}
    }

    @Test
    public void roundTripAllFieldsAndItemNbt() {
        PoppetWorldData d = new PoppetWorldData();
        UUID id = UUID.randomUUID();
        ShelfRecord r = record(id, 1);
        r.writebackPending = true;
        r.writebackVersion = 6;
        d.install(r);
        NBTTagCompound n = new NBTTagCompound();
        d.writeToNBT(n);
        PoppetWorldData copy = new PoppetWorldData();
        copy.readFromNBT(n);
        ShelfRecord c = copy.get(id);
        assertEquals("named", c.customName);
        assertEquals(7, c.location.dimension);
        assertEquals(4, c.order);
        assertEquals(6, c.version);
        assertTrue(c.writebackPending);
        assertNull(c.inventory[2]);
    }

    @Test
    public void tombstoneWins() {
        PoppetWorldData d = new PoppetWorldData();
        ShelfRecord r = record(UUID.randomUUID(), 1);
        d.install(r);
        d.delete(r);
        NBTTagCompound n = new NBTTagCompound();
        d.writeToNBT(n);
        PoppetWorldData c = new PoppetWorldData();
        c.readFromNBT(n);
        assertNull(c.get(r.id));
    }

    @Test
    public void duplicateUuidRejected() {
        NBTTagCompound n = root();
        NBTTagList l = n.getTagList("Shelves", 10);
        l.appendTag(
            l.getCompoundTagAt(0)
                .copy());
        bad(n);
    }

    @Test
    public void duplicateLocationRejected() {
        PoppetWorldData d = new PoppetWorldData();
        d.install(record(UUID.randomUUID(), 1));
        try {
            d.install(record(UUID.randomUUID(), 1));
            fail();
        } catch (IllegalStateException expected) {}
    }

    @Test
    public void missingSchemaRejected() {
        NBTTagCompound n = root();
        n.removeTag("Schema");
        bad(n);
    }

    @Test
    public void wrongSchemaRejected() {
        NBTTagCompound n = root();
        n.setInteger("Schema", 2);
        bad(n);
    }

    @Test
    public void missingShelvesRejected() {
        NBTTagCompound n = root();
        n.removeTag("Shelves");
        bad(n);
    }

    @Test
    public void wrongShelvesTypeRejected() {
        NBTTagCompound n = root();
        n.setString("Shelves", "x");
        bad(n);
    }

    @Test
    public void missingTombstonesRejected() {
        NBTTagCompound n = root();
        n.removeTag("Tombstones");
        bad(n);
    }

    @Test
    public void missingNextOrderRejected() {
        NBTTagCompound n = root();
        n.removeTag("NextOrder");
        bad(n);
    }

    @Test
    public void duplicateSlotRejected() {
        NBTTagCompound r = record(UUID.randomUUID(), 1).write();
        NBTTagList l = r.getTagList("Items", 10);
        l.appendTag(
            l.getCompoundTagAt(0)
                .copy());
        try {
            ShelfRecord.read(r);
            fail();
        } catch (IllegalStateException expected) {}
    }

    @Test
    public void badCountRejected() {
        NBTTagCompound r = record(UUID.randomUUID(), 1).write();
        NBTTagCompound item = item();
        item.setByte("Count", (byte) 0);
        NBTTagList items = new NBTTagList();
        items.appendTag(item);
        r.setTag("Items", items);
        try {
            ShelfRecord.read(r);
            fail();
        } catch (IllegalStateException expected) {}
    }

    @Test
    public void wrongItemIdTypeRejected() {
        NBTTagCompound r = record(UUID.randomUUID(), 1).write();
        NBTTagCompound item = item();
        item.setString("id", "x");
        NBTTagList items = new NBTTagList();
        items.appendTag(item);
        r.setTag("Items", items);
        try {
            ShelfRecord.read(r);
            fail();
        } catch (IllegalStateException expected) {}
    }

    private static NBTTagCompound item() {
        NBTTagCompound item = new NBTTagCompound();
        item.setByte("Slot", (byte) 0);
        item.setShort("id", (short) 1);
        item.setByte("Count", (byte) 1);
        item.setShort("Damage", (short) 0);
        return item;
    }

    @Test
    public void invalidYRejected() {
        NBTTagCompound r = record(UUID.randomUUID(), 1).write();
        r.getCompoundTag("Location")
            .setInteger("Y", 256);
        try {
            ShelfRecord.read(r);
            fail();
        } catch (IllegalStateException expected) {}
    }

    @Test
    public void completedVersionInvariant() {
        NBTTagCompound r = record(UUID.randomUUID(), 1).write();
        r.setLong("WritebackVersion", 1);
        try {
            ShelfRecord.read(r);
            fail();
        } catch (IllegalStateException expected) {}
    }

    @Test
    public void pendingVersionInvariant() {
        NBTTagCompound r = record(UUID.randomUUID(), 1).write();
        r.setBoolean("WritebackPending", true);
        r.setLong("WritebackVersion", 5);
        try {
            ShelfRecord.read(r);
            fail();
        } catch (IllegalStateException expected) {}
    }

    @Test
    public void strictDeletePayload() {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("Kind", "DELETE");
        n.setString("ShelfMost", "bad");
        n.setLong("ShelfLeast", 1);
        n.setLong("Generation", 1);
        try {
            new PoppetWorldData().applyJournal(n);
            fail();
        } catch (IllegalStateException expected) {}
    }

    @Test
    public void strictPutPayload() {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("Kind", "PUT");
        n.setString("Record", "bad");
        try {
            new PoppetWorldData().applyJournal(n);
            fail();
        } catch (IllegalStateException expected) {}
    }
}
