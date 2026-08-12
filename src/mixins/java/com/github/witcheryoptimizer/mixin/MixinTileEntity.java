package com.github.witcheryoptimizer.mixin;

import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.github.witcheryoptimizer.registry.PoppetShelfState;

@Mixin(TileEntity.class)
public abstract class MixinTileEntity {

    // Forge adds this unobfuscated method after MCP remapping. It has no SRG mapping; remap=true is invalid.
    @Inject(method = "onChunkUnload()V", at = @At("HEAD"), remap = false)
    private void witcheryoptimizer$onChunkUnload(CallbackInfo ci) {
        if ((Object) this instanceof PoppetShelfState) {
            ((PoppetShelfState) (Object) this).witcheryoptimizer$detach();
        }
    }
}
