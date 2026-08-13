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
        StrictNbt.require(tag, "UuidMost", 4);
        StrictNbt.require(tag, "UuidLeast", 4);
        StrictNbt.require(tag, "Location", 10);
        StrictNbt.require(tag, "State", 8);
        StrictNbt.require(tag, "WritebackPending", 1);
        if (tag.hasKey("CustomName")) StrictNbt.require(tag, "CustomName", 8);
        ItemStack[] inventory = new ItemStack[9];
        boolean[] occupied = new boolean[9];
        NBTTagList items = StrictNbt.list(tag, "Items", 10);
        for (int i = 0; i < items.tagCount(); i++) {
            NBTTagCompound item = items.getCompoundTagAt(i);
            StrictNbt.require(item, "Slot", 1);
            int slot = item.getByte("Slot") & 255;
            if (slot >= inventory.length || occupied[slot])
                throw new IllegalStateException("Invalid or duplicate shelf slot " + slot);
            occupied[slot] = true;
            StrictNbt.require(item, "id", 2);
            StrictNbt.require(item, "Count", 1);
            StrictNbt.require(item, "Damage", 2);
            if (item.getByte("Count") <= 0) throw new IllegalStateException("Non-positive item count in slot " + slot);
            inventory[slot] = ItemStack.loadItemStackFromNBT(item);
            if (inventory[slot] == null || inventory[slot].stackSize <= 0)
                throw new IllegalStateException("Invalid item stack in slot " + slot);
        }
        ShelfRecord record = new ShelfRecord(
            new UUID(tag.getLong("UuidMost"), tag.getLong("UuidLeast")),
            ShelfLocation.read(tag.getCompoundTag("Location")),
            tag.getString("CustomName"),
            StrictNbt.nonnegativeLong(tag, "Order"),
            inventory);
        record.version = StrictNbt.nonnegativeLong(tag, "Version");
        try {
            record.state = State.valueOf(tag.getString("State"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid shelf transaction state: " + tag.getString("State"), exception);
        }
        record.writebackPending = tag.getBoolean("WritebackPending");
        if (StrictNbt.optionalPair(tag, "RemovalTransactionMost", "RemovalTransactionLeast", 4))
            record.removalTransaction = new UUID(
                tag.getLong("RemovalTransactionMost"),
                tag.getLong("RemovalTransactionLeast"));
        if (tag.hasKey("RemovalDropsStarted")) StrictNbt.require(tag, "RemovalDropsStarted", 1);
        if (tag.hasKey("RemovalSourceVersion")) StrictNbt.require(tag, "RemovalSourceVersion", 4);
        record.removalDropsStarted = tag.getBoolean("RemovalDropsStarted");
        record.removalSourceVersion = tag.getLong("RemovalSourceVersion");
        if (record.state != State.ACTIVE && record.removalTransaction == null)
            throw new IllegalStateException("Prepared shelf removal has no transaction identity");
        if (record.state != State.ACTIVE && (!tag.hasKey("RemovalDropsStarted") || !tag.hasKey("RemovalSourceVersion")
            || record.removalSourceVersion < 0))
            throw new IllegalStateException("Incomplete prepared shelf removal metadata");
        if (record.state == State.ACTIVE && record.removalTransaction != null)
            throw new IllegalStateException("Active shelf contains removal metadata");
        if (record.state == State.ACTIVE && (record.removalDropsStarted || record.removalSourceVersion != 0))
            throw new IllegalStateException("Active shelf contains removal state");
        return record;
    }

    static ItemStack copy(ItemStack stack) {
        return stack == null ? null : stack.copy();
    }
}
