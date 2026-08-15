package com.packwatch.client;

import java.nio.file.Path;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Reload/patch calls touch GL state, so they must happen on the client thread. We poll a batch of changes once
 * per client tick instead of acting straight from the watcher thread.
 */
public class ClientTickHandler {

    private final HotReloadWatcher watcher;

    public ClientTickHandler(HotReloadWatcher watcher) {
        this.watcher = watcher;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        HotReloadWatcher.ChangeBatch batch = watcher.pollChanges();
        if (batch == null) return;

        if (batch.forceFullReload) {
            TextureReloadTrigger.refresh();
            return;
        }

        boolean allPatched = true;
        for (Path changedFile : batch.changedFiles) {
            if (!SpritePatcher.tryPatch(changedFile)) {
                allPatched = false;
                break;
            }
        }

        if (!allPatched) {
            TextureReloadTrigger.refresh();
        }
    }
}
