package com.packwatch.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.data.TextureMetadataSection;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.packwatch.PackWatch;

import cpw.mods.fml.relauncher.ReflectionHelper;

/**
 * Patches a single changed texture file directly into the already-stitched atlas, without the full
 * {@code Minecraft#refreshResources()} that F3+T (and our own fallback) does. Same public API vanilla itself
 * uses every tick to patch animated sprites (torches, water) into place -- see {@code TextureAtlasSprite
 * #updateAnimation()} and {@code TextureMap#loadTextureAtlas()} in the decompiled source, which this mirrors:
 * {@code TextureAtlasSprite#loadSprite}/{@code #generateMipmaps} to rebuild the sprite's pixel data, then
 * {@code TextureUtil#uploadTextureMipmap} to push just that sprite's region to the GPU with
 * {@code glTexSubImage2D}. Everything else -- other mods' icons, lang, sounds -- is left untouched.
 * <p>
 * Deliberately conservative: only handles a plain in-place pixel edit of a sprite that's already registered and
 * already uploaded. Anything else (new/removed files, size changes, animated sprites, explicit mip overrides,
 * anisotropic filtering) falls back to a full reload by returning {@code false}.
 */
public final class SpritePatcher {

    // Resolve by both the MCP (dev) and SRG (production) names: at dev time the field is "mipmapLevels", but in a
    // packaged modpack vanilla fields are remapped to SRG and reflection string literals are NOT reobfuscated, so
    // the field is "field_147636_j" at runtime. ReflectionHelper tries each in turn, already calls setAccessible,
    // and throws UnableToFindFieldException (loudly) if neither matches -- which is what we want on a version bump.
    private static final Field MIPMAP_LEVELS_FIELD = ReflectionHelper
        .findField(TextureMap.class, "mipmapLevels", "field_147636_j");

    private SpritePatcher() {}

    /** @return true if the change was patched in place; false if the caller should fall back to a full reload. */
    public static boolean tryPatch(Path changedFile) {
        try {
            return doPatch(changedFile);
        } catch (Exception e) {
            PackWatch.LOG.warn("Couldn't patch {} in place, falling back to a full reload", changedFile, e);
            return false;
        }
    }

    /** Logs why a file can't be patched in place, then returns false so the caller falls back to a full reload. */
    private static boolean bail(Path changedFile, String reason) {
        PackWatch.LOG
            .info("PackWatch: not patching {} in place ({}) -- falling back to full reload", changedFile, reason);
        return false;
    }

    private static boolean doPatch(Path changedFile) throws IOException {
        Minecraft mc = Minecraft.getMinecraft();

        // Anisotropic filtering pads each sprite's stored dimensions by 16px beyond the source image size; we
        // deliberately don't replicate that math here, so just bail to a full reload when it's on.
        if (mc.gameSettings.anisotropicFiltering > 1) return bail(changedFile, "anisotropic filtering is on");

        String fileName = changedFile.getFileName()
            .toString();
        if (!fileName.endsWith(".png")) return bail(changedFile, "not a .png"); // lang/sound/pack.mcmeta/etc.

        int assetsIdx = -1;
        for (int i = 0; i < changedFile.getNameCount(); i++) {
            if ("assets".equals(
                changedFile.getName(i)
                    .toString())) {
                assetsIdx = i;
                break;
            }
        }
        // assets / <domain> / textures / <blocks|items> / <rest...>
        if (assetsIdx < 0 || changedFile.getNameCount() <= assetsIdx + 4)
            return bail(changedFile, "not under assets/<domain>/textures/<blocks|items>/");
        if (!"textures".equals(
            changedFile.getName(assetsIdx + 2)
                .toString()))
            return bail(changedFile, "not under a textures/ directory");

        String domain = changedFile.getName(assetsIdx + 1)
            .toString();
        String typeDir = changedFile.getName(assetsIdx + 3)
            .toString();

        TextureMap map;
        if ("blocks".equals(typeDir)) {
            map = mc.getTextureMapBlocks();
        } else if ("items".equals(typeDir)) {
            ITextureObject tex = mc.getTextureManager()
                .getTexture(TextureMap.locationItemsTexture);
            if (!(tex instanceof TextureMap)) return bail(changedFile, "items texture map unavailable");
            map = (TextureMap) tex;
        } else {
            return bail(changedFile, "'" + typeDir + "' is not the blocks or items atlas");
        }

        StringBuilder relBuilder = new StringBuilder();
        for (int i = assetsIdx + 4; i < changedFile.getNameCount(); i++) {
            if (relBuilder.length() > 0) relBuilder.append('/');
            relBuilder.append(
                changedFile.getName(i)
                    .toString());
        }
        String rel = relBuilder.toString();
        if (!rel.endsWith(".png")) return bail(changedFile, "not a .png");
        rel = rel.substring(0, rel.length() - 4);

        // A mod-owned texture must have been registered with an explicit "domain:name" to land under
        // assets/<domain>/ in the first place (a bare name always resolves to the "minecraft" domain), but
        // vanilla's own icons are typically registered bare -- try both forms for the minecraft domain.
        String iconName = "minecraft".equals(domain) ? rel : domain + ":" + rel;
        TextureAtlasSprite sprite = map.getTextureExtry(iconName);
        if (sprite == null && "minecraft".equals(domain)) {
            sprite = map.getTextureExtry("minecraft:" + rel);
        }
        if (sprite == null) return bail(changedFile, "'" + iconName + "' is not a currently-registered sprite");

        if (sprite.hasAnimationMetadata()) return bail(changedFile, "sprite is animated");

        ResourceLocation location = new ResourceLocation(domain, "textures/" + typeDir + "/" + rel + ".png");
        IResource resource = mc.getResourceManager()
            .getResource(location);

        if (resource.getMetadata("animation") != null) return bail(changedFile, "has .mcmeta animation section");
        TextureMetadataSection textureMeta = (TextureMetadataSection) resource.getMetadata("texture");
        if (textureMeta != null && !textureMeta.getListMipmaps()
            .isEmpty()) return bail(changedFile, "has explicit mipmap overrides");

        BufferedImage image = ImageIO.read(resource.getInputStream());
        if (image == null) return bail(changedFile, "ImageIO couldn't decode the PNG (mid-write?)");
        if (image.getWidth() != sprite.getIconWidth() || image.getHeight() != sprite.getIconHeight()) return bail(
            changedFile,
            "size changed (" + image.getWidth()
                + "x"
                + image.getHeight()
                + " vs sprite "
                + sprite.getIconWidth()
                + "x"
                + sprite.getIconHeight()
                + ")");

        // Vanilla's TextureMap#loadTextureAtlas always hands loadSprite a BufferedImage[1 + mipmapLevels] with
        // only [0] populated, so each frame's pixel array is allocated with a slot for every mip level up front
        // and generateMipmaps merely fills the empty ones. Passing a length-1 array leaves that per-frame array
        // too short, and generateMipmapData indexes the missing levels directly -> ArrayIndexOutOfBoundsException
        // (seen as "Generating mipmaps for frame" on GregTech iconset sprites). Mirror vanilla's sizing exactly.
        int mipmapLevels = getMipmapLevels(map);
        BufferedImage[] images = new BufferedImage[1 + mipmapLevels];
        images[0] = image;
        sprite.loadSprite(images, null, false);
        sprite.generateMipmaps(mipmapLevels);

        // TextureUtil#bindTexture is package-private; bind directly via GL11 instead (same call it makes
        // internally).
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, map.getGlTextureId());
        TextureUtil.uploadTextureMipmap(
            sprite.getFrameTextureData(0),
            sprite.getIconWidth(),
            sprite.getIconHeight(),
            sprite.getOriginX(),
            sprite.getOriginY(),
            false,
            false);

        return true;
    }

    private static int getMipmapLevels(TextureMap map) {
        try {
            return MIPMAP_LEVELS_FIELD.getInt(map);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
