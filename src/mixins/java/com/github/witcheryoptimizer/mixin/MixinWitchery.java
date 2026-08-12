package com.github.witcheryoptimizer.mixin;

import net.minecraftforge.common.ForgeChunkManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.emoniph.witchery.Witchery;
import com.github.witcheryoptimizer.migration.WitcheryTicketCallback;

@Mixin(value = Witchery.class, remap = false)
public abstract class MixinWitchery {

    @Redirect(
        method = "postInit",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraftforge/common/ForgeChunkManager;setForcedChunkLoadingCallback(Ljava/lang/Object;Lnet/minecraftforge/common/ForgeChunkManager$LoadingCallback;)V"),
        remap = false)
    private void witcheryoptimizer$replaceTicketCallback(Object mod, ForgeChunkManager.LoadingCallback ignored) {
        WitcheryTicketCallback.register(mod);
    }
}
