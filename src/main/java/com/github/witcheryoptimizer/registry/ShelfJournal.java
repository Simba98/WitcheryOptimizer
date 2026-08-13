package com.github.witcheryoptimizer.registry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.UUID;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;

final class ShelfJournal {

    private static final String FILE = "witcheryoptimizer-v3-wal.dat";
    private final File file, temporary, directory;
    private NBTTagCompound root;

    ShelfJournal(WorldServer world) throws IOException {
        this(
            world.getSaveHandler()
                .getWorldDirectory());
    }

    ShelfJournal(File directory) throws IOException {
        this.directory = directory;
        file = new File(directory, FILE);
        temporary = new File(directory, FILE + ".tmp");
        root = load();
    }

    void recover(PoppetWorldData data) throws IOException {
        NBTTagList entries = StrictNbt.list(root, "Entries", 10);
        for (int i = 0; i < entries.tagCount(); i++) data.applyJournal(entries.getCompoundTagAt(i));
    }

    void appendPost(ShelfRecord record) throws IOException {
        NBTTagCompound op = new NBTTagCompound();
        op.setString("Kind", "PUT");
        op.setTag("Record", record.write());
        replace(record.id, op);
    }

    void appendDelete(ShelfRecord record) throws IOException {
        NBTTagCompound op = new NBTTagCompound();
        op.setString("Kind", "DELETE");
        op.setLong("ShelfMost", record.id.getMostSignificantBits());
        op.setLong("ShelfLeast", record.id.getLeastSignificantBits());
        op.setLong("Generation", record.version + 1);
        replace(record.id, op);
    }

    int entryCount() {
        return root.getTagList("Entries", 10)
            .tagCount();
    }

    private void replace(UUID id, NBTTagCompound operation) throws IOException {
        LinkedHashMap<UUID, NBTTagCompound> map = new LinkedHashMap<>();
        NBTTagList old = StrictNbt.list(root, "Entries", 10);
        for (int i = 0; i < old.tagCount(); i++) {
            NBTTagCompound e = old.getCompoundTagAt(i);
            UUID k = identity(e);
            map.put(k, e);
        }
        map.put(id, operation);
        NBTTagCompound next = new NBTTagCompound();
        next.setInteger("Schema", PoppetWorldData.SCHEMA);
        next.setLong("Sequence", root.getLong("Sequence") + 1);
        NBTTagList list = new NBTTagList();
        for (NBTTagCompound e : map.values()) list.appendTag(e);
        next.setTag("Entries", list);
        write(next);
        root = next;
    }

    private NBTTagCompound load() throws IOException {
        NBTTagCompound a = readValid(file), b = readValid(temporary);
        if (a == null && b == null) {
            if (file.exists() || temporary.exists()) throw new IOException("No valid v3 WAL copy");
            NBTTagCompound n = new NBTTagCompound();
            n.setInteger("Schema", PoppetWorldData.SCHEMA);
            n.setLong("Sequence", 0);
            n.setTag("Entries", new NBTTagList());
            return n;
        }
        NBTTagCompound n = b != null && (a == null || b.getLong("Sequence") > a.getLong("Sequence")) ? b : a;
        validate(n);
        if (n == b) move(temporary.toPath(), file.toPath());
        else if (temporary.exists() && !temporary.delete()) throw new IOException("Cannot remove stale WAL temp");
        return n;
    }

    private static NBTTagCompound readValid(File f) {
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            return CompressedStreamTools.readCompressed(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static void validate(NBTTagCompound n) throws IOException {
        try {
            StrictNbt.require(n, "Schema", 3);
            if (n.getInteger("Schema") != PoppetWorldData.SCHEMA) throw new IllegalStateException("Wrong WAL schema");
            StrictNbt.nonnegativeLong(n, "Sequence");
            NBTTagList l = StrictNbt.list(n, "Entries", 10);
            LinkedHashMap<UUID, Boolean> identities = new LinkedHashMap<>();
            for (int i = 0; i < l.tagCount(); i++) {
                NBTTagCompound e = l.getCompoundTagAt(i);
                StrictNbt.require(e, "Kind", 8);
                if ("PUT".equals(e.getString("Kind"))) {
                    StrictNbt.require(e, "Record", 10);
                    ShelfRecord.read(e.getCompoundTag("Record"));
                } else if ("DELETE".equals(e.getString("Kind"))) {
                    StrictNbt.require(e, "ShelfMost", 4);
                    StrictNbt.require(e, "ShelfLeast", 4);
                    StrictNbt.nonnegativeLong(e, "Generation");
                } else throw new IllegalStateException("Bad WAL kind");
                UUID id = identity(e);
                if (identities.put(id, Boolean.TRUE) != null)
                    throw new IllegalStateException("Duplicate WAL operation for " + id);
            }
        } catch (RuntimeException x) {
            throw new IOException("Invalid v3 WAL", x);
        }
    }

    private static UUID identity(NBTTagCompound operation) {
        if ("PUT".equals(operation.getString("Kind"))) {
            StrictNbt.require(operation, "Record", 10);
            NBTTagCompound record = operation.getCompoundTag("Record");
            StrictNbt.require(record, "UuidMost", 4);
            StrictNbt.require(record, "UuidLeast", 4);
            return new UUID(record.getLong("UuidMost"), record.getLong("UuidLeast"));
        }
        StrictNbt.require(operation, "ShelfMost", 4);
        StrictNbt.require(operation, "ShelfLeast", 4);
        return new UUID(operation.getLong("ShelfMost"), operation.getLong("ShelfLeast"));
    }

    private void write(NBTTagCompound n) throws IOException {
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            CompressedStreamTools.writeCompressed(n, out);
            sync(out);
        }
        move(temporary.toPath(), file.toPath());
        try (FileChannel c = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) {
            c.force(true);
        } catch (AccessDeniedException | UnsupportedOperationException ignored) {
            // Some file systems cannot open directories; the WAL file itself was fsynced.
        }
    }

    private static void sync(FileOutputStream out) throws IOException {
        try {
            out.getFD()
                .sync();
        } catch (java.io.SyncFailedException exception) {
            if (!Boolean.getBoolean("witcheryoptimizer.allowUnsupportedFsync")) throw exception;
        }
    }

    private static void move(Path a, Path b) throws IOException {
        try {
            Files.move(a, b, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(a, b, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
