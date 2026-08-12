package com.github.witcheryoptimizer.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final Map<UUID, UUID> committedRemovals = new LinkedHashMap<>();
    private final List<Integer> dimensionOrder = new ArrayList<>();
    private long nextOrder;
    private ImportState importState = ImportState.UNKNOWN;
    private CensusState censusState = CensusState.UNKNOWN;
    private int censusVersion;

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
        if (!root.hasKey("Schema") || root.getInteger("Schema") != SCHEMA) throw new IllegalStateException(
            "Unsupported WitcheryOptimizer v0.1/schema-1 world data; only schema " + SCHEMA + " is accepted");
        shelves.clear();
        tombstones.clear();
        tombstoneLocations.clear();
        committedRemovals.clear();
        dimensionOrder.clear();
        nextOrder = root.getLong("NextOrder");
        NBTTagList deleted = root.getTagList("Tombstones", 10);
        for (int i = 0; i < deleted.tagCount(); i++) {
            NBTTagCompound tag = deleted.getCompoundTagAt(i);
            UUID id = uuid(tag, "Shelf");
            tombstones.put(id, tag.getLong("Generation"));
            if (tag.hasKey("Location")) tombstoneLocations.put(id, ShelfLocation.read(tag.getCompoundTag("Location")));
            if (tag.hasKey("RemovalMost", 4) && tag.hasKey("RemovalLeast", 4))
                committedRemovals.put(new UUID(tag.getLong("RemovalMost"), tag.getLong("RemovalLeast")), id);
        }
        NBTTagList list = root.getTagList("Shelves", 10);
        for (int i = 0; i < list.tagCount(); i++) install(ShelfRecord.read(list.getCompoundTagAt(i)));
        int[] dimensions = root.getIntArray("DimensionOrder");
        for (int dimension : dimensions) dimensionOrder.add(dimension);
        importState = requiredState(root, "WitcheryImportState", ImportState.class);
        censusVersion = root.getInteger("CensusVersion");
        censusState = root.hasKey("CensusState") ? requiredState(root, "CensusState", CensusState.class)
            : CensusState.UNKNOWN;
    }

    @Override
    public void writeToNBT(NBTTagCompound root) {
        root.setInteger("Schema", SCHEMA);
        root.setLong("NextOrder", nextOrder);
        root.setString("WitcheryImportState", importState.name());
        root.setInteger("CensusVersion", censusVersion);
        root.setString("CensusState", censusState.name());
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
            for (Map.Entry<UUID, UUID> committed : committedRemovals.entrySet()) if (committed.getValue()
                .equals(entry.getKey())) {
                    tag.setLong(
                        "RemovalMost",
                        committed.getKey()
                            .getMostSignificantBits());
                    tag.setLong(
                        "RemovalLeast",
                        committed.getKey()
                            .getLeastSignificantBits());
                    break;
                }
            deleted.appendTag(tag);
        }
        root.setTag("Tombstones", deleted);
        int[] dimensions = new int[dimensionOrder.size()];
        for (int i = 0; i < dimensions.length; i++) dimensions[i] = dimensionOrder.get(i);
        root.setIntArray("DimensionOrder", dimensions);
    }

    ShelfRecord get(UUID id) {
        return shelves.get(id);
    }

    Collection<ShelfRecord> records() {
        return new ArrayList<>(shelves.values());
    }

    List<Integer> dimensionOrder() {
        return new ArrayList<>(dimensionOrder);
    }

    void observeDimension(int dimension) {
        if (!dimensionOrder.contains(dimension)) {
            dimensionOrder.add(dimension);
            markDirty();
        }
    }

    void normalizeDimensionOrder(List<Integer> ordered) {
        if (!dimensionOrder.equals(ordered)) {
            dimensionOrder.clear();
            dimensionOrder.addAll(ordered);
            markDirty();
        }
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
        for (ShelfRecord candidate : shelves.values())
            if (!candidate.id.equals(record.id) && candidate.location.equals(record.location))
                throw new IllegalStateException(
                    "Duplicate authoritative shelf location " + record.location
                        + " for "
                        + candidate.id
                        + " and "
                        + record.id);
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
        delete(id, generation, location, null);
    }

    void delete(UUID id, long generation, ShelfLocation location, UUID transaction) {
        ShelfRecord current = shelves.get(id);
        if (current != null && current.version > generation) return;
        shelves.remove(id);
        Long old = tombstones.get(id);
        if (old == null || old < generation) {
            tombstones.put(id, generation);
            if (location != null) tombstoneLocations.put(id, location);
            if (transaction != null) committedRemovals.put(transaction, id);
        }
        markDirty();
    }

    void applyJournal(NBTTagCompound operation) {
        if ("DELETE".equals(operation.getString("Kind"))) delete(
            uuid(operation, "Shelf"),
            operation.getLong("Generation"),
            operation.hasKey("Location") ? ShelfLocation.read(operation.getCompoundTag("Location")) : null,
            operation.hasKey("RemovalMost", 4) && operation.hasKey("RemovalLeast", 4)
                ? new UUID(operation.getLong("RemovalMost"), operation.getLong("RemovalLeast"))
                : null);
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

    CensusState censusState() {
        return censusState;
    }

    int censusVersion() {
        return censusVersion;
    }

    boolean censusComplete(int version) {
        return censusVersion == version && censusState == CensusState.COMPLETE;
    }

    void setCensusState(int version, CensusState state) {
        censusVersion = version;
        censusState = state;
        markDirty();
    }

    int pendingWritebacks() {
        int count = 0;
        for (ShelfRecord record : shelves.values()) if (record.writebackPending) count++;
        return count;
    }

    boolean hasPreparedRemovals() {
        for (ShelfRecord record : shelves.values()) if (record.state != ShelfRecord.State.ACTIVE) return true;
        return false;
    }

    boolean hasCleanupPendingRemovals() {
        for (ShelfRecord record : shelves.values())
            if (record.state == ShelfRecord.State.REMOVAL_CLEANUP_PENDING) return true;
        return false;
    }

    boolean isCommittedRemoval(UUID transaction) {
        return transaction != null && committedRemovals.containsKey(transaction);
    }

    public enum ImportState {
        UNKNOWN,
        IN_PROGRESS,
        COMPLETE,
        FAILED
    }

    public enum CensusState {
        UNKNOWN,
        IN_PROGRESS,
        COMPLETE,
        FAILED
    }

    private static <E extends Enum<E>> E requiredState(NBTTagCompound root, String key, Class<E> type) {
        if (!root.hasKey(key)) throw new IllegalStateException("Missing optimizer state " + key);
        try {
            return Enum.valueOf(type, root.getString(key));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid optimizer state " + key + "=" + root.getString(key), exception);
        }
    }

    private static UUID uuid(NBTTagCompound tag, String prefix) {
        return new UUID(tag.getLong(prefix + "Most"), tag.getLong(prefix + "Least"));
    }

    private static void putUuid(NBTTagCompound tag, String prefix, UUID id) {
        tag.setLong(prefix + "Most", id.getMostSignificantBits());
        tag.setLong(prefix + "Least", id.getLeastSignificantBits());
    }

}
