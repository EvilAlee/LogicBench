package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasPanelNameAttributeTest {
    @Test
    void nameRowIsAvailableOnlyForInputOutputAndClockComponents() {
        for (String type : List.of("input", "output", "clock")) {
            CanvasPanel canvas = canvasWithSelected(type);
            assertTrue(canvas.attributePanelState().rows().stream()
                    .anyMatch(row -> row.key().equals("name") && row.label().equals("Name")),
                    type + " should expose a Name row");
        }

        CanvasPanel gateCanvas = canvasWithSelected("and");
        assertFalse(gateCanvas.attributePanelState().rows().stream()
                .anyMatch(row -> row.key().equals("name")));
    }

    @Test
    void nameChangesTrimClearAndUseOneUndoRedoStep() {
        CanvasPanel canvas = canvasWithSelected("input");
        PlacedComponent input = canvas.schematic().components().getFirst();

        assertTrue(canvas.setSelectedAttribute("name", "  DATA  "));
        assertEquals("DATA", inputById(canvas, input.id()).label());
        assertTrue(canvas.canUndo());

        assertTrue(canvas.undo());
        assertEquals("", inputById(canvas, input.id()).label());
        assertTrue(canvas.canRedo());

        assertTrue(canvas.redo());
        assertEquals("DATA", inputById(canvas, input.id()).label());

        selectComponent(canvas, input);
        assertTrue(canvas.setSelectedAttribute("name", "   "));
        assertEquals("", inputById(canvas, input.id()).label());
        assertTrue(canvas.undo());
        assertEquals("DATA", inputById(canvas, input.id()).label());
    }

    @Test
    void duplicateSignalNamesAreAllowedAndReportedInTheStatusBar() {
        CanvasPanel canvas = canvasWithSelected("input");
        List<String> messages = new ArrayList<>();
        canvas.setStatusMessageListener(messages::add);
        PlacedComponent first = canvas.schematic().components().getFirst();
        assertTrue(canvas.setSelectedAttribute("name", "Ready"));

        canvas.placeFromDrop("clock", new Point(240, 180));
        PlacedComponent clock = canvas.schematic().components().getLast();
        assertTrue(canvas.setSelectedAttribute("name", " Ready "));

        assertEquals("Ready", clockById(canvas, clock.id()).label());
        assertEquals("Ready", inputById(canvas, first.id()).label());
        assertEquals("Duplicate name: Ready", messages.getLast());
        assertTrue(canvas.canUndo());
        assertTrue(canvas.undo());
        assertEquals("", clockById(canvas, clock.id()).label());
        assertEquals("Ready", inputById(canvas, first.id()).label());
    }

    @Test
    void doubleClickingAnIoComponentRequestsNameEditorFocus() {
        CanvasPanel canvas = canvasWithSelected("output");
        PlacedComponent output = canvas.schematic().components().getFirst();
        List<Boolean> focusRequests = new ArrayList<>();
        canvas.setNameEditorFocusListener(() -> focusRequests.add(true));

        var bounds = io.github.evilalee.gatelab.model.GateGeometry.bounds(
                canvas.schematic().definitionFor(output), output.facing());
        var screen = canvas.viewTransform().worldToScreen(
                output.x() + bounds.x() + bounds.width() / 2.0,
                output.y() + bounds.y() + bounds.height() / 2.0);
        canvas.dispatchEvent(new java.awt.event.MouseEvent(
                canvas, java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, (int) Math.round(screen.x),
                (int) Math.round(screen.y), 2, false,
                java.awt.event.MouseEvent.BUTTON1));

        assertEquals(List.of(true), focusRequests);
    }

    private static CanvasPanel canvasWithSelected(String type) {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        canvas.placeFromDrop(type, new Point(100, 100));
        return canvas;
    }

    private static void selectComponent(CanvasPanel canvas, PlacedComponent component) {
        GateGeometry.Bounds bounds = GateGeometry.bounds(
                canvas.schematic().definitionFor(component), component.facing());
        Point2D.Double screen = canvas.viewTransform().worldToScreen(
                component.x() + bounds.x() + bounds.width() / 2.0,
                component.y() + bounds.y() + bounds.height() / 2.0);
        Point point = new Point((int) Math.round(screen.x), (int) Math.round(screen.y));
        MouseEvent pressed = new MouseEvent(
                canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK, point.x, point.y, 1, false, MouseEvent.BUTTON1);
        for (var listener : canvas.getMouseListeners()) {
            listener.mousePressed(pressed);
        }
        MouseEvent released = new MouseEvent(
                canvas, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                0, point.x, point.y, 1, false, MouseEvent.BUTTON1);
        for (var listener : canvas.getMouseListeners()) {
            listener.mouseReleased(released);
        }
    }

    private static PlacedComponent inputById(CanvasPanel canvas, String id) {
        return canvas.schematic().components().stream()
                .filter(component -> component.id().equals(id))
                .findFirst().orElseThrow();
    }

    private static PlacedComponent clockById(CanvasPanel canvas, String id) {
        return inputById(canvas, id);
    }
}
