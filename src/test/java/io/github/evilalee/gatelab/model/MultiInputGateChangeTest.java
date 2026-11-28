package io.github.evilalee.gatelab.model;

import io.github.evilalee.gatelab.io.GateLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MultiInputGateChangeTest {
    private static final List<GateDef> DEFINITIONS = GateLibrary.load().all();

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
                            && pin.pinId().equals("in" + index)),
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
}
