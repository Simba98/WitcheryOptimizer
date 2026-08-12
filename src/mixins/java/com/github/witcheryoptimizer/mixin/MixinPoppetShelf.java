package com.github.witcheryoptimizer.mixin;

import java.util.UUID;

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
    private boolean witcheryoptimizer$persistentShelfId;

    @Override
    public UUID witcheryoptimizer$getShelfId() {
        return witcheryoptimizer$shelfId;
    }

    @Override
    public void witcheryoptimizer$setShelfId(UUID id) {
        witcheryoptimizer$shelfId = id;
    }

    @Override
    public boolean witcheryoptimizer$hasPersistentShelfId() {
        return witcheryoptimizer$persistentShelfId;
    }

    @Override
    public void witcheryoptimizer$setPersistentShelfId(boolean persistent) {
        witcheryoptimizer$persistentShelfId = persistent;
    }

    @Override
    public String witcheryoptimizer$getCustomName() {
        return customName == null ? "" : customName;
    }

    @Override
    public void witcheryoptimizer$setCustomName(String name) {
        customName = name == null || name.isEmpty() ? null : name;
    }

    @Redirect(
        method = "initiate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraftforge/common/ForgeChunkManager;requestTicket(Ljava/lang/Object;Lnet/minecraft/world/World;Lnet/minecraftforge/common/ForgeChunkManager$Type;)Lnet/minecraftforge/common/ForgeChunkManager$Ticket;"),
        remap = false)
    private Ticket witcheryoptimizer$disablePermanentTicket(Object mod, World world, ForgeChunkManager.Type type) {
        return null;
    }

    @Inject(method = "initiate", at = @At("RETURN"), remap = false)
    private void witcheryoptimizer$attached(CallbackInfo ci) {
        PoppetRegistry.instance()
            .attach((TileEntityPoppetShelf) (Object) this);
    }

    @Inject(method = "forceChunkLoading", at = @At("HEAD"), cancellable = true, remap = false)
    private void witcheryoptimizer$migrateTicket(Ticket ticket, CallbackInfo ci) {
        if (PoppetRegistry.instance()
            .releaseWitcheryTicket((TileEntityPoppetShelf) (Object) this, ticket)) {
            chunkTicket = null;
            ci.cancel();
        }
    }

    @Inject(method = "readFromNBT", at = @At("RETURN"), remap = true)
    private void witcheryoptimizer$readId(NBTTagCompound tag, CallbackInfo ci) {
        if (tag.hasKey("WOShelfUuidMost") && tag.hasKey("WOShelfUuidLeast")) {
            witcheryoptimizer$shelfId = new UUID(tag.getLong("WOShelfUuidMost"), tag.getLong("WOShelfUuidLeast"));
            witcheryoptimizer$persistentShelfId = true;
        } else {
            witcheryoptimizer$shelfId = UUID.randomUUID();
            witcheryoptimizer$persistentShelfId = false;
        }
    }

    @Inject(method = "writeToNBT", at = @At("HEAD"), remap = true)
    private void witcheryoptimizer$prepareWrite(NBTTagCompound tag, CallbackInfo ci) {
        PoppetRegistry.instance()
            .prepareWrite((TileEntityPoppetShelf) (Object) this);
        if (witcheryoptimizer$shelfId == null) witcheryoptimizer$shelfId = UUID.randomUUID();
    }

    @Inject(method = "writeToNBT", at = @At("RETURN"), remap = true)
    private void witcheryoptimizer$writeId(NBTTagCompound tag, CallbackInfo ci) {
        PoppetRegistry.instance()
            .writeIdentity((TileEntityPoppetShelf) (Object) this, tag);
        witcheryoptimizer$persistentShelfId = true;
    }

    @Inject(method = "markDirty", at = @At("RETURN"), remap = true)
    private void witcheryoptimizer$inventoryChanged(CallbackInfo ci) {
        PoppetRegistry.instance()
            .changed((TileEntityPoppetShelf) (Object) this);
    }

    @Inject(method = "invalidate", at = @At("HEAD"), remap = true)
    private void witcheryoptimizer$invalidate(CallbackInfo ci) {
        witcheryoptimizer$detach();
    }

    @Unique
    @Override
    public void witcheryoptimizer$detach() {
        PoppetRegistry.instance()
            .detach((TileEntityPoppetShelf) (Object) this);
        if (chunkTicket != null) {
            ForgeChunkManager.releaseTicket(chunkTicket);
            chunkTicket = null;
        }
    }
}
