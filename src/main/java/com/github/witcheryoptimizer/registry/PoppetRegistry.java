package com.github.witcheryoptimizer.registry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.emoniph.witchery.blocks.BlockPoppetShelf.TileEntityPoppetShelf;
import com.github.witcheryoptimizer.WitcheryOptimizer;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public final class PoppetRegistry {

    private static final PoppetRegistry INSTANCE = new PoppetRegistry();
    private static final int DEADLINE = 100, MAX_TICKETS = 8;
    private final Map<TileEntityPoppetShelf, UUID> attached = new IdentityHashMap<>();
    private final Map<UUID, TileEntityPoppetShelf> loaded = new HashMap<>();
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final Set<TileEntityPoppetShelf> placements = Collections
        .newSetFromMap(new IdentityHashMap<TileEntityPoppetShelf, Boolean>());
    private final ThreadLocal<Boolean> syncing = ThreadLocal.withInitial(() -> false);
    private PoppetWorldData data;
    private ShelfJournal journal;
    private long tick;
    private boolean bootOpen;

    public static PoppetRegistry instance() {
        return INSTANCE;
    }

    public synchronized boolean initialize() {
        if (data != null) return true;
        MinecraftServer s = MinecraftServer.getServer();
        if (s == null || s.worldServers == null) return false;
        WorldServer primary = null;
        for (WorldServer w : s.worldServers) if (w != null) {
            primary = w;
            break;
        }
        if (primary == null) return false;
        try {
            data = PoppetWorldData.get(primary);
            journal = new ShelfJournal(primary);
            journal.recover(data);
            for (ShelfRecord r : data.records()) if (r.writebackPending) jobs.put(r.id, new Job(r.id));
            WitcheryOptimizer.LOG.info(
                "Authority loaded: shelves={}, reconstructed writebacks={}",
                data.records()
                    .size(),
                jobs.size());
            return true;
        } catch (IOException | RuntimeException e) {
            WitcheryOptimizer.LOG.error("Authority storage failed closed", e);
            data = null;
            journal = null;
            return false;
        }
    }

    @SubscribeEvent
    public void worldLoad(WorldEvent.Load e) {
        if (!e.world.isRemote) initialize();
    }

    @SubscribeEvent
    public void worldUnload(WorldEvent.Unload e) {
        if (e.world.isRemote) return;
        for (Job job : new ArrayList<>(jobs.values())) if (job.ticket != null && job.ticket.world == e.world) {
            release(job, "world unload");
            job.retry = tick + 100;
        }
    }

    @SubscribeEvent
    public void place(BlockEvent.PlaceEvent e) {
        if (!e.world.isRemote && !e.isCanceled()
            && e.placedBlock == com.emoniph.witchery.Witchery.Blocks.POPPET_SHELF) {
            TileEntity t = e.world.getTileEntity(e.x, e.y, e.z);
            if (t instanceof TileEntityPoppetShelf) placements.add((TileEntityPoppetShelf) t);
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        tick++;
        bootOpen = initialize();
        processJobs();
    }

    public void reset() {
        for (Job j : new ArrayList<>(jobs.values())) release(j, "server stop");
        attached.clear();
        loaded.clear();
        placements.clear();
        jobs.clear();
        data = null;
        journal = null;
        bootOpen = false;
    }

    public boolean attach(TileEntityPoppetShelf shelf) {
        return attach(shelf, false);
    }

    public boolean bootstrap(TileEntityPoppetShelf shelf) {
        return attach(shelf, true);
    }

    private boolean attach(TileEntityPoppetShelf shelf, boolean bootstrap) {
        if (shelf == null || shelf.getWorldObj() == null || shelf.getWorldObj().isRemote || !initialize()) return false;
        PoppetShelfState state = (PoppetShelfState) shelf;
        ShelfLocation at = location(shelf);
        UUID id = state.witcheryoptimizer$getShelfId();
        boolean authorizedPlacement = placements.contains(shelf);
        ShelfRecord record = id == null ? null : data.get(id);
        ShelfRecord atRecord = at(at);
        if (record != null && !record.location.equals(at)) {
            WitcheryOptimizer.LOG.error("Copied shelf UUID rejected at {}", at);
            clearPhysical(shelf);
            return false;
        }

        if (record != null || atRecord != null) {
            boolean persistent = state.witcheryoptimizer$hasPersistentShelfId();
            ShelfRecord existing = record != null ? record : atRecord;
            if (!mayBindExisting(id, persistent, id != null && id.equals(existing.id), authorizedPlacement)) {
                clearPhysical(shelf);
                placements.remove(shelf);
                return false;
            }
            record = existing;
            id = existing.id;
            state.witcheryoptimizer$setShelfId(id);
            placements.remove(shelf);
        }
        if (record == null) {
            boolean persistent = state.witcheryoptimizer$hasPersistentShelfId();
            if (!mayCreateAuthority(id, persistent)) {
                clearPhysical(shelf);
                placements.remove(shelf);
                WitcheryOptimizer.LOG.error("Persistent or transient shelf identity rejected at {}", at);
                return false;
            }
            if (!bootstrap && !authorizedPlacement) return false;
            placements.remove(shelf);
            id = UUID.randomUUID();
            record = data.newRecord(id, at, state.witcheryoptimizer$getCustomName(), snapshot(shelf));
            try {
                journal.appendPost(record);
                data.install(record);
                data.markDirty();
            } catch (IOException x) {
                WitcheryOptimizer.LOG.error("New shelf import failed WAL-first", x);
                return false;
            }
            state.witcheryoptimizer$setShelfId(id);
        }
        TileEntityPoppetShelf duplicate = loaded.get(id);
        if (duplicate != null && duplicate != shelf) return false;
        mirrorAndMark(record, shelf);
        attached.put(shelf, id);
        loaded.put(id, shelf);
        if (!complete(record, shelf)) jobs.put(record.id, new Job(record.id));
        return true;
    }

    static boolean mayBindExisting(UUID physical, boolean persistent, boolean exactIdentity,
        boolean authorizedPlacement) {
        return !persistent && physical == null && !authorizedPlacement
            || persistent && physical != null && exactIdentity;
    }

    static boolean mayCreateAuthority(UUID physical, boolean persistent) {
        return !persistent && physical == null;
    }

    public static boolean denySnapshotReplacement(boolean capturing, boolean restoring) {
        return capturing && !restoring;
    }

    public static boolean prepareOrdinaryReplacement(boolean capturing, boolean restoring) {
        return !capturing && !restoring;
    }

    static boolean clearPhysicalOnRemoval(boolean authorityAtLocation, boolean persistent, boolean exactIdentity) {
        return authorityAtLocation ? !exactIdentity : persistent;
    }

    public void changed(TileEntityPoppetShelf shelf) {
        if (Boolean.TRUE.equals(syncing.get()) || !initialize()) return;
        UUID id = attached.get(shelf);
        if (id == null) {
            attach(shelf);
            id = attached.get(shelf);
        }
        ShelfRecord old = id == null ? null : data.get(id);
        if (old == null) return;
        ShelfRecord post = ShelfRecord.read(old.write());
        post.customName = ((PoppetShelfState) shelf).witcheryoptimizer$getCustomName();
        post.replace(snapshot(shelf));
        post.version++;
        try {
            journal.appendPost(post);
            data.install(post);
            data.markDirty();
        } catch (IOException e) {
            WitcheryOptimizer.LOG.error("Shelf mutation reverted after WAL failure", e);
            mirror(old, shelf);
        }
    }

    public void prepareWrite(TileEntityPoppetShelf shelf) {
        if (attach(shelf)) {
            ShelfRecord r = data.get(attached.get(shelf));
            if (r != null) mirror(r, shelf);
        }
    }

    public void writeIdentity(TileEntityPoppetShelf shelf, NBTTagCompound tag) {
        UUID id = ((PoppetShelfState) shelf).witcheryoptimizer$getShelfId();
        ShelfRecord r = id == null || data == null ? null : data.get(id);
        if (r != null) {
            tag.setLong("WOShelfUuidMost", id.getMostSignificantBits());
            tag.setLong("WOShelfUuidLeast", id.getLeastSignificantBits());
            tag.setLong("WOAuthorityVersion", r.version);
        }
    }

    public void detach(TileEntityPoppetShelf shelf) {
        UUID id = attached.remove(shelf);
        if (id != null && loaded.get(id) == shelf) loaded.remove(id);
        placements.remove(shelf);
    }

    public boolean prepareRemoval(net.minecraft.world.World world, int x, int y, int z) {
        if (world.isRemote || !initialize()) return false;
        ShelfLocation location = new ShelfLocation(world.provider.dimensionId, x, y, z);
        TileEntity tile = world.getTileEntity(x, y, z);
        ShelfRecord record = at(location);
        if (tile instanceof TileEntityPoppetShelf) {
            TileEntityPoppetShelf shelf = (TileEntityPoppetShelf) tile;
            PoppetShelfState state = (PoppetShelfState) shelf;
            UUID physical = state.witcheryoptimizer$getShelfId();
            ShelfRecord byIdentity = physical == null ? null : data.get(physical);
            boolean foreignOrUnknown = state.witcheryoptimizer$hasPersistentShelfId()
                && (byIdentity == null || !byIdentity.location.equals(location));
            if (record == null && foreignOrUnknown) clearPhysical(shelf);
            else if (record != null && (physical == null || !record.id.equals(physical))) clearPhysical(shelf);
            else if (record != null && !attach(shelf)) return false;
        }
        if (record == null) return true;
        try {
            journal.appendDelete(record);
            data.delete(record);
            return true;
        } catch (IOException e) {
            WitcheryOptimizer.LOG.error("Shelf break denied: authority tombstone WAL failed", e);
            return false;
        }
    }

    public ItemStack find(EntityPlayer p, Matcher matcher) {
        if (!bootOpen || !initialize()) return null;
        List<ShelfRecord> records = new ArrayList<>(data.records());
        Collections.sort(records, (a, b) -> Long.compare(a.order, b.order));
        for (ShelfRecord r : records) {
            ShelfRecord post = ShelfRecord.read(r.write());
            ItemStack found = matcher.find(p, new ShelfInventory(post));
            if (found == null) continue;
            TileEntityPoppetShelf shelf = loaded.get(post.id);
            boolean loadedExact = exact(shelf, post);
            post.version++;
            post.writebackPending = true;
            post.writebackVersion = post.version;
            try {
                journal.appendPost(post);
            } catch (IOException e) {
                WitcheryOptimizer.LOG.error("Poppet protection denied: authority WAL failed", e);
                return null;
            }
            data.install(post);
            data.markDirty();
            if (!loadedExact) jobs.put(post.id, new Job(post.id));
            else {
                try {
                    mirrorAndMark(post, shelf);
                    if (!complete(post, shelf)) jobs.put(post.id, new Job(post.id));
                } catch (RuntimeException e) {
                    jobs.put(post.id, new Job(post.id));
                    WitcheryOptimizer.LOG.error(
                        "Loaded shelf synchronization failed after authority consumption at {}; repair queued",
                        post.location,
                        e);
                }
            }
            return found;
        }
        return null;
    }

    private void processJobs() {
        if (data == null) return;
        int active = 0;
        for (Job j : jobs.values()) if (j.ticket != null) active++;
        for (Job j : new ArrayList<>(jobs.values())) {
            ShelfRecord r = data.get(j.id);
            if (r == null || !r.writebackPending) {
                release(j, "obsolete");
                jobs.remove(j.id);
                continue;
            }
            TileEntityPoppetShelf shelf = loaded.get(j.id);
            if (exact(shelf, r)) {
                try {
                    mirrorAndMark(r, shelf);
                    complete(r, shelf);
                } catch (RuntimeException e) {
                    WitcheryOptimizer.LOG.error("Writeback mirror failed at {}; job remains pending", r.location, e);
                }
                continue;
            }
            if (j.ticket != null && (tick >= j.deadline || System.nanoTime() >= j.nanoDeadline)) {
                WitcheryOptimizer.LOG.warn(
                    "Writeback ticket expired at {} chunk {},{} version {}",
                    r.location,
                    r.location.x >> 4,
                    r.location.z >> 4,
                    r.writebackVersion);
                release(j, "deadline");
                j.retry = tick + 100;
                active--;
                continue;
            }
            if (j.ticket != null && !leasePresent(j)) {
                WitcheryOptimizer.LOG.error(
                    "Writeback ticket disappeared at {} chunk {},{} version {}; repair remains pending",
                    r.location,
                    j.chunk.chunkXPos,
                    j.chunk.chunkZPos,
                    r.writebackVersion);
                release(j, "unexpected disappearance");
                j.retry = tick + 100;
                active--;
                continue;
            }
            if (j.ticket == null && tick >= j.retry && active < MAX_TICKETS) {
                WorldServer w = DimensionManager.getWorld(r.location.dimension);
                if (w == null) {
                    j.retry = tick + 100;
                    continue;
                }
                Ticket t = ForgeChunkManager
                    .requestTicket(WitcheryOptimizer.instance, w, ForgeChunkManager.Type.NORMAL);
                if (t == null) {
                    WitcheryOptimizer.LOG.error("Writeback ticket request failed at {}", r.location);
                    j.retry = tick + 100;
                    continue;
                }
                NBTTagCompound n = t.getModData();
                n.setInteger("Schema", 3);
                n.setString("Kind", "shelf-writeback");
                n.setLong("ShelfMost", r.id.getMostSignificantBits());
                n.setLong("ShelfLeast", r.id.getLeastSignificantBits());
                n.setInteger("X", r.location.x);
                n.setInteger("Y", r.location.y);
                n.setInteger("Z", r.location.z);
                n.setLong("Version", r.writebackVersion);
                t.setChunkListDepth(1);
                j.ticket = t;
                ChunkCoordIntPair forced = new ChunkCoordIntPair(r.location.x >> 4, r.location.z >> 4);
                j.chunk = forced;
                j.deadline = tick + DEADLINE;
                j.nanoDeadline = System.nanoTime() + 5_000_000_000L;
                try {
                    ForgeChunkManager.forceChunk(t, forced);
                    w.theChunkProviderServer.loadChunk(forced.chunkXPos, forced.chunkZPos);
                } catch (RuntimeException e) {
                    WitcheryOptimizer.LOG
                        .error("Writeback force/load failed at {}; repair remains pending", r.location, e);
                    release(j, "force/load failure");
                    j.retry = tick + 100;
                    continue;
                }
                ShelfRecord current = data.get(j.id);
                if (!leaseSurvivedSynchronousLoad(
                    jobs.get(j.id) == j,
                    j.ticket == t,
                    current != null && current.writebackPending)) continue;
                active++;
                WitcheryOptimizer.LOG.info(
                    "Writeback ticket requested at {} chunk {},{} version {}",
                    r.location,
                    forced.chunkXPos,
                    forced.chunkZPos,
                    r.writebackVersion);
            }
        }
    }

    private boolean complete(ShelfRecord r, TileEntityPoppetShelf shelf) {
        if (!r.writebackPending) return true;
        ShelfRecord done = ShelfRecord.read(r.write());
        done.writebackPending = false;
        done.writebackVersion = 0;
        try {
            journal.appendPost(done);
            data.install(done);
            data.markDirty();
            Job j = jobs.remove(r.id);
            if (j != null) release(j, "completed");
            WitcheryOptimizer.LOG.info("Writeback completed at {} version {}", r.location, r.writebackVersion);
            return true;
        } catch (IOException e) {
            WitcheryOptimizer.LOG.error("Writeback acknowledgment WAL failed at " + r.location, e);
            return false;
        }
    }

    static void synchronizeLoaded(Runnable synchronize, Runnable repair) {
        try {
            synchronize.run();
        } catch (RuntimeException e) {
            repair.run();
            throw e;
        }
    }

    static boolean leaseSurvivedSynchronousLoad(boolean jobPresent, boolean sameTicket, boolean pending) {
        return jobPresent && sameTicket && pending;
    }

    static boolean loadedCompletionNeedsJob(boolean mirrorSucceeded, boolean acknowledgementSucceeded) {
        return !mirrorSucceeded || !acknowledgementSucceeded;
    }

    static RemovalDecision removalDecision(boolean authorityAtLocation, boolean persistentIdentity,
        boolean identityMatchesLocation) {
        if (authorityAtLocation) return identityMatchesLocation ? RemovalDecision.DELETE_AUTHORITY
            : RemovalDecision.CLEAR_AND_DELETE_AUTHORITY;
        return persistentIdentity ? RemovalDecision.CLEAR_PHYSICAL : RemovalDecision.ALLOW_PHYSICAL;
    }

    enum RemovalDecision {
        ALLOW_PHYSICAL,
        CLEAR_PHYSICAL,
        DELETE_AUTHORITY,
        CLEAR_AND_DELETE_AUTHORITY
    }

    private void release(Job j, String why) {
        if (j.ticket == null) return;
        try {
            if (j.chunk != null) ForgeChunkManager.unforceChunk(j.ticket, j.chunk);
        } catch (RuntimeException e) {
            WitcheryOptimizer.LOG.warn("Writeback unforce failed (" + why + ")", e);
        }

        try {
            ForgeChunkManager.releaseTicket(j.ticket);
        } catch (RuntimeException e) {
            WitcheryOptimizer.LOG.error("Writeback ticket release failed (" + why + ")", e);
        }
        j.ticket = null;
        j.chunk = null;
    }

    private boolean leasePresent(Job job) {
        return job.ticket != null && job.chunk != null
            && job.ticket.getChunkList()
                .contains(job.chunk)
            && ForgeChunkManager.getPersistentChunksFor(job.ticket.world)
                .containsEntry(job.chunk, job.ticket);
    }

    private boolean exact(TileEntityPoppetShelf s, ShelfRecord r) {
        return s != null && !s.isInvalid()
            && location(s).equals(r.location)
            && r.id.equals(((PoppetShelfState) s).witcheryoptimizer$getShelfId());
    }

    private ShelfRecord at(ShelfLocation l) {
        for (ShelfRecord r : data.records()) if (r.location.equals(l)) return r;
        return null;
    }

    private ShelfLocation location(TileEntityPoppetShelf s) {
        return new ShelfLocation(s.getWorldObj().provider.dimensionId, s.xCoord, s.yCoord, s.zCoord);
    }

    private ItemStack[] snapshot(TileEntityPoppetShelf s) {
        ItemStack[] a = new ItemStack[9];
        for (int i = 0; i < 9; i++) a[i] = ShelfRecord.copy(s.getStackInSlot(i));
        return a;
    }

    private void mirror(ShelfRecord r, TileEntityPoppetShelf s) {
        syncing.set(true);
        try {
            applyMirror(r, s);
        } finally {
            syncing.remove();
        }
    }

    private void mirrorAndMark(ShelfRecord r, TileEntityPoppetShelf s) {
        syncing.set(true);
        try {
            applyMirror(r, s);
            s.markDirty();
        } finally {
            syncing.remove();
        }
    }

    private static void applyMirror(ShelfRecord r, TileEntityPoppetShelf s) {
        ((PoppetShelfState) s).witcheryoptimizer$setCustomName(r.customName);
        for (int i = 0; i < 9; i++) s.setInventorySlotContents(i, ShelfRecord.copy(r.inventory[i]));
    }

    private void clearPhysical(TileEntityPoppetShelf s) {
        syncing.set(true);
        try {
            for (int i = 0; i < 9; i++) s.setInventorySlotContents(i, null);
        } finally {
            syncing.remove();
        }
    }

    private static final class Job {

        final UUID id;
        Ticket ticket;
        ChunkCoordIntPair chunk;
        long deadline, retry;
        long nanoDeadline;

        Job(UUID id) {
            this.id = id;
        }
    }

    public interface Matcher {

        ItemStack find(EntityPlayer player, IInventory inventory);
    }
}
