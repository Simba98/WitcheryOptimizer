package com.github.witcheryoptimizer.mixin;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.emoniph.witchery.blocks.BlockPoppetShelf;
import com.emoniph.witchery.blocks.BlockPoppetShelf.TileEntityPoppetShelf;
import com.github.witcheryoptimizer.registry.PoppetRegistry;

@Mixin(value = BlockPoppetShelf.class, remap = false)
public abstract class MixinBlockPoppetShelf {

    @Redirect(
        method = "breakBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;spawnEntityInWorld(Lnet/minecraft/entity/Entity;)Z"),
        remap = true)
    private boolean witcheryoptimizer$tagInventoryDrop(World world, Entity entity) {
        return PoppetRegistry.instance()
            .spawnRemovalDrop(world, entity);
    }

    @Inject(method = "breakBlock", at = @At("HEAD"), cancellable = true, remap = true)
    private void witcheryoptimizer$prepareDrops(World world, int x, int y, int z, Block replacement, int metadata,
        CallbackInfo ci) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityPoppetShelf)) return;
        TileEntityPoppetShelf shelf = (TileEntityPoppetShelf) tile;
        if (PoppetRegistry.instance()
            .suppressCleanupBreak(shelf)) {
            ci.cancel();
            return;
        }
        PoppetRegistry.instance()
            .finalizeBreak(shelf);
    }

}
