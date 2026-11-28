package io.github.evilalee.gatelab.model;

import io.github.evilalee.gatelab.io.GateLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MultiInputGateChangeTest {
    private static final List<GateDef> DEFINITIONS = GateLibrary.load().all();

    @Test
    void resizingKeepsSurvivingPinWorldCoordinatesForEveryFacing() {
        for (Facing facing : Facing.values()) {
            Schematic schematic = new Schematic(DEFINITIONS);
            PlacedComponent gate = schematic.add("and", 100, 100, facing,
                    java.util.Map.of("inputs", "4"));
            Connectivity.PinPosition first = pin(schematic, gate.id(), "in0");
            Connectivity.PinPosition second = pin(schematic, gate.id(), "in1");
            Connectivity.PinPosition output = pin(schematic, gate.id(), "out");
            int outputShift = schematic.gateDefinitions().stream()
                    .filter(definition -> definition.family().equals("and")
                            && definition.size().equals("narrow")
                            && definition.inputCount() == 2)
                    .findFirst().orElseThrow().output().y()
                    - schematic.definitionFor(gate).output().y();
            GateGeometry.Offset expectedShift = GateGeometry.rotate(
                    new GateGeometry.Offset(0, outputShift), facing);

            assertTrue(schematic.setAttribute(gate.id(), "inputs", "2"));

            assertEquals(first, pin(schematic, gate.id(), "in0"), facing + " in0");
            assertEquals(second, pin(schematic, gate.id(), "in1"), facing + " in1");
            Connectivity.PinPosition resizedOutput = pin(schematic, gate.id(), "out");
            assertEquals(output.x() + expectedShift.x(), resizedOutput.x(), facing + " out x");
            assertEquals(output.y() + expectedShift.y(), resizedOutput.y(), facing + " out y");
        }
    }

    @Test
    void reducingInputCountFollowsSurvivingPinsRemovesDeletedNetsAndIsOneUndoStep() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent gate = schematic.add("and", 100, 100, Facing.EAST,
                java.util.Map.of("inputs", "4"));
        PlacedComponent output = schematic.add("output", 200, 100, Facing.EAST);
        schematic.addWire(100, 100, 200, 100);
        for (int index = 0; index < 4; index++) {
            int y = 70 + index * 20;
            PlacedComponent source = new PlacedComponent(
                    "source-" + index, "input", 40, y, Facing.EAST);
            schematic.add(source);
            schematic.addWire(40, y, 70, y);
        }

        SchematicSnapshot before = SchematicSnapshot.capture(schematic);
        SchematicHistory history = new SchematicHistory();
        history.begin(schematic);
        assertTrue(schematic.setAttribute(gate.id(), "inputs", "2"));
        assertTrue(history.commit(schematic));

        List<Connectivity.PinPosition> pins = schematic.pinPositions();
        assertEquals(2, pins.stream().filter(pin -> pin.componentId().equals(gate.id())
                && pin.pinId().startsWith("in")).count());
        Connectivity.Result topology = Connectivity.analyze(pins, schematic.wires());
        for (int index = 0; index < 2; index++) {
            int retainedIndex = index;
            Connectivity.Net net = topology.nets().stream()
                    .filter(candidate -> candidate.pins().stream().anyMatch(pin ->
                            pin.componentId().equals("source-" + retainedIndex)))
                    .findFirst().orElseThrow();
            assertTrue(net.pins().stream().anyMatch(pin ->
                    pin.componentId().equals(gate.id())
                            && pin.pinId().equals("in" + retainedIndex)),
                    "surviving input " + index + " should retain its source");
        }
        Connectivity.Net outputNet = topology.nets().stream()
                .filter(candidate -> candidate.pins().stream().anyMatch(pin ->
                        pin.componentId().equals(gate.id()) && pin.pinId().equals("out")))
                .findFirst().orElseThrow();
        assertTrue(outputNet.pins().stream().anyMatch(pin ->
                pin.componentId().equals(output.id()) && pin.pinId().equals("in0")),
                "the output wire should follow the resized gate");

        for (int index = 2; index < 4; index++) {
            int removedIndex = index;
            assertTrue(topology.nets().stream().noneMatch(candidate ->
                    candidate.pins().stream().anyMatch(pin ->
                            pin.componentId().equals("source-" + removedIndex))
                            && candidate.pins().stream().anyMatch(pin ->
                                    pin.componentId().equals(gate.id()))),
                    "deleted input " + index + " must not keep a wire connection");
        }
        assertEquals(1, history.undoDepth());
        assertTrue(history.undo(schematic));
        assertEquals(before, SchematicSnapshot.capture(schematic));
    }

    private static Connectivity.PinPosition pin(
            Schematic schematic, String componentId, String pinId) {
        return schematic.pinPositions().stream()
                .filter(candidate -> candidate.componentId().equals(componentId)
                        && candidate.pinId().equals(pinId))
                .findFirst().orElseThrow();
    }
}
