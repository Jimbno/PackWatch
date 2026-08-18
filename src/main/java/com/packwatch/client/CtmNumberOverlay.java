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
import net.minecraft.client.resources.data.AnimationMetadataSection;
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

    private static final Field ANIMATION_METADATA_FIELD = ReflectionHelper
        .findField(TextureAtlasSprite.class, "animationMetadata", "field_110982_k");

    private static final String SHEET_PATH = "/assets/packwatch/textures/ctm_numbers.png";
    private static final String QUADRANT_SHEET_PATH = "/assets/packwatch/textures/compact_numbers.png";

    /** A full MCPatcher CTM set is tiles 0-46. */
    private static final int CTM_TILE_COUNT = 47;

    private static boolean active;
    private static boolean followDebugScreen;

    /**
     * The pixels each stamped sprite had before we touched it. Held rather than re-read on the way out: a tile
     * whose source no longer resolves would otherwise be un-restorable, forcing the ~14s reload we're avoiding.
     */
    private static final Map<String, BufferedImage> originals = new LinkedHashMap<String, BufferedImage>();

    private CtmNumberOverlay() {}

    public static String toggle() {
        followDebugScreen = false;
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

    /** @return a user-facing status message. */
    public static String toggleFollowDebugScreen() {
        followDebugScreen = !followDebugScreen;
        if (!followDebugScreen) {
            if (active) {
                active = false;
                restore();
            }
            return "CTM tile numbers no longer follow F3.";
        }

        // Whatever the debug screen is doing right now wins, so the two can't get out of step.
        syncToDebugScreen();
        return "CTM tile numbers now show while F3 is open.";
    }

    /**
     * Called every client tick. Stamping ~1500 tiles costs a few ms, so it only runs on the frames where F3 is
     * actually opened or closed rather than while it's held open.
     */
    static void syncToDebugScreen() {
        if (!followDebugScreen) return;

        boolean wanted = Minecraft.getMinecraft().gameSettings.showDebugInfo;
        if (wanted == active) return;

        if (!wanted) {
            active = false;
            restore();
            return;
        }

        String error = apply();
        if (error != null) {
            followDebugScreen = false;
            PackWatch.LOG.warn("PackWatch: not following F3 with CTM numbers -- {}", error);
            return;
        }
        active = true;
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

        Sheet fullSheet;
        Sheet quadrantSheet;
        try {
            BufferedImage image = readSheet(SHEET_PATH);
            int cellSize = deriveCellSize(image);
            if (cellSize < 0) {
                return "Number sheet " + image.getWidth()
                    + "x"
                    + image.getHeight()
                    + " isn't a grid of square cells holding at least "
                    + CTM_TILE_COUNT
                    + ".";
            }
            fullSheet = new Sheet(image, cellSize, image.getWidth() / cellSize);

            // One cell per compact tile, laid out in a single row.
            BufferedImage quadrantImage = readSheet(QUADRANT_SHEET_PATH);
            int quadrantCell = quadrantImage.getWidth() / CompactCtmExpander.COMPACT_TILES;
            if (quadrantCell <= 0 || quadrantImage.getHeight() % quadrantCell != 0) {
                return "Quadrant number sheet " + quadrantImage.getWidth()
                    + "x"
                    + quadrantImage.getHeight()
                    + " isn't "
                    + CompactCtmExpander.COMPACT_TILES
                    + " square cells.";
            }
            quadrantSheet = new Sheet(quadrantImage, quadrantCell, CompactCtmExpander.COMPACT_TILES);
        } catch (IOException e) {
            PackWatch.LOG.error("Couldn't read a number sheet", e);
            return "Couldn't read a number sheet.";
        }

        TextureMap blocks = mc.getTextureMapBlocks();
        List<String> ctmSpriteNames = ctmSpriteNames(blocks);
        if (ctmSpriteNames.isEmpty()) {
            return "No MCPatcher CTM tiles are registered in the block atlas.";
        }

        Map<String, BufferedImage> previous = new LinkedHashMap<String, BufferedImage>(originals);
        Map<String, ExpandedSet> expandedSets = new LinkedHashMap<String, ExpandedSet>();
        Map<String, Boolean> quadrantSets = new LinkedHashMap<String, Boolean>();
        originals.clear();
        int stamped = 0;
        int skipped = 0;
        for (String spriteName : ctmSpriteNames) {
            TextureAtlasSprite sprite = blocks.getTextureExtry(spriteName);
            if (sprite == null) {
                skipped++;
                continue;
            }

            // A quadrant-composed set only ever shows a quarter of a tile per face, so it gets the sheet drawn
            // to survive that slicing; an expanded set renders whole tiles and takes the ordinary one.
            Sheet sheet = composesQuadrants(blocks, spriteName, quadrantSets) ? quadrantSheet : fullSheet;

            int tileIndex = tileIndexOf(spriteName);
            if (tileIndex < 0 || tileIndex >= sheet.cellCount) {
                skipped++;
                continue;
            }

            ExpandedSet expandedSet = expandedSetFor(blocks, spriteName, expandedSets, quadrantSheet);
            boolean fromExpansion = expandedSet != null && tileIndex < expandedSet.plain.length;

            BufferedImage original = fromExpansion ? expandedSet.plain[tileIndex]
                : readTileImage(spriteName, sprite, previous);
            if (original == null) {
                skipped++;
                continue;
            }

            try {
                BufferedImage numbered = fromExpansion ? expandedSet.numbered[tileIndex]
                    : stamp(original, sprite.getIconHeight(), sheet, tileIndex);
                SpritePatcher.upload(blocks, sprite, numbered, animationOf(sprite));
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
                SpritePatcher.upload(blocks, sprite, entry.getValue(), animationOf(sprite));
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

    private static BufferedImage readSheet(String path) throws IOException {
        InputStream in = CtmNumberOverlay.class.getResourceAsStream(path);
        if (in == null) throw new IOException("not on the classpath: " + path);
        try {
            BufferedImage image = ImageIO.read(in);
            if (image == null) throw new IOException("ImageIO couldn't decode " + path);
            return image;
        } finally {
            in.close();
        }
    }

    /**
     * The 47 expanded tiles for the set {@code spriteName} belongs to, or null if it isn't a compact set.
     * <p>
     * A compact set's atlas sprites hold quadrant compositions, not the 5 files on disk, so numbering one using
     * its own file as the base would overwrite the composition with a raw tile -- the same breakage a save used
     * to cause. Detected by the shape of the set rather than by parsing .properties: an index the atlas has a
     * sprite for but the pack has no file for can only have been generated. Expanded once per set per toggle,
     * then cached, since all 47 come out of one call.
     */
    private static ExpandedSet expandedSetFor(TextureMap blocks, String spriteName, Map<String, ExpandedSet> cache,
        Sheet quadrantSheet) {
        int colon = spriteName.indexOf(':');
        int lastSlash = spriteName.lastIndexOf('/');
        if (colon < 0 || lastSlash < colon) return null;

        String domain = spriteName.substring(0, colon);
        String dir = spriteName.substring(colon + 1, lastSlash);
        String key = domain + ":" + dir;
        if (cache.containsKey(key)) return cache.get(key);

        ExpandedSet set = null;
        if (looksExpanded(blocks, domain, dir)) {
            BufferedImage[] compact = new BufferedImage[CompactCtmExpander.COMPACT_TILES];
            BufferedImage[] numberedCompact = new BufferedImage[compact.length];
            for (int i = 0; i < compact.length; i++) {
                compact[i] = readResource(new ResourceLocation(domain, dir + "/" + i + ".png"));
                if (compact[i] == null) {
                    compact = null;
                    break;
                }
                // Frames are square, so a compact tile's frame height is its width.
                numberedCompact[i] = stamp(compact[i], compact[i].getWidth(), quadrantSheet, i);
            }

            if (compact != null) {
                BufferedImage[] plain = CompactCtmExpander.expand(compact);
                BufferedImage[] numbered = CompactCtmExpander.expand(numberedCompact);
                if (plain != null && numbered != null) set = new ExpandedSet(plain, numbered);
            }
        }

        cache.put(key, set);
        return set;
    }

    /**
     * An expanded set's tiles, with and without numbers. The numbers are stamped on the 5 sources and expanded
     * along with them rather than drawn on the 47 results: you author the sources, so which source feeds a
     * quadrant is the useful thing to see, and the expansion slices quadrants so it carries them for free.
     */
    private static final class ExpandedSet {

        final BufferedImage[] plain;
        final BufferedImage[] numbered;

        ExpandedSet(BufferedImage[] plain, BufferedImage[] numbered) {
            this.plain = plain;
            this.numbered = numbered;
        }
    }

    /**
     * True for a set MCPatcher composes at render time rather than expanding up front (method=compact): it keeps
     * exactly the 5 compact tiles in the atlas and takes each quadrant of a face from whichever tile suits that
     * corner. A number drawn once across such a tile is only ever seen a quarter at a time, so those sets get one
     * number per quadrant instead. Recognised by shape -- exactly tiles 0-4 and nothing above -- which also keeps
     * 1-tile "fixed" and 4-tile "horizontal" sets out of it.
     */
    private static boolean composesQuadrants(TextureMap blocks, String spriteName, Map<String, Boolean> cache) {
        int colon = spriteName.indexOf(':');
        int lastSlash = spriteName.lastIndexOf('/');
        if (colon < 0 || lastSlash < colon) return false;

        String domain = spriteName.substring(0, colon);
        String dir = spriteName.substring(colon + 1, lastSlash);
        String key = domain + ":" + dir;

        Boolean known = cache.get(key);
        if (known != null) return known.booleanValue();

        boolean composes = composesQuadrants(blocks, domain, dir);
        cache.put(key, Boolean.valueOf(composes));
        return composes;
    }

    private static boolean composesQuadrants(TextureMap blocks, String domain, String dir) {
        for (int i = 0; i < CompactCtmExpander.COMPACT_TILES; i++) {
            if (blocks.getTextureExtry(domain + ":" + dir + "/" + i + ".png") == null) return false;
        }
        for (int i = CompactCtmExpander.COMPACT_TILES; i < CompactCtmExpander.EXPANDED_TILES; i++) {
            if (blocks.getTextureExtry(domain + ":" + dir + "/" + i + ".png") != null) return false;
        }
        return true;
    }

    /** True if the atlas holds MCPatcher-generated tiles for this set, which only compact_expanded produces. */
    private static boolean looksExpanded(TextureMap blocks, String domain, String dir) {
        for (int i = 0; i < CompactCtmExpander.EXPANDED_TILES; i++) {
            TextureAtlasSprite sprite = blocks.getTextureExtry(domain + ":" + dir + "/" + i + ".png");
            if (sprite != null && CompactCtmExpander.isGeneratedSprite(sprite)) return true;
        }
        return false;
    }

    private static BufferedImage readResource(ResourceLocation location) {
        return SpritePatcher.readImageOrNull(
            Minecraft.getMinecraft()
                .getResourceManager(),
            location);
    }

    /**
     * Prefers the file on disk, because a stamped sprite's pixels are only unstamped the first time round. Falls
     * back to what we stamped over last time, then to the sprite itself -- a compact set's expanded tiles exist
     * only in memory and have no file to read.
     */
    private static BufferedImage readTileImage(String spriteName, TextureAtlasSprite sprite,
        Map<String, BufferedImage> previous) {
        BufferedImage fromDisk = readFromResources(spriteName, sprite);
        if (fromDisk != null) return fromDisk;

        BufferedImage alreadyKnown = previous.get(spriteName);
        if (alreadyKnown != null) return alreadyKnown;

        return readSpriteImage(sprite);
    }

    private static BufferedImage readFromResources(String spriteName, TextureAtlasSprite sprite) {
        int colon = spriteName.indexOf(':');
        if (colon < 0) return null;

        BufferedImage image = readResource(
            new ResourceLocation(spriteName.substring(0, colon), spriteName.substring(colon + 1)));
        if (image == null || image.getWidth() != sprite.getIconWidth()) return null;

        // An animated tile's file is a strip of frames, so only its width has to match the atlas region.
        int height = sprite.getIconHeight();
        if (image.getHeight() != height && (height <= 0 || image.getHeight() % height != 0)) return null;
        return image;
    }

    /**
     * The sprite's own pixels as a frame strip. Only useful for animated sprites: TextureMap frees the frame data
     * of everything else once the atlas is stitched, so a static tile reports no frames at all.
     *
     * @return null if the sprite has no usable pixel data, in which case it's left alone
     */
    private static BufferedImage readSpriteImage(TextureAtlasSprite sprite) {
        int width = sprite.getIconWidth();
        int height = sprite.getIconHeight();
        int frames = sprite.getFrameCount();
        if (width <= 0 || height <= 0 || frames <= 0) return null;

        BufferedImage strip = new BufferedImage(width, height * frames, BufferedImage.TYPE_INT_ARGB);
        for (int frame = 0; frame < frames; frame++) {
            int[][] mipLevels = sprite.getFrameTextureData(frame);
            if (mipLevels == null || mipLevels.length == 0) return null;

            int[] pixels = mipLevels[0];
            if (pixels == null || pixels.length != width * height) return null;
            strip.setRGB(0, frame * height, width, height, pixels, 0, width);
        }
        return strip;
    }

    /** Passed straight back to loadSprite so a re-slice keeps the animation's own frame order and timing. */
    private static AnimationMetadataSection animationOf(TextureAtlasSprite sprite) {
        try {
            return (AnimationMetadataSection) ANIMATION_METADATA_FIELD.get(sprite);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Numbers every frame of the strip, not just the first: an animated tile would otherwise show its number
     * for one frame and drop it for the rest. Nearest-neighbour so the cell stays crisp on HD packs.
     */
    private static BufferedImage stamp(BufferedImage base, int frameHeight, Sheet sheet, int tileIndex) {
        int width = base.getWidth();
        int height = base.getHeight();
        int cellSize = sheet.cellSize;
        int cellX = (tileIndex % sheet.columns) * cellSize;
        int cellY = (tileIndex / sheet.columns) * cellSize;

        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            int withinFrame = y % frameHeight;
            for (int x = 0; x < width; x++) {
                int over = sheet.image
                    .getRGB(cellX + (x * cellSize) / width, cellY + (withinFrame * cellSize) / frameHeight);
                out.setRGB(x, y, blend(base.getRGB(x, y), over));
            }
        }
        return out;
    }

    /** A number sheet with the grid it turned out to have. */
    private static final class Sheet {

        final BufferedImage image;
        final int cellSize;
        final int columns;
        final int cellCount;

        Sheet(BufferedImage image, int cellSize, int columns) {
            this.image = image;
            this.cellSize = cellSize;
            this.columns = columns;
            this.cellCount = columns * (image.getHeight() / cellSize);
        }
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
