package com.github.witcheryoptimizer.mixin;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.emoniph.witchery.Witchery;
import com.emoniph.witchery.item.ItemHunterClothes;
import com.emoniph.witchery.item.ItemPoppet;
import com.emoniph.witchery.item.ItemPoppet.PoppetType;
import com.github.witcheryoptimizer.registry.PoppetRegistry;

@Mixin(value = ItemPoppet.class, remap = false)
public abstract class MixinItemPoppet {

    @Shadow
    private static ItemStack findBoundPoppetInInventory(Item item, int damage, EntityPlayer player,
        IInventory inventory, int foundItemDamage, boolean allIndices, boolean onlyBoosted) {
        throw new AssertionError();
    }

    @Inject(
        method = "findBoundPoppetInWorld(Lcom/emoniph/witchery/item/ItemPoppet$PoppetType;Lnet/minecraft/entity/player/EntityPlayer;IZZ)Lnet/minecraft/item/ItemStack;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false)
    private static void witcheryoptimizer$cachedLookup(PoppetType type, EntityPlayer player, int amount,
        boolean allIndices, boolean onlyBoosted, CallbackInfoReturnable<ItemStack> cir) {
        if (ItemHunterClothes.isFullSetWorn(player, false)) {
            cir.setReturnValue(null);
            return;
        }
        ItemStack handheld = findBoundPoppetInInventory(
            Witchery.Items.POPPET,
            type.damageValue,
            player,
            player.inventory,
            amount,
            allIndices,
            onlyBoosted);
        if (handheld != null || onlyBoosted || player.worldObj.isRemote) {
            cir.setReturnValue(handheld);
            return;
        }
        cir.setReturnValue(
            PoppetRegistry.instance()
                .reserve(type, player, amount, allIndices));
    }
}
