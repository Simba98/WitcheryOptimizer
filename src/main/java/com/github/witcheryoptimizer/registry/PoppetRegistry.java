package com.github.witcheryoptimizer.registry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.ThreadedFileIOBase;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.emoniph.witchery.blocks.BlockPoppetShelf.TileEntityPoppetShelf;
import com.emoniph.witchery.util.Config;
import com.github.witcheryoptimizer.WitcheryOptimizer;
import com.github.witcheryoptimizer.migration.ShelfCensus;
import com.github.witcheryoptimizer.migration.TicketBatch;
import com.github.witcheryoptimizer.migration.WitcheryImportCoordinator;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public final class PoppetRegistry {

    private static final PoppetRegistry INSTANCE = new PoppetRegistry();
    private static final int CENSUS_VERSION = 2;
    private final Map<TileEntityPoppetShelf, UUID> attached = new IdentityHashMap<>();
    private final Map<UUID, TileEntityPoppetShelf> loaded = new HashMap<>();
    private final Set<WorldServer> removalRecoverySaveBarriers = Collections
        .newSetFromMap(new IdentityHashMap<WorldServer, Boolean>());
    private final Set<Integer> allowedDimensions = new HashSet<>();
    private final List<Integer> dimensionOrder = new ArrayList<>();
    private final ThreadLocal<Boolean> synchronizing = ThreadLocal.withInitial(() -> false);
    private PoppetWorldData data;
    private ShelfJournal journal;
    private final ThreadLocal<RemovalTransaction> removal = new ThreadLocal<>();
    private final ThreadLocal<CleanupContext> cleanupRemoval = new ThreadLocal<>();
    private final PlacementAuthorizations<TileEntityPoppetShelf> placementAuthorizations = new PlacementAuthorizations<>();
    private final WitcheryImportCoordinator importCoordinator = new WitcheryImportCoordinator();
    private long serverTick;
    private boolean censusAttempted;
    private long nextInitializationTick;
    private int initializationAttempts;

    public static PoppetRegistry instance() {
        return INSTANCE;
    }

    public void reset() {
        if (data != null) WitcheryOptimizer.LOG
            .info("Witchery Optimizer stopping with {} pending shelf NBT writeback(s)", data.pendingWritebacks());
        attached.clear();
        loaded.clear();
        removalRecoverySaveBarriers.clear();
        allowedDimensions.clear();
        dimensionOrder.clear();
        data = null;
        journal = null;
        removal.remove();
        cleanupRemoval.remove();
        placementAuthorizations.clear();
        importCoordinator.resetForServerStop();
        censusAttempted = false;
        nextInitializationTick = 0;
        initializationAttempts = 0;
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (!event.world.isRemote && initialize()) refreshAllowedDimensions();
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (!event.world.isRemote) {
            placementAuthorizations.clear();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onWorldSave(WorldEvent.Save event) {
        if (event.world.isRemote || !removalRecoverySaveBarriers.contains(event.world)) return;
        try {
            ThreadedFileIOBase.threadedIOInstance.waitForFinish();
            removalRecoverySaveBarriers.remove(event.world);
            if (removalRecoverySaveBarriers.isEmpty()) censusAttempted = false;
        } catch (InterruptedException exception) {
            Thread.currentThread()
                .interrupt();
            WitcheryOptimizer.LOG.error(
                "Shelf removal recovery remains blocked because asynchronous chunk persistence was interrupted",
                exception);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onPlace(BlockEvent.PlaceEvent event) {
        ShelfLocation location = new ShelfLocation(event.world.provider.dimensionId, event.x, event.y, event.z);
        if (event.isCanceled() || event.world.isRemote
            || event.placedBlock != com.emoniph.witchery.Witchery.Blocks.POPPET_SHELF) {
            placementAuthorizations.removeLocation(location);
            return;
        }
        TileEntity tile = event.world.getTileEntity(event.x, event.y, event.z);
        if (tile instanceof TileEntityPoppetShelf)
            placementAuthorizations.authorize((TileEntityPoppetShelf) tile, location, serverTick + 1);
        else placementAuthorizations.removeLocation(location);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            serverTick++;
            clearStaleRemovalAtTick();
            placementAuthorizations.expire(serverTick);
            if (data == null && serverTick >= nextInitializationTick) initialize();
            if (data != null) processLoadedCleanup();
            if (data != null && shouldFinalizeImport(data.importState()) && importCoordinator.finalizeStartup())
                persistCoordinatorState();
            long now = System.currentTimeMillis();
            if (data != null && censusEligible(
                data.importState(),
                data.censusComplete(CENSUS_VERSION),
                data.censusState(),
                censusAttempted,
                data.retryDue(now),
                !removalRecoverySaveBarriers.isEmpty())) {
                censusAttempted = true;
                runCensus();
            }
        }
    }

    static boolean shouldFinalizeImport(PoppetWorldData.ImportState state) {
        return state == PoppetWorldData.ImportState.UNKNOWN || state == PoppetWorldData.ImportState.IN_PROGRESS;
    }

    static boolean censusEligible(PoppetWorldData.ImportState importState, boolean censusComplete,
        PoppetWorldData.CensusState censusState, boolean censusAttempted, boolean retryDue,
        boolean recoveryAwaitingSave) {
        return (importState == PoppetWorldData.ImportState.COMPLETE
            || importState == PoppetWorldData.ImportState.DRAINED_CLEAN
            || importState == PoppetWorldData.ImportState.DRAINED_WITH_GAPS) && !censusComplete
            && !censusAttempted
            && (censusState != PoppetWorldData.CensusState.RETRY_WAIT || retryDue)
            && !recoveryAwaitingSave;
    }

    static boolean requiresRemovalCensus(boolean censusComplete, boolean preparedRemovals) {
        return censusComplete && preparedRemovals;
    }

    public boolean attach(TileEntityPoppetShelf shelf) {
        if (!usable(shelf) || !initialize()) return false;
        PoppetShelfState state = (PoppetShelfState) shelf;
        UUID id = state.witcheryoptimizer$getShelfId();
        ShelfLocation location = location(shelf);
        boolean authorizedPlacement = placementAuthorizations.consume(shelf, location, serverTick);
        if (state.witcheryoptimizer$hasPersistentShelfId() && id != null && data.isTombstoned(id)) {
            WitcheryOptimizer.LOG.error("Tombstoned shelf UUID {} rejected at {}", id, location);
            shelf.invalidate();
            return false;
        }
        if (!state.witcheryoptimizer$hasPersistentShelfId() && data.isTombstonedLocation(location)
            && !authorizedPlacement) {
            WitcheryOptimizer.LOG.error("Identity-less shelf at tombstoned location {} rejected", location);
            shelf.invalidate();
            return false;
        }
        LocationResolution resolution = resolveLocation(location);
        if (resolution.multiple) {
            WitcheryOptimizer.LOG.error("Multiple authoritative shelves claim {}; attachment denied", location);
            return false;
        }
        if (resolution.record != null && id != null && !resolution.record.id.equals(id)) {
            WitcheryOptimizer.LOG
                .error("Shelf UUID {} conflicts with authoritative {} at {}", id, resolution.record.id, location);
            shelf.invalidate();
            return false;
        }
        if (resolution.record != null && resolution.record.state == ShelfRecord.State.REMOVAL_PREPARED) {
            WitcheryOptimizer.LOG.error("Prepared removal at {} is quarantined", location);
            shelf.invalidate();
            return false;
        }
        if (resolution.record != null && resolution.record.state == ShelfRecord.State.REMOVAL_CLEANUP_PENDING) {
            WitcheryOptimizer.LOG.error("Removal cleanup at {} is quarantined until exact-state cleanup", location);
            return false;
        }
        if (!state.witcheryoptimizer$hasPersistentShelfId() && resolution.record != null
            && !loaded.containsKey(resolution.record.id)) {
            id = resolution.record.id;
            state.witcheryoptimizer$setShelfId(id);
            state.witcheryoptimizer$setPersistentShelfId(true);
        }
        ShelfRecord record = id == null ? null : data.get(id);
        boolean clone = record != null && !record.location.equals(location);
        if (clone || loaded.containsKey(id) && loaded.get(id) != shelf) {
            WitcheryOptimizer.LOG.error(
                "Copied/moved shelf UUID {} at {} is quarantined; generic NBT cannot prove an atomic move from {}",
                id,
                location,
                record == null ? "an unknown source" : record.location);
            shelf.invalidate();
            return false;
        }
        if (id == null) {
            id = UUID.randomUUID();
            state.witcheryoptimizer$setShelfId(id);
            state.witcheryoptimizer$setPersistentShelfId(false);
            record = null;
        }
        boolean imported = record == null;
        if (record == null) {
            record = data.newRecord(id, location, state.witcheryoptimizer$getCustomName(), snapshot(shelf));
            try {
                journal.appendPost(record);
                data.install(record);
                data.markDirty();
            } catch (IOException exception) {
                WitcheryOptimizer.LOG.error("Shelf import failed closed because its journal write failed", exception);
                return false;
            }
        } else if (!canAttach(record.state)) {
            WitcheryOptimizer.LOG.error("Unreconciled prepared removal at {} is quarantined", location);
            return false;
        } else {
            confirmPersistedMirror(shelf, record);
            record = data.get(id);
            mirror(record, shelf);
        }
        attached.put(shelf, id);
        loaded.put(id, shelf);
        if (imported) shelf.markDirty();
        return true;
    }

    static boolean canAttach(ShelfRecord.State state) {
        return state == ShelfRecord.State.ACTIVE;
    }

    public void changed(TileEntityPoppetShelf shelf) {
        if (Boolean.TRUE.equals(synchronizing.get()) || !usable(shelf) || !initialize()) return;
        UUID id = attached.get(shelf);
        if (id == null) {
            attach(shelf);
            return;
        }
        ShelfRecord record = data.get(id);
        if (record == null) return;
        ShelfRecord post = ShelfRecord.read(record.write());
        post.customName = ((PoppetShelfState) shelf).witcheryoptimizer$getCustomName();
        post.replace(snapshot(shelf));
        post.version = record.version + 1;
        try {
            journal.appendPost(post);
            data.install(post);
            data.markDirty();
        } catch (IOException exception) {
            WitcheryOptimizer.LOG.error("Shelf edit reverted because its journal write failed", exception);
            mirror(record, shelf);
        }
    }

    public void prepareWrite(TileEntityPoppetShelf shelf) {
        if (!usable(shelf) || !initialize()) return;
        if (!attached.containsKey(shelf)) attach(shelf);
        ShelfRecord record = data.get(attached.get(shelf));
        if (record != null && record.state == ShelfRecord.State.ACTIVE) mirror(record, shelf);
    }

    static boolean shouldConfirmWriteback(boolean pending, boolean active, long diskVersion, long currentVersion) {
        return pending && active && diskVersion == currentVersion;
    }

    static boolean canConfirmPersistedMirror(boolean sameLocation, boolean duplicateInstance, ShelfRecord.State state,
        boolean identityValid) {
        return sameLocation && !duplicateInstance && state == ShelfRecord.State.ACTIVE && identityValid;
    }

    private void confirmPersistedMirror(TileEntityPoppetShelf shelf, ShelfRecord record) {
        long diskVersion = ((PoppetShelfState) shelf).witcheryoptimizer$getDiskMirrorVersion();
        if (!shouldConfirmWriteback(
            record.writebackPending,
            record.state == ShelfRecord.State.ACTIVE,
            diskVersion,
            record.version)) return;
        ShelfRecord acknowledged = ShelfRecord.read(record.write());
        acknowledged.version++;
        acknowledged.writebackPending = false;
        try {
            journal.appendPost(acknowledged);
            data.install(acknowledged);
            data.markDirty();
        } catch (IOException exception) {
            WitcheryOptimizer.LOG.error("Persisted shelf mirror confirmation failed; remains pending", exception);
        }
    }

    public void writeIdentity(TileEntityPoppetShelf shelf, NBTTagCompound tag) {
        UUID id = ((PoppetShelfState) shelf).witcheryoptimizer$getShelfId();
        if (id == null) return;
        tag.setLong("WOShelfUuidMost", id.getMostSignificantBits());
        tag.setLong("WOShelfUuidLeast", id.getLeastSignificantBits());
        ShelfRecord record = data == null ? null : data.get(id);
        if (record != null) tag.setLong("WOWritebackVersion", record.version);
    }

    public void detach(TileEntityPoppetShelf shelf) {
        placementAuthorizations.remove(shelf);
        UUID id = attached.remove(shelf);
        if (id != null && loaded.get(id) == shelf) loaded.remove(id);
    }

    public boolean preRemove(net.minecraft.world.World world, int x, int y, int z) {
        if (world.isRemote || !initialize()) return false;
        RemovalTransaction existing = removal.get();
        ShelfLocation target = new ShelfLocation(world.provider.dimensionId, x, y, z);
        placementAuthorizations.removeLocation(target);
        if (!allowNewRemoval(existing != null)) {
            removal.remove();
            invalidateCensusForPreparedRemoval(existing.world);
            WitcheryOptimizer.LOG.error(
                "Stale shelf removal transaction {} detected before {}; new removal denied",
                existing.transaction,
                target);
            return false;
        }
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityPoppetShelf)) return false;
        TileEntityPoppetShelf shelf = (TileEntityPoppetShelf) tile;
        if (!attach(shelf)) return false;
        ShelfRecord record = data.get(attached.get(shelf));
        if (record == null || !record.location.equals(target)) return false;
        mirror(record, shelf);
        ShelfRecord before = ShelfRecord.read(record.write());
        try {
            ShelfRecord prepared = ShelfRecord.read(record.write());
            prepared.version++;
            prepared.state = ShelfRecord.State.REMOVAL_PREPARED;
            prepared.removalTransaction = UUID.randomUUID();
            prepared.removalSourceVersion = record.version;
            journal.appendPost(prepared);
            data.install(prepared);
        } catch (IOException exception) {
            WitcheryOptimizer.LOG
                .error("Block removal denied because shelf tombstone could not be persisted", exception);
            return false;
        }
        removal.set(new RemovalTransaction(world, target, before, shelf, data.get(record.id).removalTransaction));
        return true;
    }

    public boolean spawnRemovalDrop(net.minecraft.world.World world, Entity entity) {
        RemovalTransaction transaction = removal.get();
        if (transaction == null || !(entity instanceof EntityItem)) return world.spawnEntityInWorld(entity);
        if (!transaction.matchesWorld(world)) {
            removal.remove();
            invalidateCensusForPreparedRemoval(transaction.world);
            WitcheryOptimizer.LOG.error("Cross-world shelf removal drop denied for {}", transaction.transaction);
            return false;
        }
        if (transaction.nextDrop == 0) {
            ShelfRecord prepared = data.get(transaction.before.id);
            if (prepared == null || prepared.state != ShelfRecord.State.REMOVAL_PREPARED
                || !transaction.transaction.equals(prepared.removalTransaction)) return false;
            if (!prepared.removalDropsStarted) {
                ShelfRecord started = ShelfRecord.read(prepared.write());
                started.version++;
                started.removalDropsStarted = true;
                try {
                    journal.appendPost(started);
                    data.install(started);
                } catch (IOException exception) {
                    WitcheryOptimizer.LOG
                        .error("Shelf drop denied because its removal stage could not be persisted", exception);
                    return false;
                }
            }
        }
        NBTTagCompound forgeData = entity.getEntityData();
        forgeData.setLong("WORemovalMost", transaction.transaction.getMostSignificantBits());
        forgeData.setLong("WORemovalLeast", transaction.transaction.getLeastSignificantBits());
        forgeData.setInteger("WODropOrdinal", transaction.nextDrop++);
        forgeData.setBoolean("WORemovalLocked", true);
        ((EntityItem) entity).delayBeforeCanPickup = Short.MAX_VALUE;
        return world.spawnEntityInWorld(entity);
    }

    public void finishRemove(net.minecraft.world.World world, int x, int y, int z, boolean succeeded) {
        RemovalTransaction transaction = removal.get();
        ShelfLocation target = new ShelfLocation(world.provider.dimensionId, x, y, z);
        if (transaction == null) return;
        if (!transaction.matches(world, target)) {
            removal.remove();
            invalidateCensusForPreparedRemoval(transaction.world);
            WitcheryOptimizer.LOG.error("Mismatched shelf removal finish denied for {}", transaction.transaction);
            return;
        }
        try {
            boolean shelfBlock = world.getBlock(x, y, z) == com.emoniph.witchery.Witchery.Blocks.POPPET_SHELF;
            boolean exactOriginal = world.getTileEntity(x, y, z) == transaction.shelf;
            RemovalOutcome outcome = removalOutcome(shelfBlock, exactOriginal, transaction.nextDrop > 0);
            if (outcome == RemovalOutcome.RESTORE_EXACT_ORIGINAL) {
                ShelfRecord restored = ShelfRecord.read(transaction.before.write());
                restored.version = transaction.before.version + 2;
                try {
                    journal.appendPost(restored);
                    data.install(restored);
                    data.markDirty();
                    mirror(restored, transaction.shelf);
                } catch (IOException exception) {
                    WitcheryOptimizer.LOG.error("Failed block replacement remains quarantined", exception);
                }
            } else if (outcome == RemovalOutcome.COMMIT_AND_UNLOCK) {
                if (commitRemoval(data.get(transaction.before.id), transaction.transaction))
                    unlockLoadedDrops(transaction.transaction);
            }
        } finally {
            removal.remove();
        }
    }

    private void clearStaleRemovalAtTick() {
        RemovalTransaction stale = removal.get();
        if (!shouldClearStaleAtTick(stale != null)) return;
        removal.remove();
        invalidateCensusForPreparedRemoval(stale.world);
        WitcheryOptimizer.LOG.error(
            "Cleared shelf removal transaction {} left by an exceptional World.setBlock exit; recovery remains fail-closed",
            stale.transaction);
    }

    private void invalidateCensusForPreparedRemoval(net.minecraft.world.World world) {
        if (world instanceof WorldServer) removalRecoverySaveBarriers.add((WorldServer) world);
        censusAttempted = !removalRecoverySaveBarriers.isEmpty();
        if (data == null || journal == null || !data.censusComplete(CENSUS_VERSION)) return;
        try {
            journal.appendCensusState(CENSUS_VERSION, PoppetWorldData.CensusState.UNKNOWN);
            data.setCensusState(CENSUS_VERSION, PoppetWorldData.CensusState.UNKNOWN);
        } catch (IOException exception) {
            data.setCensusState(CENSUS_VERSION, PoppetWorldData.CensusState.FAILED);
            WitcheryOptimizer.LOG.error("Unable to reopen census after stale shelf removal", exception);
        }
    }

    static boolean allowNewRemoval(boolean existingTransaction) {
        return !existingTransaction;
    }

    static boolean shouldClearStaleAtTick(boolean existingTransaction) {
        return existingTransaction;
    }

    static boolean transactionContextMatches(boolean sameWorld, ShelfLocation expected, ShelfLocation actual) {
        return sameWorld && expected.equals(actual);
    }

    private boolean commitRemoval(ShelfRecord record) {
        return commitRemoval(record, record.removalTransaction);
    }

    private boolean commitRemoval(ShelfRecord record, UUID transaction) {
        if (record == null) return false;
        long generation = record.version + 3;
        try {
            journal.appendDelete(record, generation);
            data.delete(record.id, generation, record.location, transaction);
            return true;
        } catch (IOException exception) {
            WitcheryOptimizer.LOG
                .error("Physical shelf outcome could not be finalized; location remains quarantined", exception);
            return false;
        }
    }

    static RemovalOutcome removalOutcome(boolean shelfBlock, boolean exactOriginalTile, boolean dropsStarted) {
        if (shelfBlock && exactOriginalTile && !dropsStarted) return RemovalOutcome.RESTORE_EXACT_ORIGINAL;
        if (!shelfBlock) return RemovalOutcome.COMMIT_AND_UNLOCK;
        return RemovalOutcome.AWAIT_DURABLE_RECONCILIATION;
    }

    enum RemovalOutcome {
        RESTORE_EXACT_ORIGINAL,
        COMMIT_AND_UNLOCK,
        AWAIT_DURABLE_RECONCILIATION
    }

    public boolean shouldLockRemovalDrop(EntityItem item) {
        UUID transaction = removalDropTransaction(item.getEntityData());
        if (transaction == null) return false;
        if (data == null && !initialize()) return true;
        if (!lockRemovalDrop(item.getEntityData(), data.isCommittedRemoval(transaction))) {
            unlockRemovalDrop(item);
            return false;
        }
        return true;
    }

    static boolean lockRemovalDrop(NBTTagCompound tag, boolean committed) {
        return removalDropTransaction(tag) != null && !committed;
    }

    public boolean isAuthorizedCleanupRemoval(net.minecraft.world.World world, int x, int y, int z) {
        CleanupContext context = cleanupRemoval.get();
        return context != null && context.matches(world, x, y, z, world.getTileEntity(x, y, z));
    }

    public boolean suppressCleanupBreak(TileEntityPoppetShelf shelf) {
        CleanupContext context = cleanupRemoval.get();
        if (context == null || !context.matches(shelf.getWorldObj(), shelf.xCoord, shelf.yCoord, shelf.zCoord, shelf))
            return false;
        detach(shelf);
        return true;
    }

    static UUID removalDropTransaction(NBTTagCompound tag) {
        boolean most = tag.hasKey("WORemovalMost");
        boolean least = tag.hasKey("WORemovalLeast");
        boolean ordinal = tag.hasKey("WODropOrdinal");
        if (!most && !least && !ordinal) return null;
        if (!most || !least || !ordinal) return new UUID(0, 0);
        return new UUID(tag.getLong("WORemovalMost"), tag.getLong("WORemovalLeast"));
    }

    private void unlockLoadedDrops(UUID transaction) {
        for (WorldServer world : orderedServerWorlds())
            if (world != null) for (Object value : world.loadedEntityList) if (value instanceof EntityItem) {
                EntityItem item = (EntityItem) value;
                if (transaction.equals(removalDropTransaction(item.getEntityData()))) unlockRemovalDrop(item);
            }
    }

    private static void unlockRemovalDrop(EntityItem item) {
        NBTTagCompound tag = item.getEntityData();
        tag.removeTag("WORemovalMost");
        tag.removeTag("WORemovalLeast");
        tag.removeTag("WODropOrdinal");
        tag.removeTag("WORemovalLocked");
        item.delayBeforeCanPickup = 0;
        item.age = 0;
    }

    private void processLoadedCleanup() {
        for (ShelfRecord record : data.records()) {
            if (record.state != ShelfRecord.State.REMOVAL_CLEANUP_PENDING) continue;
            WorldServer world = DimensionManager.getWorld(record.location.dimension);
            if (world == null || !world.blockExists(record.location.x, record.location.y, record.location.z)) continue;
            TileEntity tile = world.getTileEntity(record.location.x, record.location.y, record.location.z);
            if (!(tile instanceof TileEntityPoppetShelf)) continue;
            try {
                if (!preparedLiveMatches(record, (TileEntityPoppetShelf) tile)) continue;
                CleanupContext context = new CleanupContext(world, record, (TileEntityPoppetShelf) tile);
                cleanupRemoval.set(context);
                boolean removed;
                try {
                    removed = world.setBlockToAir(record.location.x, record.location.y, record.location.z);
                } finally {
                    cleanupRemoval.remove();
                }
                if (removed && world.getBlock(record.location.x, record.location.y, record.location.z)
                    != com.emoniph.witchery.Witchery.Blocks.POPPET_SHELF) {
                    if (commitRemoval(record)) {
                        unlockLoadedDrops(record.removalTransaction);
                        censusAttempted = false;
                        journal.appendCensusState(CENSUS_VERSION, PoppetWorldData.CensusState.UNKNOWN);
                        data.setCensusState(CENSUS_VERSION, PoppetWorldData.CensusState.UNKNOWN);
                    }
                }
            } catch (IOException exception) {
                WitcheryOptimizer.LOG.error("Removal cleanup remains fail-closed at {}", record.location, exception);
            }
        }
    }

    private boolean preparedLiveMatches(ShelfRecord record, TileEntityPoppetShelf shelf) {
        PoppetShelfState state = (PoppetShelfState) shelf;
        return record.id.equals(state.witcheryoptimizer$getShelfId())
            && state.witcheryoptimizer$getDiskMirrorVersion() == record.removalSourceVersion
            && record.customName.equals(state.witcheryoptimizer$getCustomName())
            && inventoriesEqual(record.inventory, snapshot(shelf));
    }

    public void finalizeBreak(TileEntityPoppetShelf shelf) {
        ShelfLocation target = location(shelf);
        RemovalTransaction transaction = removal.get();
        if ((transaction == null || !transaction.location.equals(target))
            && !preRemove(shelf.getWorldObj(), shelf.xCoord, shelf.yCoord, shelf.zCoord))
            throw new IllegalStateException("Poppet shelf break reached without a durable removal tombstone");
        detach(shelf);
    }

    public boolean releaseWitcheryTicket(TileEntityPoppetShelf shelf, Ticket ticket) {
        if (!attach(shelf)) return false;
        if (ticket != null) ForgeChunkManager.releaseTicket(ticket);
        return true;
    }

    public boolean inspectWitcheryTickets(int dimension, List<Ticket> tickets, int maximum) {
        if (!initialize()) return false;
        boolean[] plausible = new boolean[tickets.size()];
        for (int i = 0; i < tickets.size(); i++) plausible[i] = plausibleWitcheryTicket(tickets.get(i));
        boolean accepted = importCoordinator.inspect(dimension, plausible, maximum);
        persistCoordinatorState();
        return accepted;
    }

    public void finishWitcheryTickets(int dimension, TicketBatch.BatchResult result) {
        importCoordinator.finish(dimension, result.imported, result.offered, result.releaseFailures);
        persistCoordinatorState();
    }

    private void persistCoordinatorState() {
        if (!initialize()) return;
        PoppetWorldData.ImportState state = importCoordinator.state();
        try {
            journal.appendImportState(state);
            data.setImportState(state);
            if (state == PoppetWorldData.ImportState.DRAINED_CLEAN
                || state == PoppetWorldData.ImportState.DRAINED_WITH_GAPS)
                WitcheryOptimizer.LOG.info(
                    "Witchery ticket drain completed: state={}, authoritativeShelves={}",
                    state,
                    data.records()
                        .size());
        } catch (IOException exception) {
            importCoordinator.fail();
            data.setImportState(PoppetWorldData.ImportState.DRAINED_WITH_GAPS);
            WitcheryOptimizer.LOG.error("Unable to persist Witchery ticket import state", exception);
        }
    }

    public boolean plausibleWitcheryTicket(Ticket ticket) {
        NBTTagCompound tag = ticket.getModData();
        return tag.hasKey("poppetX", 3) && tag.hasKey("poppetY", 3) && tag.hasKey("poppetZ", 3);
    }

    public boolean importWitcheryTicket(Ticket ticket, net.minecraft.world.World world) {
        if (world.isRemote || !plausibleWitcheryTicket(ticket) || !initialize()) return false;
        NBTTagCompound tag = ticket.getModData();
        TileEntity tile = world
            .getTileEntity(tag.getInteger("poppetX"), tag.getInteger("poppetY"), tag.getInteger("poppetZ"));
        if (!(tile instanceof TileEntityPoppetShelf)) {
            WitcheryOptimizer.LOG.error(
                "Restored Witchery ticket does not reference a live shelf at {},{},{}",
                tag.getInteger("poppetX"),
                tag.getInteger("poppetY"),
                tag.getInteger("poppetZ"));
            return false;
        }
        return attach((TileEntityPoppetShelf) tile);
    }

    private void runCensus() {
        int attempt = data.retryAttempt() + 1;
        WitcheryOptimizer.LOG.info(
            "Starting exhaustive shelf census: attempt={}, knownDimensions={}, authoritativeShelves={}",
            attempt,
            data.dimensionOrder()
                .size(),
            data.records()
                .size());
        try {
            journal.appendCensusState(CENSUS_VERSION, PoppetWorldData.CensusState.IN_PROGRESS);
            data.setCensusState(CENSUS_VERSION, PoppetWorldData.CensusState.IN_PROGRESS);
            importLoadedShelvesInObservedOrder();
            ShelfCensus.Snapshot census = ShelfCensus
                .scan(DimensionManager.getCurrentSaveRootDirectory(), data.dimensionOrder());
            List<Integer> loadedPrefix = new ArrayList<>();
            for (WorldServer world : orderedServerWorlds())
                if (world != null) loadedPrefix.add(world.provider.dimensionId);
            data.normalizeDimensionOrder(
                deterministicDimensionOrder(loadedPrefix, data.dimensionOrder(), census.dimensions.keySet()));
            rebuildAllowedDimensions();
            Set<ShelfLocation> reconciledRemovals = reconcilePreparedRemovals(census);
            for (ShelfCensus.Entry entry : census.entries) {
                ShelfLocation location = new ShelfLocation(
                    entry.dimension,
                    entry.tile.getInteger("x"),
                    entry.tile.getInteger("y"),
                    entry.tile.getInteger("z"));
                if (reconciledRemovals.contains(location)) continue;
                data.observeDimension(entry.dimension);
                LocationResolution existing = resolveLocation(location);
                if (existing.multiple)
                    throw new ShelfCensus.CensusException("Multiple authoritative records at " + location);
                boolean uuidMost = entry.tile.hasKey("WOShelfUuidMost");
                boolean uuidLeast = entry.tile.hasKey("WOShelfUuidLeast");
                if (uuidMost != uuidLeast) throw new ShelfCensus.CensusException("Partial shelf UUID at " + location);
                UUID physicalId = uuidMost
                    ? new UUID(entry.tile.getLong("WOShelfUuidMost"), entry.tile.getLong("WOShelfUuidLeast"))
                    : null;
                if (existing.record != null) {
                    if (!censusIdentityMatches(existing.record, physicalId, entry.tile))
                        throw new ShelfCensus.CensusException("Census identity/content conflict at " + location);
                    continue;
                }
                UUID id = physicalId == null ? UUID.randomUUID() : physicalId;
                ShelfRecord byId = data.get(id);
                if (byId != null && !byId.location.equals(location))
                    throw new ShelfCensus.CensusException("Census found copied shelf UUID " + id + " at " + location);
                ShelfRecord record = data
                    .newRecord(id, location, entry.tile.getString("CustomName"), items(entry.tile));
                journal.appendPost(record);
                data.install(record);
            }
            if (data.hasCleanupPendingRemovals()) {
                journal.appendCensusState(CENSUS_VERSION, PoppetWorldData.CensusState.UNKNOWN);
                data.setCensusState(CENSUS_VERSION, PoppetWorldData.CensusState.UNKNOWN);
                return;
            }
            if (data.hasPreparedRemovals())
                throw new ShelfCensus.CensusException("Unresolved prepared shelf removal remains");
            verifyActiveRecords(census);
            journal.appendCensusState(CENSUS_VERSION, PoppetWorldData.CensusState.COMPLETE);
            data.setCensusState(CENSUS_VERSION, PoppetWorldData.CensusState.COMPLETE);
            data.markDirty();
            WitcheryOptimizer.LOG.info(
                "Shelf census complete: dimensions={}, physicalShelves={}, authoritativeShelves={}, pendingWritebacks={}",
                census.dimensions.size(),
                census.entries.size(),
                data.records()
                    .size(),
                data.pendingWritebacks());
        } catch (IOException | RuntimeException exception) {
            boolean corruption = exception instanceof ShelfCensus.CensusException
                && ((ShelfCensus.CensusException) exception).isCorruption();
            long retryAt = System.currentTimeMillis() + RetryPolicy.delay(attempt, corruption);
            String reason = exception.getClass()
                .getSimpleName() + ": "
                + String.valueOf(exception.getMessage());
            if (reason.length() > 160) reason = reason.substring(0, 160);
            try {
                journal.appendCensusRetry(CENSUS_VERSION, attempt, retryAt, corruption, reason);
            } catch (IOException persistence) {
                exception.addSuppressed(persistence);
            }
            data.setCensusRetry(CENSUS_VERSION, attempt, retryAt, corruption, reason);
            censusAttempted = false;
            WitcheryOptimizer.LOG.error(
                "Exhaustive shelf census failed; lookup remains fail-closed, class={}, attempt={}, retryAt={}",
                corruption ? "corruption" : "transient",
                attempt,
                retryAt,
                exception);
        }

    }

    private void verifyActiveRecords(ShelfCensus.Snapshot census) throws IOException {
        Set<UUID> disk = new HashSet<>();
        for (ShelfCensus.Entry entry : census.entries) {
            ShelfLocation location = new ShelfLocation(
                entry.dimension,
                entry.tile.getInteger("x"),
                entry.tile.getInteger("y"),
                entry.tile.getInteger("z"));
            LocationResolution resolution = resolveLocation(location);
            if (resolution.record != null && resolution.record.state == ShelfRecord.State.ACTIVE
                && censusIdentityMatches(resolution.record, physicalId(entry.tile), entry.tile))
                disk.add(resolution.record.id);
        }
        for (ShelfRecord record : data.records()) {
            if (record.state != ShelfRecord.State.ACTIVE || disk.contains(record.id)) continue;
            TileEntityPoppetShelf shelf = loaded.get(record.id);
            if (!activeRecordProven(
                record.state,
                disk.contains(record.id),
                shelf != null && !shelf.isInvalid()
                    && location(shelf).equals(record.location)
                    && record.id.equals(attached.get(shelf))))
                throw new ShelfCensus.CensusException(
                    "Active authoritative shelf has no physical proof at " + record.location);
        }
    }

    static boolean activeRecordProven(ShelfRecord.State state, boolean exactDisk, boolean exactLoaded) {
        return state != ShelfRecord.State.ACTIVE || exactDisk || exactLoaded;
    }

    private Set<ShelfLocation> reconcilePreparedRemovals(ShelfCensus.Snapshot census) throws IOException {
        Map<ShelfLocation, ShelfCensus.Entry> physical = new HashMap<>();
        for (ShelfCensus.Entry entry : census.entries) {
            ShelfLocation location = new ShelfLocation(
                entry.dimension,
                entry.tile.getInteger("x"),
                entry.tile.getInteger("y"),
                entry.tile.getInteger("z"));
            if (physical.put(location, entry) != null)
                throw new ShelfCensus.CensusException("Multiple physical shelves at " + location);
        }
        Set<ShelfLocation> handled = new HashSet<>();
        for (ShelfRecord record : data.records()) {
            if (record.state != ShelfRecord.State.REMOVAL_PREPARED
                && record.state != ShelfRecord.State.REMOVAL_CLEANUP_PENDING) continue;
            ShelfCensus.Entry shelf = physical.get(record.location);
            boolean exactShelf = shelf != null && preparedPhysicalMatches(record, shelf.tile);
            ShelfCensus.DropEvidence drops = census.drops.get(record.removalTransaction);
            boolean hasDrops = drops != null && !drops.isEmpty();
            boolean completeDrops = drops != null && drops.completelyMatches(record.inventory);
            RemovalRecovery recovery = removalRecovery(
                shelf != null,
                exactShelf,
                record.removalDropsStarted,
                hasDrops,
                completeDrops);
            if (recovery == RemovalRecovery.UNRESOLVED) throw new ShelfCensus.CensusException(
                "Cannot safely reconcile prepared shelf removal at " + record.location);
            if (recovery == RemovalRecovery.RESTORE) {
                ShelfRecord restored = ShelfRecord.read(record.write());
                restored.version++;
                restored.state = ShelfRecord.State.ACTIVE;
                restored.removalTransaction = null;
                restored.writebackPending = true;
                journal.appendPost(restored);
                data.install(restored);
            } else if (recovery == RemovalRecovery.DELETE) {
                commitRemoval(record);
            } else {
                ShelfRecord cleanup = ShelfRecord.read(record.write());
                cleanup.version++;
                cleanup.state = ShelfRecord.State.REMOVAL_CLEANUP_PENDING;
                journal.appendPost(cleanup);
                data.install(cleanup);
            }
            handled.add(record.location);
        }
        return handled;
    }

    private static UUID physicalId(NBTTagCompound tile) throws IOException {
        boolean most = tile.hasKey("WOShelfUuidMost");
        boolean least = tile.hasKey("WOShelfUuidLeast");
        if (most != least) throw new IOException("Partial physical shelf UUID");
        return most ? new UUID(tile.getLong("WOShelfUuidMost"), tile.getLong("WOShelfUuidLeast")) : null;
    }

    static RemovalRecovery removalRecovery(boolean shelfPresent, boolean exactShelf, boolean dropsStarted,
        boolean hasDrops, boolean completeDrops) {
        if (shelfPresent && exactShelf && !hasDrops) return RemovalRecovery.RESTORE;
        if (!shelfPresent && dropsStarted && completeDrops) return RemovalRecovery.DELETE;
        if (shelfPresent && exactShelf && completeDrops) return RemovalRecovery.CLEANUP_PENDING;
        return RemovalRecovery.UNRESOLVED;
    }

    static boolean preparedPhysicalMatches(ShelfRecord record, NBTTagCompound tile) throws IOException {
        UUID id = physicalId(tile);
        return id != null && id.equals(record.id)
            && tile.hasKey("WOWritebackVersion")
            && tile.getLong("WOWritebackVersion") == record.removalSourceVersion
            && record.customName.equals(tile.getString("CustomName"))
            && inventoriesEqual(record.inventory, items(tile));
    }

    private static boolean inventoriesEqual(ItemStack[] left, ItemStack[] right) {
        for (int i = 0; i < left.length; i++) {
            NBTTagCompound leftTag = new NBTTagCompound();
            NBTTagCompound rightTag = new NBTTagCompound();
            if (left[i] != null) left[i].writeToNBT(leftTag);
            if (right[i] != null) right[i].writeToNBT(rightTag);
            if (!leftTag.equals(rightTag)) return false;
        }
        return true;
    }

    enum RemovalRecovery {
        RESTORE,
        DELETE,
        CLEANUP_PENDING,
        UNRESOLVED
    }

    private void importLoadedShelvesInObservedOrder() throws IOException {
        for (WorldServer world : orderedServerWorlds()) {
            if (world == null) continue;
            data.observeDimension(world.provider.dimensionId);
            for (Object value : world.loadedTileEntityList) if (value instanceof TileEntityPoppetShelf) {
                TileEntityPoppetShelf shelf = (TileEntityPoppetShelf) value;
                ShelfLocation shelfLocation = location(shelf);
                LocationResolution resolution = resolveLocation(shelfLocation);
                if (resolution.record != null && resolution.record.state == ShelfRecord.State.REMOVAL_PREPARED)
                    continue;
                PoppetShelfState state = (PoppetShelfState) shelf;
                UUID id = state.witcheryoptimizer$getShelfId();
                ShelfRecord byId = id == null ? null : data.get(id);
                boolean duplicateLoaded = id != null && loaded.containsKey(id) && loaded.get(id) != shelf;
                if (loadedAuthorityConflict(
                    state.witcheryoptimizer$hasPersistentShelfId(),
                    id,
                    shelfLocation,
                    byId,
                    resolution.record,
                    resolution.multiple,
                    duplicateLoaded,
                    id != null && data.isTombstoned(id)))
                    throw new ShelfCensus.CensusException(
                        "Loaded shelf has conflicting persistent authority at " + shelfLocation);
                if (!attach(shelf)) throw new IOException(
                    "Loaded shelf reconciliation failed in dimension " + world.provider.dimensionId);
            }
        }
    }

    static boolean loadedAuthorityConflict(boolean persistent, UUID id, ShelfLocation location, ShelfRecord byId,
        ShelfRecord atLocation, boolean multipleAtLocation, boolean duplicateLoaded, boolean tombstoned) {
        if (!persistent || id == null) return multipleAtLocation;
        return tombstoned || duplicateLoaded
            || byId != null && !byId.location.equals(location)
            || atLocation != null && !atLocation.id.equals(id)
            || multipleAtLocation;
    }

    static boolean censusIdentityMatches(ShelfRecord existing, UUID physicalId, NBTTagCompound tile)
        throws IOException {
        if (physicalId != null) return existing.id.equals(physicalId);
        if (!existing.customName.equals(tile.getString("CustomName"))) return false;
        ItemStack[] physical = items(tile);
        for (int i = 0; i < physical.length; i++) {
            NBTTagCompound left = new NBTTagCompound();
            NBTTagCompound right = new NBTTagCompound();
            if (existing.inventory[i] != null) existing.inventory[i].writeToNBT(left);
            if (physical[i] != null) physical[i].writeToNBT(right);
            if (!left.equals(right)) return false;
        }
        return true;
    }

    private static ItemStack[] items(NBTTagCompound tile) throws IOException {
        ItemStack[] result = new ItemStack[9];
        net.minecraft.nbt.NBTTagList items = tile.getTagList("Items", 10);
        for (int i = 0; i < items.tagCount(); i++) {
            NBTTagCompound item = items.getCompoundTagAt(i);
            int slot = item.getByte("Slot") & 255;
            if (slot >= result.length) throw new IOException("Invalid shelf slot " + slot);
            result[slot] = ItemStack.loadItemStackFromNBT(item);
            if (result[slot] == null) throw new IOException("Corrupt shelf item in slot " + slot);
        }
        return result;
    }

    public ItemStack find(EntityPlayer player, Matcher matcher) {
        if (!initialize()
            || (data.importState() != PoppetWorldData.ImportState.COMPLETE
                && data.importState() != PoppetWorldData.ImportState.DRAINED_CLEAN
                && data.importState() != PoppetWorldData.ImportState.DRAINED_WITH_GAPS)
            || !data.censusComplete(CENSUS_VERSION)) return null;
        Set<UUID> visited = new HashSet<>();
        for (WorldServer world : orderedServerWorlds()) {
            if (world == null || !allowedDimensions.contains(world.provider.dimensionId)) continue;
            for (Object value : world.loadedTileEntityList) {
                if (!(value instanceof TileEntityPoppetShelf)) continue;
                TileEntityPoppetShelf shelf = (TileEntityPoppetShelf) value;
                if (!attach(shelf)) continue;
                UUID id = attached.get(shelf);
                ShelfRecord record = data.get(id);
                if (record == null || record.state != ShelfRecord.State.ACTIVE || !visited.add(id)) continue;
                ItemStack result = consume(player, matcher, record);
                if (result != null) return result;
            }
        }
        for (Integer dimension : data.dimensionOrder()) {
            if (!allowedDimensions.contains(dimension)) continue;
            for (ShelfRecord record : recordsIn(dimension)) {
                if (!visited.add(record.id) || record.state != ShelfRecord.State.ACTIVE) continue;
                ItemStack result = consume(player, matcher, record);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static WorldServer[] orderedServerWorlds() {
        MinecraftServer server = MinecraftServer.getServer();
        return server == null || server.worldServers == null ? new WorldServer[0] : server.worldServers;
    }

    static boolean canConfirmMirror(boolean sameLocation, boolean duplicateInstance, ShelfRecord.State state,
        boolean validIdentity) {
        return sameLocation && !duplicateInstance && state == ShelfRecord.State.ACTIVE && validIdentity;
    }

    private ItemStack consume(EntityPlayer player, Matcher matcher, ShelfRecord record) {
        ShelfRecord post = ShelfRecord.read(record.write());
        ItemStack result = matcher.find(player, new ShelfInventory(post));
        if (result == null) return null;
        post.version++;
        post.writebackPending = true;
        try {
            journal.appendPost(post);
        } catch (IOException exception) {
            WitcheryOptimizer.LOG.error("Poppet protection denied because its consumption journal failed", exception);
            return null;
        }
        data.install(post);
        data.markDirty();
        TileEntityPoppetShelf shelf = loaded.get(post.id);
        if (shelf != null && !shelf.isInvalid() && location(shelf).equals(post.location)) {
            mirror(post, shelf);
            shelf.markDirty();
        }
        return result;
    }

    private LocationResolution resolveLocation(ShelfLocation location) {
        ShelfRecord found = null;
        for (ShelfRecord candidate : data.records()) {
            if (!candidate.location.equals(location)) continue;
            if (found != null) return new LocationResolution(null, true);
            found = candidate;
        }
        return new LocationResolution(found, false);
    }

    static boolean shouldRestoreRemoval(boolean oldShelfBlock, boolean exactOriginalTile) {
        return oldShelfBlock && exactOriginalTile;
    }

    private static final class LocationResolution {

        final ShelfRecord record;
        final boolean multiple;

        LocationResolution(ShelfRecord record, boolean multiple) {
            this.record = record;
            this.multiple = multiple;
        }
    }

    static boolean cleanupContextMatches(ShelfLocation expectedLocation, UUID expectedShelf, UUID expectedTransaction,
        int actualDimension, int x, int y, int z, UUID actualShelf, UUID actualTransaction, boolean sameWorld,
        boolean sameTile) {
        return sameWorld && sameTile
            && expectedLocation.equals(new ShelfLocation(actualDimension, x, y, z))
            && expectedShelf.equals(actualShelf)
            && expectedTransaction.equals(actualTransaction);
    }

    private static final class CleanupContext {

        final net.minecraft.world.World world;
        final ShelfLocation location;
        final UUID shelfId;
        final UUID transaction;
        final TileEntityPoppetShelf shelf;

        CleanupContext(net.minecraft.world.World world, ShelfRecord record, TileEntityPoppetShelf shelf) {
            this.world = world;
            location = record.location;
            shelfId = record.id;
            transaction = record.removalTransaction;
            this.shelf = shelf;
        }

        boolean matches(net.minecraft.world.World candidateWorld, int x, int y, int z, TileEntity candidateTile) {
            UUID actualId = candidateTile instanceof PoppetShelfState
                ? ((PoppetShelfState) candidateTile).witcheryoptimizer$getShelfId()
                : null;
            return actualId != null && cleanupContextMatches(
                location,
                shelfId,
                transaction,
                candidateWorld.provider.dimensionId,
                x,
                y,
                z,
                actualId,
                transaction,
                world == candidateWorld,
                shelf == candidateTile);
        }
    }

    private static final class RemovalTransaction {

        final net.minecraft.world.World world;
        final ShelfLocation location;
        final ShelfRecord before;
        final TileEntityPoppetShelf shelf;
        final UUID transaction;
        int nextDrop;

        RemovalTransaction(net.minecraft.world.World world, ShelfLocation location, ShelfRecord before,
            TileEntityPoppetShelf shelf, UUID transaction) {
            this.world = world;
            this.location = location;
            this.before = before;
            this.shelf = shelf;
            this.transaction = transaction;
        }

        boolean matchesWorld(net.minecraft.world.World candidate) {
            return world == candidate;
        }

        boolean matches(net.minecraft.world.World candidate, ShelfLocation target) {
            return transactionContextMatches(world == candidate, location, target);
        }
    }

    public interface Matcher {

        ItemStack find(EntityPlayer player, IInventory inventory);
    }

    private synchronized boolean initialize() {
        if (data != null) return true;
        if (serverTick < nextInitializationTick) return false;
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.worldServers == null) return false;
        WorldServer primary = null;
        for (WorldServer world : server.worldServers) {
            if (world != null) {
                primary = world;
                break;
            }
        }
        if (primary == null) return false;
        try {
            data = PoppetWorldData.get(primary);
            journal = new ShelfJournal(primary);
            journal.recover(data);
            PoppetWorldData.ImportState recovered = data.importState();
            importCoordinator.resume(recovered);
            if (recovered == PoppetWorldData.ImportState.IN_PROGRESS
                || recovered == PoppetWorldData.ImportState.FAILED) {
                journal.appendImportState(PoppetWorldData.ImportState.DRAINED_WITH_GAPS);
                data.setImportState(PoppetWorldData.ImportState.DRAINED_WITH_GAPS);
                importCoordinator.resume(PoppetWorldData.ImportState.DRAINED_WITH_GAPS);
            }
            if (data.censusState() == PoppetWorldData.CensusState.IN_PROGRESS
                || data.censusState() == PoppetWorldData.CensusState.FAILED) {
                long at = System.currentTimeMillis() + RetryPolicy.TRANSIENT_BASE;
                journal.appendCensusRetry(CENSUS_VERSION, 1, at, false, "Interrupted census");
                data.setCensusRetry(CENSUS_VERSION, 1, at, false, "Interrupted census");
            }
            if (requiresRemovalCensus(data.censusComplete(CENSUS_VERSION), data.hasPreparedRemovals())) {
                journal.appendCensusState(CENSUS_VERSION, PoppetWorldData.CensusState.UNKNOWN);
                data.setCensusState(CENSUS_VERSION, PoppetWorldData.CensusState.UNKNOWN);
            }
            rebuildAllowedDimensions();
            initializationAttempts = 0;
            WitcheryOptimizer.LOG.info(
                "Witchery Optimizer initialized: importState={}, censusState={}, authoritativeShelves={}, dimensions={}, pendingWritebacks={}, retryAttempt={}, retryAt={}, retryReason={}",
                data.importState(),
                data.censusState(),
                data.records()
                    .size(),
                data.dimensionOrder()
                    .size(),
                data.pendingWritebacks(),
                data.retryAttempt(),
                data.retryAt(System.currentTimeMillis()),
                data.retryReason());
            return true;
        } catch (IOException | RuntimeException exception) {
            WitcheryOptimizer.LOG.error("Witchery Optimizer storage initialization failed closed", exception);
            data = null;
            journal = null;
            initializationAttempts++;
            nextInitializationTick = serverTick + RetryPolicy.initializationDelayTicks(initializationAttempts);
            return false;
        }
    }

    private void refreshAllowedDimensions() {
        rebuildAllowedDimensions();
    }

    private void rebuildAllowedDimensions() {
        allowedDimensions.clear();
        List<Integer> loadedPrefix = new ArrayList<>();
        for (WorldServer world : orderedServerWorlds()) if (world != null) loadedPrefix.add(world.provider.dimensionId);
        Set<Integer> discovered = new HashSet<>();
        Collections.addAll(discovered, DimensionManager.getStaticDimensionIDs());
        Collections.addAll(discovered, DimensionManager.getIDs());
        for (WorldServer world : DimensionManager.getWorlds())
            if (world != null) discovered.add(world.provider.dimensionId);
        List<Integer> ordered = deterministicDimensionOrder(loadedPrefix, data.dimensionOrder(), discovered);
        data.normalizeDimensionOrder(ordered);
        Config config = Config.instance();
        allowedDimensions.addAll(
            allowedDimensions(
                ordered,
                config.restrictPoppetShelvesToVanillaAndSpiritDimensions,
                config.dimensionDreamID));
    }

    static List<Integer> deterministicDimensionOrder(Iterable<Integer> loaded, Iterable<Integer> persisted,
        Iterable<Integer> discovered) {
        List<Integer> result = new ArrayList<>();
        for (Integer dimension : loaded) if (!result.contains(dimension)) result.add(dimension);
        for (Integer dimension : persisted) if (!result.contains(dimension)) result.add(dimension);
        List<Integer> remaining = new ArrayList<>();
        for (Integer dimension : discovered) if (!result.contains(dimension)) remaining.add(dimension);
        Collections.sort(remaining);
        result.addAll(remaining);
        return result;
    }

    static Set<Integer> allowedDimensions(Iterable<Integer> discovered, boolean restricted, int dreamDimension) {
        Set<Integer> result = new HashSet<>();
        for (Integer dimension : discovered)
            if (!restricted || dimension == 0 || dimension == -1 || dimension == 1 || dimension == dreamDimension)
                result.add(dimension);
        return result;
    }

    private List<ShelfRecord> recordsIn(int dimension) {
        List<ShelfRecord> result = new ArrayList<>();
        for (ShelfRecord record : data.records()) if (record.location.dimension == dimension) result.add(record);
        Collections.sort(result, Comparator.comparingLong(value -> value.order));
        return result;
    }

    private boolean usable(TileEntityPoppetShelf shelf) {
        return shelf != null && shelf.getWorldObj() != null && !shelf.getWorldObj().isRemote;
    }

    private ShelfLocation location(TileEntityPoppetShelf shelf) {
        return new ShelfLocation(shelf.getWorldObj().provider.dimensionId, shelf.xCoord, shelf.yCoord, shelf.zCoord);
    }

    private ItemStack[] snapshot(TileEntityPoppetShelf shelf) {
        ItemStack[] result = new ItemStack[9];
        for (int i = 0; i < result.length; i++) result[i] = ShelfRecord.copy(shelf.getStackInSlot(i));
        return result;
    }

    private void mirror(ShelfRecord record, TileEntityPoppetShelf shelf) {
        synchronizing.set(true);
        try {
            ((PoppetShelfState) shelf).witcheryoptimizer$setCustomName(record.customName);
            for (int i = 0; i < record.inventory.length; i++)
                shelf.setInventorySlotContents(i, ShelfRecord.copy(record.inventory[i]));
        } finally {
            synchronizing.remove();
        }
    }
}
