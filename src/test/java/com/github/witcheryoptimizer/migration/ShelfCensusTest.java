package com.github.witcheryoptimizer.migration;

import static org.junit.Assert.*;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Test;

public class ShelfCensusTest {

    @Test
    public void extractsShelfInPhysicalTileSequenceWithFullNbt() throws Exception {
        NBTTagCompound chunk = new NBTTagCompound();
        NBTTagCompound level = new NBTTagCompound();
        NBTTagList tiles = new NBTTagList();
        NBTTagCompound other = new NBTTagCompound();
        other.setString("id", "Chest");
        tiles.appendTag(other);
        NBTTagCompound shelf = new NBTTagCompound();
        shelf.setString("id", "witchery:poppetshelf");
        shelf.setInteger("x", 547);
        shelf.setInteger("y", 70);
        shelf.setInteger("z", -461);
        shelf.setString("CustomName", "complete");
        shelf.setTag("Items", new NBTTagList());
        tiles.appendTag(shelf);
        level.setTag("TileEntities", tiles);
        chunk.setTag("Level", level);

        java.util.List<ShelfCensus.Entry> found = ShelfCensus.extract(7, 1, -1, 2, 3, chunk);
        assertEquals(1, found.size());
        assertEquals(1, found.get(0).tileSequence);
        assertEquals("complete", found.get(0).tile.getString("CustomName"));
    }

    @Test
    public void shelfCoordinatesMustMatchPhysicalChunkIncludingNegativeCoordinates() throws Exception {
        NBTTagCompound valid = shelf(547, 255, -461);
        ShelfCensus.validateShelfCoordinates(1, -1, 2, 3, valid);
        for (NBTTagCompound invalid : new NBTTagCompound[] { shelf(543, 70, -461), shelf(547, 70, -465),
            shelf(547, -1, -461), shelf(547, 256, -461) }) {
            try {
                ShelfCensus.validateShelfCoordinates(1, -1, 2, 3, invalid);
                fail("invalid shelf coordinates must fail census");
            } catch (ShelfCensus.CensusException expected) {
                assertTrue(expected.isCorruption());
            }
        }
    }

    @Test
    public void missingOrNonIntegerShelfCoordinatesFailClosed() throws Exception {
        NBTTagCompound missing = shelf(0, 64, 0);
        missing.removeTag("x");
        NBTTagCompound wrongType = shelf(0, 64, 0);
        wrongType.setLong("z", 0L);
        for (NBTTagCompound invalid : new NBTTagCompound[] { missing, wrongType }) {
            try {
                ShelfCensus.validateShelfCoordinates(0, 0, 0, 0, invalid);
                fail("unproven shelf coordinates must fail census");
            } catch (ShelfCensus.CensusException expected) {
                assertTrue(expected.isCorruption());
            }
        }
    }

    @Test
    public void foreignTileCoordinatesAreNotValidatedAsShelves() throws Exception {
        NBTTagCompound chunk = new NBTTagCompound();
        NBTTagCompound level = new NBTTagCompound();
        NBTTagList tiles = new NBTTagList();
        NBTTagCompound foreign = new NBTTagCompound();
        foreign.setString("id", "Chest");
        foreign.setString("x", "invalid");
        tiles.appendTag(foreign);
        level.setTag("TileEntities", tiles);
        chunk.setTag("Level", level);
        assertTrue(
            ShelfCensus.extract(0, 0, 0, 0, 0, chunk)
                .isEmpty());
    }

    @Test(expected = java.io.IOException.class)
    public void incompleteChunkCannotProveCensus() throws Exception {
        ShelfCensus.extract(0, 0, 0, 0, 0, new NBTTagCompound());
    }

    @Test
    public void similarForeignTileIdIsNotImported() throws Exception {
        NBTTagCompound chunk = new NBTTagCompound();
        NBTTagCompound level = new NBTTagCompound();
        NBTTagList tiles = new NBTTagList();
        NBTTagCompound foreign = new NBTTagCompound();
        foreign.setString("id", "other:poppetshelf");
        tiles.appendTag(foreign);
        level.setTag("TileEntities", tiles);
        chunk.setTag("Level", level);
        assertTrue(
            ShelfCensus.extract(0, 0, 0, 0, 0, chunk)
                .isEmpty());
    }

    @Test
    public void numericRegionChunkTileOrderIsDeterministic() {
        NBTTagCompound tile = new NBTTagCompound();
        java.util.List<ShelfCensus.Entry> entries = new java.util.ArrayList<>();
        entries.add(new ShelfCensus.Entry(2, 10, 0, 0, 0, 0, tile));
        entries.add(new ShelfCensus.Entry(2, 2, 0, 3, 4, 1, tile));
        entries.add(new ShelfCensus.Entry(2, 2, 0, 3, 4, 0, tile));
        ShelfCensus.sortEntries(entries);
        assertEquals(2, entries.get(0).regionX);
        assertEquals(0, entries.get(0).tileSequence);
        assertEquals(1, entries.get(1).tileSequence);
        assertEquals(10, entries.get(2).regionX);
    }

    @Test
    public void unregisteredDimensionFolderIsDiscovered() throws Exception {
        java.io.File root = java.nio.file.Files.createTempDirectory("wo-dims-")
            .toFile();
        try {
            assertTrue(new java.io.File(root, "DIM77").mkdir());
            java.util.Map<Integer, String> known = new java.util.HashMap<>();
            known.put(0, null);
            ShelfCensus.Snapshot snapshot = ShelfCensus.scanFolders(root, known);
            assertEquals("DIM77", snapshot.dimensions.get(77));
        } finally {
            new java.io.File(root, "DIM77").delete();
            root.delete();
        }
    }

    @Test
    public void persistedUnregisteredDimensionUsesStandardFolderThroughPublicFlow() throws Exception {
        java.io.File root = temporary("wo-public-dim-");
        try {
            assertTrue(new java.io.File(root, "DIM181").mkdir());
            ShelfCensus.Snapshot snapshot = ShelfCensus.scan(root, java.util.Arrays.asList(181));
            assertEquals("DIM181", snapshot.dimensions.get(181));
        } finally {
            delete(root);
        }
    }

    @Test
    public void negativeDimensionAndReturnedMappingsAreDiscovered() throws Exception {
        java.io.File root = temporary("wo-negative-dim-");
        try {
            assertTrue(new java.io.File(root, "DIM-42").mkdir());
            ShelfCensus.Snapshot snapshot = ShelfCensus.scanFolders(root, singletonRoot());
            assertTrue(snapshot.dimensions.containsKey(0));
            assertEquals("DIM-42", snapshot.dimensions.get(-42));
        } finally {
            delete(root);
        }
    }

    @Test
    public void malformedOverflowAndUnmappedRegionFoldersFailClearly() throws Exception {
        for (String name : new String[] { "DIM2147483648", "DIM+2", "TwilightForest" }) {
            java.io.File root = temporary("wo-invalid-dim-");
            try {
                regionBearing(root, name);
                try {
                    ShelfCensus.scanFolders(root, singletonRoot());
                    fail(name + " must fail");
                } catch (java.io.IOException expected) {
                    assertTrue(
                        expected.getMessage()
                            .contains(name));
                }
            } finally {
                delete(root);
            }
        }
    }

    @Test
    public void registeredCustomFolderWithRegionDataIsMapped() throws Exception {
        java.io.File root = temporary("wo-custom-dim-");
        try {
            java.io.File region = new java.io.File(new java.io.File(root, "TwilightForest"), "region");
            assertTrue(region.mkdirs());
            java.util.Map<Integer, String> known = singletonRoot();
            known.put(7, "TwilightForest");
            ShelfCensus.Snapshot snapshot = ShelfCensus.scanFolders(root, known);
            assertEquals("TwilightForest", snapshot.dimensions.get(7));
        } finally {
            delete(root);
        }
    }

    @Test
    public void canonicalIdConflictFailsClosed() throws Exception {
        java.io.File root = temporary("wo-id-conflict-");
        try {
            assertTrue(new java.io.File(root, "DIM2").mkdir());
            java.util.Map<Integer, String> known = singletonRoot();
            known.put(2, "custom");
            try {
                ShelfCensus.scanFolders(root, known);
                fail("one ID cannot map to two canonical folders");
            } catch (java.io.IOException expected) {
                assertTrue(
                    expected.getMessage()
                        .contains("maps to both"));
            }
        } finally {
            delete(root);
        }
    }

    @Test(expected = java.io.IOException.class)
    public void ambiguousFolderMappingFailsClosed() throws Exception {
        java.io.File root = java.nio.file.Files.createTempDirectory("wo-ambiguous-")
            .toFile();
        try {
            java.util.Map<Integer, String> known = new java.util.HashMap<>();
            known.put(0, null);
            known.put(2, "DIM2");
            known.put(3, "dim2");
            ShelfCensus.scanFolders(root, known);
        } finally {
            root.delete();
        }
    }

    @Test
    public void extractsAndAggregatesPersistedTaggedRemovalDrops() throws Exception {
        NBTTagCompound chunk = chunkWithLists();
        NBTTagList entities = chunk.getCompoundTag("Level")
            .getTagList("Entities", 10);
        java.util.UUID transaction = java.util.UUID.randomUUID();
        entities.appendTag(taggedItem(transaction, 0, 12));
        entities.appendTag(taggedItem(transaction, 1, 8));
        ShelfCensus.Snapshot snapshot = ShelfCensus.extractSnapshot(0, 0, 0, 0, 0, chunk);
        NBTTagCompound[] expected = new NBTTagCompound[] { itemNbt(20) };
        assertTrue(
            snapshot.drops.get(transaction)
                .completelyMatchesNbt(expected));
        assertFalse(
            snapshot.drops.get(transaction)
                .isEmpty());
    }

    @Test
    public void incompleteOrDuplicateTaggedDropsFailClosed() throws Exception {
        NBTTagCompound chunk = chunkWithLists();
        java.util.UUID transaction = java.util.UUID.randomUUID();
        NBTTagList entities = chunk.getCompoundTag("Level")
            .getTagList("Entities", 10);
        entities.appendTag(taggedItem(transaction, 0, 3));
        entities.appendTag(taggedItem(transaction, 0, 2));
        try {
            ShelfCensus.extractSnapshot(0, 0, 0, 0, 0, chunk);
            fail("duplicate transaction ordinal must fail");
        } catch (java.io.IOException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("Duplicate"));
        }
    }

    private static NBTTagCompound chunkWithLists() {
        NBTTagCompound chunk = new NBTTagCompound();
        NBTTagCompound level = new NBTTagCompound();
        level.setTag("TileEntities", new NBTTagList());
        level.setTag("Entities", new NBTTagList());
        chunk.setTag("Level", level);
        return chunk;
    }

    private static NBTTagCompound shelf(int x, int y, int z) {
        NBTTagCompound shelf = new NBTTagCompound();
        shelf.setString("id", "witchery:poppetshelf");
        shelf.setInteger("x", x);
        shelf.setInteger("y", y);
        shelf.setInteger("z", z);
        return shelf;
    }

    private static NBTTagCompound itemNbt(int count) {
        NBTTagCompound item = new NBTTagCompound();
        item.setShort("id", (short) 1);
        item.setByte("Count", (byte) count);
        item.setShort("Damage", (short) 0);
        return item;
    }

    private static NBTTagCompound taggedItem(java.util.UUID transaction, int ordinal, int count) {
        NBTTagCompound entity = new NBTTagCompound();
        entity.setString("id", "Item");
        NBTTagCompound forge = new NBTTagCompound();
        forge.setLong("WORemovalMost", transaction.getMostSignificantBits());
        forge.setLong("WORemovalLeast", transaction.getLeastSignificantBits());
        forge.setInteger("WODropOrdinal", ordinal);
        forge.setBoolean("WORemovalLocked", true);
        entity.setTag("ForgeData", forge);
        entity.setTag("Item", itemNbt(count));
        return entity;
    }

    @Test
    public void censusFailureClassificationDistinguishesAuthorityFromIo() {
        assertTrue(new ShelfCensus.CensusException("identity conflict").isCorruption());
        assertFalse(new ShelfCensus.CensusException("enumeration failed", false).isCorruption());
    }

    private static java.io.File temporary(String prefix) throws java.io.IOException {
        return java.nio.file.Files.createTempDirectory(prefix)
            .toFile();
    }

    private static java.util.Map<Integer, String> singletonRoot() {
        java.util.Map<Integer, String> known = new java.util.HashMap<>();
        known.put(0, null);
        return known;
    }

    private static void regionBearing(java.io.File root, String name) throws java.io.IOException {
        java.io.File region = new java.io.File(new java.io.File(root, name), "region");
        assertTrue(region.mkdirs());
        assertTrue(new java.io.File(region, "marker.mcr").createNewFile());
    }

    private static void delete(java.io.File file) {
        java.io.File[] children = file.listFiles();
        if (children != null) for (java.io.File child : children) delete(child);
        file.delete();
    }

}
