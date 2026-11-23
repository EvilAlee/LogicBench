package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.io.ComponentLibrary;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.WireSegment;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.synth.layout.SchematicLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentExpressionBoxLayoutMetricsTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();
    private static final long FOUR_FA_BASELINE_WIRE_LENGTH = 53_820L;
    private static final long FOUR_FA_BASELINE_AREA = 2_640_400L;
    private static final String FULL_ADDER_GATE_LAYOUT_SHA =
            "8a08c58d67b510e014c9d1aea4499b02035cec0de919e70af4aa9d1f994cd1da";
    private static final String MAJORITY_GATE_LAYOUT_SHA =
            "e24ce458e80c51c3eacc4ab034fd047b4e3d967c664891017aad72f5c9ca1370";

    private record Metrics(
            int width,
            int height,
            long area,
            long wireLength,
            int bends,
            int longestWire,
            int routingLanes,
            List<String> boxCoordinates) {
    }

    @Test
    void boxedAdderAndCounterLayoutsMeetCompactionTargetsAndPreserveGateOnlyLayouts(
            @TempDir Path directory) throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, directory);
        ComponentExpressionCompiler.Compilation adder = compile(library, fourBitAdder());
        ComponentExpressionCompiler.Compilation counter = compile(library, twoDffCounter());

        Metrics adderMetrics = measure(adder.schematic());
        Metrics counterMetrics = measure(counter.schematic());

        System.out.println("BOX_LAYOUT_AFTER 4FA " + adderMetrics);
        System.out.println("BOX_LAYOUT_AFTER 2DFF " + counterMetrics);

        assertEquals(4, adder.schematic().componentInstances().size());
        assertEquals(2, counter.schematic().componentInstances().size());
        assertEquals(9, adder.inputComponentIds().size());
        assertEquals(5, adder.outputComponentIds().size());
        assertEquals(1, counter.inputComponentIds().size(), "RESET input");
        assertEquals(1, counter.clockComponentIds().size(), "CLK input");

        assertTrue(adderMetrics.wireLength() <= FOUR_FA_BASELINE_WIRE_LENGTH * 0.40,
                () -> "4-FA wire length " + adderMetrics.wireLength()
                        + " exceeds 40% of " + FOUR_FA_BASELINE_WIRE_LENGTH);
        assertTrue(adderMetrics.longestWire() <= 3 * ComponentInstance.BOX_WIDTH,
                () -> "4-FA longest segment " + adderMetrics.longestWire()
                        + " exceeds three box widths");
        assertTrue(adderMetrics.area() < FOUR_FA_BASELINE_AREA,
                () -> "4-FA area " + adderMetrics.area()
                        + " is not smaller than baseline " + FOUR_FA_BASELINE_AREA);
        assertShortAdjacentCarryRoutes(adder.schematic(), 3);
        assertNoCollinearWireOverlaps(adder.schematic());
        assertNoCollinearWireOverlaps(counter.schematic());

        SchematicLayout gateLayout = new SchematicLayout(GATES);
        Schematic fullAdder = gateLayout.layout(ExpressionCompiler.compile("""
                Sum = A ^ B ^ Cin
                Carry = (A & B) | (Cin & (A ^ B))
                """).netlist()).schematic();
        Schematic majority = gateLayout.layout(ExpressionCompiler.compile(
                "F = (A & B) | (A & C) | (B & C)").netlist()).schematic();
        assertTrue(fullAdder.componentInstances().isEmpty());
        assertTrue(majority.componentInstances().isEmpty());
        assertEquals(FULL_ADDER_GATE_LAYOUT_SHA, gateOnlyFingerprint(fullAdder));
        assertEquals(MAJORITY_GATE_LAYOUT_SHA, gateOnlyFingerprint(majority));
    }

    @Test
    void supportsMixedGraphsBoxFanoutAndUnconnectedBoxOutputs(@TempDir Path directory)
            throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, directory);

        ComponentExpressionCompiler.Compilation mixed = compile(library, """
                U0 := HA(A, B)
                S = U0.S XOR C
                CARRY = U0.C
                """);
        assertEquals(1, mixed.schematic().componentInstances().size());
        assertOutputsForEveryInput(mixed, List.of("A", "B", "C"), List.of("S", "CARRY"),
                row -> {
                    int a = row & 1;
                    int b = (row >> 1) & 1;
                    int c = (row >> 2) & 1;
                    return List.of(a ^ b ^ c, a & b);
                });

        ComponentExpressionCompiler.Compilation fanout = compile(library, """
                U0 := FA(A, B, CIN)
                U1 := HA(U0.S, X)
                U2 := HA(U0.COUT, Y)
                S1 = U1.S
                C1 = U1.C
                S2 = U2.S
                C2 = U2.C
                """);
        assertEquals(3, fanout.schematic().componentInstances().size());
        assertOutputsForEveryInput(fanout,
                List.of("A", "B", "CIN", "X", "Y"), List.of("S1", "C1", "S2", "C2"),
                row -> {
                    int a = row & 1;
                    int b = (row >> 1) & 1;
                    int cin = (row >> 2) & 1;
                    int x = (row >> 3) & 1;
                    int y = (row >> 4) & 1;
                    int sum = a ^ b ^ cin;
                    int carry = Integer.bitCount(a + (b << 1) + (cin << 2)) >= 2 ? 1 : 0;
                    return List.of(sum ^ x, sum & x, carry ^ y, carry & y);
                });

        ComponentExpressionCompiler.Compilation unusedOutput = compile(library, """
                U0 := FA(A, B, CIN)
                S = U0.S
                """);
        assertEquals(1, unusedOutput.schematic().componentInstances().size());
        assertTrue(unusedOutput.schematic().pinPositions().stream()
                .anyMatch(pin -> pin.componentId().equals("U0") && pin.pinId().equals("COUT")),
                "the unused COUT pin must remain present on the box");
        assertOutputsForEveryInput(unusedOutput, List.of("A", "B", "CIN"), List.of("S"),
                row -> List.of((row & 1) ^ ((row >> 1) & 1) ^ ((row >> 2) & 1)));
    }

    @Test
    void eightFaRippleAdderGrowsLinearlyAndLayoutIsDeterministic(@TempDir Path directory)
            throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, directory);
        String source = rippleAdder(8);
        ComponentExpressionCompiler.Compilation first = compile(library, source);
        ComponentExpressionCompiler.Compilation second = compile(library, source);
        Metrics fourBit = measure(compile(library, fourBitAdder()).schematic());
        Metrics eightBit = measure(first.schematic());

        assertEquals(8, first.schematic().componentInstances().size());
        assertEquals(17, first.inputComponentIds().size());
        assertEquals(9, first.outputComponentIds().size());
        assertTrue(eightBit.width() <= fourBit.width() * 2 + 2 * ComponentInstance.BOX_WIDTH,
                () -> "8-FA width " + eightBit.width()
                        + " grew faster than a linear 4-FA baseline " + fourBit.width());
        assertEquals(schematicFingerprint(first.schematic()),
                schematicFingerprint(second.schematic()),
                "identical boxed expressions must produce identical layouts");
        assertNoCollinearWireOverlaps(first.schematic());
    }

    private static ComponentExpressionCompiler.Compilation compile(
            ComponentLibrary library, String source) {
        return ComponentExpressionCompiler.compile(source, GATES, library.all());
    }

    private static String fourBitAdder() {
        return rippleAdder(4);
    }

    private static String rippleAdder(int bits) {
        StringBuilder source = new StringBuilder();
        for (int bit = 0; bit < bits; bit++) {
            String carryIn = bit == 0 ? "CIN" : "U" + (bit - 1) + ".COUT";
            source.append("U").append(bit).append(" := FA(A").append(bit)
                    .append(", B").append(bit).append(", ").append(carryIn).append(")\n");
        }
        for (int bit = 0; bit < bits; bit++) {
            source.append("S").append(bit).append(" = U").append(bit).append(".S\n");
        }
        source.append("COUT = U").append(bits - 1).append(".COUT");
        return source.toString();
    }

    private static String twoDffCounter() {
        return """
                U0 := DFF((NOT RESET) AND NOT U0.Q, CLK)
                U1 := DFF((NOT RESET) AND (U0.Q XOR U1.Q), CLK)
                Q0 = U0.Q
                Q1 = U1.Q
                """;
    }

    @FunctionalInterface
    private interface ExpectedRow {
        List<Integer> values(int row);
    }

    private static void assertOutputsForEveryInput(
            ComponentExpressionCompiler.Compilation compilation,
            List<String> inputNames,
            List<String> outputNames,
            ExpectedRow expected) {
        assertEquals(inputNames.size(), compilation.inputComponentIds().size());
        assertEquals(outputNames.size(), compilation.outputComponentIds().size());
        Schematic schematic = compilation.schematic();
        for (int row = 0; row < (1 << inputNames.size()); row++) {
            for (int input = 0; input < inputNames.size(); input++) {
                schematic.setInputValue(compilation.inputComponentIds().get(inputNames.get(input)),
                        ((row >> input) & 1) == 1);
            }
            SchematicCompiler.SimulationResult actual =
                    SchematicCompiler.compileAndSettle(schematic);
            assertFalse(actual.oscillating(), "oscillation on row " + row);
            List<Integer> expectedValues = expected.values(row);
            for (int output = 0; output < outputNames.size(); output++) {
                Value value = actual.outputValues().get(
                        compilation.outputComponentIds().get(outputNames.get(output)));
                assertNotNull(value, "missing output " + outputNames.get(output));
                assertEquals(expectedValues.get(output) == 1 ? Value.ONE : Value.ZERO,
                        value, "row " + row + ", output " + outputNames.get(output));
            }
        }
    }

    private static void assertShortAdjacentCarryRoutes(Schematic schematic, int carryCount) {
        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        for (int bit = 0; bit < carryCount; bit++) {
            String sourceId = "U" + bit;
            String targetId = "U" + (bit + 1);
            Connectivity.Net carryNet = topology.nets().stream()
                    .filter(net -> hasPin(net, sourceId, "COUT")
                            && hasPin(net, targetId, "CIN"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "missing direct carry net " + sourceId + ".COUT -> " + targetId + ".CIN"));
            long length = carryNet.segments().stream()
                    .mapToLong(ComponentExpressionBoxLayoutMetricsTest::wireLength).sum();
            assertTrue(carryNet.segments().size() <= 3,
                    () -> "carry " + sourceId + " -> " + targetId + " used "
                            + carryNet.segments().size() + " segments");
            assertTrue(length <= 200,
                    () -> "carry " + sourceId + " -> " + targetId
                            + " route length " + length + " is not short");
        }
    }

    private static boolean hasPin(Connectivity.Net net, String componentId, String pinId) {
        return net.pins().stream().anyMatch(pin ->
                pin.componentId().equals(componentId) && pin.pinId().equals(pinId));
    }

    private static void assertNoCollinearWireOverlaps(Schematic schematic) {
        List<WireSegment> wires = new ArrayList<>(schematic.wires());
        for (int first = 0; first < wires.size(); first++) {
            WireSegment a = wires.get(first);
            for (int second = first + 1; second < wires.size(); second++) {
                WireSegment b = wires.get(second);
                if (a.isHorizontal() && b.isHorizontal() && a.y1() == b.y1()) {
                    int overlap = Math.min(Math.max(a.x1(), a.x2()), Math.max(b.x1(), b.x2()))
                            - Math.max(Math.min(a.x1(), a.x2()), Math.min(b.x1(), b.x2()));
                    assertTrue(overlap <= 0,
                            () -> "overlapping horizontal lanes: " + a + " and " + b);
                } else if (!a.isHorizontal() && !b.isHorizontal() && a.x1() == b.x1()) {
                    int overlap = Math.min(Math.max(a.y1(), a.y2()), Math.max(b.y1(), b.y2()))
                            - Math.max(Math.min(a.y1(), a.y2()), Math.min(b.y1(), b.y2()));
                    assertTrue(overlap <= 0,
                            () -> "overlapping vertical lanes: " + a + " and " + b);
                }
            }
        }
    }

    private static long wireLength(WireSegment wire) {
        return (long) Math.abs(wire.x2() - wire.x1()) + Math.abs(wire.y2() - wire.y1());
    }

    private static Metrics measure(Schematic schematic) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (PlacedComponent component : schematic.components()) {
            GateGeometry.Bounds local = GateGeometry.bounds(
                    schematic.definitionFor(component), component.facing());
            minX = Math.min(minX, component.x() + local.x());
            minY = Math.min(minY, component.y() + local.y());
            maxX = Math.max(maxX, component.x() + local.x() + local.width());
            maxY = Math.max(maxY, component.y() + local.y() + local.height());
        }
        for (ComponentInstance instance : schematic.componentInstances()) {
            GateGeometry.Bounds bounds =
                    instance.bounds(schematic.componentDefinitionFor(instance));
            minX = Math.min(minX, bounds.x());
            minY = Math.min(minY, bounds.y());
            maxX = Math.max(maxX, bounds.x() + bounds.width());
            maxY = Math.max(maxY, bounds.y() + bounds.height());
        }

        long totalWireLength = 0;
        int longestWire = 0;
        Set<Integer> lanes = new TreeSet<>();
        for (WireSegment wire : schematic.wires()) {
            int length = (int) wireLength(wire);
            totalWireLength += length;
            longestWire = Math.max(longestWire, length);
            if (wire.isHorizontal() && wire.y1() >= maxY) {
                lanes.add(wire.y1());
            }
        }

        Map<String, Set<Boolean>> endpointDirections = new java.util.TreeMap<>();
        for (WireSegment wire : schematic.wires()) {
            endpointDirections.computeIfAbsent(
                    wire.x1() + "," + wire.y1(), ignored -> new LinkedHashSet<>())
                    .add(wire.isHorizontal());
            endpointDirections.computeIfAbsent(
                    wire.x2() + "," + wire.y2(), ignored -> new LinkedHashSet<>())
                    .add(wire.isHorizontal());
        }
        int bends = (int) endpointDirections.values().stream()
                .filter(directions -> directions.size() == 2)
                .count();

        List<String> boxes = schematic.componentInstances().stream()
                .sorted(Comparator.comparing(ComponentInstance::id))
                .map(instance -> instance.id() + "=(" + instance.x() + "," + instance.y() + ")")
                .toList();
        int width = maxX - minX;
        int height = maxY - minY;
        return new Metrics(width, height, (long) width * height, totalWireLength,
                bends, longestWire, lanes.size(), boxes);
    }

    private static String gateOnlyFingerprint(Schematic schematic) {
        String snapshot = schematic.components() + "\n" + schematic.wires();
        return sha256(snapshot);
    }

    private static String schematicFingerprint(Schematic schematic) {
        String snapshot = schematic.components() + "\n"
                + schematic.componentInstances() + "\n" + schematic.wires();
        return sha256(snapshot);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
