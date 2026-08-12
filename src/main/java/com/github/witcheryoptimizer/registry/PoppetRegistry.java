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
import net.minecraft.world.WorldProviderEnd;
import net.minecraft.world.WorldProviderHell;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.emoniph.witchery.blocks.BlockPoppetShelf.TileEntityPoppetShelf;
import com.emoniph.witchery.util.Config;
import com.github.witcheryoptimizer.WitcheryOptimizer;
import com.github.witcheryoptimizer.migration.WitcheryImportCoordinator;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public final class PoppetRegistry {

    private static final PoppetRegistry INSTANCE = new PoppetRegistry();
    private final Map<TileEntityPoppetShelf, UUID> attached = new IdentityHashMap<>();
    private final Map<UUID, TileEntityPoppetShelf> loaded = new HashMap<>();
    private final Set<Integer> allowedDimensions = new HashSet<>();
    private final ThreadLocal<Boolean> synchronizing = ThreadLocal.withInitial(() -> false);
    private PoppetWorldData data;
    private ShelfJournal journal;
    private final ThreadLocal<RemovalTransaction> removal = new ThreadLocal<>();
    private final PlacementAuthorizations<TileEntityPoppetShelf> placementAuthorizations = new PlacementAuthorizations<>();
    private final WitcheryImportCoordinator importCoordinator = new WitcheryImportCoordinator();
    private long serverTick;

    public static PoppetRegistry instance() {
        return INSTANCE;
    }

    public void reset() {
        attached.clear();
        loaded.clear();
        allowedDimensions.clear();
        data = null;
        journal = null;
        removal.remove();
        placementAuthorizations.clear();
        importCoordinator.reset();
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (!event.world.isRemote && initialize()) refreshAllowedDimensions();
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (!event.world.isRemote) {
            allowedDimensions.remove(event.world.provider.dimensionId);
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
            placementAuthorizations.expire(serverTick);
            if (data != null && data.importState() == PoppetWorldData.ImportState.UNKNOWN
                && importCoordinator.finalizeStartup()) persistCoordinatorState();
        }
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
        if (!state.witcheryoptimizer$hasPersistentShelfId()) {
            LocationResolution resolution = resolveLocation(location);
            if (resolution.multiple) {
                WitcheryOptimizer.LOG.error("Multiple authoritative shelves claim {}; attachment denied", location);
                return false;
            }
            if (resolution.record != null && !loaded.containsKey(resolution.record.id)) {
                id = resolution.record.id;
                state.witcheryoptimizer$setShelfId(id);
                state.witcheryoptimizer$setPersistentShelfId(true);
            }
        }
        ShelfRecord record = id == null ? null : data.get(id);
        boolean clone = record != null && !record.location.equals(location);
        if (id == null || clone || loaded.containsKey(id) && loaded.get(id) != shelf) {
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
        } else mirror(record, shelf);
        attached.put(shelf, id);
        loaded.put(id, shelf);
        if (imported) shelf.markDirty();
        return true;
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
        if (record != null) mirror(record, shelf);
    }

    public void writeIdentity(TileEntityPoppetShelf shelf, NBTTagCompound tag) {
        UUID id = ((PoppetShelfState) shelf).witcheryoptimizer$getShelfId();
        if (id == null) return;
        tag.setLong("WOShelfUuidMost", id.getMostSignificantBits());
        tag.setLong("WOShelfUuidLeast", id.getLeastSignificantBits());
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
        if (existing != null && existing.location.equals(target)) return true;
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityPoppetShelf)) return false;
        TileEntityPoppetShelf shelf = (TileEntityPoppetShelf) tile;
        if (!attach(shelf)) return false;
        ShelfRecord record = data.get(attached.get(shelf));
        if (record == null || !record.location.equals(target)) return false;
        mirror(record, shelf);
        ShelfRecord before = ShelfRecord.read(record.write());
        try {
            journal.appendDelete(record, record.version + 1);
        } catch (IOException exception) {
            WitcheryOptimizer.LOG
                .error("Block removal denied because shelf tombstone could not be persisted", exception);
            return false;
        }
        data.delete(record.id, record.version + 1, record.location);
        removal.set(new RemovalTransaction(target, before, shelf));
        return true;
    }

    public void finishRemove(net.minecraft.world.World world, int x, int y, int z, boolean succeeded) {
        RemovalTransaction transaction = removal.get();
        ShelfLocation target = new ShelfLocation(world.provider.dimensionId, x, y, z);
        if (transaction == null || !transaction.location.equals(target)) return;
        try {
            boolean originalRemains = shouldRestoreRemoval(
                world.getBlock(x, y, z) == com.emoniph.witchery.Witchery.Blocks.POPPET_SHELF,
                world.getTileEntity(x, y, z) == transaction.shelf);
            if (originalRemains) {
                ShelfRecord restored = ShelfRecord.read(transaction.before.write());
                restored.version = transaction.before.version + 2;
                try {
                    journal.appendPost(restored);
                    data.install(restored);
                    data.markDirty();
                    mirror(restored, transaction.shelf);
                } catch (IOException exception) {
                    WitcheryOptimizer.LOG
                        .error("Failed block replacement left a durable tombstone; shelf remains unusable", exception);
                }
            }
        } finally {
            removal.remove();
        }
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
        boolean[] plausible = new boolean[tickets.size()];
        for (int i = 0; i < tickets.size(); i++) plausible[i] = plausibleWitcheryTicket(tickets.get(i));
        boolean accepted = importCoordinator.inspect(dimension, plausible, maximum);
        persistCoordinatorState();
        return accepted;
    }

    public void finishWitcheryTickets(int dimension, int successes, int offered) {
        importCoordinator.finish(dimension, successes, offered);
        persistCoordinatorState();
    }

    private void persistCoordinatorState() {
        if (!initialize()) return;
        PoppetWorldData.ImportState state = importCoordinator.state();
        try {
            journal.appendImportState(state);
            data.setImportState(state);
        } catch (IOException exception) {
            importCoordinator.fail();
            data.setImportState(PoppetWorldData.ImportState.FAILED);
            WitcheryOptimizer.LOG.error("Unable to persist Witchery ticket import state", exception);
        }
    }

    public boolean plausibleWitcheryTicket(Ticket ticket) {
        NBTTagCompound tag = ticket.getModData();
        return tag.hasKey("poppetX") && tag.hasKey("poppetY") && tag.hasKey("poppetZ");
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

    public ItemStack find(EntityPlayer player, Matcher matcher) {
        if (!initialize() || data.importState() != PoppetWorldData.ImportState.COMPLETE) return null;
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.worldServers == null) return null;
        for (WorldServer world : server.worldServers) {
            if (world == null || !allowedDimensions.contains(world.provider.dimensionId)) continue;
            for (ShelfRecord record : recordsIn(world.provider.dimensionId)) {
                ShelfRecord post = ShelfRecord.read(record.write());
                ItemStack result = matcher.find(player, new ShelfInventory(post));
                if (result == null) continue;
                post.version = record.version + 1;
                try {
                    journal.appendPost(post);
                } catch (IOException exception) {
                    WitcheryOptimizer.LOG
                        .error("Poppet protection denied because its consumption journal failed", exception);
                    return null;
                }
                data.install(post);
                data.markDirty();
                TileEntityPoppetShelf shelf = loaded.get(post.id);
                if (shelf != null && !shelf.isInvalid() && location(shelf).equals(post.location)) mirror(post, shelf);
                return result;
            }
        }
        return null;
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

    private static final class RemovalTransaction {

        final ShelfLocation location;
        final ShelfRecord before;
        final TileEntityPoppetShelf shelf;

        RemovalTransaction(ShelfLocation location, ShelfRecord before, TileEntityPoppetShelf shelf) {
            this.location = location;
            this.before = before;
            this.shelf = shelf;
        }
    }

    public interface Matcher {

        ItemStack find(EntityPlayer player, IInventory inventory);
    }

    private synchronized boolean initialize() {
        if (data != null) return true;
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
        data = PoppetWorldData.get(primary);
        try {
            journal = new ShelfJournal(primary);
            journal.recover(data);
            PoppetWorldData.ImportState recovered = data.importState();
            importCoordinator.resume(recovered);
            if (recovered == PoppetWorldData.ImportState.IN_PROGRESS) {
                journal.appendImportState(PoppetWorldData.ImportState.FAILED);
                data.setImportState(PoppetWorldData.ImportState.FAILED);
            }
            rebuildAllowedDimensions(server.worldServers);
            return true;
        } catch (IOException exception) {
            WitcheryOptimizer.LOG.error("Witchery Optimizer storage initialization failed closed", exception);
            data = null;
            journal = null;
            return false;
        }
    }

    private void refreshAllowedDimensions() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null && server.worldServers != null) rebuildAllowedDimensions(server.worldServers);
    }

    private void rebuildAllowedDimensions(WorldServer[] worlds) {
        allowedDimensions.clear();
        Config config = Config.instance();
        for (WorldServer world : worlds) {
            if (world == null) continue;
            if (!config.restrictPoppetShelvesToVanillaAndSpiritDimensions
                || world.provider.getClass() == WorldProviderSurface.class
                || world.provider.getClass() == WorldProviderHell.class
                || world.provider.getClass() == WorldProviderEnd.class
                || world.provider.dimensionId == config.dimensionDreamID)
                allowedDimensions.add(world.provider.dimensionId);
        }
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
