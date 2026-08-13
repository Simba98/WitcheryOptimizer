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
    private final List<Integer> dimensionOrder = new ArrayList<>();
    private long nextOrder;
    private ImportState importState = ImportState.UNKNOWN;
    private CensusState censusState = CensusState.UNKNOWN;
    private int censusVersion;
    private int retryAttempt;
    private long retryAt;
    private boolean retryCorruption;
    private String retryReason = "";

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
        if (!root.hasKey("Schema", 3)) throw new IllegalStateException(
            "Unsupported WitcheryOptimizer v0.1/schema-1 world data; only schema " + SCHEMA + " is accepted");
        if (root.getInteger("Schema") != SCHEMA) throw new IllegalStateException(
            "Unsupported WitcheryOptimizer v0.1/schema-1 world data; only schema " + SCHEMA + " is accepted");
        StrictNbt.require(root, "NextOrder", 4);
        StrictNbt.require(root, "Tombstones", 9);
        StrictNbt.require(root, "Shelves", 9);
        StrictNbt.require(root, "DimensionOrder", 11);
        shelves.clear();
        tombstones.clear();
        tombstoneLocations.clear();
        dimensionOrder.clear();
        nextOrder = StrictNbt.nonnegativeLong(root, "NextOrder");
        NBTTagList deleted = StrictNbt.list(root, "Tombstones", 10);
        for (int i = 0; i < deleted.tagCount(); i++) {
            NBTTagCompound tag = deleted.getCompoundTagAt(i);
            UUID id = uuid(tag, "Shelf");
            long generation = StrictNbt.nonnegativeLong(tag, "Generation");
            if (tombstones.put(id, generation) != null)
                throw new IllegalStateException("Duplicate shelf tombstone " + id);
            if (tag.hasKey("Location")) {
                StrictNbt.require(tag, "Location", 10);
                tombstoneLocations.put(id, ShelfLocation.read(tag.getCompoundTag("Location")));
            }
        }
        NBTTagList list = StrictNbt.list(root, "Shelves", 10);
        for (int i = 0; i < list.tagCount(); i++) install(ShelfRecord.read(list.getCompoundTagAt(i)));
        int[] dimensions = root.getIntArray("DimensionOrder");
        for (int dimension : dimensions) {
            if (dimensionOrder.contains(dimension))
                throw new IllegalStateException("Duplicate dimension order " + dimension);
            dimensionOrder.add(dimension);
        }
        importState = requiredState(root, "WitcheryImportState", ImportState.class);
        if (root.hasKey("CensusVersion")) censusVersion = StrictNbt.nonnegativeInt(root, "CensusVersion");
        else censusVersion = 0;
        censusState = root.hasKey("CensusState") ? requiredState(root, "CensusState", CensusState.class)
            : CensusState.UNKNOWN;
        retryAttempt = root.hasKey("CensusRetryAttempt") ? StrictNbt.nonnegativeInt(root, "CensusRetryAttempt") : 0;
        if (root.hasKey("CensusRetryAt")) StrictNbt.require(root, "CensusRetryAt", 4);
        retryAt = root.getLong("CensusRetryAt");
        if (retryAt < 0) throw new IllegalStateException("Negative validation retry deadline");
        if (root.hasKey("CensusRetryCorruption")) StrictNbt.require(root, "CensusRetryCorruption", 1);
        retryCorruption = root.getBoolean("CensusRetryCorruption");
        if (root.hasKey("CensusRetryReason")) StrictNbt.require(root, "CensusRetryReason", 8);
        retryReason = root.getString("CensusRetryReason");
        if (censusState == CensusState.IN_PROGRESS || censusState == CensusState.FAILED)
            censusState = CensusState.RETRY_WAIT;
    }

    @Override
    public void writeToNBT(NBTTagCompound root) {
        root.setInteger("Schema", SCHEMA);
        root.setLong("NextOrder", nextOrder);
        root.setString("WitcheryImportState", importState.name());
        root.setInteger("CensusVersion", censusVersion);
        root.setString("CensusState", censusState.name());
        root.setInteger("CensusRetryAttempt", retryAttempt);
        root.setLong("CensusRetryAt", retryAt);
        root.setBoolean("CensusRetryCorruption", retryCorruption);
        root.setString("CensusRetryReason", retryReason);
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
        }
        markDirty();
    }

    void applyJournal(NBTTagCompound operation) {
        StrictNbt.require(operation, "Kind", 8);
        String kind = operation.getString("Kind");
        if ("DELETE".equals(kind)) delete(
            uuid(operation, "Shelf"),
            StrictNbt.nonnegativeLong(operation, "Generation"),
            location(operation),
            StrictNbt.optionalPair(operation, "RemovalMost", "RemovalLeast", 4)
                ? new UUID(operation.getLong("RemovalMost"), operation.getLong("RemovalLeast"))
                : null);
        else if ("PUT".equals(kind)) {
            StrictNbt.require(operation, "Record", 10);
            install(ShelfRecord.read(operation.getCompoundTag("Record")));
        } else throw new IllegalStateException("Invalid journal operation kind " + kind);
        markDirty();
    }

    private static ShelfLocation location(NBTTagCompound operation) {
        if (!operation.hasKey("Location")) return null;
        StrictNbt.require(operation, "Location", 10);
        return ShelfLocation.read(operation.getCompoundTag("Location"));
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
        if (state == CensusState.COMPLETE) {
            retryAttempt = 0;
            retryAt = 0;
            retryCorruption = false;
            retryReason = "";
        }
        markDirty();
    }

    void setCensusRetry(int version, int attempt, long at, boolean corruption, String reason) {
        censusVersion = version;
        censusState = CensusState.RETRY_WAIT;
        retryAttempt = Math.max(1, attempt);
        retryAt = at;
        retryCorruption = corruption;
        retryReason = reason == null ? "" : reason.substring(0, Math.min(160, reason.length()));
        markDirty();
    }

    int retryAttempt() {
        return retryAttempt;
    }

    long retryAt(long now) {
        return RetryPolicy.clampDeadline(now, retryAt, retryCorruption);
    }

    boolean retryDue(long now) {
        return RetryPolicy.due(now, retryAt, retryCorruption);
    }

    boolean retryCorruption() {
        return retryCorruption;
    }

    String retryReason() {
        return retryReason;
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

    public enum ImportState {
        UNKNOWN,
        IN_PROGRESS,
        COMPLETE,
        FAILED,
        DRAINED_CLEAN,
        DRAINED_WITH_GAPS
    }

    public enum CensusState {
        UNKNOWN,
        IN_PROGRESS,
        COMPLETE,
        FAILED,
        RETRY_WAIT
    }

    private static <E extends Enum<E>> E requiredState(NBTTagCompound root, String key, Class<E> type) {
        StrictNbt.require(root, key, 8);
        try {
            return Enum.valueOf(type, root.getString(key));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid optimizer state " + key + "=" + root.getString(key), exception);
        }
    }

    private static UUID uuid(NBTTagCompound tag, String prefix) {
        StrictNbt.require(tag, prefix + "Most", 4);
        StrictNbt.require(tag, prefix + "Least", 4);
        return new UUID(tag.getLong(prefix + "Most"), tag.getLong(prefix + "Least"));
    }

    private static void putUuid(NBTTagCompound tag, String prefix, UUID id) {
        tag.setLong(prefix + "Most", id.getMostSignificantBits());
        tag.setLong(prefix + "Least", id.getLeastSignificantBits());
    }

}
