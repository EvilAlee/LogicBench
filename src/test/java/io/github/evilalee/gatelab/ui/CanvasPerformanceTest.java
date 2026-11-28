package io.github.evilalee.gatelab.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void panningAndZoomingDoNotRecomputeTheCircuit() {
        CanvasPanel canvas = new CanvasPanel();
        long recomputations = canvas.simulationRecomputeCount();

        canvas.viewTransform().panBy(17.0, -9.0);
        canvas.viewTransform().zoomAt(300.0, 250.0, 1.25);

        assertEquals(recomputations, canvas.simulationRecomputeCount());
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

    @Test
    void escapeReturnsToEditAndArrowActionSetsPreviewFacing() {
        CanvasPanel escapeCanvas = new CanvasPanel();
        escapeCanvas.setSize(900, 700);
        escapeCanvas.enterAddMode("and");
        escapeCanvas.getActionMap().get("return-to-edit").actionPerformed(
                new ActionEvent(escapeCanvas, ActionEvent.ACTION_PERFORMED, "escape"));
        click(escapeCanvas, 240, 220);
        assertTrue(escapeCanvas.schematic().components().isEmpty(),
                "click after Escape must not place an Add preview");

        CanvasPanel facingCanvas = new CanvasPanel();
        facingCanvas.setSize(900, 700);
        facingCanvas.enterAddMode("and");
        facingCanvas.getActionMap().get("face-south").actionPerformed(
                new ActionEvent(facingCanvas, ActionEvent.ACTION_PERFORMED, "down"));
        click(facingCanvas, 310, 270);
        assertEquals("and", facingCanvas.schematic().components().getFirst().typeId());
        assertEquals(io.github.evilalee.gatelab.model.Facing.SOUTH,
                facingCanvas.schematic().components().getFirst().facing());
    }

    @Test
    void arrowFacingKeysAreBoundInTheFocusedInputMap() {
        CanvasPanel canvas = new CanvasPanel();
        var focused = canvas.getInputMap(JComponent.WHEN_FOCUSED);
        var window = canvas.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);

        assertEquals("face-east", focused.get(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0)));
        assertEquals("face-south", focused.get(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)));
        assertEquals("face-west", focused.get(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0)));
        assertEquals("face-north", focused.get(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0)));

        assertNull(window.get(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0)));
        assertNull(window.get(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)));
        assertNull(window.get(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0)));
        assertNull(window.get(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0)));

        assertEquals("return-to-edit", window.get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)));
        assertEquals("delete-selection", window.get(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0)));
        assertEquals("backspace-delete-selection",
                window.get(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0)));
        assertEquals("pan-space-down",
                window.get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, false)));
        assertEquals("pan-space-up",
                window.get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, true)));
        assertEquals("toggle-pin-markers", window.get(KeyStroke.getKeyStroke(KeyEvent.VK_G, 0)));
    }

    @Test
    void treeDropPlacementStillSnapsToGrid() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);

        // The canvas TransferHandler delegates incoming tree drops to this method.
        canvas.placeFromDrop("nand", new Point(147, 153));

        var placed = canvas.schematic().components().getFirst();
        assertEquals("nand", placed.typeId());
        assertEquals(120, placed.x());
        assertEquals(130, placed.y());
        assertEquals(io.github.evilalee.gatelab.model.Facing.EAST, placed.facing());
    }

    private static void click(CanvasPanel canvas, int x, int y) {
        long now = System.currentTimeMillis();
        MouseEvent pressed = new MouseEvent(
                canvas, MouseEvent.MOUSE_PRESSED, now,
                InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1);
        MouseEvent released = new MouseEvent(
                canvas, MouseEvent.MOUSE_RELEASED, now + 1,
                0, x, y, 1, false, MouseEvent.BUTTON1);
        for (var listener : canvas.getMouseListeners()) {
            listener.mousePressed(pressed);
        }
        for (var listener : canvas.getMouseListeners()) {
            listener.mouseReleased(released);
        }
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
