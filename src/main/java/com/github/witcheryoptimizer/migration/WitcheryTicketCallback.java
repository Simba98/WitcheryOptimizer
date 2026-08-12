package com.github.witcheryoptimizer.migration;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.OrderedLoadingCallback;
import net.minecraftforge.common.ForgeChunkManager.Ticket;

import com.github.witcheryoptimizer.WitcheryOptimizer;
import com.github.witcheryoptimizer.registry.PoppetRegistry;

public final class WitcheryTicketCallback implements OrderedLoadingCallback {

    @Override
    public List<Ticket> ticketsLoaded(List<Ticket> tickets, World world, int maxTicketCount) {
        PoppetRegistry registry = PoppetRegistry.instance();
        if (!registry.inspectWitcheryTickets(world.provider.dimensionId, tickets, maxTicketCount)) {
            WitcheryOptimizer.LOG
                .error("Restored Witchery tickets failed validation in dimension {}", world.provider.dimensionId);
            return new ArrayList<>();
        }
        return new ArrayList<>(tickets);
    }

    @Override
    public void ticketsLoaded(List<Ticket> tickets, World world) {
        int successes = 0;
        for (Ticket ticket : tickets) {
            if (PoppetRegistry.instance()
                .importWitcheryTicket(ticket, world)) {
                ForgeChunkManager.releaseTicket(ticket);
                successes++;
            }
        }
        PoppetRegistry.instance()
            .finishWitcheryTickets(world.provider.dimensionId, successes, tickets.size());
    }

    public static void register(Object witchery) {
        ForgeChunkManager.setForcedChunkLoadingCallback(witchery, new WitcheryTicketCallback());
    }
}
