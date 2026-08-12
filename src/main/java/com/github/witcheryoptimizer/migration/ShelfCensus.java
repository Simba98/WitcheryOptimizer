package com.github.witcheryoptimizer.migration;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern DIMENSION_FOLDER = Pattern.compile("^DIM(-?\\d+)$");
    private static final Comparator<Entry> ENTRY_ORDER = Comparator.comparingInt((Entry value) -> value.dimension)
        .thenComparingInt(value -> value.regionX)
        .thenComparingInt(value -> value.regionZ)
        .thenComparingInt(value -> value.localZ)
        .thenComparingInt(value -> value.localX)
        .thenComparingInt(value -> value.tileSequence);

    private ShelfCensus() {}

    public static Snapshot scan(File saveRoot, Iterable<Integer> observed) throws IOException {
        Map<Integer, String> folders = discoverStandardDimensionFolders(saveRoot);
        List<Integer> registered = new ArrayList<>();
        for (Integer id : DimensionManager.getStaticDimensionIDs())
            if (DimensionManager.isDimensionRegistered(id) && !registered.contains(id)) registered.add(id);
        for (Integer id : DimensionManager.getIDs())
            if (DimensionManager.isDimensionRegistered(id) && !registered.contains(id)) registered.add(id);
        for (WorldServer world : DimensionManager.getWorlds())
            if (world != null && DimensionManager.isDimensionRegistered(world.provider.dimensionId)
                && !registered.contains(world.provider.dimensionId)) registered.add(world.provider.dimensionId);
        for (Integer id : registered) {
            if (id == 0 || !DimensionManager.isDimensionRegistered(id)) continue;
            WorldProvider provider;
            try {
                provider = DimensionManager.createProviderFor(id);
            } catch (RuntimeException exception) {
                throw new CensusException(
                    "Cannot resolve provider folder for registered dimension " + id,
                    false,
                    exception);
            }
            if (provider == null || provider.getSaveFolder() == null)
                throw new CensusException("Registered dimension " + id + " has no unambiguous save folder", true);
            mergeFolder(saveRoot, folders, id, provider.getSaveFolder());
        }
        for (Integer id : observed) if (id != null && id != 0 && !folders.containsKey(id)) folders.put(id, "DIM" + id);
        return scanFolders(saveRoot, folders);
    }

    static Snapshot scanResolved(File root, Iterable<Integer> observed, Map<Integer, String> registeredFolders)
        throws IOException {
        Map<Integer, String> folders = discoverStandardDimensionFolders(root);
        for (Map.Entry<Integer, String> registered : registeredFolders.entrySet())
            mergeFolder(root, folders, registered.getKey(), registered.getValue());
        for (Integer id : observed) if (id != null && id != 0 && !folders.containsKey(id)) folders.put(id, "DIM" + id);
        return scanFolders(root, folders);
    }

    private static void mergeFolder(File root, Map<Integer, String> folders, int id, String folder) throws IOException {
        String existing = folders.get(id);
        if (folders.containsKey(id) && !sameFolder(root, existing, folder))
            throw new CensusException("Dimension " + id + " maps to both " + existing + " and " + folder);
        folders.put(id, folder);
    }

    private static Map<Integer, String> discoverStandardDimensionFolders(File root) throws IOException {
        Map<Integer, String> result = new HashMap<>();
        result.put(0, null);
        File[] children = root.listFiles(File::isDirectory);
        if (children == null) throw new IOException("Cannot enumerate save root " + root);
        for (File child : children) {
            Matcher matcher = DIMENSION_FOLDER.matcher(child.getName());
            if (!matcher.matches()) continue;
            try {
                mergeFolder(root, result, Integer.parseInt(matcher.group(1)), child.getName());
            } catch (NumberFormatException exception) {
                if (containsRegionData(child))
                    throw new CensusException("Overflow dimension folder with region data: " + child.getName());
            }
        }
        return result;
    }

    static Snapshot scanFolders(File root, Map<Integer, String> folders) throws IOException {
        Map<Integer, String> merged = new HashMap<>(folders);
        for (Map.Entry<Integer, String> discovered : discoverDimensionFolders(root, folders).entrySet()) {
            String existing = merged.get(discovered.getKey());
            if (merged.containsKey(discovered.getKey()) && !sameFolder(root, existing, discovered.getValue()))
                throw new CensusException(
                    "Dimension " + discovered.getKey() + " maps to both " + existing + " and " + discovered.getValue());
            merged.put(discovered.getKey(), discovered.getValue());
        }
        File canonicalRoot = root.getCanonicalFile();
        Map<String, Integer> reverse = new HashMap<>();
        Map<Integer, String> normalized = new HashMap<>();
        for (Map.Entry<Integer, String> entry : merged.entrySet()) {
            File target = entry.getValue() == null ? canonicalRoot
                : new File(canonicalRoot, entry.getValue()).getCanonicalFile();
            if (!inside(canonicalRoot, target))
                throw new CensusException("Dimension folder escapes save root: " + entry.getValue());
            String canonical = target.getPath();
            Integer duplicate = reverse.put(canonical.toLowerCase(java.util.Locale.ROOT), entry.getKey());
            if (duplicate != null && duplicate.intValue() != entry.getKey())
                throw new CensusException("Ambiguous dimension folder " + entry.getValue());
            normalized.put(
                entry.getKey(),
                canonicalRoot.equals(target) ? null
                    : canonicalRoot.toPath()
                        .relativize(target.toPath())
                        .toString());
        }

        List<DimensionFolder> dimensions = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : normalized.entrySet()) dimensions.add(
            new DimensionFolder(entry.getKey(), entry.getValue() == null ? root : new File(root, entry.getValue())));
        Collections.sort(dimensions, Comparator.comparingInt(value -> value.id));
        List<Entry> result = new ArrayList<>();
        Map<UUID, DropEvidence> drops = new HashMap<>();
        for (DimensionFolder dimension : dimensions) scanDimension(dimension, result, drops);
        Collections.sort(result, ENTRY_ORDER);
        return new Snapshot(result, drops, normalized);
    }

    private static Map<Integer, String> discoverDimensionFolders(File root, Map<Integer, String> known)
        throws IOException {
        File[] children = root.listFiles(File::isDirectory);
        if (children == null) throw new IOException("Cannot enumerate save root " + root);
        Map<Integer, String> result = new HashMap<>();
        result.put(0, null);
        for (File child : children) {
            boolean regions = containsRegionData(child);
            Matcher matcher = DIMENSION_FOLDER.matcher(child.getName());
            if (matcher.matches()) {
                final int id;
                try {
                    id = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException exception) {
                    if (regions)
                        throw new CensusException("Overflow dimension folder with region data: " + child.getName());
                    continue;
                }
                String old = result.put(id, child.getName());
                if (old != null && !old.equals(child.getName())) throw new CensusException(
                    "Multiple folders claim dimension " + id + ": " + old + " and " + child.getName());
            } else if (regions && !isMappedCustomFolder(root, child, known)) {
                String kind = child.getName()
                    .startsWith("DIM") ? "Malformed DIM-like" : "Unmapped custom";
                throw new CensusException(kind + " folder with region data: " + child.getName());
            }
        }
        return result;
    }

    private static boolean isMappedCustomFolder(File root, File child, Map<Integer, String> known) throws IOException {
        File candidate = child.getCanonicalFile();
        for (String folder : known.values())
            if (folder != null && candidate.equals(new File(root, folder).getCanonicalFile())) return true;
        return false;
    }

    private static boolean sameFolder(File root, String first, String second) throws IOException {
        File a = first == null ? root.getCanonicalFile() : new File(root, first).getCanonicalFile();
        File b = second == null ? root.getCanonicalFile() : new File(root, second).getCanonicalFile();
        return a.equals(b);
    }

    private static boolean containsRegionData(File folder) {
        File region = new File(folder, "region");
        File[] files = region.listFiles((directory, name) -> name.endsWith(".mca") || name.endsWith(".mcr"));
        return files != null && files.length > 0;
    }

    private static boolean inside(File root, File child) {
        return child.equals(root) || child.toPath()
            .startsWith(root.toPath());
    }

    public static final class CensusException extends IOException {

        private final boolean corruption;

        public CensusException(String message) {
            this(message, true, null);
        }

        public CensusException(String message, boolean corruption) {
            this(message, corruption, null);
        }

        public CensusException(String message, boolean corruption, Throwable cause) {
            super(message, cause);
            this.corruption = corruption;
        }

        public boolean isCorruption() {
            return corruption;
        }
    }

    static List<Entry> extract(int dimension, int regionX, int regionZ, int localX, int localZ, NBTTagCompound chunk)
        throws IOException {
        if (!chunk.hasKey("Level", 10)) throw new CensusException("Chunk has no Level compound");
        NBTTagCompound level = chunk.getCompoundTag("Level");
        if (!level.hasKey("TileEntities", 9)) throw new CensusException("Chunk has no TileEntities list");
        return extractTiles(dimension, regionX, regionZ, localX, localZ, level);
    }

    static Snapshot extractSnapshot(int dimension, int regionX, int regionZ, int localX, int localZ,
        NBTTagCompound chunk) throws IOException {
        if (!chunk.hasKey("Level", 10)) throw new CensusException("Chunk has no Level compound");
        NBTTagCompound level = chunk.getCompoundTag("Level");
        if (!level.hasKey("TileEntities", 9)) throw new CensusException("Chunk has no TileEntities list");
        List<Entry> entries = extractTiles(dimension, regionX, regionZ, localX, localZ, level);
        if (!level.hasKey("Entities", 9)) throw new CensusException("Chunk has no Entities list");
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
                || !entity.hasKey("Item", 10)) throw new CensusException("Malformed Witchery Optimizer removal drop");
            UUID transaction = new UUID(forgeData.getLong("WORemovalMost"), forgeData.getLong("WORemovalLeast"));
            NBTTagCompound stack = entity.getCompoundTag("Item");
            int count = stack.getByte("Count") & 255;
            if (!stack.hasKey("id") || count <= 0) throw new CensusException("Corrupt removal drop " + transaction);
            DropEvidence evidence = drops.computeIfAbsent(transaction, ignored -> new DropEvidence());
            if (evidence.items.put(forgeData.getInteger("WODropOrdinal"), (NBTTagCompound) stack.copy()) != null)
                throw new CensusException("Duplicate removal drop ordinal for " + transaction);
        }
        Map<Integer, String> dimensions = new HashMap<>();
        dimensions.put(dimension, dimension == 0 ? null : "DIM" + dimension);
        return new Snapshot(entries, drops, dimensions);
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
                    if (chunk == null) throw new CensusException("Empty chunk NBT in " + file);
                    Snapshot found = extractSnapshot(dimension, regionX, regionZ, localX, localZ, chunk);
                    result.addAll(found.entries);
                    mergeDrops(drops, found.drops);
                } catch (RuntimeException exception) {
                    throw new CensusException(
                        "Corrupt chunk in " + file + " at " + localX + "," + localZ,
                        true,
                        exception);
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
                    throw new CensusException("Duplicate removal drop ordinal for " + entry.getKey());
        }
    }

    public static final class Snapshot {

        public final List<Entry> entries;
        public final Map<UUID, DropEvidence> drops;
        public final Map<Integer, String> dimensions;

        Snapshot(List<Entry> entries, Map<UUID, DropEvidence> drops, Map<Integer, String> dimensions) {
            this.entries = entries;
            this.drops = drops;
            this.dimensions = Collections.unmodifiableMap(new HashMap<>(dimensions));
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
