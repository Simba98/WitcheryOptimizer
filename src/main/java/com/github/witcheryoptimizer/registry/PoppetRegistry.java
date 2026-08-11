package com.github.witcheryoptimizer.registry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.event.world.WorldEvent;

import com.emoniph.witchery.Witchery;
import com.emoniph.witchery.blocks.BlockPoppetShelf.TileEntityPoppetShelf;
import com.emoniph.witchery.item.ItemPoppet.PoppetType;
import com.github.witcheryoptimizer.WitcheryOptimizer;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public final class PoppetRegistry {

    private static final PoppetRegistry INSTANCE = new PoppetRegistry();
    private final Map<UUID, List<CachedPoppet>> byOwner = new HashMap<>();
    private final Queue<PendingConsumption> pending = new ArrayDeque<>();
    private PoppetWorldData data;

    public static PoppetRegistry instance() {
        return INSTANCE;
    }

    public void reset() {
        byOwner.clear();
        pending.clear();
        data = null;
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.world.isRemote || event.world.provider.dimensionId != 0 || data != null) return;
        data = PoppetWorldData.get(event.world);
        for (NBTTagCompound tag : data.cached) add(CachedPoppet.read(tag));
        pending.addAll(data.pending);
    }

    public void indexShelf(TileEntityPoppetShelf shelf) {
        if (shelf == null || shelf.getWorldObj() == null || shelf.getWorldObj().isRemote) return;
        ShelfLocation location = new ShelfLocation(
            shelf.getWorldObj().provider.dimensionId,
            shelf.xCoord,
            shelf.yCoord,
            shelf.zCoord);
        removeShelf(location);
        for (int slot = 0; slot < shelf.getSizeInventory(); slot++) {
            ItemStack stack = shelf.getStackInSlot(slot);
            if (stack == null || stack.getItem() != Witchery.Items.POPPET || !stack.hasTagCompound()) continue;
            String name = stack.getTagCompound()
                .getString("WITCPlayer1");
            if (name.isEmpty()) continue;
            UUID owner = resolveOwner(name);
            if (owner == null) continue;
            int type = stack.getItemDamage();
            boolean destroy = type == 1 || type == 2 || type == 3 || type == 4 || type == 5 || type == 6 || type == 11;
            NBTTagCompound serialized = new NBTTagCompound();
            stack.writeToNBT(serialized);
            add(
                new CachedPoppet(
                    owner,
                    name,
                    location,
                    slot,
                    type,
                    hasSecondTaglock(stack),
                    destroy,
                    stack.getTagCompound()
                        .getInteger("WITCDamage"),
                    serialized));
        }
        for (PendingConsumption consumption : pending) {
            if (!consumption.reconciling && consumption.shelf.equals(location)) reservePending(consumption);
        }
        persistCache();
    }

    public ItemStack reserve(PoppetType type, EntityPlayer player, int amount, boolean allIndices) {
        List<CachedPoppet> values = byOwner.get(player.getUniqueID());
        if (values == null) return null;
        for (CachedPoppet value : values) {
            if (value.type != type.damageValue || allIndices && !value.hasSecondTaglock) continue;
            if (!value.boundName.equals(player.getCommandSenderName()) || !isDimensionAllowed(value.shelf.dimension))
                continue;
            if (!value.reserve(amount)) continue;
            PendingConsumption consumption = new PendingConsumption(
                value.owner,
                value.boundName,
                value.shelf,
                value.slot,
                value.type,
                amount);
            pending.add(consumption);
            persistAll();
            return value.resultStack();
        }
        return null;
    }

    public void releaseMigratedTicket(TileEntityPoppetShelf shelf, Ticket ticket) {
        indexShelf(shelf);
        ForgeChunkManager.releaseTicket(ticket);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pending.isEmpty()) return;
        PendingConsumption consumption = pending.peek();
        if (apply(consumption)) {
            pending.remove();
        } else if (++consumption.attempts % 100 == 0) {
            WitcheryOptimizer.LOG.error(
                "Unable to reconcile poppet consumption at {} after {} attempts",
                consumption.shelf,
                consumption.attempts);
        }
        persistAll();
    }

    private boolean apply(PendingConsumption consumption) {
        WorldServer world = MinecraftServer.getServer()
            .worldServerForDimension(consumption.shelf.dimension);
        if (world == null) return false;
        Ticket ticket = ForgeChunkManager
            .requestTicket(WitcheryOptimizer.instance, world, ForgeChunkManager.Type.NORMAL);
        if (ticket == null) return false;
        try {
            consumption.reconciling = true;
            int chunkX = consumption.shelf.x >> 4;
            int chunkZ = consumption.shelf.z >> 4;
            ForgeChunkManager.forceChunk(ticket, new net.minecraft.world.ChunkCoordIntPair(chunkX, chunkZ));
            world.getChunkProvider()
                .loadChunk(chunkX, chunkZ);
            TileEntity tile = world.getTileEntity(consumption.shelf.x, consumption.shelf.y, consumption.shelf.z);
            if (!(tile instanceof TileEntityPoppetShelf)) {
                removeShelf(consumption.shelf);
                return true;
            }
            TileEntityPoppetShelf shelf = (TileEntityPoppetShelf) tile;
            ItemStack stack = shelf.getStackInSlot(consumption.slot);
            if (!matches(stack, consumption)) {
                indexShelf(shelf);
                return true;
            }
            if (isDestroyOnUse(consumption.type)) {
                shelf.setInventorySlotContents(consumption.slot, null);
            } else {
                int damage = Math.min(
                    1000,
                    stack.getTagCompound()
                        .getInteger("WITCDamage") + consumption.amount);
                stack.getTagCompound()
                    .setInteger("WITCDamage", damage);
                if (damage >= 1000) shelf.setInventorySlotContents(consumption.slot, null);
                else shelf.markDirty();
            }
            indexShelf(shelf);
            return true;
        } finally {
            consumption.reconciling = false;
            ForgeChunkManager.releaseTicket(ticket);
        }
    }

    private boolean matches(ItemStack stack, PendingConsumption value) {
        return stack != null && stack.getItem() == Witchery.Items.POPPET
            && stack.getItemDamage() == value.type
            && stack.hasTagCompound()
            && value.boundName.equals(
                stack.getTagCompound()
                    .getString("WITCPlayer1"));
    }

    private void removeShelf(ShelfLocation location) {
        Iterator<Map.Entry<UUID, List<CachedPoppet>>> owners = byOwner.entrySet()
            .iterator();
        while (owners.hasNext()) {
            List<CachedPoppet> values = owners.next()
                .getValue();
            values.removeIf(value -> value.shelf.equals(location));
            if (values.isEmpty()) owners.remove();
        }
    }

    private void add(CachedPoppet value) {
        List<CachedPoppet> values = byOwner.computeIfAbsent(value.owner, ignored -> new ArrayList<>());
        values.add(value);
        Collections.sort(values);
    }

    private UUID resolveOwner(String name) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return null;
        for (Object value : server.getConfigurationManager().playerEntityList) {
            EntityPlayer player = (EntityPlayer) value;
            if (player.getCommandSenderName()
                .equals(name)) return player.getUniqueID();
        }
        GameProfile profile = server.func_152358_ax()
            .func_152655_a(name);
        if (profile != null && profile.getId() != null) return profile.getId();
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private void reservePending(PendingConsumption consumption) {
        List<CachedPoppet> values = byOwner.get(consumption.owner);
        if (values == null) return;
        for (CachedPoppet value : values) {
            if (value.shelf.equals(consumption.shelf) && value.slot == consumption.slot
                && value.type == consumption.type) {
                value.reserve(consumption.amount);
                return;
            }
        }
    }

    private boolean hasSecondTaglock(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return tag.hasKey("WITCPlayer2") || tag.hasKey("WITCEntityIDm2") && tag.hasKey("WITCEntityIDl2");
    }

    private boolean isDimensionAllowed(int dimension) {
        com.emoniph.witchery.util.Config config = com.emoniph.witchery.util.Config.instance();
        return !config.restrictPoppetShelvesToVanillaAndSpiritDimensions || dimension == 0
            || dimension == -1
            || dimension == 1
            || dimension == config.dimensionDreamID;
    }

    private boolean isDestroyOnUse(int type) {
        return type == 1 || type == 2 || type == 3 || type == 4 || type == 5 || type == 6 || type == 11;
    }

    private void persistCache() {
        if (data == null) return;
        List<CachedPoppet> values = new ArrayList<>();
        for (List<CachedPoppet> ownerValues : byOwner.values()) values.addAll(ownerValues);
        data.replaceCache(values);
    }

    private void persistAll() {
        if (data == null) return;
        persistCache();
        data.replacePending(pending);
    }
}
