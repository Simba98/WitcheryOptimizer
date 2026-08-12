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
        PoppetRegistry registry = PoppetRegistry.instance();
        TicketBatch.process(tickets, new TicketBatch.Actions<Ticket>() {

            @Override
            public boolean importTicket(Ticket ticket) {
                return registry.importWitcheryTicket(ticket, world);
            }

            @Override
            public void release(Ticket ticket) {
                ForgeChunkManager.releaseTicket(ticket);
            }

            @Override
            public void finish(int successes, int offered) {
                registry.finishWitcheryTickets(world.provider.dimensionId, successes, offered);
            }

            @Override
            public void failure(Ticket ticket, Throwable failure) {
                WitcheryOptimizer.LOG
                    .error("Witchery ticket import failed; continuing retained-ticket cleanup", failure);
            }
        });
    }

    public static void register(Object witchery) {
        ForgeChunkManager.setForcedChunkLoadingCallback(witchery, new WitcheryTicketCallback());
    }
}
