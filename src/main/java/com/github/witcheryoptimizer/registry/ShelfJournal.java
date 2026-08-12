package com.github.witcheryoptimizer.registry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;

final class ShelfJournal {

    private static final String FILE_NAME = "witcheryoptimizer-journal.dat";
    private final File file;
    private final File temporary;
    private final File directory;
    private NBTTagCompound root;

    ShelfJournal(WorldServer world) throws IOException {
        this(
            world.getSaveHandler()
                .getWorldDirectory());
    }

    private static void validateSchema(NBTTagCompound value) throws IOException {
        if (value == null) return;
        if (!value.hasKey("Schema", 3) || value.getInteger("Schema") != PoppetWorldData.SCHEMA)
            throw new IOException("Unsupported optimizer journal: required integer Schema=" + PoppetWorldData.SCHEMA);
    }

    ShelfJournal(File directory) throws IOException {
        this.directory = directory;
        file = new File(directory, FILE_NAME);
        temporary = new File(directory, FILE_NAME + ".tmp");
        root = loadNewest();
    }

    void recover(PoppetWorldData data) throws IOException {
        NBTTagList operations = root.getTagList("Entries", 10);
        for (int i = 0; i < operations.tagCount(); i++) data.applyJournal(operations.getCompoundTagAt(i));
        if (operations.tagCount() > 0) data.markDirty();
        if (root.hasKey("ImportState")) try {
            data.setImportState(PoppetWorldData.ImportState.valueOf(root.getString("ImportState")));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid journal ImportState=" + root.getString("ImportState"), exception);
        }
        if (root.hasKey("CensusState")) try {
            data.setCensusState(
                root.getInteger("CensusVersion"),
                PoppetWorldData.CensusState.valueOf(root.getString("CensusState")));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid journal CensusState=" + root.getString("CensusState"), exception);
        }
        if (root.getString("CensusState")
            .equals(PoppetWorldData.CensusState.RETRY_WAIT.name()))
            data.setCensusRetry(
                root.getInteger("CensusVersion"),
                root.getInteger("CensusRetryAttempt"),
                root.getLong("CensusRetryAt"),
                root.getBoolean("CensusRetryCorruption"),
                root.getString("CensusRetryReason"));
    }

    void appendImportState(PoppetWorldData.ImportState state) throws IOException {
        NBTTagCompound next = (NBTTagCompound) root.copy();
        next.setString("ImportState", state.name());
        next.setLong("Sequence", root.getLong("Sequence") + 1);
        writeAndReplace(next, temporary, file);
        root = next;
    }

    void appendCensusState(int version, PoppetWorldData.CensusState state) throws IOException {
        NBTTagCompound next = (NBTTagCompound) root.copy();
        next.setInteger("CensusVersion", version);
        next.setString("CensusState", state.name());
        if (state == PoppetWorldData.CensusState.COMPLETE) {
            next.removeTag("CensusRetryAttempt");
            next.removeTag("CensusRetryAt");
            next.removeTag("CensusRetryCorruption");
            next.removeTag("CensusRetryReason");
        }
        next.setLong("Sequence", root.getLong("Sequence") + 1);
        writeAndReplace(next, temporary, file);
        root = next;
    }

    void appendCensusRetry(int version, int attempt, long at, boolean corruption, String reason) throws IOException {
        NBTTagCompound next = (NBTTagCompound) root.copy();
        next.setInteger("CensusVersion", version);
        next.setString("CensusState", PoppetWorldData.CensusState.RETRY_WAIT.name());
        next.setInteger("CensusRetryAttempt", attempt);
        next.setLong("CensusRetryAt", at);
        next.setBoolean("CensusRetryCorruption", corruption);
        next.setString("CensusRetryReason", reason);
        next.setLong("Sequence", root.getLong("Sequence") + 1);
        writeAndReplace(next, temporary, file);
        root = next;
    }

    void appendPost(ShelfRecord record) throws IOException {
        NBTTagCompound operation = new NBTTagCompound();
        operation.setString("Kind", "PUT");
        operation.setTag("Record", record.write());
        replaceEntry(record.id, operation);
    }

    void appendDelete(ShelfRecord record, long generation) throws IOException {
        NBTTagCompound operation = new NBTTagCompound();
        operation.setString("Kind", "DELETE");
        putUuid(operation, "Shelf", record.id);
        operation.setLong("Generation", generation);
        operation.setTag("Location", record.location.write());
        if (record.removalTransaction != null) {
            operation.setLong("RemovalMost", record.removalTransaction.getMostSignificantBits());
            operation.setLong("RemovalLeast", record.removalTransaction.getLeastSignificantBits());
        }
        replaceEntry(record.id, operation);
    }

    private void replaceEntry(UUID shelf, NBTTagCompound operation) throws IOException {
        Map<UUID, NBTTagCompound> compacted = entries((NBTTagCompound) root.copy());
        compacted.put(shelf, (NBTTagCompound) operation.copy());
        NBTTagCompound next = new NBTTagCompound();
        next.setInteger("Schema", PoppetWorldData.SCHEMA);
        NBTTagList list = new NBTTagList();
        for (NBTTagCompound entry : compacted.values()) list.appendTag(entry);
        next.setTag("Entries", list);
        if (root.hasKey("ImportState")) next.setString("ImportState", root.getString("ImportState"));
        if (root.hasKey("CensusState")) {
            next.setInteger("CensusVersion", root.getInteger("CensusVersion"));
            next.setString("CensusState", root.getString("CensusState"));
            if (root.getString("CensusState")
                .equals(PoppetWorldData.CensusState.RETRY_WAIT.name())) {
                next.setInteger("CensusRetryAttempt", root.getInteger("CensusRetryAttempt"));
                next.setLong("CensusRetryAt", root.getLong("CensusRetryAt"));
                next.setBoolean("CensusRetryCorruption", root.getBoolean("CensusRetryCorruption"));
                next.setString("CensusRetryReason", root.getString("CensusRetryReason"));
            }
        }
        next.setLong("Sequence", root.getLong("Sequence") + 1);
        writeAndReplace(next, temporary, file);
        root = next;
    }

    int entryCount() {
        return root.getTagList("Entries", 10)
            .tagCount();
    }

    private static Map<UUID, NBTTagCompound> entries(NBTTagCompound value) {
        Map<UUID, NBTTagCompound> result = new LinkedHashMap<>();
        NBTTagList list = value.getTagList("Entries", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            UUID id = "DELETE".equals(entry.getString("Kind")) ? uuid(entry, "Shelf")
                : uuid(entry.getCompoundTag("Record"), "Uuid");
            result.put(id, entry);
        }
        return result;
    }

    private NBTTagCompound loadNewest() throws IOException {
        NBTTagCompound main = validRead(file);
        NBTTagCompound temp = validRead(temporary);
        if (main == null && temp == null) {
            if (file.exists() || temporary.exists()) throw new IOException("No valid optimizer journal copy exists");
            NBTTagCompound fresh = new NBTTagCompound();
            fresh.setInteger("Schema", PoppetWorldData.SCHEMA);
            return fresh;
        }
        validateSchema(main);
        validateSchema(temp);
        if (temp != null && (main == null || temp.getLong("Sequence") > main.getLong("Sequence"))) {
            replace(temporary.toPath(), file.toPath());
            forceDirectory();
            return temp;
        }
        if (temporary.exists() && !temporary.delete())
            throw new IOException("Unable to remove stale journal temp file");
        return main;
    }

    private static NBTTagCompound validRead(File source) {
        if (!source.isFile()) return null;
        try {
            return read(source);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static NBTTagCompound read(File source) throws IOException {
        try (FileInputStream input = new FileInputStream(source)) {
            NBTTagCompound result = CompressedStreamTools.readCompressed(input);
            if (result == null) throw new IOException("Empty optimizer journal " + source);
            return result;
        }
    }

    private void writeAndReplace(NBTTagCompound value, File temp, File destination) throws IOException {
        try (FileOutputStream output = new FileOutputStream(temp)) {
            CompressedStreamTools.writeCompressed(value, new NonClosingOutputStream(output));
            output.getFD()
                .sync();
        }
        replace(temp.toPath(), destination.toPath());
        forceDirectory();
    }

    private static void replace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void forceDirectory() throws IOException {
        try (FileChannel channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) {
            channel.force(true);
        } catch (java.nio.file.AccessDeniedException | UnsupportedOperationException ignored) {
            // Windows and some file systems do not permit opening directories; the file itself was fsynced.
        }
    }

    private static UUID uuid(NBTTagCompound tag, String prefix) {
        return new UUID(tag.getLong(prefix + "Most"), tag.getLong(prefix + "Least"));
    }

    private static void putUuid(NBTTagCompound tag, String prefix, UUID id) {
        tag.setLong(prefix + "Most", id.getMostSignificantBits());
        tag.setLong(prefix + "Least", id.getLeastSignificantBits());
    }

    private static final class NonClosingOutputStream extends java.io.FilterOutputStream {

        NonClosingOutputStream(FileOutputStream output) {
            super(output);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
