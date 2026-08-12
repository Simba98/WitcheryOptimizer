package com.github.witcheryoptimizer.migration;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraftforge.common.DimensionManager;

/** Read-only, main-thread census of chunk NBT. It never asks a provider to load a chunk. */
public final class ShelfCensus {

    private static final String SHELF_TILE_ID = "witchery:poppetshelf";
    private static final Comparator<Entry> ENTRY_ORDER = Comparator.comparingInt((Entry value) -> value.dimension)
        .thenComparingInt(value -> value.regionX)
        .thenComparingInt(value -> value.regionZ)
        .thenComparingInt(value -> value.localZ)
        .thenComparingInt(value -> value.localX)
        .thenComparingInt(value -> value.tileSequence);

    private ShelfCensus() {}

    public static Snapshot scan(File saveRoot, Iterable<Integer> observed) throws IOException {
        Set<Integer> ids = new HashSet<>(Arrays.asList(DimensionManager.getStaticDimensionIDs()));
        ids.addAll(Arrays.asList(DimensionManager.getIDs()));
        for (WorldServer world : DimensionManager.getWorlds()) if (world != null) ids.add(world.provider.dimensionId);
        for (Integer id : observed) ids.add(id);
        Map<Integer, String> folders = new HashMap<>();
        folders.put(0, null);
        for (Integer id : ids) {
            if (id == 0) continue;
            WorldProvider provider;
            try {
                provider = DimensionManager.createProviderFor(id);
            } catch (RuntimeException exception) {
                throw new IOException("Cannot create provider for registered dimension " + id, exception);
            }
            if (provider == null || provider.getSaveFolder() == null)
                throw new IOException("Registered dimension " + id + " has no unambiguous save folder");
            folders.put(id, provider.getSaveFolder());
        }
        return scanFolders(saveRoot, folders);
    }

    static Snapshot scanFolders(File root, Map<Integer, String> folders) throws IOException {
        Map<String, Integer> reverse = new HashMap<>();
        for (Map.Entry<Integer, String> entry : folders.entrySet()) {
            String folder = entry.getValue();
            if (folder == null) continue;
            Integer duplicate = reverse.put(folder.toLowerCase(), entry.getKey());
            if (duplicate != null && duplicate.intValue() != entry.getKey())
                throw new IOException("Ambiguous dimension folder " + folder);
        }
        File[] children = root.listFiles(File::isDirectory);
        if (children == null) throw new IOException("Cannot enumerate save root " + root);
        for (File child : children) if (child.getName()
            .matches("DIM-?\\d+")
            && !reverse.containsKey(
                child.getName()
                    .toLowerCase()))
            throw new IOException("Unknown dimension folder " + child.getName());

        List<DimensionFolder> dimensions = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : folders.entrySet()) dimensions.add(
            new DimensionFolder(entry.getKey(), entry.getValue() == null ? root : new File(root, entry.getValue())));
        Collections.sort(dimensions, Comparator.comparingInt(value -> value.id));
        List<Entry> result = new ArrayList<>();
        Map<UUID, DropEvidence> drops = new HashMap<>();
        for (DimensionFolder dimension : dimensions) scanDimension(dimension, result, drops);
        Collections.sort(result, ENTRY_ORDER);
        return new Snapshot(result, drops);
    }

    static List<Entry> extract(int dimension, int regionX, int regionZ, int localX, int localZ, NBTTagCompound chunk)
        throws IOException {
        if (!chunk.hasKey("Level", 10)) throw new IOException("Chunk has no Level compound");
        NBTTagCompound level = chunk.getCompoundTag("Level");
        if (!level.hasKey("TileEntities", 9)) throw new IOException("Chunk has no TileEntities list");
        return extractTiles(dimension, regionX, regionZ, localX, localZ, level);
    }

    static Snapshot extractSnapshot(int dimension, int regionX, int regionZ, int localX, int localZ,
        NBTTagCompound chunk) throws IOException {
        if (!chunk.hasKey("Level", 10)) throw new IOException("Chunk has no Level compound");
        NBTTagCompound level = chunk.getCompoundTag("Level");
        if (!level.hasKey("TileEntities", 9)) throw new IOException("Chunk has no TileEntities list");
        List<Entry> entries = extractTiles(dimension, regionX, regionZ, localX, localZ, level);
        if (!level.hasKey("Entities", 9)) throw new IOException("Chunk has no Entities list");
        Map<UUID, DropEvidence> drops = new HashMap<>();
        NBTTagList entities = level.getTagList("Entities", 10);
        for (int sequence = 0; sequence < entities.tagCount(); sequence++) {
            NBTTagCompound entity = entities.getCompoundTagAt(sequence);
            if (!entity.hasKey("ForgeData", 10)) continue;
            NBTTagCompound forgeData = entity.getCompoundTag("ForgeData");
            boolean most = forgeData.hasKey("WORemovalMost");
            boolean least = forgeData.hasKey("WORemovalLeast");
            boolean ordinal = forgeData.hasKey("WODropOrdinal");
            boolean locked = forgeData.hasKey("WORemovalLocked");
            if (!most && !least && !ordinal && !locked) continue;
            if (!most || !least
                || !ordinal
                || !locked
                || !forgeData.getBoolean("WORemovalLocked")
                || !"Item".equals(entity.getString("id"))
                || !entity.hasKey("Item", 10)) throw new IOException("Malformed Witchery Optimizer removal drop");
            UUID transaction = new UUID(forgeData.getLong("WORemovalMost"), forgeData.getLong("WORemovalLeast"));
            NBTTagCompound stack = entity.getCompoundTag("Item");
            int count = stack.getByte("Count") & 255;
            if (!stack.hasKey("id") || count <= 0) throw new IOException("Corrupt removal drop " + transaction);
            DropEvidence evidence = drops.computeIfAbsent(transaction, ignored -> new DropEvidence());
            if (evidence.items.put(forgeData.getInteger("WODropOrdinal"), (NBTTagCompound) stack.copy()) != null)
                throw new IOException("Duplicate removal drop ordinal for " + transaction);
        }
        return new Snapshot(entries, drops);
    }

    private static List<Entry> extractTiles(int dimension, int regionX, int regionZ, int localX, int localZ,
        NBTTagCompound level) {
        NBTTagList tiles = level.getTagList("TileEntities", 10);
        List<Entry> entries = new ArrayList<>();
        for (int sequence = 0; sequence < tiles.tagCount(); sequence++) {
            NBTTagCompound tile = tiles.getCompoundTagAt(sequence);
            if (isShelf(tile)) entries.add(new Entry(dimension, regionX, regionZ, localX, localZ, sequence, tile));
        }
        return entries;
    }

    static void sortEntries(List<Entry> entries) {
        Collections.sort(entries, ENTRY_ORDER);
    }

    private static boolean isShelf(NBTTagCompound tile) {
        String id = tile.getString("id");
        return SHELF_TILE_ID.equals(id);
    }

    private static void scanDimension(DimensionFolder dimension, List<Entry> result, Map<UUID, DropEvidence> drops)
        throws IOException {
        File regionDirectory = new File(dimension.folder, "region");
        File[] files = regionDirectory.listFiles((directory, name) -> name.matches("r\\.-?\\d+\\.-?\\d+\\.mca"));
        if (files == null) {
            if (regionDirectory.isDirectory()) throw new IOException("Cannot enumerate " + regionDirectory);
            return;
        }
        Arrays.sort(
            files,
            Comparator.comparingInt(ShelfCensus::regionX)
                .thenComparingInt(ShelfCensus::regionZ));
        for (File file : files) scanRegion(dimension.id, file, result, drops);
    }

    private static int regionX(File file) {
        return Integer.parseInt(
            file.getName()
                .split("\\.")[1]);
    }

    private static int regionZ(File file) {
        return Integer.parseInt(
            file.getName()
                .split("\\.")[2]);
    }

    private static void scanRegion(int dimension, File file, List<Entry> result, Map<UUID, DropEvidence> drops)
        throws IOException {
        int regionX = regionX(file);
        int regionZ = regionZ(file);
        RegionFile region = new RegionFile(file);
        try {
            for (int localZ = 0; localZ < 32; localZ++) for (int localX = 0; localX < 32; localX++) {
                DataInputStream input = region.getChunkDataInputStream(localX, localZ);
                if (input == null) continue;
                try {
                    NBTTagCompound chunk = CompressedStreamTools.read(input);
                    if (chunk == null) throw new IOException("Empty chunk NBT in " + file);
                    Snapshot found = extractSnapshot(dimension, regionX, regionZ, localX, localZ, chunk);
                    result.addAll(found.entries);
                    mergeDrops(drops, found.drops);
                } catch (RuntimeException exception) {
                    throw new IOException("Corrupt chunk in " + file + " at " + localX + "," + localZ, exception);
                } finally {
                    input.close();
                }
            }
        } finally {
            region.close();
        }
    }

    private static void mergeDrops(Map<UUID, DropEvidence> target, Map<UUID, DropEvidence> source) throws IOException {
        for (Map.Entry<UUID, DropEvidence> entry : source.entrySet()) {
            DropEvidence accumulated = target.computeIfAbsent(entry.getKey(), ignored -> new DropEvidence());
            for (Map.Entry<Integer, NBTTagCompound> item : entry.getValue().items.entrySet())
                if (accumulated.items.put(item.getKey(), item.getValue()) != null)
                    throw new IOException("Duplicate removal drop ordinal for " + entry.getKey());
        }
    }

    public static final class Snapshot {

        public final List<Entry> entries;
        public final Map<UUID, DropEvidence> drops;

        Snapshot(List<Entry> entries, Map<UUID, DropEvidence> drops) {
            this.entries = entries;
            this.drops = drops;
        }
    }

    public static final class DropEvidence {

        private final Map<Integer, NBTTagCompound> items = new HashMap<>();

        public boolean isEmpty() {
            return items.isEmpty();
        }

        public boolean completelyMatches(ItemStack[] expected) {
            if (items.isEmpty()) {
                for (ItemStack stack : expected) if (stack != null && stack.stackSize > 0) return false;
                return true;
            }
            int ordinal = 0;
            for (Integer value : new java.util.TreeSet<>(items.keySet())) if (value != ordinal++) return false;
            Map<NBTTagCompound, Integer> expectedTotals = new HashMap<>();
            for (ItemStack stack : expected) {
                if (stack == null) continue;
                NBTTagCompound tag = new NBTTagCompound();
                stack.writeToNBT(tag);
                addTotal(expectedTotals, tag);
            }
            Map<NBTTagCompound, Integer> actualTotals = new HashMap<>();
            for (NBTTagCompound tag : items.values()) addTotal(actualTotals, tag);
            return expectedTotals.equals(actualTotals);
        }

        boolean completelyMatchesNbt(NBTTagCompound[] expected) {
            Map<NBTTagCompound, Integer> expectedTotals = new HashMap<>();
            for (NBTTagCompound tag : expected) if (tag != null) addTotal(expectedTotals, tag);
            Map<NBTTagCompound, Integer> actualTotals = new HashMap<>();
            for (NBTTagCompound tag : items.values()) addTotal(actualTotals, tag);
            return expectedTotals.equals(actualTotals);
        }

        private static void addTotal(Map<NBTTagCompound, Integer> totals, NBTTagCompound source) {
            NBTTagCompound identity = (NBTTagCompound) source.copy();
            int count = identity.getByte("Count") & 255;
            identity.setByte("Count", (byte) 1);
            totals.put(identity, totals.getOrDefault(identity, 0) + count);
        }
    }

    public static final class Entry {

        public final int dimension, regionX, regionZ, localX, localZ, tileSequence;
        public final NBTTagCompound tile;

        Entry(int dimension, int regionX, int regionZ, int localX, int localZ, int tileSequence, NBTTagCompound tile) {
            this.dimension = dimension;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.localX = localX;
            this.localZ = localZ;
            this.tileSequence = tileSequence;
            this.tile = (NBTTagCompound) tile.copy();
        }
    }

    private static final class DimensionFolder {

        final int id;
        final File folder;

        DimensionFolder(int id, File folder) {
            this.id = id;
            this.folder = folder;
        }
    }
}
