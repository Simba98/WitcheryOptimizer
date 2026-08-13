package com.github.witcheryoptimizer.mixin;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.gen.ChunkProviderServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.github.witcheryoptimizer.registry.PoppetRegistry;

@Mixin(ChunkProviderServer.class)
public abstract class MixinChunkProviderServer {

    @Shadow
    public net.minecraft.world.WorldServer worldObj;

    @Inject(method = "safeLoadChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("RETURN"), remap = true)
    private void witcheryoptimizer$observeDiskLoad(int chunkX, int chunkZ, CallbackInfoReturnable<Chunk> cir) {
        PoppetRegistry.instance()
            .observeStartupDiskLoad(worldObj, chunkX, chunkZ, cir.getReturnValue() != null);
    }

    @Redirect(
        method = "loadChunk(IILjava/lang/Runnable;)Lnet/minecraft/world/chunk/Chunk;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraftforge/common/chunkio/ChunkIOExecutor;syncChunkLoad(Lnet/minecraft/world/World;Lnet/minecraft/world/chunk/storage/AnvilChunkLoader;Lnet/minecraft/world/gen/ChunkProviderServer;II)Lnet/minecraft/world/chunk/Chunk;",
            remap = false),
        remap = false)
    private Chunk witcheryoptimizer$observeSyncDiskLoad(net.minecraft.world.World world, AnvilChunkLoader loader,
        ChunkProviderServer provider, int chunkX, int chunkZ) {
        Chunk loaded = net.minecraftforge.common.chunkio.ChunkIOExecutor
            .syncChunkLoad(world, loader, provider, chunkX, chunkZ);
        PoppetRegistry.instance()
            .observeStartupDiskLoad(world, chunkX, chunkZ, loaded != null);
        return loaded;
    }
}
