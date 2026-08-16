package com.packwatch.client;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

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

        CtmNumberOverlay.syncToDebugScreen();

        HotReloadWatcher.ChangeBatch batch = watcher.pollChanges();
        if (batch == null) return;

        if (batch.forceFullReload) {
            PackWatch.LOG.info(
                "PackWatch: full reload ({} changed file(s), batch flagged non-patchable)",
                batch.changedFiles.size());
            TextureReloadTrigger.refresh();
            CtmNumberOverlay.reapplyIfActive();
            return;
        }

        Set<Path> changedFiles = dropMetaSavedWithItsTexture(batch.changedFiles);

        long startNanos = System.nanoTime();
        boolean allPatched = true;
        for (Path changedFile : changedFiles) {
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
                changedFiles.size(),
                (System.nanoTime() - startNanos) / 1_000_000L);
        }

        CtmNumberOverlay.reapplyIfActive();
    }

    /**
     * Export tools tend to rewrite {@code foo.png.mcmeta} every time they write {@code foo.png}. On its own a
     * .mcmeta means a full reload, but patching the .png re-reads its metadata from disk anyway, so when both
     * land in one batch the .mcmeta is already covered and would only force the reload we just avoided. A
     * .mcmeta changing on its own still reloads -- that's the case where the animation itself was edited.
     */
    private static Set<Path> dropMetaSavedWithItsTexture(Set<Path> changedFiles) {
        Set<Path> kept = new LinkedHashSet<Path>();
        for (Path changedFile : changedFiles) {
            String name = changedFile.getFileName()
                .toString();
            if (name.endsWith(".mcmeta")
                && changedFiles.contains(changedFile.resolveSibling(name.substring(0, name.length() - 7)))) continue;
            kept.add(changedFile);
        }
        return kept;
    }
}
