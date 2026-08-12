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

    public static final String NAME = "witcheryoptimizer_shelves";
    public static final int SCHEMA = 2;
    private final Map<UUID, ShelfRecord> shelves = new LinkedHashMap<>();
    private final Map<UUID, Long> tombstones = new LinkedHashMap<>();
    private final Map<UUID, ShelfLocation> tombstoneLocations = new LinkedHashMap<>();
    private long nextOrder;
    private ImportState importState = ImportState.UNKNOWN;

    public PoppetWorldData() {
        super(NAME);
    }

    public PoppetWorldData(String name) {
        super(name);
    }

    static PoppetWorldData get(World world) {
        PoppetWorldData data = (PoppetWorldData) world.mapStorage.loadData(PoppetWorldData.class, NAME);
        if (data == null) {
            data = new PoppetWorldData();
            world.mapStorage.setData(NAME, data);
        }
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound root) {
        shelves.clear();
        tombstones.clear();
        tombstoneLocations.clear();
        nextOrder = root.getLong("NextOrder");
        NBTTagList deleted = root.getTagList("Tombstones", 10);
        for (int i = 0; i < deleted.tagCount(); i++) {
            NBTTagCompound tag = deleted.getCompoundTagAt(i);
            UUID id = uuid(tag, "Shelf");
            tombstones.put(id, tag.getLong("Generation"));
            if (tag.hasKey("Location")) tombstoneLocations.put(id, ShelfLocation.read(tag.getCompoundTag("Location")));
        }
        NBTTagList list = root.getTagList("Shelves", 10);
        for (int i = 0; i < list.tagCount(); i++) install(ShelfRecord.read(list.getCompoundTagAt(i)));
        try {
            importState = ImportState.valueOf(root.getString("WitcheryImportState"));
        } catch (IllegalArgumentException exception) {
            importState = ImportState.UNKNOWN;
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound root) {
        root.setInteger("Schema", SCHEMA);
        root.setLong("NextOrder", nextOrder);
        root.setString("WitcheryImportState", importState.name());
        NBTTagList list = new NBTTagList();
        for (ShelfRecord record : shelves.values()) list.appendTag(record.write());
        root.setTag("Shelves", list);
        NBTTagList deleted = new NBTTagList();
        for (Map.Entry<UUID, Long> entry : tombstones.entrySet()) {
            NBTTagCompound tag = new NBTTagCompound();
            putUuid(tag, "Shelf", entry.getKey());
            tag.setLong("Generation", entry.getValue());
            ShelfLocation location = tombstoneLocations.get(entry.getKey());
            if (location != null) tag.setTag("Location", location.write());
            deleted.appendTag(tag);
        }
        root.setTag("Tombstones", deleted);
    }

    ShelfRecord get(UUID id) {
        return shelves.get(id);
    }

    Collection<ShelfRecord> records() {
        return new ArrayList<>(shelves.values());
    }

    boolean isTombstoned(UUID id) {
        return tombstones.containsKey(id);
    }

    boolean isTombstonedLocation(ShelfLocation location) {
        for (ShelfRecord record : shelves.values()) if (record.location.equals(location)) return false;
        return tombstoneLocations.containsValue(location);
    }

    void clearLocationTombstone(ShelfLocation location) {
        UUID remove = null;
        for (Map.Entry<UUID, ShelfLocation> entry : tombstoneLocations.entrySet()) if (entry.getValue()
            .equals(location)) {
                remove = entry.getKey();
                break;
            }
        if (remove != null) {
            tombstoneLocations.remove(remove);
            tombstones.remove(remove);
            markDirty();
        }
    }

    ShelfRecord newRecord(UUID id, ShelfLocation location, String name, ItemStack[] inventory) {
        return new ShelfRecord(id, location, name, nextOrder, inventory);
    }

    void install(ShelfRecord record) {
        Long deletion = tombstones.get(record.id);
        if (deletion != null && deletion >= record.version) return;
        if (deletion != null) {
            tombstones.remove(record.id);
            tombstoneLocations.remove(record.id);
        }
        ShelfRecord current = shelves.get(record.id);
        if (current == null || current.version <= record.version) shelves.put(record.id, record);
        nextOrder = Math.max(nextOrder, record.order + 1);
    }

    void delete(UUID id, long generation, ShelfLocation location) {
        ShelfRecord current = shelves.get(id);
        if (current != null && current.version > generation) return;
        shelves.remove(id);
        Long old = tombstones.get(id);
        if (old == null || old < generation) {
            tombstones.put(id, generation);
            if (location != null) tombstoneLocations.put(id, location);
        }
        markDirty();
    }

    void applyJournal(NBTTagCompound operation) {
        if ("DELETE".equals(operation.getString("Kind"))) delete(
            uuid(operation, "Shelf"),
            operation.getLong("Generation"),
            operation.hasKey("Location") ? ShelfLocation.read(operation.getCompoundTag("Location")) : null);
        else install(ShelfRecord.read(operation.getCompoundTag("Record")));
        markDirty();
    }

    ImportState importState() {
        return importState;
    }

    void setImportState(ImportState state) {
        importState = state;
        markDirty();
    }

    public enum ImportState {
        UNKNOWN,
        IN_PROGRESS,
        COMPLETE,
        FAILED
    }

    private static UUID uuid(NBTTagCompound tag, String prefix) {
        return new UUID(tag.getLong(prefix + "Most"), tag.getLong(prefix + "Least"));
    }

    private static void putUuid(NBTTagCompound tag, String prefix, UUID id) {
        tag.setLong(prefix + "Most", id.getMostSignificantBits());
        tag.setLong(prefix + "Least", id.getLeastSignificantBits());
    }

}
