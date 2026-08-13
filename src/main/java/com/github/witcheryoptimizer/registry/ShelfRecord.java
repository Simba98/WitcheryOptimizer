package com.github.witcheryoptimizer.registry;

import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

final class ShelfRecord {

    final UUID id;
    final long order;
    final ItemStack[] inventory = new ItemStack[9];
    long version;
    ShelfLocation location;
    String customName;
    boolean writebackPending;
    long writebackVersion;

    ShelfRecord(UUID id, ShelfLocation location, String name, long order, ItemStack[] source) {
        this.id = id;
        this.location = location;
        this.customName = name == null ? "" : name;
        this.order = order;
        replace(source);
    }

    void replace(ItemStack[] source) {
        for (int i = 0; i < 9; i++) inventory[i] = copy(source[i]);
    }

    NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setLong("UuidMost", id.getMostSignificantBits());
        tag.setLong("UuidLeast", id.getLeastSignificantBits());
        tag.setLong("Order", order);
        tag.setLong("Version", version);
        tag.setTag("Location", location.write());
        tag.setString("CustomName", customName);
        tag.setBoolean("WritebackPending", writebackPending);
        tag.setLong("WritebackVersion", writebackVersion);
        NBTTagList items = new NBTTagList();
        for (int slot = 0; slot < 9; slot++) if (inventory[slot] != null) {
            NBTTagCompound item = new NBTTagCompound();
            item.setByte("Slot", (byte) slot);
            inventory[slot].writeToNBT(item);
            items.appendTag(item);
        }
        tag.setTag("Items", items);
        return tag;
    }

    static ShelfRecord read(NBTTagCompound tag) {
        StrictNbt.require(tag, "UuidMost", 4);
        StrictNbt.require(tag, "UuidLeast", 4);
        StrictNbt.require(tag, "Order", 4);
        StrictNbt.require(tag, "Version", 4);
        StrictNbt.require(tag, "Location", 10);
        StrictNbt.require(tag, "CustomName", 8);
        StrictNbt.require(tag, "WritebackPending", 1);
        StrictNbt.require(tag, "WritebackVersion", 4);
        ItemStack[] inventory = new ItemStack[9];
        boolean[] seen = new boolean[9];
        NBTTagList items = StrictNbt.list(tag, "Items", 10);
        for (int i = 0; i < items.tagCount(); i++) {
            NBTTagCompound item = items.getCompoundTagAt(i);
            StrictNbt.require(item, "Slot", 1);
            int slot = item.getByte("Slot") & 255;
            if (slot >= 9 || seen[slot]) throw new IllegalStateException("Invalid or duplicate shelf slot " + slot);
            seen[slot] = true;
            StrictNbt.require(item, "id", 2);
            StrictNbt.require(item, "Count", 1);
            StrictNbt.require(item, "Damage", 2);
            if (item.getByte("Count") <= 0) throw new IllegalStateException("Non-positive item count in slot " + slot);
            inventory[slot] = ItemStack.loadItemStackFromNBT(item);
            if (inventory[slot] == null || inventory[slot].stackSize <= 0)
                throw new IllegalStateException("Invalid shelf item in slot " + slot);
        }
        ShelfRecord record = new ShelfRecord(
            new UUID(tag.getLong("UuidMost"), tag.getLong("UuidLeast")),
            ShelfLocation.read(tag.getCompoundTag("Location")),
            tag.getString("CustomName"),
            StrictNbt.nonnegativeLong(tag, "Order"),
            inventory);
        record.version = StrictNbt.nonnegativeLong(tag, "Version");
        record.writebackPending = tag.getBoolean("WritebackPending");
        record.writebackVersion = StrictNbt.nonnegativeLong(tag, "WritebackVersion");
        if (!record.writebackPending && record.writebackVersion != 0)
            throw new IllegalStateException("Completed record has a writeback version");
        if (record.writebackPending && record.writebackVersion != record.version)
            throw new IllegalStateException("Pending writeback must target the current authority version");
        return record;
    }

    static ItemStack copy(ItemStack stack) {
        return stack == null ? null : stack.copy();
    }
}
