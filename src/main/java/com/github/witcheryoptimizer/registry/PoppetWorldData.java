package com.github.witcheryoptimizer.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

public final class PoppetWorldData extends WorldSavedData {

    public static final String NAME = "witcheryoptimizer_shelves_v3";
    public static final int SCHEMA = 3;
    private final Map<UUID, ShelfRecord> shelves = new LinkedHashMap<>();
    private final Map<UUID, Long> tombstones = new LinkedHashMap<>();
    private long nextOrder;

    public PoppetWorldData() {
        super(NAME);
    }

    public PoppetWorldData(String name) {
        super(name);
    }

    static PoppetWorldData get(World world) {
        PoppetWorldData value = (PoppetWorldData) world.mapStorage.loadData(PoppetWorldData.class, NAME);
        if (value == null) {
            value = new PoppetWorldData();
            world.mapStorage.setData(NAME, value);
        }
        return value;
    }

    @Override
    public void readFromNBT(NBTTagCompound root) {
        StrictNbt.require(root, "Schema", 3);
        if (root.getInteger("Schema") != SCHEMA) throw new IllegalStateException("Only v0.3 schema 3 is supported");
        StrictNbt.require(root, "NextOrder", 4);
        shelves.clear();
        tombstones.clear();
        nextOrder = StrictNbt.nonnegativeLong(root, "NextOrder");
        NBTTagList list = StrictNbt.list(root, "Shelves", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            ShelfRecord record = ShelfRecord.read(list.getCompoundTagAt(i));
            if (shelves.containsKey(record.id)) throw new IllegalStateException("Duplicate shelf UUID " + record.id);
            install(record);
        }
        NBTTagList deleted = StrictNbt.list(root, "Tombstones", 10);
        for (int i = 0; i < deleted.tagCount(); i++) {
            NBTTagCompound tag = deleted.getCompoundTagAt(i);
            StrictNbt.require(tag, "UuidMost", 4);
            StrictNbt.require(tag, "UuidLeast", 4);
            UUID id = new UUID(tag.getLong("UuidMost"), tag.getLong("UuidLeast"));
            if (tombstones.put(id, StrictNbt.nonnegativeLong(tag, "Version")) != null)
                throw new IllegalStateException("Duplicate tombstone " + id);
            ShelfRecord live = shelves.get(id);
            if (live != null) {
                long deletion = tombstones.get(id);
                if (deletion >= live.version) shelves.remove(id);
                else throw new IllegalStateException("Live shelf conflicts with older tombstone " + id);
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound root) {
        root.setInteger("Schema", SCHEMA);
        root.setLong("NextOrder", nextOrder);
        NBTTagList list = new NBTTagList();
        for (ShelfRecord r : shelves.values()) list.appendTag(r.write());
        root.setTag("Shelves", list);
        NBTTagList deleted = new NBTTagList();
        for (Map.Entry<UUID, Long> e : tombstones.entrySet()) {
            NBTTagCompound t = new NBTTagCompound();
            t.setLong(
                "UuidMost",
                e.getKey()
                    .getMostSignificantBits());
            t.setLong(
                "UuidLeast",
                e.getKey()
                    .getLeastSignificantBits());
            t.setLong("Version", e.getValue());
            deleted.appendTag(t);
        }
        root.setTag("Tombstones", deleted);
    }

    ShelfRecord get(UUID id) {
        return shelves.get(id);
    }

    Collection<ShelfRecord> records() {
        return new ArrayList<>(shelves.values());
    }

    ShelfRecord newRecord(UUID id, ShelfLocation at, String name, ItemStack[] inventory) {
        return new ShelfRecord(id, at, name, nextOrder, inventory);
    }

    void install(ShelfRecord record) {
        Long dead = tombstones.get(record.id);
        if (dead != null && dead >= record.version) return;
        for (ShelfRecord other : shelves.values())
            if (!other.id.equals(record.id) && other.location.equals(record.location))
                throw new IllegalStateException("Duplicate authority location " + record.location);
        ShelfRecord old = shelves.get(record.id);
        if (old == null || old.version <= record.version) shelves.put(record.id, record);
        nextOrder = Math.max(nextOrder, record.order + 1);
    }

    void delete(ShelfRecord record) {
        shelves.remove(record.id);
        tombstones.put(record.id, record.version + 1);
        markDirty();
    }

    boolean tombstoned(UUID id) {
        return tombstones.containsKey(id);
    }

    int pendingWritebacks() {
        int n = 0;
        for (ShelfRecord r : shelves.values()) if (r.writebackPending) n++;
        return n;
    }

    void applyJournal(NBTTagCompound operation) {
        StrictNbt.require(operation, "Kind", 8);
        if ("PUT".equals(operation.getString("Kind"))) {
            StrictNbt.require(operation, "Record", 10);
            install(ShelfRecord.read(operation.getCompoundTag("Record")));
        } else if ("DELETE".equals(operation.getString("Kind"))) {
            StrictNbt.require(operation, "ShelfMost", 4);
            StrictNbt.require(operation, "ShelfLeast", 4);
            UUID id = new UUID(operation.getLong("ShelfMost"), operation.getLong("ShelfLeast"));
            shelves.remove(id);
            tombstones.put(id, StrictNbt.nonnegativeLong(operation, "Generation"));
        } else throw new IllegalStateException("Invalid journal operation");
        markDirty();
    }
}
