package io.github.evilalee.gatelab.ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * Caches a transparent viewport-sized grid image. Integer pans shift the
 * existing image and redraw only the newly exposed edge strips.
 */
final class GridPatternCache {
    private static final Color DOT_COLOR = new Color(205, 209, 214);

    private GridCacheKey key;
    private BufferedImage image;
    private double lastTranslateX;
    private double lastTranslateY;
    private long keyChangeCount;
    private long fullRebuildCount;
    private long incrementalUpdateCount;

    BufferedImage imageFor(
            int width, int height, double scale,
            double translateX, double translateY,
            int worldSpacing, double minimumScreenSpacing) {
        GridCacheKey next = GridCacheKey.forView(
                width, height, scale, translateX, translateY,
                worldSpacing, minimumScreenSpacing);
        if (next.equals(key)) {
            return image;
        }

        keyChangeCount++;
        boolean canShift = canShift(next, translateX, translateY);
        if (width <= 0 || height <= 0 || !next.dotsVisible()) {
            image = null;
        } else if (canShift) {
            image = shiftAndPaintExposed(
                    image, width, height, (int) Math.rint(translateX - lastTranslateX),
                    (int) Math.rint(translateY - lastTranslateY),
                    scale, translateX, translateY, worldSpacing);
            incrementalUpdateCount++;
        } else {
            image = buildImage(width, height, scale, translateX, translateY, worldSpacing);
            fullRebuildCount++;
        }

        key = next;
        lastTranslateX = translateX;
        lastTranslateY = translateY;
        return image;
    }

    long keyChangeCount() {
        return keyChangeCount;
    }

    long fullRebuildCount() {
        return fullRebuildCount;
    }

    long incrementalUpdateCount() {
        return incrementalUpdateCount;
    }

    private boolean canShift(GridCacheKey next, double translateX, double translateY) {
        if (image == null || key == null || !key.dotsVisible()
                || !next.dotsVisible()
                || key.width() != next.width()
                || key.height() != next.height()
                || key.scaleBits() != next.scaleBits()) {
            return false;
        }
        double dx = translateX - lastTranslateX;
        double dy = translateY - lastTranslateY;
        return dx == Math.rint(dx) && dy == Math.rint(dy)
                && Math.abs(dx) < next.width() && Math.abs(dy) < next.height()
                && (dx != 0.0 || dy != 0.0);
    }

    private static BufferedImage buildImage(
            int width, int height, double scale,
            double translateX, double translateY, int worldSpacing) {
        BufferedImage result = new BufferedImage(
                width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            paintDots(graphics, new Rectangle(0, 0, width, height),
                    width, height, scale, translateX, translateY, worldSpacing);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static BufferedImage shiftAndPaintExposed(
            BufferedImage previous, int width, int height, int dx, int dy,
            double scale, double translateX, double translateY, int worldSpacing) {
        BufferedImage shifted = new BufferedImage(
                width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = shifted.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(previous, dx, dy, null);
            graphics.setComposite(AlphaComposite.SrcOver);

            if (dx > 0) {
                paintDots(graphics, new Rectangle(0, 0, dx, height),
                        width, height, scale, translateX, translateY, worldSpacing);
            } else if (dx < 0) {
                paintDots(graphics, new Rectangle(width + dx, 0, -dx, height),
                        width, height, scale, translateX, translateY, worldSpacing);
            }
            if (dy > 0) {
                paintDots(graphics, new Rectangle(0, 0, width, dy),
                        width, height, scale, translateX, translateY, worldSpacing);
            } else if (dy < 0) {
                paintDots(graphics, new Rectangle(0, height + dy, width, -dy),
                        width, height, scale, translateX, translateY, worldSpacing);
            }
        } finally {
            graphics.dispose();
        }
        return shifted;
    }

    private static void paintDots(
            Graphics2D graphics, Rectangle clip,
            int width, int height, double scale,
            double translateX, double translateY, int worldSpacing) {
        if (clip.isEmpty()) {
            return;
        }
        double spacing = worldSpacing * scale;
        if (spacing < 4.0) {
            return;
        }
        graphics.setClip(clip);
        graphics.setColor(DOT_COLOR);
        int dotSize = spacing >= 8.0 ? 2 : 1;
        double phaseX = positiveModulo(translateX, spacing);
        double phaseY = positiveModulo(translateY, spacing);

        // Start one period before the viewport because a rounded negative
        // lattice coordinate can still land on screen pixel zero.
        for (int row = -1; ; row++) {
            int screenY = (int) Math.round(phaseY + row * spacing);
            if (screenY >= height + dotSize) {
                break;
            }
            for (int column = -1; ; column++) {
                int screenX = (int) Math.round(phaseX + column * spacing);
                if (screenX >= width + dotSize) {
                    break;
                }
                graphics.fillRect(screenX, screenY, dotSize, dotSize);
            }
        }
    }

    private static double positiveModulo(double value, double period) {
        double result = value % period;
        return result < 0.0 ? result + period : result;
    }
}
