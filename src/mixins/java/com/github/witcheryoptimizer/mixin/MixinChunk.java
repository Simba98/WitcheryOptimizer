package com.github.witcheryoptimizer.mixin;

import net.minecraft.block.Block;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.github.witcheryoptimizer.registry.PoppetRegistry;

@Mixin(Chunk.class)
public abstract class MixinChunk {

    @Redirect(
        method = "func_150807_a(IIILnet/minecraft/block/Block;I)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/block/Block;breakBlock(Lnet/minecraft/world/World;IIILnet/minecraft/block/Block;I)V"),
        remap = true)
    private void witcheryoptimizer$withRemovalContext(Block original, World world, int x, int y, int z,
        Block replacement, int metadata) {
        PoppetRegistry registry = PoppetRegistry.instance();
        boolean activated = registry.beginWitcheryDrops(world, x, y, z);
        try {
            original.breakBlock(world, x, y, z, replacement, metadata);
        } finally {
            registry.finishWitcheryDrops(world, x, y, z, activated);
        }
    }
}
