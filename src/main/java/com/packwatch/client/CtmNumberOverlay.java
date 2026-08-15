package com.packwatch.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;

import com.packwatch.PackWatch;

import cpw.mods.fml.relauncher.ReflectionHelper;

/**
 * Stamps each MCPatcher connected-texture tile with its own tile index, so you can see in-world which of a CTM
 * set's tiles a face actually picked. Numbers come from a sheet of pre-rendered cells rather than a font: at 16px
 * a rendered glyph is an unreadable smudge and varies by JRE.
 */
public final class CtmNumberOverlay {

    // MCP name in dev, SRG name in a packaged pack -- see SpritePatcher's mipmap field.
    private static final Field REGISTERED_SPRITES_FIELD = ReflectionHelper
        .findField(TextureMap.class, "mapRegisteredSprites", "field_110574_e");

    private static final String SHEET_PATH = "/assets/packwatch/textures/ctm_numbers.png";

    /** A full MCPatcher CTM set is tiles 0-46. */
    private static final int CTM_TILE_COUNT = 47;

    private static boolean active;

    /**
     * The pixels each stamped sprite had before we touched it. Held rather than re-read on the way out: a tile
     * whose source no longer resolves would otherwise be un-restorable, forcing the ~14s reload we're avoiding.
     */
    private static final Map<String, BufferedImage> originals = new LinkedHashMap<String, BufferedImage>();

    private CtmNumberOverlay() {}

    public static String toggle() {
        if (active) {
            active = false;
            restore();
            return "CTM tile numbers off.";
        }

        String error = apply();
        if (error != null) return error;

        active = true;
        return null;
    }

    /** A reload or an in-place patch restores the pack's original pixels, silently wiping the numbers. */
    public static void reapplyIfActive() {
        if (!active) return;
        String error = apply();
        if (error != null) {
            active = false;
            PackWatch.LOG.warn("PackWatch: dropping CTM tile numbers -- {}", error);
        }
    }

    private static String apply() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings.anisotropicFiltering > 1) {
            return "Can't draw CTM tile numbers while anisotropic filtering is on.";
        }

        BufferedImage sheet;
        try {
            sheet = readSheet();
        } catch (IOException e) {
            PackWatch.LOG.error("Couldn't read {}", SHEET_PATH, e);
            return "Couldn't read the number sheet (" + SHEET_PATH + ").";
        }

        int cellSize = deriveCellSize(sheet);
        if (cellSize < 0) {
            return "Number sheet " + sheet.getWidth()
                + "x"
                + sheet.getHeight()
                + " isn't a grid of square cells holding at least "
                + CTM_TILE_COUNT
                + ".";
        }
        int columns = sheet.getWidth() / cellSize;
        int cellCount = columns * (sheet.getHeight() / cellSize);

        TextureMap blocks = mc.getTextureMapBlocks();
        List<String> ctmSpriteNames = ctmSpriteNames(blocks);
        if (ctmSpriteNames.isEmpty()) {
            return "No MCPatcher CTM tiles are registered in the block atlas.";
        }

        originals.clear();
        int stamped = 0;
        int skipped = 0;
        for (String spriteName : ctmSpriteNames) {
            TextureAtlasSprite sprite = blocks.getTextureExtry(spriteName);
            if (sprite == null || sprite.hasAnimationMetadata()) {
                skipped++;
                continue;
            }

            int tileIndex = tileIndexOf(spriteName);
            if (tileIndex < 0 || tileIndex >= cellCount) {
                skipped++;
                continue;
            }

            BufferedImage original = readOriginalOrNull(spriteName, sprite);
            if (original == null) {
                skipped++;
                continue;
            }

            try {
                SpritePatcher.upload(blocks, sprite, stamp(original, sheet, cellSize, columns, tileIndex));
                originals.put(spriteName, original);
                stamped++;
            } catch (Exception e) {
                PackWatch.LOG.warn("Couldn't number CTM tile {}", spriteName, e);
                skipped++;
            }
        }

        PackWatch.LOG.info("PackWatch: numbered {} CTM tile(s), skipped {}", stamped, skipped);
        if (stamped == 0) return "Couldn't number any of the " + ctmSpriteNames.size() + " CTM tiles found.";
        return null;
    }

    /**
     * Re-uploads the pack's own pixels over the numbers, which is instant where a full reload costs ~14s. Any
     * tile we can't read back leaves the atlas half-numbered, so that falls back to the reload.
     */
    private static void restore() {
        TextureMap blocks = Minecraft.getMinecraft()
            .getTextureMapBlocks();

        int restored = 0;
        String failure = null;
        for (Map.Entry<String, BufferedImage> entry : originals.entrySet()) {
            TextureAtlasSprite sprite = blocks.getTextureExtry(entry.getKey());
            if (sprite == null) {
                failure = entry.getKey() + " is no longer a registered sprite";
                break;
            }

            try {
                SpritePatcher.upload(blocks, sprite, entry.getValue());
                restored++;
            } catch (Exception e) {
                PackWatch.LOG.warn("Couldn't un-number CTM tile {}", entry.getKey(), e);
                failure = entry.getKey() + " wouldn't upload";
                break;
            }
        }

        originals.clear();
        if (failure == null) {
            PackWatch.LOG.info("PackWatch: restored {} CTM tile(s) in place (no full reload)", restored);
            return;
        }

        PackWatch.LOG.info("PackWatch: falling back to full reload -- {}", failure);
        TextureReloadTrigger.refresh();
    }

    /**
     * The column count is inferred rather than fixed, since 12x4 and 16x3 are both natural ways to lay out the
     * same 48 cells. Square cells tiling the whole image leave the cell size as the only unknown: the largest
     * one dividing both dimensions that still yields a full CTM set, as bigger candidates just group that grid.
     *
     * @return the cell size in pixels, or -1 if no grid fits
     */
    private static int deriveCellSize(BufferedImage sheet) {
        for (int cellSize = Math.min(sheet.getWidth(), sheet.getHeight()); cellSize >= 1; cellSize--) {
            if (sheet.getWidth() % cellSize != 0 || sheet.getHeight() % cellSize != 0) continue;
            int cells = (sheet.getWidth() / cellSize) * (sheet.getHeight() / cellSize);
            if (cells >= CTM_TILE_COUNT) return cellSize;
        }
        return -1;
    }

    private static BufferedImage readSheet() throws IOException {
        InputStream in = CtmNumberOverlay.class.getResourceAsStream(SHEET_PATH);
        if (in == null) throw new IOException("not on the classpath: " + SHEET_PATH);
        try {
            BufferedImage image = ImageIO.read(in);
            if (image == null) throw new IOException("ImageIO couldn't decode " + SHEET_PATH);
            return image;
        } finally {
            in.close();
        }
    }

    /** Null means the tile gets left alone entirely -- we only number what we can put back. */
    private static BufferedImage readOriginalOrNull(String spriteName, TextureAtlasSprite sprite) {
        int colon = spriteName.indexOf(':');
        ResourceLocation location = new ResourceLocation(
            spriteName.substring(0, colon),
            spriteName.substring(colon + 1));

        try {
            IResource resource = Minecraft.getMinecraft()
                .getResourceManager()
                .getResource(location);
            BufferedImage image = ImageIO.read(resource.getInputStream());
            if (image == null) return null;
            if (image.getWidth() != sprite.getIconWidth() || image.getHeight() != sprite.getIconHeight()) return null;
            return image;
        } catch (IOException e) {
            return null;
        }
    }

    /** Nearest-neighbour rather than smooth scaling, so the cell stays crisp on HD packs. */
    private static BufferedImage stamp(BufferedImage base, BufferedImage sheet, int cellSize, int columns,
        int tileIndex) {
        int width = base.getWidth();
        int height = base.getHeight();
        int cellX = (tileIndex % columns) * cellSize;
        int cellY = (tileIndex / columns) * cellSize;

        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int over = sheet.getRGB(cellX + (x * cellSize) / width, cellY + (y * cellSize) / height);
                out.setRGB(x, y, blend(base.getRGB(x, y), over));
            }
        }
        return out;
    }

    private static int blend(int under, int over) {
        int overAlpha = (over >>> 24) & 0xFF;
        if (overAlpha == 0xFF) return over;
        if (overAlpha == 0) return under;

        int underAlpha = (under >>> 24) & 0xFF;
        int outAlpha = overAlpha + (underAlpha * (0xFF - overAlpha)) / 0xFF;
        if (outAlpha == 0) return 0;

        return (outAlpha << 24) | (channel(under, over, overAlpha, underAlpha, outAlpha, 16) << 16)
            | (channel(under, over, overAlpha, underAlpha, outAlpha, 8) << 8)
            | channel(under, over, overAlpha, underAlpha, outAlpha, 0);
    }

    private static int channel(int under, int over, int overAlpha, int underAlpha, int outAlpha, int shift) {
        int o = (over >>> shift) & 0xFF;
        int u = (under >>> shift) & 0xFF;
        return ((o * overAlpha) + (u * underAlpha * (0xFF - overAlpha)) / 0xFF) / outAlpha;
    }

    /** "minecraft:mcpatcher/ctm/machine_top/4.png" -> 4. */
    private static int tileIndexOf(String spriteName) {
        int slash = spriteName.lastIndexOf('/');
        int dot = spriteName.lastIndexOf('.');
        if (slash < 0 || dot < slash) return -1;
        try {
            return Integer.parseInt(spriteName.substring(slash + 1, dot));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Snapshotted rather than iterated live: uploading re-enters the atlas and may register new sprites. */
    private static List<String> ctmSpriteNames(TextureMap map) {
        Map<?, ?> registered;
        try {
            registered = (Map<?, ?>) REGISTERED_SPRITES_FIELD.get(map);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        List<String> names = new ArrayList<String>();
        for (Object key : registered.keySet()) {
            String name = (String) key;
            if (name.indexOf(':') < 0 || !name.endsWith(".png")) continue;
            if (name.contains("mcpatcher/ctm/") || name.contains("optifine/ctm/")) names.add(name);
        }
        return names;
    }
}
