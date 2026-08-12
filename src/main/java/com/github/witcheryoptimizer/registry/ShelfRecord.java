package com.github.witcheryoptimizer.registry;

import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

final class ShelfRecord {

    enum State {
        ACTIVE,
        REMOVAL_PREPARED,
        REMOVAL_CLEANUP_PENDING
    }

    final UUID id;
    final long order;
    final ItemStack[] inventory = new ItemStack[9];
    long version;
    ShelfLocation location;
    String customName;
    State state = State.ACTIVE;
    boolean writebackPending;
    UUID removalTransaction;
    boolean removalDropsStarted;
    long removalSourceVersion;

    ShelfRecord(UUID id, ShelfLocation location, String customName, long order, ItemStack[] source) {
        this.id = id;
        this.location = location;
        this.customName = customName == null ? "" : customName;
        this.order = order;
        replace(source);
    }

    void replace(ItemStack[] source) {
        for (int i = 0; i < inventory.length; i++) inventory[i] = copy(source[i]);
    }

    NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setLong("UuidMost", id.getMostSignificantBits());
        tag.setLong("UuidLeast", id.getLeastSignificantBits());
        tag.setLong("Order", order);
        tag.setLong("Version", version);
        tag.setString("State", state.name());
        tag.setBoolean("WritebackPending", writebackPending);
        if (removalTransaction != null) {
            tag.setLong("RemovalTransactionMost", removalTransaction.getMostSignificantBits());
            tag.setLong("RemovalTransactionLeast", removalTransaction.getLeastSignificantBits());
            tag.setBoolean("RemovalDropsStarted", removalDropsStarted);
            tag.setLong("RemovalSourceVersion", removalSourceVersion);
        }
        tag.setTag("Location", location.write());
        if (!customName.isEmpty()) tag.setString("CustomName", customName);
        NBTTagList items = new NBTTagList();
        for (int slot = 0; slot < inventory.length; slot++) {
            if (inventory[slot] == null) continue;
            NBTTagCompound item = new NBTTagCompound();
            item.setByte("Slot", (byte) slot);
            inventory[slot].writeToNBT(item);
            items.appendTag(item);
        }
        tag.setTag("Items", items);
        return tag;
    }

    static ShelfRecord read(NBTTagCompound tag) {
        ItemStack[] inventory = new ItemStack[9];
        NBTTagList items = tag.getTagList("Items", 10);
        for (int i = 0; i < items.tagCount(); i++) {
            NBTTagCompound item = items.getCompoundTagAt(i);
            int slot = item.getByte("Slot") & 255;
            if (slot < inventory.length) inventory[slot] = ItemStack.loadItemStackFromNBT(item);
        }
        ShelfRecord record = new ShelfRecord(
            new UUID(tag.getLong("UuidMost"), tag.getLong("UuidLeast")),
            ShelfLocation.read(tag.getCompoundTag("Location")),
            tag.getString("CustomName"),
            tag.getLong("Order"),
            inventory);
        record.version = tag.getLong("Version");
        if (tag.hasKey("State")) {
            try {
                record.state = State.valueOf(tag.getString("State"));
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                    "Invalid shelf transaction state: " + tag.getString("State"),
                    exception);
            }
        }
        record.writebackPending = tag.getBoolean("WritebackPending");
        if (tag.hasKey("RemovalTransactionMost") && tag.hasKey("RemovalTransactionLeast"))
            record.removalTransaction = new UUID(
                tag.getLong("RemovalTransactionMost"),
                tag.getLong("RemovalTransactionLeast"));
        record.removalDropsStarted = tag.getBoolean("RemovalDropsStarted");
        record.removalSourceVersion = tag.getLong("RemovalSourceVersion");
        if (record.state != State.ACTIVE && record.removalTransaction == null)
            throw new IllegalStateException("Prepared shelf removal has no transaction identity");
        return record;
    }

    static ItemStack copy(ItemStack stack) {
        return stack == null ? null : stack.copy();
    }
}
