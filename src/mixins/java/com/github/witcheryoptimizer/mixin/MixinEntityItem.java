package com.github.witcheryoptimizer.mixin;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.github.witcheryoptimizer.registry.PoppetRegistry;

@Mixin(EntityItem.class)
public abstract class MixinEntityItem {

    @Inject(method = "onUpdate()V", at = @At("HEAD"), cancellable = true)
    private void witcheryoptimizer$guardUpdate(CallbackInfo ci) {
        EntityItem self = (EntityItem) (Object) this;
        if (PoppetRegistry.instance()
            .shouldLockRemovalDrop(self)) {
            self.delayBeforeCanPickup = 32767;
            self.age = 0;
            ci.cancel();
        }
    }

    @Inject(method = "combineItems(Lnet/minecraft/entity/item/EntityItem;)Z", at = @At("HEAD"), cancellable = true)
    private void witcheryoptimizer$guardMerge(EntityItem other, CallbackInfoReturnable<Boolean> cir) {
        if (PoppetRegistry.instance()
            .shouldLockRemovalDrop((EntityItem) (Object) this)
            || PoppetRegistry.instance()
                .shouldLockRemovalDrop(other))
            cir.setReturnValue(false);
    }

    @Inject(
        method = "onCollideWithPlayer(Lnet/minecraft/entity/player/EntityPlayer;)V",
        at = @At("HEAD"),
        cancellable = true)
    private void witcheryoptimizer$guardPickup(EntityPlayer player, CallbackInfo ci) {
        if (PoppetRegistry.instance()
            .shouldLockRemovalDrop((EntityItem) (Object) this)) ci.cancel();
    }
}
