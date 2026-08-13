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
    private void witcheryoptimizer$precommitRemoval(int x, int y, int z, Block replacement, int metadata, int flags,
        CallbackInfoReturnable<Boolean> result) {
        World world = (World) (Object) this;
        if (world.isRemote || world.getBlock(x, y, z) != Witchery.Blocks.POPPET_SHELF
            || replacement == Witchery.Blocks.POPPET_SHELF) return;
        if (PoppetRegistry.denySnapshotReplacement(world.captureBlockSnapshots, world.restoringBlockSnapshots)) {
            result.setReturnValue(false);
            return;
        }
        if (PoppetRegistry.prepareOrdinaryReplacement(world.captureBlockSnapshots, world.restoringBlockSnapshots)
            && !PoppetRegistry.instance()
                .prepareRemoval(world, x, y, z))
            result.setReturnValue(false);
    }
}
