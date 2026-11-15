package io.github.evilalee.gatelab.model;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentWireLinkTest {
    private static final List<GateDef> DEFINITIONS = GateLibrary.load().all();

    @Test
    void movingWiredAndGatePreservesInputAndOutputConnectivityForHorizontalVerticalAndDiagonalMoves() {
        for (int[] delta : List.of(new int[]{10, 0}, new int[]{0, 10}, new int[]{20, 10})) {
            CircuitFixture fixture = completeAndCircuit();
            Schematic schematic = fixture.schematic();
            long beforeModificationCount = schematic.modificationCount();

            schematic.moveSelection(Set.of(fixture.gate().id()), Set.of(), delta[0], delta[1]);

            assertEquals(beforeModificationCount + 1, schematic.modificationCount(),
                    "component movement and generated links are one model change");
            assertConnected(schematic,
                    pin(schematic, fixture.inputA(), "out"),
                    pin(schematic, fixture.gate(), "in0"));
            assertConnected(schematic,
                    pin(schematic, fixture.inputB(), "out"),
                    pin(schematic, fixture.gate(), "in1"));
            assertConnected(schematic,
                    pin(schematic, fixture.gate(), "out"),
                    pin(schematic, fixture.output(), "in0"));

            if (delta[0] == 20 && delta[1] == 10) {
                assertTrue(schematic.wires().contains(new WireSegment(70, -10, 90, -10)),
                        "the larger horizontal delta determines the first leg of the link");
                assertTrue(schematic.wires().contains(new WireSegment(90, -10, 90, 0)));
            }
            assertEquals(Value.ONE, SchematicCompiler.compileAndSettle(schematic)
                    .outputValues().get(fixture.output().id()),
                    "the moved gate must still simulate to the same output");
        }
    }

    @Test
    void horizontalMoveDetoursAroundAStationaryPinInsteadOfShortingItsNet() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent input = schematic.add("input", 0, -10, Facing.EAST);
        PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);
        PlacedComponent unrelatedInput = schematic.add("input", 80, -10, Facing.EAST);
        connect(schematic, input, "out", gate, "in0");

        schematic.move(gate.id(), 120, 0);

        Connectivity.PinPosition movedPin = pin(schematic, gate, "in0");
        assertConnected(schematic, pin(schematic, input, "out"), movedPin);
        assertNotConnected(schematic, movedPin, pin(schematic, unrelatedInput, "out"));
    }

    @Test
    void movingPinRoutesAroundAStationaryPinInsteadOfShortingItsNet() {
        CircuitFixture fixture = completeAndCircuit();
        Schematic schematic = fixture.schematic();
        PlacedComponent unrelatedInput = schematic.add("input", 90, -10, Facing.EAST);

        schematic.moveSelection(Set.of(fixture.gate().id()), Set.of(), 20, 10);

        Connectivity.PinPosition movedPin = pin(schematic, fixture.gate(), "in0");
        Connectivity.PinPosition unrelatedPin = pin(schematic, unrelatedInput, "out");
        assertConnected(schematic, pin(schematic, fixture.inputA(), "out"), movedPin);
        assertNotConnected(schematic, movedPin, unrelatedPin);
    }

    @Test
    void rotatingPinRoutesAroundStationaryPinAndOutputInsteadOfCreatingShorts() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent input = schematic.add("input", 0, -10, Facing.EAST);
        PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);
        PlacedComponent unrelatedInput = schematic.add("input", 120, -10, Facing.EAST);
        connect(schematic, input, "out", gate, "in0");

        schematic.setFacing(gate.id(), Facing.WEST);

        Connectivity.PinPosition rotatedPin = pin(schematic, gate, "in0");
        assertConnected(schematic, pin(schematic, input, "out"), rotatedPin);
        assertNotConnected(schematic, rotatedPin, pin(schematic, unrelatedInput, "out"));
        assertNotConnected(schematic, rotatedPin, pin(schematic, gate, "out"));
    }

    @Test
    void movingOntoTheSameStationaryWireDoesNotAddAnotherLink() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent input = schematic.add("input", 0, -10, Facing.EAST);
        PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);
        WireSegment trunk = new WireSegment(0, -10, 90, -10);
        schematic.addWire(trunk);

        schematic.moveSelection(Set.of(gate.id()), Set.of(), 20, 0);

        assertEquals(List.of(trunk), schematic.wires(),
                "the moved pin already lands on the existing net; no duplicate stud is needed");
        assertConnected(schematic, pin(schematic, input, "out"), pin(schematic, gate, "in0"));
    }

    @Test
    void oldPinCoincidentWithANonMovingComponentPinGetsALink() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);
        PlacedComponent input = schematic.add("input", 70, -10, Facing.EAST);

        schematic.move(gate.id(), 120, 0);

        assertEquals(List.of(new WireSegment(70, -10, 90, -10)), schematic.wires());
        assertConnected(schematic,
                pin(schematic, input, "out"),
                pin(schematic, gate, "in0"));
    }

    @Test
    void unattachedPinsDoNotCreateAnyLinks() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);

        schematic.move(gate.id(), 120, 0);

        assertTrue(schematic.wires().isEmpty());
    }

    @Test
    void selectedWireMovesWithTheComponentAndDoesNotGenerateASeparateLink() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);
        WireSegment selectedWire = new WireSegment(40, -10, 70, -10);
        schematic.addWire(selectedWire);

        List<WireSegment> moved = schematic.moveSelection(
                Set.of(gate.id()), Set.of(selectedWire), 20, 0);

        WireSegment translated = new WireSegment(60, -10, 90, -10);
        assertEquals(List.of(translated), moved);
        assertEquals(List.of(translated), schematic.wires(),
                "a selected wire itself is moved; it must not also cause a pin link");
    }

    @Test
    void facingChangeLinksMovedInputsButDoesNotLinkTheStationaryOutputPin() {
        CircuitFixture fixture = completeAndCircuit();
        Schematic schematic = fixture.schematic();
        Connectivity.PinPosition outputBefore = pin(schematic, fixture.gate(), "out");
        int wiresBefore = schematic.wires().size();

        schematic.setFacing(fixture.gate().id(), Facing.WEST);

        assertConnected(schematic,
                pin(schematic, fixture.inputA(), "out"),
                pin(schematic, fixture.gate(), "in0"));
        assertConnected(schematic,
                pin(schematic, fixture.inputB(), "out"),
                pin(schematic, fixture.gate(), "in1"));
        assertConnected(schematic,
                pin(schematic, fixture.gate(), "out"),
                pin(schematic, fixture.output(), "in0"));
        assertEquals(outputBefore, pin(schematic, fixture.gate(), "out"),
                "the output pin is the fixed origin");
        assertTrue(schematic.wires().size() > wiresBefore,
                "the moved inputs receive safe links; the fixed output pin adds none");
        assertNotConnected(schematic,
                pin(schematic, fixture.gate(), "in0"),
                pin(schematic, fixture.gate(), "in1"));
    }

    @Test
    void movingMultipleComponentsLinksEveryChangedPinOnEachMultiPinGate() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent first = schematic.add("and", 100, 0, Facing.EAST);
        PlacedComponent second = schematic.add("or", 300, 100, Facing.EAST);
        List<Connectivity.PinPosition> oldPins = new ArrayList<>();
        List<WireSegment> oldStubs = new ArrayList<>();
        for (PlacedComponent component : List.of(first, second)) {
            for (String pinId : List.of("in0", "in1", "out")) {
                Connectivity.PinPosition oldPin = pin(schematic, component, pinId);
                oldPins.add(oldPin);
                WireSegment stub = "in1".equals(pinId)
                        ? new WireSegment(oldPin.x(), oldPin.y(), oldPin.x(), oldPin.y() + 10)
                        : new WireSegment(oldPin.x(), oldPin.y() - 10, oldPin.x(), oldPin.y());
                oldStubs.add(stub);
                schematic.addWire(stub);
            }
        }
        int wiresBefore = schematic.wires().size();

        schematic.moveSelection(Set.of(first.id(), second.id()), Set.of(), 20, 0);

        assertEquals(wiresBefore + 6, schematic.wires().size(),
                "every attached, moved pin on both gates must receive one link");
        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        for (int index = 0; index < oldPins.size(); index++) {
            Connectivity.PinPosition oldPin = oldPins.get(index);
            Connectivity.PinPosition newPin = pin(schematic,
                    oldPin.componentId().equals(first.id()) ? first : second, oldPin.pinId());
            Connectivity.Net net = topology.nets().stream()
                    .filter(candidate -> candidate.pins().contains(newPin))
                    .findFirst().orElseThrow();
            assertTrue(net.segments().contains(oldStubs.get(index)),
                    "the old stationary stub remains connected to " + oldPin);
            assertTrue(net.segments().stream().anyMatch(segment ->
                            segment.x1() == oldPin.x() && segment.x2() == oldPin.x() + 20
                                    && segment.y1() == oldPin.y() && segment.y2() == oldPin.y()),
                    "the moved pin must have an orthogonal link from " + oldPin);
        }
    }

    @Test
    void facingChangeOnMultipleSelectedComponentsLinksEachComponentsMovedPins() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent first = schematic.add("and", 100, 0, Facing.EAST);
        PlacedComponent second = schematic.add("or", 300, 100, Facing.EAST);
        for (PlacedComponent component : List.of(first, second)) {
            for (String pinId : List.of("in0", "in1")) {
                Connectivity.PinPosition pin = pin(schematic, component, pinId);
                schematic.addWire(new WireSegment(pin.x(), pin.y() - 10, pin.x(), pin.y()));
            }
        }
        int wiresBefore = schematic.wires().size();

        schematic.setFacing(List.of(first.id(), second.id()), Facing.NORTH);

        assertTrue(schematic.wires().size() > wiresBefore,
                "all moved pins receive safe links, detouring where another route occupies a point");
        for (PlacedComponent component : List.of(first, second)) {
            assertNotConnected(schematic,
                    pin(schematic, component, "in0"),
                    pin(schematic, component, "in1"));
        }
        for (PlacedComponent component : List.of(first, second)) {
            for (String pinId : List.of("in0", "in1")) {
                Connectivity.PinPosition currentPin = pin(schematic, component, pinId);
                Connectivity.Net net = Connectivity.analyze(
                        schematic.pinPositions(), schematic.wires()).nets().stream()
                        .filter(candidate -> candidate.pins().contains(currentPin))
                        .findFirst().orElseThrow();
                assertTrue(net.segments().size() >= 2,
                        "rotated pin " + component.id() + "." + pinId + " stays linked to its stub");
            }
        }
    }

    @Test
    void repeatedMovesKeepBothInputNetsConnectedWhenOnePinIsSurroundedByItsOwnBranches() {
        CircuitFixture fixture = completeAndCircuit();
        Schematic schematic = fixture.schematic();
        Connectivity.PinPosition firstInputPin = pin(schematic, fixture.gate(), "in0");

        // This is one net: the old pin already has a branch in every direction.
        schematic.addWire(new WireSegment(firstInputPin.x(), firstInputPin.y() - 10,
                firstInputPin.x(), firstInputPin.y()));
        schematic.addWire(new WireSegment(firstInputPin.x(), firstInputPin.y(),
                firstInputPin.x() + 10, firstInputPin.y()));
        schematic.addWire(new WireSegment(firstInputPin.x(), firstInputPin.y(),
                firstInputPin.x(), firstInputPin.y() + 10));

        for (int[] position : List.of(
                new int[]{120, 0},
                new int[]{140, 0},
                new int[]{120, 0},
                new int[]{160, 0},
                new int[]{180, 0})) {
            schematic.move(fixture.gate().id(), position[0], position[1]);
            assertCircuitNetsRemainSeparate(fixture);
        }
    }

    @Test
    void repeatedRotationsKeepEachInputConnectedToItsOriginalNet() {
        CircuitFixture fixture = completeAndCircuit();
        Schematic schematic = fixture.schematic();
        Connectivity.PinPosition firstInputPin = pin(schematic, fixture.gate(), "in0");

        schematic.addWire(new WireSegment(firstInputPin.x(), firstInputPin.y() - 10,
                firstInputPin.x(), firstInputPin.y()));
        schematic.addWire(new WireSegment(firstInputPin.x(), firstInputPin.y(),
                firstInputPin.x() + 10, firstInputPin.y()));
        schematic.addWire(new WireSegment(firstInputPin.x(), firstInputPin.y(),
                firstInputPin.x(), firstInputPin.y() + 10));

        for (Facing facing : List.of(
                Facing.NORTH, Facing.WEST, Facing.SOUTH, Facing.EAST, Facing.NORTH)) {
            schematic.setFacing(fixture.gate().id(), facing);
            assertCircuitNetsRemainSeparate(fixture);
        }
    }

    private static void assertCircuitNetsRemainSeparate(CircuitFixture fixture) {
        Schematic schematic = fixture.schematic();
        assertConnected(schematic,
                pin(schematic, fixture.inputA(), "out"),
                pin(schematic, fixture.gate(), "in0"));
        assertConnected(schematic,
                pin(schematic, fixture.inputB(), "out"),
                pin(schematic, fixture.gate(), "in1"));
        assertConnected(schematic,
                pin(schematic, fixture.gate(), "out"),
                pin(schematic, fixture.output(), "in0"));
        assertNotConnected(schematic,
                pin(schematic, fixture.gate(), "in0"),
                pin(schematic, fixture.gate(), "in1"));
        assertEquals(schematic.wires().size(), Set.copyOf(schematic.wires()).size(),
                "repeated edits must not append exact duplicate segments");
        assertEquals(Value.ONE, SchematicCompiler.compileAndSettle(schematic)
                .outputValues().get(fixture.output().id()),
                "the circuit's output must remain stable after every move or rotation");
    }

    @Test
    void undoRestoresExactOriginalGeometryAndRedoReappliesAllGeneratedLinksInOneStep() {
        CircuitFixture fixture = completeAndCircuit();
        Schematic schematic = fixture.schematic();
        List<PlacedComponent> originalComponents = schematic.components();
        List<WireSegment> originalWires = schematic.wires();
        SchematicHistory history = new SchematicHistory();

        history.begin(schematic);
        schematic.moveSelection(Set.of(fixture.gate().id()), Set.of(), 20, 10);
        assertTrue(history.commit(schematic));
        List<PlacedComponent> movedComponents = schematic.components();
        List<WireSegment> movedWires = schematic.wires();
        assertTrue(movedWires.size() > originalWires.size(),
                "movement must have added links to the original geometry");

        assertTrue(history.undo(schematic));
        assertEquals(originalComponents, schematic.components());
        assertEquals(originalWires, schematic.wires(),
                "undo removes every generated link and restores the exact original wire list");

        assertTrue(history.redo(schematic));
        assertEquals(movedComponents, schematic.components());
        assertEquals(movedWires, schematic.wires(),
                "redo reapplies the moved components and all links exactly");
    }

    private static CircuitFixture completeAndCircuit() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent inputA = schematic.add("input", 0, -10, Facing.EAST);
        PlacedComponent inputB = schematic.add("input", 0, 10, Facing.EAST);
        PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);
        PlacedComponent output = schematic.add("output", 160, 0, Facing.EAST);
        connect(schematic, inputA, "out", gate, "in0");
        connect(schematic, inputB, "out", gate, "in1");
        connect(schematic, gate, "out", output, "in0");
        schematic.setInputValue(inputA.id(), true);
        schematic.setInputValue(inputB.id(), true);
        return new CircuitFixture(schematic, inputA, inputB, gate, output);
    }

    private static void connect(
            Schematic schematic,
            PlacedComponent first,
            String firstPin,
            PlacedComponent second,
            String secondPin) {
        Connectivity.PinPosition a = pin(schematic, first, firstPin);
        Connectivity.PinPosition b = pin(schematic, second, secondPin);
        if (a.x() == b.x() || a.y() == b.y()) {
            schematic.addWire(a.x(), a.y(), b.x(), b.y());
        } else {
            schematic.addWire(a.x(), a.y(), b.x(), a.y());
            schematic.addWire(b.x(), a.y(), b.x(), b.y());
        }
    }

    private static Connectivity.PinPosition pin(
            Schematic schematic, PlacedComponent component, String pinId) {
        return schematic.pinPositions().stream()
                .filter(pin -> pin.componentId().equals(component.id())
                        && pin.pinId().equals(pinId))
                .findFirst().orElseThrow();
    }

    private static void assertConnected(
            Schematic schematic,
            Connectivity.PinPosition first,
            Connectivity.PinPosition second) {
        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        assertTrue(topology.nets().stream().anyMatch(net ->
                        net.pins().contains(first) && net.pins().contains(second)),
                () -> first + " should remain connected to " + second);
    }

    private static void assertNotConnected(
            Schematic schematic,
            Connectivity.PinPosition first,
            Connectivity.PinPosition second) {
        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        assertFalse(topology.nets().stream().anyMatch(net ->
                        net.pins().contains(first) && net.pins().contains(second)),
                () -> first + " must not be shorted to " + second);
    }

    private record CircuitFixture(
            Schematic schematic,
            PlacedComponent inputA,
            PlacedComponent inputB,
            PlacedComponent gate,
            PlacedComponent output) {
    }
}
