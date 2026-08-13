package com.github.witcheryoptimizer.mixin;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.emoniph.witchery.Witchery;
import com.github.witcheryoptimizer.registry.PoppetRegistry;

@Mixin(World.class)
public abstract class MixinWorld {

    @Inject(method = "setBlock(IIILnet/minecraft/block/Block;II)Z", at = @At("HEAD"), cancellable = true, remap = true)
    private void witcheryoptimizer$preRemove(int x, int y, int z, Block replacement, int metadata, int flags,
        CallbackInfoReturnable<Boolean> cir) {
        World world = (World) (Object) this;
        PoppetRegistry registry = PoppetRegistry.instance();
        if (!world.isRemote && world.getBlock(x, y, z) == Witchery.Blocks.POPPET_SHELF
            && replacement != Witchery.Blocks.POPPET_SHELF) {
            if (registry.beginSetBlock(world, x, y, z) == PoppetRegistry.SetBlockRemoval.TRANSIENT_FAILURE)
                cir.setReturnValue(false);
        }
    }

    @Inject(method = "setBlock(IIILnet/minecraft/block/Block;II)Z", at = @At("RETURN"), remap = true)
    private void witcheryoptimizer$finishRemove(int x, int y, int z, Block replacement, int metadata, int flags,
        CallbackInfoReturnable<Boolean> cir) {
        PoppetRegistry.instance()
            .finishSetBlock((World) (Object) this, x, y, z, cir.getReturnValue());
    }
}
