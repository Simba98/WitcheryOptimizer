package com.github.witcheryoptimizer.migration;

import java.util.List;

import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager.OrderedLoadingCallback;
import net.minecraftforge.common.ForgeChunkManager.Ticket;

import com.github.witcheryoptimizer.WitcheryOptimizer;

public final class OptimizerTicketCallback implements OrderedLoadingCallback {

    @Override
    public List<Ticket> ticketsLoaded(List<Ticket> tickets, World world, int maxTicketCount) {
        if (!tickets.isEmpty()) WitcheryOptimizer.LOG.warn(
            "Dropping {} restored optimizer temporary ticket(s) in dimension {}; authority flags reconstruct jobs",
            tickets.size(),
            world.provider.dimensionId);
        return new java.util.ArrayList<>();
    }

    @Override
    public void ticketsLoaded(List<Ticket> tickets, World world) {
        if (!tickets.isEmpty()) throw new IllegalStateException("Optimizer restored tickets must be rejected ordered");
    }
}
