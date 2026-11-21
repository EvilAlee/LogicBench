package io.github.evilalee.gatelab.model;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SchematicComponentInstanceSelectionTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();

    private record Fixture(
            Schematic schematic,
            ComponentDefinition definition,
            ComponentInstance instance,
            String sourceId,
            String probeId,
            List<WireSegment> originalWires) {
    }

    @Test
    void instanceHitAndMarqueeUseItsBodyBoundsAndRejectOutsidePoints() {
        Schematic schematic = new Schematic(GATES);
        ComponentDefinition definition = passThroughDefinition();
        ComponentInstance instance = schematic.addInstance(
                definition, "U1", 200, 300, Facing.EAST);
        GateGeometry.Bounds bounds = instance.bounds(definition);

        assertEquals(new GateGeometry.Bounds(200, 300, 100, 40), bounds);
        assertEquals(instance, schematic.componentInstanceAt(250, 320).orElseThrow());
        assertTrue(schematic.componentInstanceAt(301, 320).isEmpty());
        assertEquals(List.of(instance), schematic.componentInstancesIntersecting(
                new GateGeometry.Bounds(250, 310, 20, 20)));
        assertTrue(schematic.componentInstancesIntersecting(
                new GateGeometry.Bounds(310, 310, 20, 20)).isEmpty());
    }

    @Test
    void deletingInstanceRemovesAttachedNetsAndUndoRedoRestoresThemTogether() {
        Fixture fixture = connectedFixture();
        Schematic schematic = fixture.schematic();
        SchematicHistory history = new SchematicHistory();
        assertEquals(2, schematic.wires().size());

        history.begin(schematic);
        assertTrue(schematic.remove(fixture.instance().id()));
        assertTrue(history.commit(schematic));
        assertTrue(schematic.componentInstances().isEmpty());
        assertTrue(schematic.wires().isEmpty());

        assertTrue(history.undo(schematic));
        assertEquals(List.of(fixture.instance()), schematic.componentInstances());
        assertEquals(fixture.originalWires(), schematic.wires());

        assertTrue(history.redo(schematic));
        assertTrue(schematic.componentInstances().isEmpty());
        assertTrue(schematic.wires().isEmpty());
    }

    @Test
    void movingAndRotatingInstanceFollowsPinsAndKeepsFlattenedSimulationStable() {
        Fixture fixture = connectedFixture();
        Schematic schematic = fixture.schematic();
        schematic.setInputValue(fixture.sourceId(), true);
        assertOutputHigh(schematic, fixture.probeId());

        ComponentInstance beforeMove = schematic.componentInstances().getFirst();
        List<Connectivity.PinPosition> originalPins = beforeMove.pinPositions(
                fixture.definition());
        List<WireSegment> originalWires = schematic.wires();
        SchematicHistory history = new SchematicHistory();

        history.begin(schematic);
        Schematic.MoveResult move = schematic.moveSelection(
                List.of(beforeMove.id()), List.of(), 20, 20);
        assertTrue(move.linksFollowed());
        assertTrue(history.commit(schematic));
        ComponentInstance moved = schematic.componentInstances().getFirst();
        assertEquals(beforeMove.x() + 20, moved.x());
        assertEquals(beforeMove.y() + 20, moved.y());
        assertNotEquals(originalPins,
                moved.pinPositions(schematic.componentDefinitionFor(moved)));
        assertNotEquals(originalWires, schematic.wires());
        assertOutputHigh(schematic, fixture.probeId());

        history.begin(schematic);
        assertTrue(schematic.setFacing(List.of(moved.id()), Facing.SOUTH));
        assertTrue(history.commit(schematic));
        ComponentInstance rotated = schematic.componentInstances().getFirst();
        assertEquals(Facing.SOUTH, rotated.rotation());
        assertNotEquals(moved.pinPositions(fixture.definition()),
                rotated.pinPositions(fixture.definition()));
        assertOutputHigh(schematic, fixture.probeId());

        assertTrue(history.undo(schematic));
        ComponentInstance restored = schematic.componentInstances().getFirst();
        assertEquals(Facing.EAST, restored.rotation());
        assertEquals(moved.pinPositions(fixture.definition()),
                restored.pinPositions(fixture.definition()));
        assertOutputHigh(schematic, fixture.probeId());

        assertTrue(history.undo(schematic));
        ComponentInstance original = schematic.componentInstances().getFirst();
        assertEquals(beforeMove, original);
        assertEquals(originalWires, schematic.wires());
        assertOutputHigh(schematic, fixture.probeId());
    }

    private static Fixture connectedFixture() {
        ComponentDefinition definition = passThroughDefinition();
        Schematic schematic = new Schematic(GATES);
        ComponentInstance instance = schematic.addInstance(
                definition, "U1", 500, 200, Facing.EAST);
        ComponentInstance.PinGeometry inputPin = instance.geometry(definition).pins().stream()
                .filter(pin -> pin.name().equals("A")).findFirst().orElseThrow();
        ComponentInstance.PinGeometry outputPin = instance.geometry(definition).pins().stream()
                .filter(pin -> pin.name().equals("Y")).findFirst().orElseThrow();

        int sourcePinX = inputPin.x() - 40;
        int probePinX = outputPin.x() + 40;
        addInputAt(schematic, "source", sourcePinX, inputPin.y());
        addOutputAt(schematic, "probe", probePinX, outputPin.y());
        schematic.addWire(new WireSegment(
                sourcePinX, inputPin.y(), inputPin.x(), inputPin.y()));
        schematic.addWire(new WireSegment(
                outputPin.x(), outputPin.y(), probePinX, outputPin.y()));
        return new Fixture(schematic, definition, instance, "source", "probe",
                schematic.wires());
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
                "SelectablePass", "Pass-through selection test", "Test", "Y", internal);
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

    private static void assertOutputHigh(Schematic schematic, String outputId) {
        assertEquals(Value.ONE, SchematicCompiler.compileAndSettle(schematic)
                .outputValues().get(outputId));
    }
}
