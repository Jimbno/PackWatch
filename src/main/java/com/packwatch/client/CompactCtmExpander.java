package com.packwatch.client;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Expands a compact CTM set's 5 tiles into the 47 the renderer actually samples, mirroring Angelica's
 * {@code com.prupe.mcpatcher.ctm.CTMTextureGenerator}. MCPatcher does this once while the atlas is stitched, so
 * a live edit has to redo it -- patching the 5 source tiles alone leaves the other 42 holding pre-edit pixels.
 */
final class CompactCtmExpander {

    static final int EXPANDED_TILES = 47;
    static final int COMPACT_TILES = 5;

    private static final int FULL = 0;
    private static final int NONE = 1;
    private static final int VERT = 2;
    private static final int HORI = 3;
    private static final int CORN = 4;

    /**
     * One row per expanded tile: which compact tile each quadrant comes from, as {top-left, top-right,
     * bottom-left, bottom-right}. Transcribed from CTMTextureGenerator#generateTextures. The rows vanilla
     * assigns a whole tile to (0, 2, 24, 26, 46) are written as four identical quadrants, which composes to
     * exactly that tile -- the quadrants are copied from matching coordinates, so it's a straight copy.
     */
    private static final int[][] QUADRANTS = { { FULL, FULL, FULL, FULL }, { FULL, HORI, FULL, HORI },
        { HORI, HORI, HORI, HORI }, { HORI, FULL, HORI, FULL }, { FULL, HORI, VERT, CORN }, { HORI, FULL, CORN, VERT },
        { VERT, CORN, VERT, CORN }, { HORI, HORI, CORN, CORN }, { CORN, NONE, CORN, CORN }, { CORN, CORN, CORN, NONE },
        { NONE, CORN, NONE, CORN }, { NONE, NONE, CORN, CORN }, { FULL, FULL, VERT, VERT }, { FULL, HORI, VERT, NONE },
        { HORI, HORI, NONE, NONE }, { HORI, FULL, NONE, VERT }, { VERT, CORN, FULL, HORI }, { CORN, VERT, HORI, FULL },
        { CORN, CORN, HORI, HORI }, { CORN, VERT, CORN, VERT }, { NONE, CORN, CORN, CORN }, { CORN, CORN, NONE, CORN },
        { CORN, CORN, NONE, NONE }, { CORN, NONE, CORN, NONE }, { VERT, VERT, VERT, VERT }, { VERT, NONE, VERT, NONE },
        { NONE, NONE, NONE, NONE }, { NONE, VERT, NONE, VERT }, { VERT, CORN, VERT, NONE }, { HORI, HORI, NONE, CORN },
        { VERT, NONE, VERT, CORN }, { HORI, HORI, CORN, NONE }, { NONE, NONE, NONE, CORN }, { NONE, NONE, CORN, NONE },
        { CORN, NONE, NONE, CORN }, { NONE, CORN, CORN, NONE }, { VERT, VERT, FULL, FULL }, { VERT, NONE, FULL, HORI },
        { NONE, NONE, HORI, HORI }, { NONE, VERT, HORI, FULL }, { CORN, NONE, HORI, HORI }, { NONE, VERT, NONE, VERT },
        { NONE, CORN, HORI, HORI }, { CORN, VERT, NONE, VERT }, { NONE, CORN, NONE, NONE }, { CORN, NONE, NONE, NONE },
        { CORN, CORN, CORN, CORN } };

    private CompactCtmExpander() {}

    /**
     * True for a tile Angelica's compact_expanded method generated at stitch time -- generation ignores any
     * files at the generated indices, so the sprite's class (matched by name; no compile-time Angelica dep) is
     * the only reliable signal, not the pack's files.
     */
    static boolean isGeneratedSprite(TextureAtlasSprite sprite) {
        return "GeneratedCTMAtlasSprite".equals(
            sprite.getClass()
                .getSimpleName());
    }

    /** @return the 47 expanded tiles, or null if the compact tiles aren't a usable set. */
    static BufferedImage[] expand(BufferedImage[] compact) {
        if (compact.length != COMPACT_TILES) return null;

        int width = compact[0].getWidth();
        for (BufferedImage tile : compact) {
            if (tile == null) return null;
            // Quadrants are copied between tiles at matching coordinates, so every tile has to line up.
            if (tile.getWidth() != width || tile.getHeight() != compact[0].getHeight()) return null;
        }
        if (width <= 0 || width % 2 != 0 || compact[0].getHeight() % width != 0) return null;

        BufferedImage[] expanded = new BufferedImage[EXPANDED_TILES];
        for (int i = 0; i < EXPANDED_TILES; i++) {
            int[] quadrants = QUADRANTS[i];
            expanded[i] = generate(
                compact[quadrants[0]],
                compact[quadrants[1]],
                compact[quadrants[2]],
                compact[quadrants[3]]);
        }
        return expanded;
    }

    private static BufferedImage generate(BufferedImage topLeft, BufferedImage topRight, BufferedImage bottomLeft,
        BufferedImage bottomRight) {
        int width = topLeft.getWidth();
        int half = width / 2;
        int frames = topLeft.getHeight() / width;

        BufferedImage out = new BufferedImage(width, topLeft.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = out.createGraphics();
        try {
            for (int frame = 0; frame < frames; frame++) {
                int y = width * frame;
                copy(topLeft, graphics, 0, y, half);
                copy(topRight, graphics, half, y, half);
                copy(bottomLeft, graphics, 0, y + half, half);
                copy(bottomRight, graphics, half, y + half, half);
            }
        } finally {
            graphics.dispose();
        }
        return out;
    }

    /** Source and destination rectangles are identical: each quadrant keeps its own position. */
    private static void copy(BufferedImage src, Graphics2D graphics, int x, int y, int size) {
        graphics.drawImage(src, x, y, x + size, y + size, x, y, x + size, y + size, null);
    }
}
