package com.packwatch.client;

import java.nio.file.Path;

import com.packwatch.PackWatch;

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
            PackWatch.LOG.info(
                "PackWatch: full reload ({} changed file(s), batch flagged non-patchable)",
                batch.changedFiles.size());
            TextureReloadTrigger.refresh();
            return;
        }

        long startNanos = System.nanoTime();
        boolean allPatched = true;
        for (Path changedFile : batch.changedFiles) {
            if (!SpritePatcher.tryPatch(changedFile)) {
                allPatched = false;
                break;
            }
        }

        if (!allPatched) {
            PackWatch.LOG.info("PackWatch: full reload (a changed file couldn't be patched in place)");
            TextureReloadTrigger.refresh();
        } else {
            PackWatch.LOG.info(
                "PackWatch: patched {} sprite(s) in place in {} ms (no full reload)",
                batch.changedFiles.size(),
                (System.nanoTime() - startNanos) / 1_000_000L);
        }
    }
}
