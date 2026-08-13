package com.github.witcheryoptimizer.mixin;

import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.emoniph.witchery.blocks.BlockPoppetShelf.TileEntityPoppetShelf;
import com.github.witcheryoptimizer.registry.PoppetRegistry;
import com.github.witcheryoptimizer.registry.PoppetShelfState;

@Mixin(value = TileEntityPoppetShelf.class, remap = false)
public abstract class MixinPoppetShelf implements PoppetShelfState {

    @Shadow
    private Ticket chunkTicket;
    @Shadow
    protected String customName;
    @Unique
    private UUID witcheryoptimizer$shelfId;
    @Unique
    private boolean witcheryoptimizer$persistent;

    public UUID witcheryoptimizer$getShelfId() {
        return witcheryoptimizer$shelfId;
    }

    public void witcheryoptimizer$setShelfId(UUID id) {
        witcheryoptimizer$shelfId = id;
    }

    public boolean witcheryoptimizer$hasPersistentShelfId() {
        return witcheryoptimizer$persistent;
    }

    public void witcheryoptimizer$setPersistentShelfId(boolean v) {
        witcheryoptimizer$persistent = v;
    }

    public String witcheryoptimizer$getCustomName() {
        return customName == null ? "" : customName;
    }

    public void witcheryoptimizer$setCustomName(String n) {
        customName = n == null || n.isEmpty() ? null : n;
    }

    @Redirect(
        method = "initiate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraftforge/common/ForgeChunkManager;requestTicket(Ljava/lang/Object;Lnet/minecraft/world/World;Lnet/minecraftforge/common/ForgeChunkManager$Type;)Lnet/minecraftforge/common/ForgeChunkManager$Ticket;"),
        remap = false)
    private Ticket wo$noPermanent(Object m, World w, ForgeChunkManager.Type t) {
        return null;
    }

    @Inject(method = "initiate", at = @At("RETURN"), remap = false)
    private void wo$attach(CallbackInfo c) {
        PoppetRegistry.instance()
            .attach((TileEntityPoppetShelf) (Object) this);
    }

    @Inject(method = "forceChunkLoading", at = @At("HEAD"), cancellable = true, remap = false)
    private void wo$release(Ticket t, CallbackInfo c) {
        if (t != null) ForgeChunkManager.releaseTicket(t);
        chunkTicket = null;
        c.cancel();
    }

    @Inject(method = "readFromNBT", at = @At("RETURN"), remap = true)
    private void wo$read(NBTTagCompound n, CallbackInfo c) {
        if (n.hasKey("WOShelfUuidMost", 4) && n.hasKey("WOShelfUuidLeast", 4)) {
            witcheryoptimizer$shelfId = new UUID(n.getLong("WOShelfUuidMost"), n.getLong("WOShelfUuidLeast"));
            witcheryoptimizer$persistent = true;
        }
        PoppetRegistry.instance()
            .attach((TileEntityPoppetShelf) (Object) this);
    }

    @Inject(method = "writeToNBT", at = @At("HEAD"), remap = true)
    private void wo$writeHead(NBTTagCompound n, CallbackInfo c) {
        PoppetRegistry.instance()
            .prepareWrite((TileEntityPoppetShelf) (Object) this);
    }

    @Inject(method = "writeToNBT", at = @At("RETURN"), remap = true)
    private void wo$write(NBTTagCompound n, CallbackInfo c) {
        PoppetRegistry.instance()
            .writeIdentity((TileEntityPoppetShelf) (Object) this, n);
        witcheryoptimizer$persistent = true;
    }

    @Inject(method = "markDirty", at = @At("RETURN"), remap = true)
    private void wo$dirty(CallbackInfo c) {
        PoppetRegistry.instance()
            .changed((TileEntityPoppetShelf) (Object) this);
    }

    @Inject(method = "getStackInSlotOnClosing(I)Lnet/minecraft/item/ItemStack;", at = @At("RETURN"), remap = true)
    private void wo$close(int s, CallbackInfoReturnable<ItemStack> c) {
        if (c.getReturnValue() != null) ((TileEntityPoppetShelf) (Object) this).markDirty();
    }

    @Inject(method = "invalidate", at = @At("HEAD"), remap = true)
    private void wo$invalid(CallbackInfo c) {
        witcheryoptimizer$detach();
    }

    public void witcheryoptimizer$detach() {
        PoppetRegistry.instance()
            .detach((TileEntityPoppetShelf) (Object) this);
        if (chunkTicket != null) {
            ForgeChunkManager.releaseTicket(chunkTicket);
            chunkTicket = null;
        }
    }
}
