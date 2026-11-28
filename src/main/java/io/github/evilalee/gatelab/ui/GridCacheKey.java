package io.github.evilalee.gatelab.ui;

/** Stable identity for the visible grid lattice at one viewport and view transform. */
record GridCacheKey(
        int width,
        int height,
        long scaleBits,
        long phaseXBits,
        long phaseYBits,
        long spacingBits,
        boolean dotsVisible) {

    static GridCacheKey forView(
            int width, int height, double scale,
            double translateX, double translateY,
            int worldSpacing, double minimumScreenSpacing) {
        if (width < 0 || height < 0 || !Double.isFinite(scale) || scale <= 0.0
                || !Double.isFinite(translateX) || !Double.isFinite(translateY)
                || worldSpacing <= 0 || !Double.isFinite(minimumScreenSpacing)
                || minimumScreenSpacing <= 0.0) {
            throw new IllegalArgumentException("invalid grid cache key inputs");
        }

        double screenSpacing = worldSpacing * scale;
        boolean visible = screenSpacing >= minimumScreenSpacing;
        double phaseX = visible ? positiveModulo(translateX, screenSpacing) : 0.0;
        double phaseY = visible ? positiveModulo(translateY, screenSpacing) : 0.0;
        return new GridCacheKey(
                width, height,
                Double.doubleToLongBits(scale),
                Double.doubleToLongBits(phaseX),
                Double.doubleToLongBits(phaseY),
                Double.doubleToLongBits(screenSpacing),
                visible);
    }

    double phaseX() {
        return Double.longBitsToDouble(phaseXBits);
    }

    double phaseY() {
        return Double.longBitsToDouble(phaseYBits);
    }

    double spacing() {
        return Double.longBitsToDouble(spacingBits);
    }

    int tileSize() {
        return Math.max(1, (int) Math.ceil(spacing()));
    }

    private static double positiveModulo(double value, double period) {
        double result = value % period;
        return result < 0.0 ? result + period : result;
    }
}
