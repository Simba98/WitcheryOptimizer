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

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.emoniph.witchery.blocks.BlockPoppetShelf.TileEntityPoppetShelf;
import com.emoniph.witchery.util.Config;
import com.github.witcheryoptimizer.WitcheryOptimizer;
import com.github.witcheryoptimizer.migration.TicketBatch;
import com.github.witcheryoptimizer.migration.WitcheryImportCoordinator;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public final class PoppetRegistry {

    public enum SetBlockRemoval {
        AUTHORITATIVE,
        PHYSICAL_CLEANUP,
        TRANSIENT_FAILURE
    }

    private enum RemovalMode {
        AUTHORITATIVE,
        PHYSICAL_CLEANUP
    }

    enum StartupValidationDecision {
        DELETE,
        RETRY,
        MIRROR
    }

    private static final PoppetRegistry INSTANCE = new PoppetRegistry();
    private static final int VALIDATION_VERSION = 3;
    private final Map<TileEntityPoppetShelf, UUID> attached = new IdentityHashMap<>();
    private final Map<UUID, TileEntityPoppetShelf> loaded = new HashMap<>();
    private final Set<Integer> allowedDimensions = new HashSet<>();
    private final List<Integer> dimensionOrder = new ArrayList<>();
    private final ThreadLocal<Boolean> synchronizing = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<ShelfRecord> startupValidation = new ThreadLocal<>();
    private final ThreadLocal<DiskLoadObservation> startupDiskLoad = new ThreadLocal<>();
    private PoppetWorldData data;
    private ShelfJournal journal;
    private final ThreadLocal<RemovalContext> pendingRemoval = new ThreadLocal<>();
    private final ThreadLocal<RemovalContext> activeRemoval = new ThreadLocal<>();
    private final PlacementAuthorizations<TileEntityPoppetShelf> placementAuthorizations = new PlacementAuthorizations<>();
    private final WitcheryImportCoordinator importCoordinator = new WitcheryImportCoordinator();
    private long serverTick;
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
        allowedDimensions.clear();
        dimensionOrder.clear();
        data = null;
        journal = null;
        pendingRemoval.remove();
        activeRemoval.remove();
        placementAuthorizations.clear();
        importCoordinator.resetForServerStop();
        nextInitializationTick = 0;
        initializationAttempts = 0;
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (!event.world.isRemote && startupValidation.get() == null && initialize()) refreshAllowedDimensions();
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (!event.world.isRemote) {
            placementAuthorizations.clear();
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
            if (data != null && shouldFinalizeImport(data.importState()) && importCoordinator.finalizeStartup())
                persistCoordinatorState();
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

    static boolean quarantineDuringStartupValidation(ShelfRecord.State state) {
        return state != ShelfRecord.State.ACTIVE;
    }

    public boolean attach(TileEntityPoppetShelf shelf) {
        if (!usable(shelf) || !initialize()) return false;
        PoppetShelfState state = (PoppetShelfState) shelf;
        UUID id = state.witcheryoptimizer$getShelfId();
        ShelfLocation location = location(shelf);
        ShelfRecord validating = startupValidation.get();
        if (validating != null && validating.location.equals(location)) {
            // Chunk loading invokes Witchery initiate(). Non-active records must remain physically untouched
            // until validation has enough positive evidence to choose a recovery action.
            if (validating.state != ShelfRecord.State.ACTIVE) return false;
            if (!startupIdentityMatches(
                state.witcheryoptimizer$hasPersistentShelfId(),
                state.witcheryoptimizer$getShelfId(),
                validating.id)) return false;
            TileEntityPoppetShelf duplicate = loaded.get(validating.id);
            if (duplicate != null && duplicate != shelf) return false;
            mirror(validating, shelf);
            attached.put(shelf, validating.id);
            loaded.put(validating.id, shelf);
            return true;
        }
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
        if (removalSuppressesShelf(shelf) || Boolean.TRUE.equals(synchronizing.get())
            || !usable(shelf)
            || !initialize()) return;
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
        if (removalSuppressesShelf(shelf) || !usable(shelf) || !initialize()) return;
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

    public SetBlockRemoval beginSetBlock(net.minecraft.world.World world, int x, int y, int z) {
        if (world.isRemote || !initialize()) return SetBlockRemoval.TRANSIENT_FAILURE;
        if (!canBeginRemoval(pendingRemoval.get() != null, activeRemoval.get() != null))
            return SetBlockRemoval.TRANSIENT_FAILURE;
        ShelfLocation target = new ShelfLocation(world.provider.dimensionId, x, y, z);
        placementAuthorizations.removeLocation(target);
        TileEntity tile = world.getTileEntity(target.x, target.y, target.z);
        if (!(tile instanceof TileEntityPoppetShelf)) {
            LocationResolution resolution = resolveLocation(target);
            MissingTileRemoval decision = classifyMissingTileRemoval(resolution.record != null, resolution.multiple);
            if (decision == MissingTileRemoval.TRANSIENT_FAILURE) return SetBlockRemoval.TRANSIENT_FAILURE;
            if (decision == MissingTileRemoval.DELETE_THEN_CLEANUP
                && !deleteStaleAuthority(resolution.record, "exact shelf TE is absent during removal"))
                return SetBlockRemoval.TRANSIENT_FAILURE;
            pendingRemoval.set(RemovalContext.cleanup(world, target, null));
            return SetBlockRemoval.PHYSICAL_CLEANUP;
        }
        TileEntityPoppetShelf shelf = (TileEntityPoppetShelf) tile;
        // Resolve and attach before publishing either removal context. Publishing first would make
        // normal lifecycle callbacks observe a removal that has not yet been durably committed.
        if (!attach(shelf)) {
            if (!physicalCleanupCandidate(shelf, target)) return SetBlockRemoval.TRANSIENT_FAILURE;
            pendingRemoval.set(RemovalContext.cleanup(world, target, shelf));
            return SetBlockRemoval.PHYSICAL_CLEANUP;
        }
        ShelfRecord record = data.get(attached.get(shelf));
        if (record == null || record.state != ShelfRecord.State.ACTIVE || !record.location.equals(target))
            return SetBlockRemoval.TRANSIENT_FAILURE;
        ItemStack[] snapshot = copyInventory(record.inventory);
        long generation = record.version + 1;
        try {
            journal.appendDelete(record, generation);
            data.delete(record.id, generation, record.location);
        } catch (IOException exception) {
            WitcheryOptimizer.LOG
                .error("Shelf removal denied because its authority deletion was not durable", exception);
            return SetBlockRemoval.TRANSIENT_FAILURE;
        }
        pendingRemoval.set(RemovalContext.authoritative(world, target, shelf, snapshot));
        return SetBlockRemoval.AUTHORITATIVE;
    }

    private boolean deleteStaleAuthority(ShelfRecord record, String reason) {
        long generation = record.version + 1;
        try {
            journal.appendDelete(record, generation);
            data.delete(record.id, generation, record.location, record.removalTransaction);
            WitcheryOptimizer.LOG.warn("Deleted stale shelf authority at {}: {}", record.location, reason);
            return true;
        } catch (IOException exception) {
            WitcheryOptimizer.LOG
                .error("Shelf cleanup denied because stale authority deletion was not durable", exception);
            return false;
        }
    }

    private boolean physicalCleanupCandidate(TileEntityPoppetShelf shelf, ShelfLocation target) {
        PoppetShelfState state = (PoppetShelfState) shelf;
        UUID id = state.witcheryoptimizer$getShelfId();
        LocationResolution atTarget = resolveLocation(target);
        return shelf.isInvalid() || atTarget.multiple
            || data.isTombstonedLocation(target)
            || state.witcheryoptimizer$hasPersistentShelfId() && (id == null || data.isTombstoned(id)
                || data.get(id) != null && !data.get(id).location.equals(target)
                || atTarget.record != null && !atTarget.record.id.equals(id));
    }

    public ItemStack authoritativeRemovalStack(TileEntityPoppetShelf shelf, int slot) {
        RemovalContext context = activeRemoval.get();
        if (context != null && context.mode == RemovalMode.PHYSICAL_CLEANUP) return null;
        if (!canServeRemovalSnapshot(context != null, context != null && context.shelf == shelf) || slot < 0
            || slot >= context.inventory.length) return shelf.getStackInSlot(slot);
        return context.inventory[slot];
    }

    public boolean beginWitcheryDrops(net.minecraft.world.World world, int x, int y, int z) {
        RemovalContext context = exactContext(pendingRemoval.get(), world, x, y, z);
        if (!canActivateRemoval(context != null, activeRemoval.get() != null)) return false;
        activeRemoval.set(context);
        return true;
    }

    public void finishWitcheryDrops(net.minecraft.world.World world, int x, int y, int z, boolean activated) {
        if (!activated) return;
        RemovalContext context = exactContext(activeRemoval.get(), world, x, y, z);
        if (context == null) return;
        activeRemoval.remove();
        if (pendingRemoval.get() == context) pendingRemoval.remove();
        detach(context.shelf);
    }

    public void finishSetBlock(net.minecraft.world.World world, int x, int y, int z, boolean succeeded) {
        RemovalContext context = exactContext(pendingRemoval.get(), world, x, y, z);
        if (!shouldClearPendingAtReturn(context != null)) return;
        pendingRemoval.remove();
        if (context.mode == RemovalMode.AUTHORITATIVE) WitcheryOptimizer.LOG.warn(
            "Shelf authority was deleted but Witchery drop code was skipped at {}; drops will not replay",
            context.location);
        detach(context.shelf);
    }

    private RemovalContext exactContext(RemovalContext context, net.minecraft.world.World world, int x, int y, int z) {
        if (context == null) return null;
        ShelfLocation location = new ShelfLocation(world.provider.dimensionId, x, y, z);
        return context.matches(world, location) ? context : null;
    }

    private boolean removalSuppressesShelf(TileEntityPoppetShelf shelf) {
        RemovalContext pending = pendingRemoval.get();
        RemovalContext active = activeRemoval.get();
        return contextOwnsShelf(pending != null, pending == null ? null : pending.shelf, shelf)
            || contextOwnsShelf(active != null, active == null ? null : active.shelf, shelf);
    }

    static boolean canBeginRemoval(boolean pending, boolean active) {
        return !pending && !active;
    }

    static boolean canActivateRemoval(boolean exactPending, boolean active) {
        return exactPending && !active;
    }

    static boolean shouldClearPendingAtReturn(boolean exactPending) {
        return exactPending;
    }

    static boolean contextOwnsShelf(boolean contextPresent, Object owner, Object shelf) {
        return contextPresent && owner == shelf;
    }

    static boolean canServeRemovalSnapshot(boolean active, boolean exactShelf) {
        return active && exactShelf;
    }

    static boolean startupIdentityMatches(boolean persistent, UUID physical, UUID authoritative) {
        return persistent && physical != null && physical.equals(authoritative);
    }

    static SetBlockRemoval classifyRemoval(boolean attached, boolean cleanupCandidate) {
        if (attached) return SetBlockRemoval.AUTHORITATIVE;
        return cleanupCandidate ? SetBlockRemoval.PHYSICAL_CLEANUP : SetBlockRemoval.TRANSIENT_FAILURE;
    }

    enum MissingTileRemoval {
        CLEANUP,
        DELETE_THEN_CLEANUP,
        TRANSIENT_FAILURE
    }

    static MissingTileRemoval classifyMissingTileRemoval(boolean authorityAtTarget, boolean multipleAtTarget) {
        if (multipleAtTarget) return MissingTileRemoval.TRANSIENT_FAILURE;
        return authorityAtTarget ? MissingTileRemoval.DELETE_THEN_CLEANUP : MissingTileRemoval.CLEANUP;
    }

    private void clearStaleRemovalAtTick() {
        RemovalContext stale = pendingRemoval.get();
        if (stale == null) return;
        pendingRemoval.remove();
        WitcheryOptimizer.LOG.warn(
            stale.mode == RemovalMode.AUTHORITATIVE
                ? "Cleared interrupted at-most-once shelf removal at {}; drops will not replay"
                : "Cleared interrupted physical shelf cleanup at {}; inventory remains quarantined",
            stale.location);
    }

    static boolean deletionBeforeSpawn(boolean deletionDurable, boolean removalSucceeded) {
        return deletionDurable && removalSucceeded;
    }

    private static ItemStack[] copyInventory(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int slot = 0; slot < source.length; slot++) copy[slot] = ShelfRecord.copy(source[slot]);
        return copy;
    }

    public boolean releaseWitcheryTicket(TileEntityPoppetShelf shelf, Ticket ticket) {
        if (startupValidation.get() != null) {
            if (ticket != null) ForgeChunkManager.releaseTicket(ticket);
            return true;
        }
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
                    throw new IOException("Loaded shelf has conflicting persistent authority at " + shelfLocation);
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
            || !data.censusComplete(VALIDATION_VERSION)) return null;
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

    private static final class LocationResolution {

        final ShelfRecord record;
        final boolean multiple;

        LocationResolution(ShelfRecord record, boolean multiple) {
            this.record = record;
            this.multiple = multiple;
        }
    }

    private static final class RemovalContext {

        final net.minecraft.world.World world;
        final ShelfLocation location;
        final TileEntityPoppetShelf shelf;
        final ItemStack[] inventory;
        final RemovalMode mode;

        RemovalContext(net.minecraft.world.World world, ShelfLocation location, TileEntityPoppetShelf shelf,
            ItemStack[] inventory, RemovalMode mode) {
            this.world = world;
            this.location = location;
            this.shelf = shelf;
            this.inventory = inventory;
            this.mode = mode;
        }

        static RemovalContext authoritative(net.minecraft.world.World world, ShelfLocation location,
            TileEntityPoppetShelf shelf, ItemStack[] inventory) {
            return new RemovalContext(world, location, shelf, inventory, RemovalMode.AUTHORITATIVE);
        }

        static RemovalContext cleanup(net.minecraft.world.World world, ShelfLocation location,
            TileEntityPoppetShelf shelf) {
            return new RemovalContext(world, location, shelf, new ItemStack[0], RemovalMode.PHYSICAL_CLEANUP);
        }

        boolean matches(net.minecraft.world.World candidate, ShelfLocation target) {
            return world == candidate && location.equals(target);
        }
    }

    public interface Matcher {

        ItemStack find(EntityPlayer player, IInventory inventory);
    }

    private void validateAuthorities() throws IOException {
        List<ShelfRecord> candidates = new ArrayList<>(data.records());
        int validated = 0;
        int deleted = 0;
        WitcheryOptimizer.LOG.info("Starting startup authority validation: records={}", candidates.size());
        journal.appendCensusState(VALIDATION_VERSION, PoppetWorldData.CensusState.IN_PROGRESS);
        data.setCensusState(VALIDATION_VERSION, PoppetWorldData.CensusState.IN_PROGRESS);
        for (ShelfRecord record : candidates) {
            if (record.state != ShelfRecord.State.ACTIVE) {
                long generation = record.version + 1;
                journal.appendDelete(record, generation);
                data.delete(record.id, generation, record.location, record.removalTransaction);
                deleted++;
                WitcheryOptimizer.LOG
                    .warn("Tombstoned legacy interrupted removal at {}: no drops will be replayed", record.location);
                continue;
            }
            String failure = validateAuthority(record);
            if (failure == null) {
                validated++;
                continue;
            }
            long generation = record.version + 1;
            journal.appendDelete(record, generation);
            data.delete(record.id, generation, record.location, record.removalTransaction);
            deleted++;
            WitcheryOptimizer.LOG.warn(
                "Deleted startup shelf authority at {} (dimension {}): {}",
                record.location,
                record.location.dimension,
                failure);
        }
        journal.appendCensusState(VALIDATION_VERSION, PoppetWorldData.CensusState.COMPLETE);
        data.setCensusState(VALIDATION_VERSION, PoppetWorldData.CensusState.COMPLETE);
        WitcheryOptimizer.LOG
            .info("Startup authority validation complete: validated={}, deleted={}, pending=0", validated, deleted);
    }

    private String validateAuthority(ShelfRecord record) throws IOException {
        int dimension = record.location.dimension;
        if (!DimensionManager.isDimensionRegistered(dimension))
            throw new IOException("Authority dimension " + dimension + " is not currently registered");
        WorldServer world = DimensionManager.getWorld(dimension);
        boolean worldWasLoaded = world != null;
        if (world == null) {
            DimensionManager.initDimension(dimension);
            world = DimensionManager.getWorld(dimension);
        }
        if (world == null) throw new IOException("Registered dimension " + dimension + " could not be initialized");
        int chunkX = record.location.x >> 4;
        int chunkZ = record.location.z >> 4;
        boolean chunkWasLoaded = world.theChunkProviderServer.chunkExists(chunkX, chunkZ);
        if (!chunkWasLoaded) {
            if (!(world.theChunkProviderServer.currentChunkLoader instanceof AnvilChunkLoader))
                throw new IOException("Authority validation requires an observable Anvil chunk loader");
            try {
                if (!((AnvilChunkLoader) world.theChunkProviderServer.currentChunkLoader)
                    .chunkExists(world, chunkX, chunkZ))
                    throw new IOException("Authority validation found no persisted exact chunk");
            } catch (RuntimeException exception) {
                throw new IOException("Authority validation persisted chunk existence check failed", exception);
            }
        }
        Throwable operationalFailure = null;
        startupValidation.set(record);
        startupDiskLoad.set(new DiskLoadObservation(world, chunkX, chunkZ));
        try {
            if (world.theChunkProviderServer.loadChunk(chunkX, chunkZ) == null)
                throw new IOException("Authority validation chunk load returned null");
            DiskLoadObservation diskLoad = startupDiskLoad.get();
            boolean diskProven = !chunkWasLoaded && diskLoad != null && diskLoad.observed && diskLoad.loaded;
            if (!chunkWasLoaded && !diskProven)
                throw new IOException("Authority validation could not prove a successful exact disk chunk load");
            boolean exactBlock = world.getBlock(record.location.x, record.location.y, record.location.z)
                == com.emoniph.witchery.Witchery.Blocks.POPPET_SHELF;
            TileEntity tile = world.getTileEntity(record.location.x, record.location.y, record.location.z);
            boolean exactTile = tile instanceof TileEntityPoppetShelf;
            boolean exactIdentity = exactTile && startupIdentityMatches(
                ((PoppetShelfState) tile).witcheryoptimizer$hasPersistentShelfId(),
                ((PoppetShelfState) tile).witcheryoptimizer$getShelfId(),
                record.id);
            StartupValidationDecision decision = classifyPhysicalValidation(
                chunkWasLoaded,
                diskProven,
                exactBlock,
                exactTile,
                exactIdentity);
            if (decision == StartupValidationDecision.RETRY)
                throw new IOException("Preloaded authority chunk has no disk provenance for physical mismatch");
            if (decision == StartupValidationDecision.DELETE)
                return startupValidationConfirmedAbsence(exactBlock, exactTile, exactIdentity);
            TileEntityPoppetShelf shelf = (TileEntityPoppetShelf) tile;
            mirror(record, shelf);
            attached.put(shelf, record.id);
            loaded.put(record.id, shelf);
            synchronizing.set(true);
            try {
                shelf.markDirty();
            } finally {
                synchronizing.remove();
            }
            return null;
        } catch (IOException | RuntimeException exception) {
            operationalFailure = exception;
            throw exception;
        } finally {
            startupValidation.remove();
            startupDiskLoad.remove();
            if (!chunkWasLoaded) {
                try {
                    world.theChunkProviderServer.unloadChunksIfNotNearSpawn(chunkX, chunkZ);
                } catch (RuntimeException unloadFailure) {
                    if (operationalFailure != null) operationalFailure.addSuppressed(unloadFailure);
                    else throw unloadFailure;
                }
            }
            if (!worldWasLoaded)
                WitcheryOptimizer.LOG.debug("Temporarily loaded dimension {} for authority validation", dimension);
        }
    }

    static String startupValidationConfirmedAbsence(boolean exactBlock, boolean exactTile, boolean exactIdentity) {
        if (!exactBlock) return "exact Witchery Poppet Shelf is absent";
        if (!exactTile) return "exact Witchery Poppet Shelf TE is absent";
        return exactIdentity ? null : "exact shelf identity does not match authority";
    }

    static StartupValidationDecision classifyStartupValidation(boolean registered, boolean worldResolved,
        boolean chunkLoaded, boolean diskLoadProven, boolean exactBlock, boolean exactTile, boolean exactIdentity) {
        if (!registered || !worldResolved || !chunkLoaded) return StartupValidationDecision.RETRY;
        return classifyPhysicalValidation(false, diskLoadProven, exactBlock, exactTile, exactIdentity);
    }

    static StartupValidationDecision classifyPhysicalValidation(boolean preloaded, boolean diskProven,
        boolean exactBlock, boolean exactTile, boolean exactIdentity) {
        if (exactBlock && exactTile && exactIdentity) return StartupValidationDecision.MIRROR;
        return !preloaded && diskProven ? StartupValidationDecision.DELETE : StartupValidationDecision.RETRY;
    }

    public void observeStartupDiskLoad(net.minecraft.world.World world, int chunkX, int chunkZ, boolean loaded) {
        DiskLoadObservation observation = startupDiskLoad.get();
        if (observation != null && observation.matches(world, chunkX, chunkZ)) {
            observation.observed = true;
            observation.loaded = loaded;
        }
    }

    private static final class DiskLoadObservation {

        final net.minecraft.world.World world;
        final int chunkX;
        final int chunkZ;
        boolean observed;
        boolean loaded;

        DiskLoadObservation(net.minecraft.world.World world, int chunkX, int chunkZ) {
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        boolean matches(net.minecraft.world.World candidate, int x, int z) {
            return world == candidate && chunkX == x && chunkZ == z;
        }
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
            if (!data.censusComplete(VALIDATION_VERSION)) validateAuthorities();
            rebuildAllowedDimensions();
            initializationAttempts = 0;
            WitcheryOptimizer.LOG.info(
                "Witchery Optimizer initialized: importState={}, validationState={}, authoritativeShelves={}, dimensions={}, pendingWritebacks={}",
                data.importState(),
                data.censusState(),
                data.records()
                    .size(),
                data.dimensionOrder()
                    .size(),
                data.pendingWritebacks());
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
