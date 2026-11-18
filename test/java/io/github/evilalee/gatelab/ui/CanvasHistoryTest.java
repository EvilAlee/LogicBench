package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.WireSegment;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasHistoryTest {
    @Test
    void palettePlacementCanBeUndoneRedoneAndNewDocumentClearsHistory() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        canvas.enterAddMode("input");
        Point target = screen(canvas, 100, 100);

        press(canvas, target);
        release(canvas, target);
        assertEquals(1, canvas.schematic().components().size());
        assertTrue(canvas.canUndo());

        assertTrue(canvas.undo());
        assertTrue(canvas.schematic().components().isEmpty());
        assertTrue(canvas.canRedo());
        assertTrue(canvas.redo());
        assertEquals(1, canvas.schematic().components().size());

        canvas.replaceSchematic(new Schematic(GateLibrary.load().all()));
        assertFalse(canvas.canUndo());
        assertFalse(canvas.canRedo());
    }

    @Test
    void completedTwoSegmentWireIsOneUndoRedoStep() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        PlacedComponent input = canvas.schematic().add("input", 100, 100, Facing.EAST);
        canvas.schematic().add("output", 200, 140, Facing.EAST);
        var start = canvas.schematic().pinPositions().stream()
                .filter(pin -> pin.componentId().equals(input.id()))
                .findFirst().orElseThrow();
        Point from = screen(canvas, start.x(), start.y());
        Point to = screen(canvas, 200, 140);

        press(canvas, from);
        drag(canvas, to);
        release(canvas, to);

        List<WireSegment> expected = List.of(
                new WireSegment(100, 100, 200, 100),
                new WireSegment(200, 100, 200, 140));
        assertEquals(expected, canvas.schematic().wires());
        assertTrue(canvas.undo());
        assertTrue(canvas.schematic().wires().isEmpty());
        assertTrue(canvas.redo());
        assertEquals(expected, canvas.schematic().wires());
    }

    @Test
    void movingFacingAndDeletingASelectedComponentAreIndividuallyUndoable() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        PlacedComponent component = canvas.schematic().add("and", 100, 100, Facing.EAST);
        var bounds = GateGeometry.bounds(
                GateLibrary.load().get("and"), Facing.EAST);
        Point center = screen(canvas,
                component.x() + bounds.x() + bounds.width() / 2,
                component.y() + bounds.y() + bounds.height() / 2);

        click(canvas, center);
        Point moved = screen(canvas,
                component.x() + bounds.x() + bounds.width() / 2 + 20,
                component.y() + bounds.y() + bounds.height() / 2 + 20);
        press(canvas, center);
        drag(canvas, moved);
        release(canvas, moved);
        assertEquals(120, canvas.schematic().components().getFirst().x());
        assertTrue(canvas.undo());
        assertEquals(100, canvas.schematic().components().getFirst().x());

        click(canvas, center);
        canvas.getActionMap().get("face-north").actionPerformed(
                new java.awt.event.ActionEvent(
                        canvas, java.awt.event.ActionEvent.ACTION_PERFORMED, "face-north"));
        assertEquals(Facing.NORTH, canvas.schematic().components().getFirst().facing());
        assertTrue(canvas.undo());
        assertEquals(Facing.EAST, canvas.schematic().components().getFirst().facing());

        click(canvas, center);
        canvas.getActionMap().get("delete-selection").actionPerformed(
                new java.awt.event.ActionEvent(
                        canvas, java.awt.event.ActionEvent.ACTION_PERFORMED, "delete"));
        assertTrue(canvas.schematic().components().isEmpty());
        assertTrue(canvas.undo());
        assertEquals(component.id(), canvas.schematic().components().getFirst().id());
    }

    private static Point screen(CanvasPanel canvas, int worldX, int worldY) {
        var point = canvas.viewTransform().worldToScreen(worldX, worldY);
        return new Point((int) Math.round(point.x), (int) Math.round(point.y));
    }

    private static void click(CanvasPanel canvas, Point point) {
        press(canvas, point);
        release(canvas, point);
    }

    private static void press(CanvasPanel canvas, Point point) {
        MouseEvent event = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK,
                point.x, point.y, 1, false, MouseEvent.BUTTON1);
        for (var listener : canvas.getMouseListeners()) {
            listener.mousePressed(event);
        }
    }

    private static void drag(CanvasPanel canvas, Point point) {
        MouseEvent event = new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED,
                System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK,
                point.x, point.y, 0, false, MouseEvent.NOBUTTON);
        for (MouseMotionListener listener : canvas.getMouseMotionListeners()) {
            listener.mouseDragged(event);
        }
    }

    private static void release(CanvasPanel canvas, Point point) {
        MouseEvent event = new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), 0,
                point.x, point.y, 1, false, MouseEvent.BUTTON1);
        for (var listener : canvas.getMouseListeners()) {
            listener.mouseReleased(event);
        }
    }
}
