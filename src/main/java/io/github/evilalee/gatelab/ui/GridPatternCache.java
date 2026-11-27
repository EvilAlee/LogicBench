package io.github.evilalee.gatelab.ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;

/**
 * Caches a transparent viewport-sized grid image. Integer pans shift the
 * existing image in place and redraw only the newly exposed edge strips.
 */
final class GridPatternCache {
    private static final int DOT_ARGB = new Color(205, 209, 214).getRGB();

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
            shiftAndPaintExposed(
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
        paintDots(result, new Rectangle(0, 0, width, height),
                scale, translateX, translateY, worldSpacing);
        return result;
    }

    private static void shiftAndPaintExposed(
            BufferedImage target, int width, int height, int dx, int dy,
            double scale, double translateX, double translateY, int worldSpacing) {
        Graphics2D graphics = target.createGraphics();
        Rectangle vertical = null;
        Rectangle horizontal = null;
        try {
            graphics.copyArea(0, 0, width, height, dx, dy);
            if (dx > 0) {
                vertical = new Rectangle(0, 0, dx, height);
            } else if (dx < 0) {
                vertical = new Rectangle(width + dx, 0, -dx, height);
            }
            if (dy > 0) {
                horizontal = new Rectangle(0, 0, width, dy);
            } else if (dy < 0) {
                horizontal = new Rectangle(0, height + dy, width, -dy);
            }
        } finally {
            graphics.dispose();
        }

        if (vertical != null) {
            clearRect(target, vertical);
            paintDots(target, vertical, scale, translateX, translateY, worldSpacing);
        }
        if (horizontal != null) {
            clearRect(target, horizontal);
            paintDots(target, horizontal, scale, translateX, translateY, worldSpacing);
        }
    }

    private static void clearRect(BufferedImage target, Rectangle rectangle) {
        int width = target.getWidth();
        int height = target.getHeight();
        int[] pixels = ((DataBufferInt) target.getRaster().getDataBuffer()).getData();
        int left = Math.max(0, rectangle.x);
        int top = Math.max(0, rectangle.y);
        int right = Math.min(width, rectangle.x + rectangle.width);
        int bottom = Math.min(height, rectangle.y + rectangle.height);
        for (int y = top; y < bottom; y++) {
            Arrays.fill(pixels, y * width + left, y * width + right, 0);
        }
    }

    private static void paintDots(
            BufferedImage target, Rectangle clip,
            double scale, double translateX, double translateY, int worldSpacing) {
        if (clip.isEmpty()) {
            return;
        }
        double spacing = worldSpacing * scale;
        if (spacing < 4.0) {
            return;
        }
        int width = target.getWidth();
        int height = target.getHeight();
        int dotSize = spacing >= 8.0 ? 2 : 1;
        int left = Math.max(0, clip.x);
        int top = Math.max(0, clip.y);
        int right = Math.min(width, clip.x + clip.width);
        int bottom = Math.min(height, clip.y + clip.height);
        int[] pixels = ((DataBufferInt) target.getRaster().getDataBuffer()).getData();
        double phaseX = positiveModulo(translateX, spacing);
        double phaseY = positiveModulo(translateY, spacing);

        // Begin just before the clipped viewport region. A rounded negative
        // lattice coordinate can still land on screen pixel zero.
        int firstRow = (int) Math.floor((top - phaseY) / spacing) - 1;
        int firstColumn = (int) Math.floor((left - phaseX) / spacing) - 1;
        for (int row = firstRow; ; row++) {
            int screenY = (int) Math.round(phaseY + row * spacing);
            if (screenY >= bottom) {
                break;
            }
            int dotTop = Math.max(top, screenY);
            int dotBottom = Math.min(bottom, screenY + dotSize);
            if (dotBottom <= dotTop) {
                continue;
            }
            for (int column = firstColumn; ; column++) {
                int screenX = (int) Math.round(phaseX + column * spacing);
                if (screenX >= right) {
                    break;
                }
                int dotLeft = Math.max(left, screenX);
                int dotRight = Math.min(right, screenX + dotSize);
                if (dotRight <= dotLeft) {
                    continue;
                }
                for (int y = dotTop; y < dotBottom; y++) {
                    Arrays.fill(pixels, y * width + dotLeft, y * width + dotRight, DOT_ARGB);
                }
            }
        }
    }

    private static double positiveModulo(double value, double period) {
        double result = value % period;
        return result < 0.0 ? result + period : result;
    }
}
