package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.WireSegment;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.Facing;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasWiringTest {
    @Test
    void draggingFromAPinCreatesAnOrthogonalWireAndClickingSelectsAndDeletesIt() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        var input = canvas.schematic().add("input", 100, 100,
                io.github.evilalee.gatelab.model.Facing.EAST);
        canvas.schematic().add("output", 200, 140,
                io.github.evilalee.gatelab.model.Facing.EAST);

        Connectivity.PinPosition start = canvas.schematic().pinPositions().stream()
                .filter(pin -> pin.componentId().equals(input.id()))
                .findFirst().orElseThrow();
        Point startScreen = screen(canvas, start.x(), start.y());
        Point endScreen = screen(canvas, 200, 140);

        press(canvas, startScreen);
        drag(canvas, endScreen);
        release(canvas, endScreen);

        List<WireSegment> expected = List.of(
                new WireSegment(100, 100, 200, 100),
                new WireSegment(200, 100, 200, 140));
        assertEquals(expected, canvas.schematic().wires());

        Point middleOfFirstSegment = screen(canvas, 150, 100);
        press(canvas, middleOfFirstSegment);
        release(canvas, middleOfFirstSegment);
        assertEquals(List.of(expected.getFirst()), canvas.selectedWires());

        canvas.getActionMap().get("delete-selection").actionPerformed(
                new java.awt.event.ActionEvent(
                        canvas, java.awt.event.ActionEvent.ACTION_PERFORMED, "delete"));
        assertEquals(List.of(expected.get(1)), canvas.schematic().wires(),
                "deleting a selected wire leaves the other unselected segment intact");
        assertTrue(canvas.selectedWires().isEmpty());
    }

    @Test
    void pokeTogglesOnlyInputsAndIgnoresOtherComponents() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        var input = canvas.schematic().add("input", 100, 100, Facing.EAST);
        canvas.schematic().add("and", 200, 100, Facing.EAST);
        canvas.setPokeMode();

        Point inputCenter = screen(canvas, 90, 100);
        press(canvas, inputCenter);
        release(canvas, inputCenter);
        assertEquals(Value.ONE, canvas.simulationResult().inputValues().get(input.id()));

        Point gateCenter = screen(canvas, 185, 100);
        press(canvas, gateCenter);
        release(canvas, gateCenter);
        assertEquals(Value.ONE, canvas.simulationResult().inputValues().get(input.id()));

        press(canvas, inputCenter);
        release(canvas, inputCenter);
        assertEquals(Value.ZERO, canvas.simulationResult().inputValues().get(input.id()));
    }

    @Test
    void draggingASelectedWireMovesItByGridSnappedSelectionDelta() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        WireSegment original = new WireSegment(100, 100, 200, 100);
        canvas.schematic().addWire(original);
        Point start = screen(canvas, 150, 100);
        press(canvas, start);
        release(canvas, start);
        assertEquals(List.of(original), canvas.selectedWires());

        Point destination = screen(canvas, 160, 120);
        press(canvas, start);
        drag(canvas, destination);
        release(canvas, destination);

        assertEquals(List.of(new WireSegment(110, 120, 210, 120)),
                canvas.schematic().wires());
        assertEquals(canvas.schematic().wires(), canvas.selectedWires());
    }

    @Test
    void shortWireNextToAPinCanStillBeSelectedByClickingItsLine() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        canvas.schematic().add("input", 100, 100,
                io.github.evilalee.gatelab.model.Facing.EAST);
        WireSegment shortWire = new WireSegment(100, 100, 110, 100);
        canvas.schematic().addWire(shortWire);
        Point midpoint = screen(canvas, 105, 100);

        press(canvas, midpoint);
        release(canvas, midpoint);

        assertEquals(List.of(shortWire), canvas.selectedWires());
    }

    @Test
    void shiftClickTogglesWireSelection() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        WireSegment wire = new WireSegment(100, 100, 200, 100);
        canvas.schematic().addWire(wire);
        Point point = screen(canvas, 150, 100);

        press(canvas, point);
        release(canvas, point);
        assertEquals(List.of(wire), canvas.selectedWires());

        press(canvas, point, InputEvent.SHIFT_DOWN_MASK);
        release(canvas, point, InputEvent.SHIFT_DOWN_MASK);
        assertTrue(canvas.selectedWires().isEmpty());
    }

    private static Point screen(CanvasPanel canvas, int worldX, int worldY) {
        var point = canvas.viewTransform().worldToScreen(worldX, worldY);
        return new Point((int) Math.round(point.x), (int) Math.round(point.y));
    }

    private static void press(CanvasPanel canvas, Point point) {
        press(canvas, point, 0);
    }

    private static void press(CanvasPanel canvas, Point point, int modifiers) {
        MouseEvent event = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), modifiers | InputEvent.BUTTON1_DOWN_MASK,
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
        release(canvas, point, 0);
    }

    private static void release(CanvasPanel canvas, Point point, int modifiers) {
        MouseEvent event = new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), modifiers,
                point.x, point.y, 1, false, MouseEvent.BUTTON1);
        for (var listener : canvas.getMouseListeners()) {
            listener.mouseReleased(event);
        }
    }
}
