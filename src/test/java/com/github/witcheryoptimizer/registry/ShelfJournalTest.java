package com.github.witcheryoptimizer.registry;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

import net.minecraft.item.ItemStack;

import org.junit.Test;

public class ShelfJournalTest {

    @Test
    public void repeatedAppendAndReplayIsIdempotent() throws Exception {
        File directory = Files.createTempDirectory("wo-journal-")
            .toFile();
        try {
            ShelfJournal journal = new ShelfJournal(directory);
            ShelfRecord first = record(1);
            journal.appendPost(first);
            first.version = 2;
            journal.appendPost(first);
            PoppetWorldData data = new PoppetWorldData();
            new ShelfJournal(directory).recover(data);
            new ShelfJournal(directory).recover(data);
            assertEquals(2, data.get(first.id).version);
        } finally {
            delete(directory);
        }
    }

    @Test
    public void newerSyncedTempIsRecovered() throws Exception {
        File directory = Files.createTempDirectory("wo-temp-")
            .toFile();
        try {
            ShelfRecord first = record(1);
            ShelfJournal journal = new ShelfJournal(directory);
            journal.appendPost(first);
            File main = new File(directory, "witcheryoptimizer-journal.dat");
            byte[] older = Files.readAllBytes(main.toPath());
            first.version = 2;
            journal.appendPost(first);
            Files.copy(main.toPath(), new File(directory, "witcheryoptimizer-journal.dat.tmp").toPath());
            Files.write(main.toPath(), older);
            PoppetWorldData data = new PoppetWorldData();
            new ShelfJournal(directory).recover(data);
            assertEquals(2, data.get(first.id).version);
        } finally {
            delete(directory);
        }
    }

    @Test
    public void failedAppendDoesNotLeakIntoLaterJournal() throws Exception {
        File directory = Files.createTempDirectory("wo-fail-")
            .toFile();
        try {
            ShelfJournal journal = new ShelfJournal(directory);
            ShelfRecord first = record(1);
            journal.appendPost(first);
            File temp = new File(directory, "witcheryoptimizer-journal.dat.tmp");
            assertTrue(temp.mkdir());
            first.version = 2;
            try {
                journal.appendPost(first);
                fail("append should fail");
            } catch (java.io.IOException expected) {}
            assertTrue(temp.delete());
            PoppetWorldData data = new PoppetWorldData();
            new ShelfJournal(directory).recover(data);
            assertEquals(1, data.get(first.id).version);
        } finally {
            delete(directory);
        }
    }

    @Test
    public void tenThousandUpdatesRemainOneBoundedEntry() throws Exception {
        File directory = Files.createTempDirectory("wo-compact-")
            .toFile();
        try {
            ShelfJournal journal = new ShelfJournal(directory);
            ShelfRecord record = record(0);
            for (int i = 1; i <= 10000; i++) {
                record.version = i;
                journal.appendPost(record);
            }
            assertEquals(1, journal.entryCount());
            assertTrue(new File(directory, "witcheryoptimizer-journal.dat").length() < 4096);
            PoppetWorldData data = new PoppetWorldData();
            new ShelfJournal(directory).recover(data);
            assertEquals(10000, data.get(record.id).version);
        } finally {
            delete(directory);
        }
    }

    @Test
    public void alternatingPutDeleteRecoversLatestTombstone() throws Exception {
        File directory = Files.createTempDirectory("wo-delete-")
            .toFile();
        try {
            ShelfJournal journal = new ShelfJournal(directory);
            ShelfRecord record = record(1);
            journal.appendPost(record);
            journal.appendDelete(record, 2);
            assertEquals(1, journal.entryCount());
            PoppetWorldData data = new PoppetWorldData();
            new ShelfJournal(directory).recover(data);
            assertNull(data.get(record.id));
            assertTrue(data.isTombstoned(record.id));
        } finally {
            delete(directory);
        }
    }

    @Test
    public void retryMetadataSurvivesCompaction() throws Exception {
        File directory = Files.createTempDirectory("wo-retry-")
            .toFile();
        try {
            ShelfJournal journal = new ShelfJournal(directory);
            journal.appendCensusRetry(1, 3, 123456L, true, "corrupt nbt");
            journal.appendPost(record(1));
            PoppetWorldData data = new PoppetWorldData();
            new ShelfJournal(directory).recover(data);
            assertEquals(PoppetWorldData.CensusState.RETRY_WAIT, data.censusState());
            assertEquals(3, data.retryAttempt());
            assertEquals(123456L, data.retryAt(100000L));
        } finally {
            delete(directory);
        }
    }

    @Test
    public void failedCompleteAppendLeavesRunningDurably() throws Exception {
        File directory = Files.createTempDirectory("wo-complete-fail-")
            .toFile();
        try {
            ShelfJournal journal = new ShelfJournal(directory);
            journal.appendCensusState(1, PoppetWorldData.CensusState.IN_PROGRESS);
            File temp = new File(directory, "witcheryoptimizer-journal.dat.tmp");
            assertTrue(temp.mkdir());
            try {
                journal.appendCensusState(1, PoppetWorldData.CensusState.COMPLETE);
                fail("append should fail");
            } catch (java.io.IOException expected) {}
            assertTrue(temp.delete());
            PoppetWorldData data = new PoppetWorldData();
            new ShelfJournal(directory).recover(data);
            assertEquals(PoppetWorldData.CensusState.IN_PROGRESS, data.censusState());
        } finally {
            delete(directory);
        }
    }

    private static ShelfRecord record(long version) {
        ShelfRecord record = new ShelfRecord(UUID.randomUUID(), new ShelfLocation(7, 1, 2, 3), "", 0, new ItemStack[9]);
        record.version = version;
        return record;
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
