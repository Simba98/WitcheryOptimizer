package com.github.witcheryoptimizer.registry;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

final class ShelfInventory implements IInventory {

    private final ShelfRecord record;

    ShelfInventory(ShelfRecord record) {
        this.record = record;
    }

    @Override
    public int getSizeInventory() {
        return record.inventory.length;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return record.inventory[slot];
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount) {
        ItemStack stack = record.inventory[slot];
        if (stack == null) return null;
        if (stack.stackSize <= amount) {
            record.inventory[slot] = null;
            return stack;
        }
        ItemStack split = stack.splitStack(amount);
        if (stack.stackSize == 0) record.inventory[slot] = null;
        return split;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot) {
        ItemStack stack = record.inventory[slot];
        record.inventory[slot] = null;
        return stack;
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        record.inventory[slot] = stack;
    }

    @Override
    public String getInventoryName() {
        return record.customName.isEmpty() ? "container.poppetShelf" : record.customName;
    }

    @Override
    public boolean hasCustomInventoryName() {
        return !record.customName.isEmpty();
    }

    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    @Override
    public void markDirty() {}

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        return false;
    }

    @Override
    public void openInventory() {}

    @Override
    public void closeInventory() {}

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        return true;
    }
}
