package io.github.evilalee.gatelab.model;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ComponentWireFollowSafetyTest {
    private static final List<GateDef> DEFINITIONS = GateLibrary.load().all();

    @Test
    void movedAndGateKeepsBothInputNetsSeparateForOffsetsIncludingSmallVerticalMoves() {
        for (int[] delta : List.of(
                new int[]{10, 0},
                new int[]{20, 10},
                new int[]{0, 10},
                new int[]{0, -10},
                new int[]{30, 0})) {
            CircuitFixture fixture = connectedAndCircuit();
            Schematic schematic = fixture.schematic();
            List<PlacedComponent> beforeComponents = schematic.components();
            List<WireSegment> beforeWires = schematic.wires();
            Map<PinKey, Integer> beforeNets = netIds(schematic);

            Schematic.MoveResult result = schematic.moveSelection(
                    Set.of(fixture.gate().id()), Set.of(), delta[0], delta[1]);

            assertNoNewShorts(schematic, beforeNets);
            if (result.linksFollowed()) {
                assertBeforeConnectionsPreserved(schematic, beforeNets);
                assertNotEquals(beforeComponents, schematic.components());
            } else {
                assertEquals(beforeWires, schematic.wires(),
                        "a rejected/fallback move never leaves partial generated links");
                assertEquals(beforeComponents, schematic.components(),
                        "when the pin destinations themselves would short, reject the move");
            }
            assertAndTruthTable(fixture);
        }
    }

    @Test
    void fiveConsecutiveRotationsKeepInputNetsSeparateAndPreserveAndSimulation() {
        CircuitFixture fixture = connectedAndCircuit();
        Schematic schematic = fixture.schematic();
        for (Facing facing : List.of(
                Facing.NORTH, Facing.WEST, Facing.SOUTH, Facing.EAST, Facing.NORTH)) {
            Map<PinKey, Integer> beforeNets = netIds(schematic);
            List<WireSegment> beforeWires = schematic.wires();
            List<PlacedComponent> beforeComponents = schematic.components();

            boolean linksFollowed = schematic.setFacing(fixture.gate().id(), facing);

            assertNoNewShorts(schematic, beforeNets);
            if (linksFollowed) {
                assertBeforeConnectionsPreserved(schematic, beforeNets);
                assertNotEquals(beforeComponents, schematic.components());
            } else {
                assertEquals(beforeWires, schematic.wires(),
                        "fallback discards all links from this rotation");
            }
            assertInputNetsRemainDistinct(fixture);
            assertAndTruthTable(fixture);
        }
    }

    @Test
    void failedCandidateSearchFallsBackWithoutAddingAnyLinks() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent input = schematic.add("input", 0, 10, Facing.EAST);
        PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);
        schematic.addWire(0, 10, 100, 10);
        List<PlacedComponent> beforeComponents = schematic.components();
        List<WireSegment> beforeWires = schematic.wires();
        Map<PinKey, Integer> beforeNets = netIds(schematic);

        // Put stationary pins at every possible candidate-route grid point.
        // The original input wire and all moved gate-pin destinations are exempt.
        Set<Connectivity.Point> exempt = new HashSet<>();
        for (int x = 80; x <= 100; x += 10) {
            exempt.add(new Connectivity.Point(x, 10));
        }
        for (Connectivity.Point point : List.of(
                new Connectivity.Point(100, 10),
                new Connectivity.Point(140, 10),
                new Connectivity.Point(140, 20),
                new Connectivity.Point(140, 30))) {
            exempt.add(point);
        }
        for (int x = 80; x <= 160; x += 10) {
            for (int y = -20; y <= 70; y += 10) {
                Connectivity.Point point = new Connectivity.Point(x, y);
                if (!exempt.contains(point)) {
                    schematic.add("output", point.x(), point.y(), Facing.EAST);
                }
            }
        }
        beforeComponents = schematic.components();
        beforeWires = schematic.wires();
        beforeNets = netIds(schematic);

        Schematic.MoveResult result = schematic.moveSelection(
                Set.of(gate.id()), Set.of(), 40, 20);

        assertFalse(result.linksFollowed(), "all L/Z candidates should be blocked by unrelated nets");
        assertEquals(beforeWires, schematic.wires(),
                "failed routing must remove every tentative link");
        assertTrue(schematic.components().stream().anyMatch(component ->
                component.id().equals(gate.id()) && component.x() == 140 && component.y() == 20),
                "the safe no-link fallback still commits a move when the destination geometry is safe");
        assertNoNewShorts(schematic, beforeNets);
        assertFalse(isConnected(schematic,
                new PinKey(input.id(), "out"), new PinKey(gate.id(), "in1")),
                "the fallback leaves the original wire behind rather than connecting via an unsafe route");
    }

    @Test
    void twoHundredFuzzSeedsRemainShortFreeAndUndoRestoresEveryGestureExactly() {
        for (int seed = 0; seed < 200; seed++) {
            Random random = new Random(seed);
            RandomCircuit circuit = randomCircuit(random);
            Schematic schematic = circuit.schematic();
            SchematicHistory history = new SchematicHistory();

            for (int step = 0; step < 20; step++) {
                SchematicSnapshot before = SchematicSnapshot.capture(schematic);
                Map<PinKey, Integer> beforeNets = netIds(schematic);
                int previousUndoDepth = history.undoDepth();
                history.begin(schematic);

                PlacedComponent gate = circuit.gates().get(random.nextInt(circuit.gates().size()));
                boolean linksFollowed;
                if (random.nextBoolean()) {
                    PlacedComponent original = circuit.originalPositions().get(gate.id());
                    int nextX = original.x() + (random.nextInt(7) - 3) * 10;
                    int nextY = original.y() + (random.nextInt(7) - 3) * 10;
                    if (nextX == gate.x() && nextY == gate.y()) {
                        nextX += 10;
                    }
                    Schematic.MoveResult result = schematic.moveSelection(
                            Set.of(gate.id()), Set.of(), nextX - gate.x(), nextY - gate.y());
                    linksFollowed = result.linksFollowed();
                } else {
                    Facing facing = gate.facing();
                    while (facing == gate.facing()) {
                        facing = Facing.values()[random.nextInt(Facing.values().length)];
                    }
                    linksFollowed = schematic.setFacing(gate.id(), facing);
                }

                SchematicSnapshot after = SchematicSnapshot.capture(schematic);
                assertNoNewShorts(schematic, beforeNets,
                        "seed=" + seed + " step=" + step);
                if (linksFollowed) {
                    assertBeforeConnectionsPreserved(schematic, beforeNets,
                            "seed=" + seed + " step=" + step);
                } else {
                    assertEquals(before.wires(), schematic.wires(),
                            "fallback must not leave partial links; seed=" + seed + " step=" + step);
                }

                boolean changed = !before.equals(after);
                assertEquals(changed, history.commit(schematic),
                        "one gesture creates at most one history entry; seed=" + seed + " step=" + step);
                if (changed) {
                    assertEquals(previousUndoDepth + 1, history.undoDepth());
                    assertTrue(history.undo(schematic));
                    assertEquals(before, SchematicSnapshot.capture(schematic),
                            "undo restores exact positions and wire order; seed=" + seed + " step=" + step);
                    assertTrue(history.redo(schematic));
                    assertEquals(after, SchematicSnapshot.capture(schematic),
                            "redo restores exact committed gesture; seed=" + seed + " step=" + step);
                } else {
                    assertEquals(previousUndoDepth, history.undoDepth());
                }
            }
        }
    }

    private static CircuitFixture connectedAndCircuit() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent inputA = schematic.add("input", 0, -10, Facing.EAST);
        PlacedComponent inputB = schematic.add("input", 0, 10, Facing.EAST);
        PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);
        PlacedComponent output = schematic.add("output", 180, 0, Facing.EAST);
        schematic.addWire(0, -10, 100, -10);
        schematic.addWire(0, 10, 100, 10);
        schematic.addWire(100, 0, 100, -40);
        schematic.addWire(100, -40, 180, -40);
        schematic.addWire(180, -40, 180, 0);
        return new CircuitFixture(schematic, inputA, inputB, gate, output);
    }

    private static void assertAndTruthTable(CircuitFixture fixture) {
        Schematic schematic = fixture.schematic();
        for (boolean a : new boolean[]{false, true}) {
            for (boolean b : new boolean[]{false, true}) {
                schematic.setInputValue(fixture.inputA().id(), a);
                schematic.setInputValue(fixture.inputB().id(), b);
                Value expected = a && b ? Value.ONE : Value.ZERO;
                assertEquals(expected, SchematicCompiler.compileAndSettle(schematic)
                        .outputValues().get(fixture.output().id()));
            }
        }
    }

    private static void assertInputNetsRemainDistinct(CircuitFixture fixture) {
        assertFalse(isConnected(fixture.schematic(),
                new PinKey(fixture.inputA().id(), "out"),
                new PinKey(fixture.inputB().id(), "out")));
        assertFalse(isConnected(fixture.schematic(),
                new PinKey(fixture.gate().id(), "in0"),
                new PinKey(fixture.gate().id(), "in1")));
    }

    private static RandomCircuit randomCircuit(Random random) {
        Schematic schematic = new Schematic(DEFINITIONS);
        schematic.add("input", -100, -100, Facing.EAST);
        schematic.add("input", -100, 100, Facing.EAST);
        int count = 3 + random.nextInt(3);
        List<PlacedComponent> gates = new ArrayList<>();
        Map<String, PlacedComponent> originals = new HashMap<>();
        for (int index = 0; index < count; index++) {
            String type = List.of("and", "or", "xor").get(random.nextInt(3));
            int x = 160 + index * 220;
            int y = (random.nextInt(9) - 4) * 100;
            PlacedComponent gate = schematic.add(type, x, y, Facing.EAST);
            gates.add(gate);
            originals.put(gate.id(), gate);
        }
        schematic.add("output", 160 + count * 220, (random.nextInt(9) - 4) * 100, Facing.EAST);

        List<Connectivity.PinPosition> pins = schematic.pinPositions();
        int wireCount = 3 + random.nextInt(10);
        for (int index = 0; index < wireCount; index++) {
            Connectivity.PinPosition first = pins.get(random.nextInt(pins.size()));
            Connectivity.PinPosition second = pins.get(random.nextInt(pins.size()));
            if (first.equals(second) || (first.x() == second.x() && first.y() == second.y())) {
                continue;
            }
            connectRandomly(schematic, first, second, random.nextBoolean());
        }
        return new RandomCircuit(schematic, List.copyOf(gates), Map.copyOf(originals));
    }

    private static void connectRandomly(
            Schematic schematic,
            Connectivity.PinPosition first,
            Connectivity.PinPosition second,
            boolean horizontalFirst) {
        if (first.x() == second.x() || first.y() == second.y()) {
            schematic.addWire(first.x(), first.y(), second.x(), second.y());
        } else if (horizontalFirst) {
            schematic.addWire(first.x(), first.y(), second.x(), first.y());
            schematic.addWire(second.x(), first.y(), second.x(), second.y());
        } else {
            schematic.addWire(first.x(), first.y(), first.x(), second.y());
            schematic.addWire(first.x(), second.y(), second.x(), second.y());
        }
    }

    private static Map<PinKey, Integer> netIds(Schematic schematic) {
        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        Map<PinKey, Integer> ids = new HashMap<>();
        int index = 0;
        for (Connectivity.Net net : topology.nets()) {
            for (Connectivity.PinPosition pin : net.pins()) {
                ids.put(new PinKey(pin.componentId(), pin.pinId()), index);
            }
            index++;
        }
        return ids;
    }

    private static void assertNoNewShorts(Schematic schematic, Map<PinKey, Integer> beforeNets) {
        assertNoNewShorts(schematic, beforeNets, "");
    }

    private static void assertNoNewShorts(
            Schematic schematic, Map<PinKey, Integer> beforeNets, String message) {
        Connectivity.Result after = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        for (Connectivity.Net net : after.nets()) {
            Set<Integer> originalNets = new HashSet<>();
            for (Connectivity.PinPosition pin : net.pins()) {
                originalNets.add(beforeNets.get(new PinKey(pin.componentId(), pin.pinId())));
            }
            assertTrue(originalNets.size() <= 1,
                    "a net contains pins from different before nets; " + message + ": " + net);
        }
    }

    private static void assertBeforeConnectionsPreserved(
            Schematic schematic, Map<PinKey, Integer> beforeNets) {
        assertBeforeConnectionsPreserved(schematic, beforeNets, "");
    }

    private static void assertBeforeConnectionsPreserved(
            Schematic schematic, Map<PinKey, Integer> beforeNets, String message) {
        Map<PinKey, Integer> afterNets = netIds(schematic);
        Map<Integer, Integer> currentNetPerBeforeNet = new HashMap<>();
        for (Map.Entry<PinKey, Integer> entry : beforeNets.entrySet()) {
            Integer currentNet = afterNets.get(entry.getKey());
            Integer previous = currentNetPerBeforeNet.putIfAbsent(entry.getValue(), currentNet);
            assertNotNull(currentNet, message);
            assertTrue(previous == null || previous.equals(currentNet),
                    "previously connected pins were disconnected; " + message);
        }
    }

    private static boolean isConnected(Schematic schematic, PinKey first, PinKey second) {
        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        return topology.nets().stream().anyMatch(net ->
                net.pins().contains(new Connectivity.PinPosition(
                        first.componentId(), first.pinId(),
                        pin(schematic, first).x(), pin(schematic, first).y()))
                && net.pins().contains(new Connectivity.PinPosition(
                        second.componentId(), second.pinId(),
                        pin(schematic, second).x(), pin(schematic, second).y())));
    }

    private static Connectivity.PinPosition pin(Schematic schematic, PinKey key) {
        return schematic.pinPositions().stream()
                .filter(pin -> pin.componentId().equals(key.componentId())
                        && pin.pinId().equals(key.pinId()))
                .findFirst().orElseThrow();
    }

    private record PinKey(String componentId, String pinId) {
    }

    private record CircuitFixture(
            Schematic schematic,
            PlacedComponent inputA,
            PlacedComponent inputB,
            PlacedComponent gate,
            PlacedComponent output) {
    }

    private record RandomCircuit(
            Schematic schematic,
            List<PlacedComponent> gates,
            Map<String, PlacedComponent> originalPositions) {
    }
}
