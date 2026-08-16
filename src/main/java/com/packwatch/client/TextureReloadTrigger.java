package com.packwatch.client;

import net.minecraft.client.Minecraft;

/**
 * The full-reload fallback: {@code Minecraft#refreshResources()} is exactly what F3+T calls, and restitches the
 * texture atlas and reloads lang/sounds/etc. {@link SpritePatcher} handles plain texture edits without it, so
 * this only runs for changes it can't confidently patch in place (new/removed files, size changes, .mcmeta and
 * .properties edits, lang/sound changes).
 */
public class TextureReloadTrigger {

    public static void refresh() {
        Minecraft.getMinecraft()
            .refreshResources();
    }
}
