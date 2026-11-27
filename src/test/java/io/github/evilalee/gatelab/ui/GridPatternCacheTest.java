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
    void cacheReusesTextureTileAcrossPanAndHidesDotsBelowFourPixels() {
        GridPatternCache cache = new GridPatternCache();

        var first = cache.paintFor(800, 600, 1.0, 24.0, 24.0, 10, 4.0);
        long tileBuilds = cache.tileBuildCount();
        var wholeGridPan = cache.paintFor(800, 600, 1.0, 34.0, 24.0, 10, 4.0);
        assertSame(first, wholeGridPan);
        assertEquals(tileBuilds, cache.tileBuildCount());

        var partialPan = cache.paintFor(800, 600, 1.0, 35.0, 24.0, 10, 4.0);
        assertNotSame(first, partialPan);
        assertEquals(tileBuilds, cache.tileBuildCount(),
                "changing only the phase changes the anchor, not the tiny tile");

        assertNull(cache.paintFor(800, 600, 0.25, 35.0, 24.0, 10, 4.0));
        assertEquals(tileBuilds, cache.tileBuildCount());
    }
}
