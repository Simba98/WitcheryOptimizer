package com.github.witcheryoptimizer.mixin;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.emoniph.witchery.item.ItemPoppet;

@Mixin(value = ItemPoppet.class, remap = false)
public interface ItemPoppetInvoker {

    @Invoker(value = "findBoundPoppetInInventory", remap = false)
    static ItemStack witcheryoptimizer$findBoundPoppetInInventory(Item item, int damage, EntityPlayer player,
        IInventory inventory, int foundItemDamage, boolean allIndices, boolean onlyBoosted) {
        throw new AssertionError();
    }
}
