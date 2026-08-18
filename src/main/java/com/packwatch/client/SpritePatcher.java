package com.packwatch.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import net.minecraft.client.resources.data.TextureMetadataSection;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.packwatch.PackWatch;

import cpw.mods.fml.relauncher.ReflectionHelper;

/**
 * Deliberately conservative: only handles a plain in-place pixel edit of a sprite that's already registered and
 * already uploaded. Anything else (new/removed files, atlas-region size changes, a texture gaining or losing an
 * animation, explicit mip overrides, anisotropic filtering) falls back to a full reload by returning
 * {@code false}.
 */
public final class SpritePatcher {

    // Both the MCP (dev) and SRG (production) names: reflection string literals are NOT reobfuscated, so the
    // field is "mipmapLevels" at dev time and "field_147636_j" in a packaged modpack.
    private static final Field MIPMAP_LEVELS_FIELD = ReflectionHelper
        .findField(TextureMap.class, "mipmapLevels", "field_147636_j");

    private SpritePatcher() {}

    public static boolean tryPatch(Path changedFile) {
        try {
            return doPatch(changedFile);
        } catch (Exception e) {
            PackWatch.LOG.warn("Couldn't patch {} in place, falling back to a full reload", changedFile, e);
            return false;
        }
    }

    private static boolean bail(Path changedFile, String reason) {
        PackWatch.LOG
            .info("PackWatch: not patching {} in place ({}) -- falling back to full reload", changedFile, reason);
        return false;
    }

    private static boolean doPatch(Path changedFile) throws IOException {
        Minecraft mc = Minecraft.getMinecraft();

        // Anisotropic filtering pads each sprite's stored dimensions by 16px beyond the source image size; we
        // deliberately don't replicate that math here.
        if (mc.gameSettings.anisotropicFiltering > 1) return bail(changedFile, "anisotropic filtering is on");

        String fileName = changedFile.getFileName()
            .toString();
        if (!fileName.endsWith(".png")) return bail(changedFile, "not a .png");

        int assetsIdx = -1;
        for (int i = 0; i < changedFile.getNameCount(); i++) {
            if ("assets".equals(
                changedFile.getName(i)
                    .toString())) {
                assetsIdx = i;
                break;
            }
        }
        if (assetsIdx < 0 || changedFile.getNameCount() <= assetsIdx + 2)
            return bail(changedFile, "not under assets/<domain>/");

        String domain = changedFile.getName(assetsIdx + 1)
            .toString();
        String category = changedFile.getName(assetsIdx + 2)
            .toString();

        // MCPatcher stitches CTM tiles straight into the *block* atlas under the name
        // "<domain>:<path below assets/domain>", extension kept -- e.g. "minecraft:mcpatcher/ctm/machine_top/4.png".
        if (("mcpatcher".equals(category) || "optifine".equals(category)) && hasSegment(changedFile, "ctm")) {
            String relFromDomain = joinSegments(changedFile, assetsIdx + 2);

            TextureMap blocks = mc.getTextureMapBlocks();
            TextureAtlasSprite sprite = blocks.getTextureExtry(domain + ":" + relFromDomain);
            if (sprite == null)
                return bail(changedFile, "CTM tile '" + domain + ":" + relFromDomain + "' is not a registered sprite");

            // A generated tile's whole set derives from its 5 sources; any other tile patches alone.
            if (CompactCtmExpander.isGeneratedSprite(sprite))
                return patchCompactCtmSet(blocks, changedFile, domain, relFromDomain);
            return applyPatch(blocks, sprite, new ResourceLocation(domain, relFromDomain), changedFile);
        }

        if (!"textures".equals(category)) return bail(changedFile, "not under a textures/ or mcpatcher/ctm/ path");
        if (changedFile.getNameCount() <= assetsIdx + 4)
            return bail(changedFile, "not under assets/<domain>/textures/<blocks|items>/");

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

        String rel = joinSegments(changedFile, assetsIdx + 4);
        if (!rel.endsWith(".png")) return bail(changedFile, "not a .png");
        rel = rel.substring(0, rel.length() - 4);

        // Vanilla's own icons are typically registered bare, mod-owned ones as "domain:name" -- try both forms
        // for the minecraft domain.
        String iconName = "minecraft".equals(domain) ? rel : domain + ":" + rel;
        TextureAtlasSprite sprite = map.getTextureExtry(iconName);
        if (sprite == null && "minecraft".equals(domain)) {
            sprite = map.getTextureExtry("minecraft:" + rel);
        }
        if (sprite == null) return bail(changedFile, "'" + iconName + "' is not a currently-registered sprite");

        return applyPatch(
            map,
            sprite,
            new ResourceLocation(domain, "textures/" + typeDir + "/" + rel + ".png"),
            changedFile);
    }

    private static boolean applyPatch(TextureMap map, TextureAtlasSprite sprite, ResourceLocation location,
        Path changedFile) throws IOException {
        IResourceManager resourceManager = Minecraft.getMinecraft()
            .getResourceManager();

        // A sprite can declare that it loads its own pixels (MCPatcher's CTM tiles do, as GeneratedCTMAtlasSprite);
        // TextureMap#loadTextureAtlas then skips loadSprite entirely for it. Driving loadSprite ourselves would
        // write data the sprite never renders from, so hand it the same call the reload path makes. The location
        // is the sprite's own name, not the completed .png path -- that's what vanilla passes.
        ResourceLocation spriteLocation = new ResourceLocation(sprite.getIconName());
        if (sprite.hasCustomLoader(resourceManager, spriteLocation))
            return patchViaCustomLoader(map, sprite, spriteLocation, resourceManager, changedFile);

        IResource resource = resourceManager.getResource(location);

        AnimationMetadataSection animation;
        BufferedImage image;
        try {
            animation = (AnimationMetadataSection) resource.getMetadata("animation");

            // Gaining or losing an animation changes atlas-level registration, not just pixels: TextureMap keeps
            // animated sprites in listAnimatedSprites and ticks them, and membership is only decided at stitch
            // time. Patching across that boundary would either leave a ticking sprite with null metadata (NPE in
            // updateAnimation) or a newly-animated one that never ticks, so it stays a full reload.
            if (sprite.hasAnimationMetadata() != (animation != null))
                return bail(changedFile, animation != null ? "gained an animation" : "lost its animation");

            TextureMetadataSection textureMeta = (TextureMetadataSection) resource.getMetadata("texture");
            if (textureMeta != null && !textureMeta.getListMipmaps()
                .isEmpty()) return bail(changedFile, "has explicit mipmap overrides");

            image = ImageIO.read(resource.getInputStream());
        } finally {
            closeQuietly(resource);
        }
        if (image == null) return bail(changedFile, "ImageIO couldn't decode the PNG (mid-write?)");

        String sizeProblem = animation == null ? staticSizeProblem(image, sprite) : frameStripProblem(image, sprite);
        if (sizeProblem != null) return bail(changedFile, sizeProblem);

        upload(map, sprite, image, animation);

        PackWatch.LOG.info(
            "PackWatch: patched sprite '{}' [{}] {}x{}{}",
            sprite.getIconName(),
            sprite.getClass()
                .getSimpleName(),
            sprite.getIconWidth(),
            sprite.getIconHeight(),
            animation == null ? ""
                : " animated: " + (image.getHeight() / image.getWidth())
                    + " frames in the strip, "
                    + sprite.getFrameCount()
                    + " loaded");
        return true;
    }

    /**
     * Re-runs the compact-to-47 expansion the stitch would have done and patches every tile of the set, not just
     * the one that changed: the renderer samples the generated tiles, and all 47 are derived from all 5 sources,
     * so any one edit invalidates the lot. They live at the same addresses as the source tiles ({@code 0.png} to
     * {@code 46.png} beside the .properties), which is why writing a raw source tile over one of them looked
     * almost right for tile 0 and wrong everywhere else.
     */
    private static boolean patchCompactCtmSet(TextureMap map, Path changedFile, String domain, String relFromDomain) {
        int lastSlash = relFromDomain.lastIndexOf('/');
        if (lastSlash < 0) return bail(changedFile, "CTM tile isn't inside a set directory");
        String dir = relFromDomain.substring(0, lastSlash);

        IResourceManager resourceManager = Minecraft.getMinecraft()
            .getResourceManager();

        BufferedImage[] compact = new BufferedImage[CompactCtmExpander.COMPACT_TILES];
        for (int i = 0; i < compact.length; i++) {
            compact[i] = readImageOrNull(resourceManager, new ResourceLocation(domain, dir + "/" + i + ".png"));
            if (compact[i] == null) return bail(changedFile, "couldn't read compact tile " + i + " of the set");
        }

        BufferedImage[] expanded = CompactCtmExpander.expand(compact);
        if (expanded == null) return bail(changedFile, "compact tiles aren't a uniform, even-sized set");

        int patched = 0;
        for (int i = 0; i < expanded.length; i++) {
            ResourceLocation location = new ResourceLocation(domain, dir + "/" + i + ".png");
            TextureAtlasSprite sprite = map.getTextureExtry(location.toString());
            if (sprite == null) continue;
            if (!sprite.hasCustomLoader(resourceManager, location))
                return bail(changedFile, "expanded tile " + i + " isn't loaded by MCPatcher");
            if (!patchWithImage(map, sprite, location, expanded[i], changedFile)) return false;
            patched++;
        }

        if (patched == 0) return bail(changedFile, "none of the set's expanded tiles are registered sprites");
        PackWatch.LOG.info("PackWatch: re-expanded compact CTM set {} -- patched {} tile(s)", dir, patched);
        return true;
    }

    static BufferedImage readImageOrNull(IResourceManager resourceManager, ResourceLocation location) {
        IResource resource;
        try {
            resource = resourceManager.getResource(location);
        } catch (IOException e) {
            return null;
        }
        try {
            return ImageIO.read(resource.getInputStream());
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(resource);
        }
    }

    /**
     * getResource eagerly opens the .mcmeta stream too, and getMetadata is the only path that closes it; left
     * open, both handles keep the pack's files locked on Windows.
     */
    static void closeQuietly(IResource resource) {
        try {
            resource.getInputStream()
                .close();
        } catch (Exception ignored) {}
        try {
            resource.getMetadata("animation");
        } catch (Exception ignored) {}
    }

    private static boolean patchViaCustomLoader(TextureMap map, TextureAtlasSprite sprite,
        ResourceLocation spriteLocation, IResourceManager resourceManager, Path changedFile) {
        BufferedImage fresh = readImageOrNull(resourceManager, spriteLocation);
        if (fresh == null) return bail(changedFile, "couldn't read the sprite's own image");

        if (!patchWithImage(map, sprite, spriteLocation, fresh, changedFile)) return false;

        PackWatch.LOG.info(
            "PackWatch: patched sprite '{}' [{}] {}x{} via its own loader, {} frame(s)",
            sprite.getIconName(),
            sprite.getClass()
                .getSimpleName(),
            sprite.getIconWidth(),
            sprite.getIconHeight(),
            sprite.getFrameCount());
        return true;
    }

    /** Hands {@code image} to a custom-loader sprite and re-uploads it, leaving its atlas region untouched. */
    private static boolean patchWithImage(TextureMap map, TextureAtlasSprite sprite, ResourceLocation location,
        BufferedImage image, Path changedFile) {
        int width = sprite.getIconWidth();
        int height = sprite.getIconHeight();

        if (!putCachedImage(sprite, location, image))
            return bail(changedFile, "couldn't reach the custom loader's image cache");

        // Reads backwards, but false is the success case: TextureMap only re-stitches a sprite whose load()
        // returned false, so false means "I loaded my pixels" and true means "nothing to load, skip me" --
        // which is what these sprites return when their image cache is empty.
        if (sprite.load(
            Minecraft.getMinecraft()
                .getResourceManager(),
            location)) return bail(changedFile, "custom loader had no image to load");
        if (sprite.getIconWidth() != width || sprite.getIconHeight() != height)
            return bail(changedFile, "custom loader changed the sprite size");

        sprite.generateMipmaps(getMipmapLevels(map));
        uploadToGpu(map, sprite);
        return true;
    }

    /**
     * MCPatcher's CTM sprites take their pixels from a TileLoader cache, which is emptied once the atlas has
     * been stitched -- so mid-game their load() finds nothing and returns "skip me" without touching a pixel.
     * Put the image we want it to load back into that cache. By reflection because PackWatch doesn't compile
     * against Angelica and the cache has no public way in; a miss (renamed field, some other CTM
     * implementation) just means a full reload.
     */
    private static boolean putCachedImage(TextureAtlasSprite sprite, ResourceLocation location, BufferedImage image) {
        try {
            Field loaderField = sprite.getClass()
                .getDeclaredField("loader");
            loaderField.setAccessible(true);
            Object tileLoader = loaderField.get(sprite);

            Field imagesField = tileLoader.getClass()
                .getDeclaredField("tileImages");
            imagesField.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<ResourceLocation, BufferedImage> tileImages = (Map<ResourceLocation, BufferedImage>) imagesField
                .get(tileLoader);
            tileImages.put(location, image);
            return true;
        } catch (Exception e) {
            PackWatch.LOG.warn("Couldn't refresh the cached tile image for {}", location, e);
            return false;
        }
    }

    private static String staticSizeProblem(BufferedImage image, TextureAtlasSprite sprite) {
        if (image.getWidth() == sprite.getIconWidth() && image.getHeight() == sprite.getIconHeight()) return null;
        return "size changed (" + image.getWidth()
            + "x"
            + image.getHeight()
            + " vs sprite "
            + sprite.getIconWidth()
            + "x"
            + sprite.getIconHeight()
            + ")";
    }

    /**
     * An animation's source is a vertical strip of square frames, so only its width has to match the atlas
     * region -- adding or removing frames just makes the strip taller and is safe to patch.
     */
    private static String frameStripProblem(BufferedImage image, TextureAtlasSprite sprite) {
        if (image.getWidth() != sprite.getIconWidth())
            return "frame width changed (" + image.getWidth() + " vs sprite " + sprite.getIconWidth() + ")";
        if (image.getHeight() < image.getWidth() || image.getHeight() % image.getWidth() != 0)
            return "animation strip " + image.getWidth()
                + "x"
                + image.getHeight()
                + " isn't a whole number of square frames";
        return null;
    }

    static void upload(TextureMap map, TextureAtlasSprite sprite, BufferedImage image) {
        upload(map, sprite, image, null);
    }

    /**
     * {@code animation} must be non-null for a sprite that's already animated: loadSprite resets the sprite's
     * metadata from this argument, and TextureMap goes on ticking it either way.
     */
    static void upload(TextureMap map, TextureAtlasSprite sprite, BufferedImage image,
        AnimationMetadataSection animation) {
        // Must be sized 1 + mipmapLevels even though only [0] is populated, exactly as vanilla's
        // TextureMap#loadTextureAtlas does: the per-frame pixel array is allocated from this length, and
        // generateMipmapData then indexes the missing levels directly -> ArrayIndexOutOfBoundsException.
        int mipmapLevels = getMipmapLevels(map);
        BufferedImage[] images = new BufferedImage[1 + mipmapLevels];
        images[0] = image;
        sprite.loadSprite(images, animation, false);
        sprite.generateMipmaps(mipmapLevels);
        uploadToGpu(map, sprite);
    }

    private static void uploadToGpu(TextureMap map, TextureAtlasSprite sprite) {
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
    }

    private static String joinSegments(Path path, int fromIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = fromIndex; i < path.getNameCount(); i++) {
            if (sb.length() > 0) sb.append('/');
            sb.append(
                path.getName(i)
                    .toString());
        }
        return sb.toString();
    }

    private static int getMipmapLevels(TextureMap map) {
        try {
            return MIPMAP_LEVELS_FIELD.getInt(map);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean hasSegment(Path path, String segment) {
        for (int i = 0; i < path.getNameCount(); i++) {
            if (segment.equals(
                path.getName(i)
                    .toString()))
                return true;
        }
        return false;
    }
}
