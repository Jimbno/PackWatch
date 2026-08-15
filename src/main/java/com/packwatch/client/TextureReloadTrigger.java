package com.packwatch.client;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.ResourcePackRepository;

import com.packwatch.PackWatch;

/**
 * Talks to vanilla's own resource-reload plumbing (verified against the decompiled 1.7.10 sources: see
 * {@code Minecraft#refreshResources()}, which is exactly what F3+T calls). We deliberately don't inject a custom
 * {@code IResourcePack} via reflection/Mixin -- everything here goes through public API:
 * <ol>
 * <li>{@code resourcepacks/<name>/} with a {@code pack.mcmeta} is a normal unpacked resource pack; vanilla
 * already knows how to scan and load it.</li>
 * <li>Enabling it is just adding its folder name to {@code GameSettings#resourcePacks} (public field) and
 * pushing that into {@code ResourcePackRepository}'s active entry list -- the same thing the in-game
 * "Resource Packs" screen does when you click a pack.</li>
 * <li>{@code Minecraft#refreshResources()} (public) does the actual reload -- restitches the texture atlas,
 * reloads lang/sounds/etc. Same cost as pressing F3+T. This is the fallback path: {@link SpritePatcher} handles
 * plain texture edits without it, so this only runs for changes it can't confidently patch in place (new/removed
 * files, size changes, animated sprites, lang/sound changes, or explicit {@code /pw reload}).</li>
 * </ol>
 */
public class TextureReloadTrigger {

    public static void refresh() {
        Minecraft.getMinecraft()
            .refreshResources();
    }

    /**
     * Ensures {@code resourcepacks/<folderName>/} exists and has a pack.mcmeta (creating a minimal one if
     * missing), enables it if it isn't already, and reloads.
     *
     * @return null on success, or a user-facing error message
     */
    public static String enableAndReload(String folderName) {
        Minecraft mc = Minecraft.getMinecraft();
        ResourcePackRepository repo = mc.getResourcePackRepository();

        File dir = new File(repo.getDirResourcepacks(), folderName);
        if (!dir.isDirectory()) {
            return "No such directory: " + dir.getAbsolutePath();
        }

        File meta = new File(dir, "pack.mcmeta");
        if (!meta.isFile()) {
            if (!writeDefaultPackMeta(meta)) {
                return "Couldn't create pack.mcmeta in " + dir.getAbsolutePath();
            }
        }

        if (!mc.gameSettings.resourcePacks.contains(folderName)) {
            mc.gameSettings.resourcePacks.add(folderName);
            mc.gameSettings.saveOptions();
        }

        repo.updateRepositoryEntriesAll();

        ResourcePackRepository.Entry match = null;
        for (ResourcePackRepository.Entry entry : repo.getRepositoryEntriesAll()) {
            if (entry.getResourcePackName()
                .equals(folderName)) {
                match = entry;
                break;
            }
        }

        if (match == null) {
            return "Vanilla didn't recognize " + dir.getAbsolutePath()
                + " as a resource pack even after adding pack.mcmeta";
        }

        List<ResourcePackRepository.Entry> active = new ArrayList<ResourcePackRepository.Entry>(
            repo.getRepositoryEntries());
        if (!active.contains(match)) {
            active.add(match);
            // The obfuscated name is intentional: MCP's "stable" channel for 1.7.10 never mapped this setter.
            // It's the same call GuiScreenResourcePacks makes when you enable a pack from the in-game menu.
            repo.func_148527_a(active);
        }

        refresh();

        PackWatch.LOG.info("Enabled and reloaded resource pack '{}'", folderName);
        return null;
    }

    private static boolean writeDefaultPackMeta(File meta) {
        FileWriter writer = null;
        try {
            writer = new FileWriter(meta);
            writer.write("{\"pack\":{\"pack_format\":1,\"description\":\"Live-reloaded by PackWatch\"}}");
            return true;
        } catch (IOException e) {
            PackWatch.LOG.error("Couldn't write {}", meta, e);
            return false;
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {}
            }
        }
    }
}
