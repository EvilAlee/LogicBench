package io.github.evilalee.gatelab.ui;

import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ViewTransformTest {
    @Test
    void worldAndScreenCoordinatesRoundTrip() {
        ViewTransform transform = new ViewTransform(1.75, 120.0, -35.0);

        Point2D.Double screen = transform.worldToScreen(83.5, -19.25);
        Point2D.Double world = transform.screenToWorld(screen.x, screen.y);

        assertEquals(83.5, world.x, 1.0e-9);
        assertEquals(-19.25, world.y, 1.0e-9);
    }

    @Test
    void zoomKeepsTheWorldPointUnderTheCursorFixed() {
        ViewTransform transform = new ViewTransform(1.25, 48.0, -17.0);
        double cursorX = 413.0;
        double cursorY = 286.0;
        Point2D.Double before = transform.screenToWorld(cursorX, cursorY);

        transform.zoomAt(cursorX, cursorY, 1.8);

        Point2D.Double after = transform.screenToWorld(cursorX, cursorY);
        assertEquals(before.x, after.x, 1.0e-9);
        assertEquals(before.y, after.y, 1.0e-9);
        assertEquals(2.25, transform.scale(), 1.0e-9);
    }

    @Test
    void zoomIsClampedToTheSupportedRange() {
        ViewTransform transform = new ViewTransform();

        transform.zoomAt(0.0, 0.0, 100.0);
        assertEquals(ViewTransform.MAX_ZOOM, transform.scale());

        transform.zoomAt(0.0, 0.0, 0.001);
        assertEquals(ViewTransform.MIN_ZOOM, transform.scale());
    }

    @Test
    void rejectsInvalidZoomFactors() {
        ViewTransform transform = new ViewTransform();
        assertThrows(IllegalArgumentException.class, () -> transform.zoomAt(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> transform.zoomAt(0, 0, Double.NaN));
    }
}
