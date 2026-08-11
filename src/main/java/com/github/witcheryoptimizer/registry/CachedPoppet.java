package com.github.witcheryoptimizer.registry;

import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

final class CachedPoppet implements Comparable<CachedPoppet> {

    final UUID owner;
    final String boundName;
    final ShelfLocation shelf;
    final int slot;
    final int type;
    final boolean hasSecondTaglock;
    final boolean destroyOnUse;
    int damage;
    final NBTTagCompound stackNbt;

    CachedPoppet(UUID owner, String boundName, ShelfLocation shelf, int slot, int type, boolean hasSecondTaglock,
        boolean destroyOnUse, int damage, NBTTagCompound stackNbt) {
        this.owner = owner;
        this.boundName = boundName;
        this.shelf = shelf;
        this.slot = slot;
        this.type = type;
        this.hasSecondTaglock = hasSecondTaglock;
        this.destroyOnUse = destroyOnUse;
        this.damage = damage;
        this.stackNbt = stackNbt;
    }

    ItemStack resultStack() {
        return ItemStack.loadItemStackFromNBT((NBTTagCompound) stackNbt.copy());
    }

    NBTTagCompound write() {
        NBTTagCompound tag = shelf.write();
        tag.setLong("OwnerMost", owner.getMostSignificantBits());
        tag.setLong("OwnerLeast", owner.getLeastSignificantBits());
        tag.setString("BoundName", boundName);
        tag.setInteger("Slot", slot);
        tag.setInteger("Type", type);
        tag.setBoolean("SecondTaglock", hasSecondTaglock);
        tag.setBoolean("DestroyOnUse", destroyOnUse);
        tag.setInteger("Damage", damage);
        tag.setTag("Stack", stackNbt.copy());
        return tag;
    }

    static CachedPoppet read(NBTTagCompound tag) {
        return new CachedPoppet(
            new UUID(tag.getLong("OwnerMost"), tag.getLong("OwnerLeast")),
            tag.getString("BoundName"),
            ShelfLocation.read(tag),
            tag.getInteger("Slot"),
            tag.getInteger("Type"),
            tag.getBoolean("SecondTaglock"),
            tag.getBoolean("DestroyOnUse"),
            tag.getInteger("Damage"),
            tag.getCompoundTag("Stack"));
    }

    boolean reserve(int amount) {
        if (destroyOnUse) {
            if (damage >= 1000) return false;
            damage = 1000;
            return true;
        }
        if (damage >= 1000) return false;
        damage = Math.min(1000, damage + amount);
        return true;
    }

    @Override
    public int compareTo(CachedPoppet other) {
        int result = shelf.compareTo(other.shelf);
        return result == 0 ? Integer.compare(slot, other.slot) : result;
    }
}
