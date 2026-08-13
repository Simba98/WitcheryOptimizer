package com.github.witcheryoptimizer.migration;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.OrderedLoadingCallback;
import net.minecraftforge.common.ForgeChunkManager.Ticket;

import com.emoniph.witchery.Witchery;
import com.emoniph.witchery.blocks.BlockPoppetShelf.TileEntityPoppetShelf;
import com.github.witcheryoptimizer.WitcheryOptimizer;
import com.github.witcheryoptimizer.registry.PoppetRegistry;

public final class WitcheryTicketCallback implements OrderedLoadingCallback {

    public List<Ticket> ticketsLoaded(List<Ticket> tickets, World world, int maximum) {
        List<Ticket> retained = new ArrayList<>();
        for (Ticket ticket : tickets) {
            if (retained.size() >= maximum || !plausible(ticket)) continue;
            net.minecraft.nbt.NBTTagCompound n = ticket.getModData();
            int x = n.getInteger("poppetX");
            int y = n.getInteger("poppetY");
            int z = n.getInteger("poppetZ");
            try {
                world.getChunkFromChunkCoords(x >> 4, z >> 4);
                TileEntity tile = world.getTileEntity(x, y, z);
                if (world.getBlock(x, y, z) == Witchery.Blocks.POPPET_SHELF && tile instanceof TileEntityPoppetShelf)
                    retained.add(ticket);
                else WitcheryOptimizer.LOG.warn(
                    "Dropping stale Witchery shelf ticket in dimension {} at {},{},{}",
                    world.provider.dimensionId,
                    x,
                    y,
                    z);
            } catch (RuntimeException e) {
                WitcheryOptimizer.LOG
                    .error("Unable to prepare Witchery shelf ticket in dimension " + world.provider.dimensionId, e);
            }
        }
        return retained;
    }

    public void ticketsLoaded(List<Ticket> tickets, World world) {
        int imported = 0, stale = 0, released = 0;
        for (Ticket t : tickets) {
            try {
                if (plausible(t)) {
                    net.minecraft.nbt.NBTTagCompound n = t.getModData();
                    world.getChunkFromChunkCoords(n.getInteger("poppetX") >> 4, n.getInteger("poppetZ") >> 4);
                    TileEntity te = world
                        .getTileEntity(n.getInteger("poppetX"), n.getInteger("poppetY"), n.getInteger("poppetZ"));
                    if (te instanceof TileEntityPoppetShelf && PoppetRegistry.instance()
                        .bootstrap((TileEntityPoppetShelf) te)) imported++;
                    else stale++;
                } else stale++;
            } catch (RuntimeException e) {
                stale++;
                WitcheryOptimizer.LOG
                    .error("Legacy Witchery ticket import failed in dimension " + world.provider.dimensionId, e);
            } finally {
                try {
                    ForgeChunkManager.releaseTicket(t);
                    released++;
                } catch (RuntimeException e) {
                    WitcheryOptimizer.LOG.error("Legacy Witchery ticket release failed", e);
                }
            }
        }
        WitcheryOptimizer.LOG.info(
            "Witchery ticket bootstrap dimension {}: imported={}, stale={}, released={}",
            world.provider.dimensionId,
            imported,
            stale,
            released);
    }

    private static boolean plausible(Ticket t) {
        net.minecraft.nbt.NBTTagCompound n = t.getModData();
        return n.hasKey("poppetX", 3) && n.hasKey("poppetY", 3)
            && n.hasKey("poppetZ", 3)
            && n.getInteger("poppetY") >= 0
            && n.getInteger("poppetY") <= 255;
    }

    public static void register(Object witchery) {
        ForgeChunkManager.setForcedChunkLoadingCallback(witchery, new WitcheryTicketCallback());
    }
}
