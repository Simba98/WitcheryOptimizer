package com.github.witcheryoptimizer.registry;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

final class PendingConsumption {

    final UUID owner;
    final String boundName;
    final ShelfLocation shelf;
    final int slot;
    final int type;
    final int amount;
    int attempts;
    transient boolean reconciling;

    PendingConsumption(UUID owner, String boundName, ShelfLocation shelf, int slot, int type, int amount) {
        this.owner = owner;
        this.boundName = boundName;
        this.shelf = shelf;
        this.slot = slot;
        this.type = type;
        this.amount = amount;
    }

    NBTTagCompound write() {
        NBTTagCompound tag = shelf.write();
        tag.setLong("OwnerMost", owner.getMostSignificantBits());
        tag.setLong("OwnerLeast", owner.getLeastSignificantBits());
        tag.setString("BoundName", boundName);
        tag.setInteger("Slot", slot);
        tag.setInteger("Type", type);
        tag.setInteger("Amount", amount);
        tag.setInteger("Attempts", attempts);
        return tag;
    }

    static PendingConsumption read(NBTTagCompound tag) {
        PendingConsumption value = new PendingConsumption(
            new UUID(tag.getLong("OwnerMost"), tag.getLong("OwnerLeast")),
            tag.getString("BoundName"),
            ShelfLocation.read(tag),
            tag.getInteger("Slot"),
            tag.getInteger("Type"),
            tag.getInteger("Amount"));
        value.attempts = tag.getInteger("Attempts");
        return value;
    }
}
