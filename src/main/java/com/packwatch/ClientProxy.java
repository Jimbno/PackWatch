package com.packwatch;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraftforge.client.ClientCommandHandler;

import com.packwatch.client.ClientTickHandler;
import com.packwatch.client.EnabledPackSync;
import com.packwatch.client.HotReloadCommand;
import com.packwatch.client.HotReloadWatcher;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);

        // Deliberately deferred from preInit to postInit: registering our reload listener forces an immediate
        // synchronous resource-manager reload (SimpleReloadableResourceManager#registerReloadListener fires
        // onResourceManagerReload on registration), and doing that during preInit raced with Angelica's own
        // preInit-time async texture/shader setup (observed as a ConcurrentModificationException in vanilla's
        // Locale class on a real GTNH+Angelica install). postInit runs only after every mod has finished both
        // preInit and init, so Angelica's early setup is done by the time we touch the resource manager.
        HotReloadWatcher watcher = new HotReloadWatcher();
        watcher.start();

        // mcResourceManager is always a SimpleReloadableResourceManager under the hood (verified against the
        // decompiled source); the field's declared type on Minecraft is just the narrower IResourceManager.
        IReloadableResourceManager resourceManager = (IReloadableResourceManager) Minecraft.getMinecraft()
            .getResourceManager();
        resourceManager.registerReloadListener(new EnabledPackSync(watcher));

        FMLCommonHandler.instance()
            .bus()
            .register(new ClientTickHandler(watcher));
        ClientCommandHandler.instance.registerCommand(new HotReloadCommand());
    }
}
