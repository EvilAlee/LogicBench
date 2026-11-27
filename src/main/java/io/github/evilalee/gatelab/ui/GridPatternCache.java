package io.github.evilalee.gatelab.ui;

import java.awt.Color;
import java.awt.TexturePaint;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/** Reuses a tiny repeating dot tile instead of rasterizing the whole canvas per pan. */
final class GridPatternCache {
    private static final Color DOT_COLOR = new Color(205, 209, 214);
    private GridCacheKey key;
    private BufferedImage tile;
    private int tileSize = -1;
    private TexturePaint texture;
    private long keyChangeCount;
    private long tileBuildCount;

    TexturePaint paintFor(
            int width, int height, double scale,
            double translateX, double translateY,
            int worldSpacing, double minimumScreenSpacing) {
        GridCacheKey next = GridCacheKey.forView(
                width, height, scale, translateX, translateY,
                worldSpacing, minimumScreenSpacing);
        if (next.equals(key)) {
            return texture;
        }
        key = next;
        keyChangeCount++;
        texture = null;

        if (width <= 0 || height <= 0 || !next.dotsVisible()) {
            return null;
        }

        int requiredTileSize = next.tileSize();
        if (tile == null || tileSize != requiredTileSize) {
            tileSize = requiredTileSize;
            tile = createTile(tileSize);
            tileBuildCount++;
        }
        texture = new TexturePaint(tile, new Rectangle2D.Double(
                next.phaseX(), next.phaseY(), next.spacing(), next.spacing()));
        return texture;
    }

    long keyChangeCount() {
        return keyChangeCount;
    }

    long tileBuildCount() {
        return tileBuildCount;
    }

    private static BufferedImage createTile(int size) {
        BufferedImage image = new BufferedImage(
                size, size, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(DOT_COLOR);
            graphics.fillRect(0, 0, size >= 8 ? 2 : 1, size >= 8 ? 2 : 1);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
