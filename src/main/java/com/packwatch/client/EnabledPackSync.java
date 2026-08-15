package com.packwatch.client;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.resources.ResourcePackRepository;

/**
 * Keeps the watcher in sync with whatever resource packs are actually enabled. Registered as a vanilla
 * {@link IResourceManagerReloadListener}, so it re-derives the enabled set on every resource reload -- F3+T,
 * the in-game Resource Packs screen being applied, or our own {@code /pw enable} -- with no separate
 * watch/unwatch bookkeeping. Disable a pack in the vanilla menu and it stops being watched automatically.
 * Registering a listener fires {@link #onResourceManagerReload} immediately (verified against the decompiled
 * source: {@code SimpleReloadableResourceManager#registerReloadListener}), so this also handles the initial
 * sync on startup for free -- whatever was enabled last session, straight from options.txt.
 */
public class EnabledPackSync implements IResourceManagerReloadListener {

    private final HotReloadWatcher watcher;

    public EnabledPackSync(HotReloadWatcher watcher) {
        this.watcher = watcher;
    }

    @Override
    public void onResourceManagerReload(IResourceManager resourceManager) {
        ResourcePackRepository repo = Minecraft.getMinecraft()
            .getResourcePackRepository();
        File packsDir = repo.getDirResourcepacks();

        Set<String> enabledFolders = new HashSet<String>();
        for (ResourcePackRepository.Entry entry : repo.getRepositoryEntries()) {
            File dir = new File(packsDir, entry.getResourcePackName());
            if (dir.isDirectory()) enabledFolders.add(dir.getAbsolutePath());
        }

        watcher.syncRoots(enabledFolders);
    }
}
