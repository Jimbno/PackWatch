package com.packwatch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * Inspired by BasiqueEvangelist's ScaldingHot (https://github.com/BasiqueEvangelist/ScaldingHot) for modern
 * Minecraft, reimplemented from scratch for 1.7.10 / Forge: 1.7.10 predates data packs, JSON models and
 * Fabric, so none of upstream's Mixin targets or file formats exist here. What survives is the goal -- watch a
 * directory on disk and push changed assets into a running client without a restart.
 */
@Mod(modid = PackWatch.MODID, version = Tags.VERSION, name = "PackWatch", acceptedMinecraftVersions = "[1.7.10]")
public class PackWatch {

    public static final String MODID = "packwatch";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "com.packwatch.ClientProxy", serverSide = "com.packwatch.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }
}
