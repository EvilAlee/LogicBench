package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.WireSegment;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CanvasPanelInstanceSelectionTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();

    private record Fixture(
            CanvasPanel canvas,
            ComponentDefinition definition,
            ComponentInstance instance,
            String sourceId,
            String probeId) {
    }

    @Test
    void bodyClickSelectsInstanceOutsideClickClearsAndPinClickStartsWire() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        ComponentDefinition definition = passThroughDefinition();
        ComponentInstance instance = canvas.schematic().addInstance(
                definition, "U1", 100, 100, Facing.EAST);
        ComponentInstance.PinGeometry inputPin = instance.geometry(definition).pins().stream()
                .filter(pin -> pin.name().equals("A")).findFirst().orElseThrow();

        click(canvas, screen(canvas, 150, 120));
        assertEquals(List.of(instance.id()), canvas.selectedInstanceIds());

        click(canvas, screen(canvas, 211, 120));
        assertTrue(canvas.selectedInstanceIds().isEmpty());

        click(canvas, screen(canvas, inputPin.x(), inputPin.y()));
        assertTrue(canvas.selectedInstanceIds().isEmpty(),
                "a pin hit starts a wire instead of selecting the box body");
        assertTrue(canvas.schematic().wires().isEmpty());
    }

    @Test
    void marqueeSelectsGatesInstancesAndWiresAndModifiersToggleInstanceSelection() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        PlacedComponent gate = canvas.schematic().add("and", 100, 100, Facing.EAST);
        ComponentDefinition definition = passThroughDefinition();
        ComponentInstance instance = canvas.schematic().addInstance(
                definition, "U1", 300, 300, Facing.EAST);
        WireSegment wire = new WireSegment(200, 220, 260, 220);
        canvas.schematic().addWire(wire);
        List<PlacedComponent> gateSelection = new ArrayList<>();
        canvas.setSelectionListener(selected -> {
            gateSelection.clear();
            gateSelection.addAll(selected);
        });

        Point start = screen(canvas, 50, 50);
        Point end = screen(canvas, 500, 500);
        press(canvas, start, 0);
        drag(canvas, end);
        release(canvas, end, 0);

        assertEquals(List.of(instance.id()), canvas.selectedInstanceIds());
        assertTrue(gateSelection.stream().anyMatch(component -> component.id().equals(gate.id())));
        assertEquals(List.of(wire), canvas.selectedWires());

        click(canvas, screen(canvas, 350, 320), InputEvent.CTRL_DOWN_MASK);
        assertTrue(canvas.selectedInstanceIds().isEmpty(),
                "Ctrl-click toggles an already selected instance off");
        assertTrue(gateSelection.stream().anyMatch(component -> component.id().equals(gate.id())));

        click(canvas, screen(canvas, 350, 320), InputEvent.SHIFT_DOWN_MASK);
        assertEquals(List.of(instance.id()), canvas.selectedInstanceIds(),
                "Shift-click adds the instance back to the mixed selection");
    }

    @Test
    void draggingRotatingAndDeletingInstanceUsesUndoableGesturesAndKeepsSimulation() {
        Fixture fixture = connectedFixture();
        CanvasPanel canvas = fixture.canvas();
        ComponentInstance instance = fixture.instance();
        canvas.schematic().setInputValue(fixture.sourceId(), true);
        assertOutputHigh(canvas, fixture.probeId());

        Point centre = screen(canvas, instance.x() + 50, instance.y() + 20);
        click(canvas, centre);
        assertEquals(List.of(instance.id()), canvas.selectedInstanceIds());
        Point moved = screen(canvas, instance.x() + 70, instance.y() + 40);
        press(canvas, centre, 0);
        drag(canvas, moved);
        release(canvas, moved, 0);

        ComponentInstance movedInstance = canvas.schematic().componentInstances().getFirst();
        assertEquals(instance.x() + 20, movedInstance.x());
        assertEquals(instance.y() + 20, movedInstance.y());
        assertOutputHigh(canvas, fixture.probeId());

        List<Connectivity.PinPosition> beforeRotation = movedInstance.pinPositions(
                fixture.definition());
        canvas.getActionMap().get("face-south").actionPerformed(
                new java.awt.event.ActionEvent(
                        canvas, java.awt.event.ActionEvent.ACTION_PERFORMED, "face-south"));
        ComponentInstance rotated = canvas.schematic().componentInstances().getFirst();
        assertEquals(Facing.SOUTH, rotated.rotation());
        assertNotEquals(beforeRotation, rotated.pinPositions(fixture.definition()));
        assertOutputHigh(canvas, fixture.probeId());

        assertTrue(canvas.undo());
        ComponentInstance restoredFacing = canvas.schematic().componentInstances().getFirst();
        assertEquals(Facing.EAST, restoredFacing.rotation());
        assertOutputHigh(canvas, fixture.probeId());

        // The next undo restores the moved component's original location and wire geometry.
        assertTrue(canvas.undo());
        ComponentInstance restoredMove = canvas.schematic().componentInstances().getFirst();
        assertEquals(instance.x(), restoredMove.x());
        assertEquals(instance.y(), restoredMove.y());
        assertOutputHigh(canvas, fixture.probeId());

        click(canvas, centre);
        canvas.getActionMap().get("delete-selection").actionPerformed(
                new java.awt.event.ActionEvent(
                        canvas, java.awt.event.ActionEvent.ACTION_PERFORMED, "delete"));
        assertTrue(canvas.schematic().componentInstances().isEmpty());
        assertTrue(canvas.schematic().wires().isEmpty());
        assertTrue(canvas.undo());
        assertEquals(List.of(instance), canvas.schematic().componentInstances());
        assertEquals(2, canvas.schematic().wires().size());
        assertOutputHigh(canvas, fixture.probeId());
        assertTrue(canvas.redo());
        assertTrue(canvas.schematic().componentInstances().isEmpty());
        assertTrue(canvas.schematic().wires().isEmpty());
    }

    private static Fixture connectedFixture() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        ComponentDefinition definition = passThroughDefinition();
        ComponentInstance instance = canvas.schematic().addInstance(
                definition, "U1", 500, 200, Facing.EAST);
        ComponentInstance.PinGeometry inputPin = instance.geometry(definition).pins().stream()
                .filter(pin -> pin.name().equals("A")).findFirst().orElseThrow();
        ComponentInstance.PinGeometry outputPin = instance.geometry(definition).pins().stream()
                .filter(pin -> pin.name().equals("Y")).findFirst().orElseThrow();

        int sourcePinX = inputPin.x() - 40;
        int probePinX = outputPin.x() + 40;
        addInputAt(canvas.schematic(), "source", sourcePinX, inputPin.y());
        addOutputAt(canvas.schematic(), "probe", probePinX, outputPin.y());
        canvas.schematic().addWire(new WireSegment(
                sourcePinX, inputPin.y(), inputPin.x(), inputPin.y()));
        canvas.schematic().addWire(new WireSegment(
                outputPin.x(), outputPin.y(), probePinX, outputPin.y()));
        return new Fixture(canvas, definition, instance, "source", "probe");
    }

    private static ComponentDefinition passThroughDefinition() {
        Schematic internal = new Schematic(GATES);
        internal.add(new PlacedComponent(
                "input-A", "input", 100, 100, Facing.EAST, Map.of(), "A"));
        Connectivity.PinPosition inputPin = pin(internal, "input-A", "out");
        GateGeometry.Offset outputOffset =
                GateGeometry.inputOffsets(gate("output"), Facing.EAST).getFirst();
        internal.add(new PlacedComponent(
                "output-Y", "output",
                400 - outputOffset.x(), inputPin.y() - outputOffset.y(),
                Facing.EAST, Map.of(), "Y"));
        Connectivity.PinPosition outputPin = pin(internal, "output-Y", "in0");
        internal.addWire(new WireSegment(
                inputPin.x(), inputPin.y(), outputPin.x(), outputPin.y()));
        return ComponentDefinition.fromCircuit(
                "CanvasSelectionPass", "Pass-through canvas selection test",
                "Test", "Y", internal);
    }

    private static void addInputAt(Schematic schematic, String id, int pinX, int pinY) {
        GateGeometry.Offset offset = GateGeometry.outputOffset(gate("input"), Facing.EAST);
        schematic.add(new PlacedComponent(
                id, "input", pinX - offset.x(), pinY - offset.y(),
                Facing.EAST, Map.of(), "SOURCE"));
    }

    private static void addOutputAt(Schematic schematic, String id, int pinX, int pinY) {
        GateGeometry.Offset offset =
                GateGeometry.inputOffsets(gate("output"), Facing.EAST).getFirst();
        schematic.add(new PlacedComponent(
                id, "output", pinX - offset.x(), pinY - offset.y(),
                Facing.EAST, Map.of(), "RESULT"));
    }

    private static Connectivity.PinPosition pin(
            Schematic schematic, String componentId, String pinId) {
        return schematic.pinPositions().stream()
                .filter(candidate -> candidate.componentId().equals(componentId)
                        && candidate.pinId().equals(pinId))
                .findFirst().orElseThrow();
    }

    private static GateDef gate(String family) {
        return GATES.stream().filter(definition -> definition.family().equals(family))
                .findFirst().orElseThrow();
    }

    private static void assertOutputHigh(CanvasPanel canvas, String outputId) {
        assertEquals(Value.ONE,
                canvas.simulationResult().outputValues().get(outputId));
    }

    private static Point screen(CanvasPanel canvas, int worldX, int worldY) {
        var point = canvas.viewTransform().worldToScreen(worldX, worldY);
        return new Point((int) Math.round(point.x), (int) Math.round(point.y));
    }

    private static void click(CanvasPanel canvas, Point point) {
        click(canvas, point, 0);
    }

    private static void click(CanvasPanel canvas, Point point, int modifiers) {
        press(canvas, point, modifiers);
        release(canvas, point, modifiers);
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

    private static void release(CanvasPanel canvas, Point point, int modifiers) {
        MouseEvent event = new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), modifiers,
                point.x, point.y, 1, false, MouseEvent.BUTTON1);
        for (var listener : canvas.getMouseListeners()) {
            listener.mouseReleased(event);
        }
    }
}
