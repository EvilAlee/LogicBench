package io.github.evilalee.gatelab.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GridPatternCacheTest {
    @Test
    void gridCacheKeyUsesViewportZoomAndOffsetModuloWorldGridSpacing() {
        GridCacheKey original = GridCacheKey.forView(
                900, 700, 1.0, 24.0, -16.0, 10, 4.0);
        GridCacheKey afterWholeGridPan = GridCacheKey.forView(
                900, 700, 1.0, 34.0, -6.0, 10, 4.0);
        GridCacheKey afterPartialPan = GridCacheKey.forView(
                900, 700, 1.0, 25.0, -16.0, 10, 4.0);
        GridCacheKey afterZoom = GridCacheKey.forView(
                900, 700, 0.5, 24.0, -16.0, 10, 4.0);
        GridCacheKey afterResize = GridCacheKey.forView(
                901, 700, 1.0, 24.0, -16.0, 10, 4.0);

        assertEquals(original, afterWholeGridPan,
                "a full grid-period pan reuses the existing lattice");
        assertNotEquals(original, afterPartialPan);
        assertNotEquals(original, afterZoom);
        assertNotEquals(original, afterResize);
    }

    @Test
    void cacheReusesWholePeriodPansAndIncrementallyShiftsPartialPans() {
        GridPatternCache cache = new GridPatternCache();

        var first = cache.imageFor(800, 600, 1.0, 24.0, 24.0, 10, 4.0);
        var wholeGridPan = cache.imageFor(800, 600, 1.0, 34.0, 24.0, 10, 4.0);
        assertSame(first, wholeGridPan);
        assertEquals(1, cache.fullRebuildCount());
        assertEquals(0, cache.incrementalUpdateCount());

        var partialPan = cache.imageFor(800, 600, 1.0, 35.0, 24.0, 10, 4.0);
        assertNotSame(first, partialPan);
        assertEquals(1, cache.fullRebuildCount());
        assertEquals(1, cache.incrementalUpdateCount());

        var fresh = new GridPatternCache().imageFor(
                800, 600, 1.0, 35.0, 24.0, 10, 4.0);
        assertImagesEqual(fresh, partialPan,
                "incremental pan must produce the same dots as a fresh render");

        var diagonalPan = cache.imageFor(800, 600, 1.0, 32.0, 26.0, 10, 4.0);
        var freshDiagonal = new GridPatternCache().imageFor(
                800, 600, 1.0, 32.0, 26.0, 10, 4.0);
        assertImagesEqual(freshDiagonal, diagonalPan,
                "diagonal partial pan must preserve the exact lattice");
        assertEquals(1, cache.fullRebuildCount());
        assertEquals(2, cache.incrementalUpdateCount());
    }

    @Test
    void gridDotsAreHiddenWhenRawScreenSpacingFallsBelowFourPixels() {
        GridPatternCache cache = new GridPatternCache();
        assertNull(cache.imageFor(800, 600, 0.25, 24.0, 24.0, 10, 4.0));
        assertEquals(0, cache.fullRebuildCount());
        assertEquals(0, cache.incrementalUpdateCount());
    }

    private static void assertImagesEqual(
            java.awt.image.BufferedImage expected,
            java.awt.image.BufferedImage actual,
            String message) {
        assertEquals(expected.getWidth(), actual.getWidth(), message);
        assertEquals(expected.getHeight(), actual.getHeight(), message);
        int[] expectedPixels = expected.getRGB(
                0, 0, expected.getWidth(), expected.getHeight(), null, 0, expected.getWidth());
        int[] actualPixels = actual.getRGB(
                0, 0, actual.getWidth(), actual.getHeight(), null, 0, actual.getWidth());
        assertArrayEquals(expectedPixels, actualPixels, message);
    }
}
