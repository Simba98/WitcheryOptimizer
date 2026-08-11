package com.github.witcheryoptimizer;

import net.minecraftforge.common.MinecraftForge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.witcheryoptimizer.registry.PoppetRegistry;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;

@Mod(
    modid = WitcheryOptimizer.MODID,
    name = "Witchery Optimizer",
    version = Tags.VERSION,
    dependencies = "required-after:witchery@[0.24.1,0.25)",
    acceptedMinecraftVersions = "[1.7.10]")
public final class WitcheryOptimizer {

    public static final String MODID = "witcheryoptimizer";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @Mod.Instance(MODID)
    public static WitcheryOptimizer instance;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        PoppetRegistry registry = PoppetRegistry.instance();
        FMLCommonHandler.instance()
            .bus()
            .register(registry);
        MinecraftForge.EVENT_BUS.register(registry);
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        PoppetRegistry.instance()
            .reset();
    }
}
