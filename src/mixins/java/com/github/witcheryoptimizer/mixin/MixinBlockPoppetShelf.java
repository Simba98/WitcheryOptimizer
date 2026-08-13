package com.github.witcheryoptimizer.mixin;

import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.emoniph.witchery.blocks.BlockPoppetShelf;
import com.emoniph.witchery.blocks.BlockPoppetShelf.TileEntityPoppetShelf;
import com.github.witcheryoptimizer.registry.PoppetRegistry;

@Mixin(value = BlockPoppetShelf.class, remap = false)
public abstract class MixinBlockPoppetShelf {

    @Redirect(
        method = "breakBlock(Lnet/minecraft/world/World;IIILnet/minecraft/block/Block;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/emoniph/witchery/blocks/BlockPoppetShelf$TileEntityPoppetShelf;getStackInSlot(I)Lnet/minecraft/item/ItemStack;",
            remap = true),
        remap = true)
    private ItemStack witcheryoptimizer$authoritativeInventory(TileEntityPoppetShelf shelf, int slot) {
        return PoppetRegistry.instance()
            .authoritativeRemovalStack(shelf, slot);
    }
}
