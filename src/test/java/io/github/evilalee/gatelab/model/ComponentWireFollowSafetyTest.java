package io.github.evilalee.gatelab.model;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    private static final List<GateDef> VARIANT_DEFINITIONS = loadVariantDefinitions();

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
        PlacedComponent input = schematic.add("input", 0, -10, Facing.EAST);
        PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);
        schematic.addWire(0, -10, 70, -10);

        // Occupy every grid point that any of the bounded L/Z candidates can use.
        // Do not occupy the original source-net stub, existing pins, or new pins.
        Set<Connectivity.Point> exempt = Set.of(
                new Connectivity.Point(70, -10),
                new Connectivity.Point(70, 10),
                new Connectivity.Point(100, 0),
                new Connectivity.Point(110, 10),
                new Connectivity.Point(110, 30));
        for (int x = 50; x <= 130; x += 10) {
            for (int y = -50; y <= 50; y += 10) {
                Connectivity.Point point = new Connectivity.Point(x, y);
                boolean onOriginalSourceStub = y == -10 && x <= 70;
                if (!exempt.contains(point) && !onOriginalSourceStub) {
                    schematic.add("output", point.x(), point.y(), Facing.EAST);
                }
            }
        }

        List<WireSegment> beforeWires = schematic.wires();
        Map<PinKey, Integer> beforeNets = netIds(schematic);
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
                new PinKey(input.id(), "out"), new PinKey(gate.id(), "in0")),
                "the fallback leaves the original wire behind rather than connecting via an unsafe route");
    }

    @Test
    void movingSelectedWireDoesNotTriggerAutoFollowWarningOrGenerateExtraLinks() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent input = schematic.add("input", 0, 0, Facing.EAST);
        PlacedComponent output = schematic.add("output", 100, 0, Facing.EAST);
        WireSegment selected = new WireSegment(0, 0, 100, 0);
        schematic.addWire(selected);
        List<WireSegment> before = schematic.wires();

        Schematic.MoveResult result = schematic.moveSelection(
                Set.of(), Set.of(selected), 0, 20);

        assertTrue(result.linksFollowed(),
                "moving selected geometry is intentional, not a failed auto-follow route");
        assertEquals(List.of(new WireSegment(0, 20, 100, 20)), schematic.wires());
        assertEquals(List.of(new WireSegment(0, 20, 100, 20)), result.movedWires());
        assertNotEquals(before, schematic.wires());
        assertFalse(isConnected(schematic,
                new PinKey(input.id(), "out"), new PinKey(output.id(), "in0")));
    }

    @Test
    void movingGateAndItsSelectedInputWireDoesNotGenerateAnAutoFollowLink() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);
        Connectivity.PinPosition inputPin = schematic.pinPositions().stream()
                .filter(pin -> pin.componentId().equals(gate.id())
                        && pin.pinId().equals("in0"))
                .findFirst().orElseThrow();
        WireSegment selected = new WireSegment(
                inputPin.x() - 40, inputPin.y(), inputPin.x(), inputPin.y());
        schematic.addWire(selected);
        int wireCountBefore = schematic.wires().size();

        Schematic.MoveResult result = schematic.moveSelection(
                Set.of(gate.id()), Set.of(selected), 20, 10);

        assertTrue(result.linksFollowed());
        assertEquals(wireCountBefore, schematic.wires().size(),
                "a pin attached only to a moving wire does not get a stationary auto-link");
        assertEquals(List.of(new WireSegment(
                selected.x1() + 20, selected.y1() + 10,
                selected.x2() + 20, selected.y2() + 10)), schematic.wires());
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

                String gateId = circuit.gates()
                        .get(random.nextInt(circuit.gates().size())).id();
                PlacedComponent gate = schematic.components().stream()
                        .filter(component -> component.id().equals(gateId))
                        .findFirst().orElseThrow();
                boolean linksFollowed;
                int operation = random.nextInt(3);
                if (operation == 0) {
                    int nextX = gate.x() + (random.nextInt(7) - 3) * 10;
                    int nextY = gate.y() + (random.nextInt(7) - 3) * 10;
                    if (nextX == gate.x() && nextY == gate.y()) {
                        nextX += 10;
                    }
                    Schematic.MoveResult result = schematic.moveSelection(
                            Set.of(gate.id()), Set.of(), nextX - gate.x(), nextY - gate.y());
                    linksFollowed = result.linksFollowed();
                } else if (operation == 1 || !gate.typeId().equals("and")) {
                    Facing facing = Facing.values()[random.nextInt(Facing.values().length)];
                    linksFollowed = schematic.setFacing(gate.id(), facing);
                } else {
                    String key = random.nextBoolean() ? "size" : "inputs";
                    List<String> options = schematic.attributeOptions(gate.typeId(), key);
                    String value = options.get(random.nextInt(options.size()));
                    try {
                        linksFollowed = schematic.setAttribute(gate.id(), key, value);
                    } catch (IllegalArgumentException unavailable) {
                        assertTrue(unavailable.getMessage().contains("Not available"),
                                unavailable.getMessage());
                        linksFollowed = true;
                    }
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

    private static List<GateDef> loadVariantDefinitions() {
        try (InputStream stream = ComponentWireFollowSafetyTest.class.getResourceAsStream(
                "/gates-variants.json")) {
            if (stream == null) {
                throw new IllegalStateException("missing test gate variant fixture");
            }
            return GateLibrary.fromJson(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8)).all();
        } catch (IOException exception) {
            throw new IllegalStateException("could not read gate variant fixture", exception);
        }
    }

    private static CircuitFixture connectedAndCircuit() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent inputA = schematic.add("input", 0, -10, Facing.EAST);
        PlacedComponent inputB = schematic.add("input", 0, 10, Facing.EAST);
        PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);
        PlacedComponent output = schematic.add("output", 180, 0, Facing.EAST);
        schematic.addWire(0, -10, 70, -10);
        schematic.addWire(0, 10, 70, 10);
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
        Schematic schematic = new Schematic(VARIANT_DEFINITIONS);
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
                Integer original = beforeNets.get(new PinKey(pin.componentId(), pin.pinId()));
                if (original != null) {
                    originalNets.add(original);
                }
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
            if (currentNet == null) {
                continue;
            }
            Integer previous = currentNetPerBeforeNet.putIfAbsent(entry.getValue(), currentNet);
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
