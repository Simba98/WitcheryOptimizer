package com.github.witcheryoptimizer.mixin;

import net.minecraftforge.common.ForgeChunkManager.Ticket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.emoniph.witchery.blocks.BlockPoppetShelf.TileEntityPoppetShelf;
import com.github.witcheryoptimizer.registry.PoppetRegistry;

@Mixin(value = TileEntityPoppetShelf.class, remap = false)
public abstract class MixinPoppetShelf {

    @Shadow
    private Ticket chunkTicket;

    @Inject(method = "forceChunkLoading", at = @At("HEAD"), cancellable = true, remap = false)
    private void witcheryoptimizer$migrateTicket(Ticket ticket, CallbackInfo ci) {
        PoppetRegistry.instance()
            .releaseMigratedTicket((TileEntityPoppetShelf) (Object) this, ticket);
        chunkTicket = null;
        ci.cancel();
    }

    @Inject(method = "func_70296_d", at = @At("RETURN"), remap = false)
    private void witcheryoptimizer$inventoryChanged(CallbackInfo ci) {
        PoppetRegistry.instance()
            .indexShelf((TileEntityPoppetShelf) (Object) this);
    }

    @Inject(method = "func_145839_a", at = @At("RETURN"), remap = false)
    private void witcheryoptimizer$loaded(net.minecraft.nbt.NBTTagCompound tag, CallbackInfo ci) {
        PoppetRegistry.instance()
            .indexShelf((TileEntityPoppetShelf) (Object) this);
    }
}
