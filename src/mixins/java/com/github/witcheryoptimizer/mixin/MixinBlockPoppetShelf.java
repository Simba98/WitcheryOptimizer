package com.github.witcheryoptimizer.mixin;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.emoniph.witchery.blocks.BlockPoppetShelf;
import com.emoniph.witchery.blocks.BlockPoppetShelf.TileEntityPoppetShelf;
import com.github.witcheryoptimizer.registry.PoppetRegistry;

@Mixin(value = BlockPoppetShelf.class, remap = false)
public abstract class MixinBlockPoppetShelf {

    @Inject(method = "breakBlock", at = @At("HEAD"), remap = true)
    private void witcheryoptimizer$prepareDrops(World world, int x, int y, int z, Block replacement, int metadata,
        CallbackInfo ci) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityPoppetShelf) PoppetRegistry.instance()
            .finalizeBreak((TileEntityPoppetShelf) tile);
    }

}
