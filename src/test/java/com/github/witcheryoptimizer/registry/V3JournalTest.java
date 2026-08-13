package com.github.witcheryoptimizer.registry;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.UUID;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class V3JournalTest {

    private File dir;

    @Before
    public void before() throws IOException {
        dir = new File("build/test-wal/" + UUID.randomUUID());
        assertTrue(dir.mkdirs());
    }

    @After
    public void after() {
        delete(dir);
    }

    private static void delete(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) for (File c : f.listFiles()) delete(c);
        f.delete();
    }

    private static ShelfRecord r(UUID id, long v) {
        ShelfRecord r = new ShelfRecord(id, new ShelfLocation(2, 3, 4, 5), "", 1, new net.minecraft.item.ItemStack[9]);
        r.version = v;
        return r;
    }

    private static void write(File f, NBTTagCompound n) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) {
            CompressedStreamTools.writeCompressed(n, o);
        }
    }

    private static NBTTagCompound wal(long seq, NBTTagCompound... ops) {
        NBTTagCompound n = new NBTTagCompound();
        n.setInteger("Schema", 3);
        n.setLong("Sequence", seq);
        NBTTagList l = new NBTTagList();
        for (NBTTagCompound o : ops) l.appendTag(o);
        n.setTag("Entries", l);
        return n;
    }

    private static NBTTagCompound put(ShelfRecord r) {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("Kind", "PUT");
        n.setTag("Record", r.write());
        return n;
    }

    private static NBTTagCompound del(ShelfRecord r) {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("Kind", "DELETE");
        n.setLong("ShelfMost", r.id.getMostSignificantBits());
        n.setLong("ShelfLeast", r.id.getLeastSignificantBits());
        n.setLong("Generation", r.version + 1);
        return n;
    }

    @Test
    public void fresh() throws Exception {
        assertEquals(0, new ShelfJournal(dir).entryCount());
    }

    @Test
    public void putReplay() throws Exception {
        UUID id = UUID.randomUUID();
        ShelfJournal j = new ShelfJournal(dir);
        j.appendPost(r(id, 2));
        PoppetWorldData d = new PoppetWorldData();
        j.recover(d);
        assertEquals(2, d.get(id).version);
    }

    @Test
    public void deleteReplay() throws Exception {
        UUID id = UUID.randomUUID();
        ShelfJournal j = new ShelfJournal(dir);
        ShelfRecord r = r(id, 2);
        j.appendPost(r);
        j.appendDelete(r);
        PoppetWorldData d = new PoppetWorldData();
        j.recover(d);
        assertNull(d.get(id));
    }

    @Test
    public void compactsLatest() throws Exception {
        ShelfJournal j = new ShelfJournal(dir);
        UUID id = UUID.randomUUID();
        j.appendPost(r(id, 1));
        j.appendPost(r(id, 2));
        assertEquals(1, j.entryCount());
    }

    @Test
    public void newestTempWins() throws Exception {
        UUID id = UUID.randomUUID();
        write(new File(dir, "witcheryoptimizer-v3-wal.dat"), wal(1));
        write(new File(dir, "witcheryoptimizer-v3-wal.dat.tmp"), wal(2, put(r(id, 2))));
        PoppetWorldData d = new PoppetWorldData();
        new ShelfJournal(dir).recover(d);
        assertNotNull(d.get(id));
    }

    @Test
    public void corruptMainValidTemp() throws Exception {
        try (FileWriter w = new FileWriter(new File(dir, "witcheryoptimizer-v3-wal.dat"))) {
            w.write("bad");
        }
        write(new File(dir, "witcheryoptimizer-v3-wal.dat.tmp"), wal(1));
        assertEquals(0, new ShelfJournal(dir).entryCount());
    }

    @Test
    public void corruptBothFail() throws Exception {
        for (String s : new String[] { "witcheryoptimizer-v3-wal.dat", "witcheryoptimizer-v3-wal.dat.tmp" })
            try (FileWriter w = new FileWriter(new File(dir, s))) {
                w.write("bad");
            }
        try {
            new ShelfJournal(dir);
            fail();
        } catch (IOException expected) {}
    }

    @Test
    public void wrongEntriesFails() throws Exception {
        NBTTagCompound n = wal(1);
        n.setString("Entries", "bad");
        write(new File(dir, "witcheryoptimizer-v3-wal.dat"), n);
        try {
            new ShelfJournal(dir);
            fail();
        } catch (IOException expected) {}
    }

    @Test
    public void wrongSequenceFails() throws Exception {
        NBTTagCompound n = wal(1);
        n.setString("Sequence", "bad");
        write(new File(dir, "witcheryoptimizer-v3-wal.dat"), n);
        try {
            new ShelfJournal(dir);
            fail();
        } catch (IOException expected) {}
    }

    @Test
    public void wrongKindFails() throws Exception {
        NBTTagCompound o = new NBTTagCompound();
        o.setString("Kind", "bad");
        write(new File(dir, "witcheryoptimizer-v3-wal.dat"), wal(1, o));
        try {
            new ShelfJournal(dir);
            fail();
        } catch (IOException expected) {}
    }

    @Test
    public void duplicateIdentityFails() throws Exception {
        ShelfRecord r = r(UUID.randomUUID(), 1);
        write(new File(dir, "witcheryoptimizer-v3-wal.dat"), wal(1, put(r), put(r)));
        try {
            new ShelfJournal(dir);
            fail();
        } catch (IOException expected) {}
    }

    @Test
    public void pendingReplay() throws Exception {
        ShelfRecord r = r(UUID.randomUUID(), 3);
        r.writebackPending = true;
        r.writebackVersion = 3;
        ShelfJournal j = new ShelfJournal(dir);
        j.appendPost(r);
        PoppetWorldData d = new PoppetWorldData();
        j.recover(d);
        assertEquals(1, d.pendingWritebacks());
    }

    @Test
    public void staleWorldDataGetsWalPost() throws Exception {
        UUID id = UUID.randomUUID();
        ShelfJournal j = new ShelfJournal(dir);
        j.appendPost(r(id, 4));
        PoppetWorldData stale = new PoppetWorldData();
        stale.install(r(id, 1));
        j.recover(stale);
        assertEquals(4, stale.get(id).version);
    }

    @Test
    public void walDeletePreventsGhost() throws Exception {
        UUID id = UUID.randomUUID();
        ShelfRecord live = r(id, 4);
        ShelfJournal j = new ShelfJournal(dir);
        j.appendDelete(live);
        PoppetWorldData stale = new PoppetWorldData();
        stale.install(live);
        j.recover(stale);
        assertNull(stale.get(id));
    }
}
