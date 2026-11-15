package io.github.evilalee.gatelab.ui;

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasPerformanceTest {
    @Test
    void cachedGridIsNotRebuiltForPreviewMotion() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        paint(canvas);
        assertEquals(1L, canvas.gridCacheRebuildCount());

        canvas.enterAddMode("and");
        for (int index = 0; index < 12; index++) {
            Point point = new Point(100 + index * 17, 120 + index * 11);
            MouseEvent event = new MouseEvent(
                    canvas, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(),
                    0, point.x, point.y, 0, false);
            for (MouseMotionListener listener : canvas.getMouseMotionListeners()) {
                listener.mouseMoved(event);
            }
            paint(canvas);
        }

        assertEquals(1L, canvas.gridCacheRebuildCount(),
                "preview movement must reuse the grid image");
    }

    @Test
    void cachedGridRebuildsWhenTransformOrCanvasSizeChanges() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        paint(canvas);
        assertEquals(1L, canvas.gridCacheRebuildCount());

        canvas.viewTransform().panBy(4.0, -3.0);
        paint(canvas);
        assertEquals(2L, canvas.gridCacheRebuildCount(),
                "translation change must rebuild the grid");

        canvas.viewTransform().zoomAt(300.0, 250.0, 1.1);
        paint(canvas);
        assertEquals(3L, canvas.gridCacheRebuildCount(),
                "scale/translation change must rebuild the grid");

        canvas.setSize(920, 700);
        paint(canvas);
        assertEquals(4L, canvas.gridCacheRebuildCount(),
                "canvas resize must rebuild the grid");
    }

    @Test
    void fractionalControlWheelRotationStillZoomsPrecisely() {
        CanvasPanel canvas = new CanvasPanel();
        double originalScale = canvas.viewTransform().scale();
        MouseWheelEvent event = new MouseWheelEvent(
                canvas, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(),
                InputEvent.CTRL_DOWN_MASK, 120, 100, 120, 100, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 0, 0.5);

        for (MouseWheelListener listener : canvas.getMouseWheelListeners()) {
            listener.mouseWheelMoved(event);
        }

        assertTrue(canvas.viewTransform().scale() < originalScale);
        assertEquals(originalScale * Math.pow(1.12, -0.5),
                canvas.viewTransform().scale(), 1.0e-12);
    }

    private static void paint(CanvasPanel canvas) {
        BufferedImage target = new BufferedImage(
                canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        try {
            canvas.paint(graphics);
        } finally {
            graphics.dispose();
        }
    }
}
