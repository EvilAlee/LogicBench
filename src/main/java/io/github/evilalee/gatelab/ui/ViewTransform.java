package io.github.evilalee.gatelab.ui;

import java.awt.geom.Point2D;

/**
 * Converts between world coordinates and canvas screen coordinates.
 * Kept independent of Swing so its math can be tested in headless builds.
 */
public final class ViewTransform {
    public static final double MIN_ZOOM = 0.25;
    public static final double MAX_ZOOM = 4.0;

    private double scale;
    private double translateX;
    private double translateY;

    public ViewTransform() {
        this(1.0, 0.0, 0.0);
    }

    public ViewTransform(double scale, double translateX, double translateY) {
        if (!Double.isFinite(scale) || scale <= 0.0) {
            throw new IllegalArgumentException("scale must be finite and positive");
        }
        if (!Double.isFinite(translateX) || !Double.isFinite(translateY)) {
            throw new IllegalArgumentException("translation must be finite");
        }
        this.scale = clamp(scale);
        this.translateX = translateX;
        this.translateY = translateY;
    }

    public Point2D.Double worldToScreen(double worldX, double worldY) {
        return new Point2D.Double(
                worldX * scale + translateX,
                worldY * scale + translateY);
    }

    public Point2D.Double screenToWorld(double screenX, double screenY) {
        return new Point2D.Double(
                (screenX - translateX) / scale,
                (screenY - translateY) / scale);
    }

    /** Moves the view by a screen-space delta. */
    public void panBy(double screenDeltaX, double screenDeltaY) {
        if (!Double.isFinite(screenDeltaX) || !Double.isFinite(screenDeltaY)) {
            throw new IllegalArgumentException("pan delta must be finite");
        }
        translateX += screenDeltaX;
        translateY += screenDeltaY;
    }

    /**
     * Changes zoom while preserving the world coordinate beneath the cursor.
     * A multiplicative factor above one zooms in; below one zooms out.
     */
    public void zoomAt(double screenX, double screenY, double factor) {
        if (!Double.isFinite(factor) || factor <= 0.0) {
            throw new IllegalArgumentException("zoom factor must be finite and positive");
        }

        Point2D.Double anchor = screenToWorld(screenX, screenY);
        scale = clamp(scale * factor);
        translateX = screenX - anchor.x * scale;
        translateY = screenY - anchor.y * scale;
    }

    public double scale() {
        return scale;
    }

    public double translateX() {
        return translateX;
    }

    public double translateY() {
        return translateY;
    }

    private static double clamp(double value) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value));
    }
}
