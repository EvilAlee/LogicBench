package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.io.ComponentLibrary;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.WireSegment;
import io.github.evilalee.gatelab.synth.layout.SchematicLayout;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComponentExpressionBoxLayoutMetricsTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();

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
    void recordsPreInstanceAwareLayoutMeasurements(@TempDir Path directory) throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, directory);
        ComponentExpressionCompiler.Compilation adder = ComponentExpressionCompiler.compile(
                fourBitAdder(), GATES, library.all());
        ComponentExpressionCompiler.Compilation counter = ComponentExpressionCompiler.compile(
                twoDffCounter(), GATES, library.all());

        Metrics adderMetrics = measure(adder.schematic());
        Metrics counterMetrics = measure(counter.schematic());

        System.out.println("BOX_LAYOUT_BASELINE 4FA " + adderMetrics);
        System.out.println("BOX_LAYOUT_BASELINE 2DFF " + counterMetrics);

        SchematicLayout gateLayout = new SchematicLayout(GATES);
        Schematic fullAdder = gateLayout.layout(ExpressionCompiler.compile("""
                Sum = A ^ B ^ Cin
                Carry = (A & B) | (Cin & (A ^ B))
                """).netlist()).schematic();
        Schematic majority = gateLayout.layout(ExpressionCompiler.compile(
                "F = (A & B) | (A & C) | (B & C)").netlist()).schematic();
        System.out.println("NO_BOX_LAYOUT_HASH fullAdder=" + fingerprint(fullAdder));
        System.out.println("NO_BOX_LAYOUT_HASH majority=" + fingerprint(majority));
        assertEquals(4, adder.schematic().componentInstances().size());
        assertEquals(2, counter.schematic().componentInstances().size());
        assertEquals(9, adder.inputComponentIds().size());
        assertEquals(5, adder.outputComponentIds().size());
        assertEquals(1, counter.inputComponentIds().size(), "RESET input");
        assertEquals(1, counter.clockComponentIds().size(), "CLK input");
    }

    private static String fingerprint(Schematic schematic) {
        String snapshot = schematic.components() + "\n" + schematic.wires();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(snapshot.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String fourBitAdder() {
        return """
                U0 := FA(A0, B0, CIN)
                U1 := FA(A1, B1, U0.COUT)
                U2 := FA(A2, B2, U1.COUT)
                U3 := FA(A3, B3, U2.COUT)
                S0 = U0.S
                S1 = U1.S
                S2 = U2.S
                S3 = U3.S
                COUT = U3.COUT
                """;
    }

    private static String twoDffCounter() {
        return """
                U0 := DFF((NOT RESET) AND NOT U0.Q, CLK)
                U1 := DFF((NOT RESET) AND (U0.Q XOR U1.Q), CLK)
                Q0 = U0.Q
                Q1 = U1.Q
                """;
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
            int length = Math.abs(wire.x2() - wire.x1())
                    + Math.abs(wire.y2() - wire.y1());
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
        return new Metrics(
                width, height, (long) width * height, totalWireLength,
                bends, longestWire, lanes.size(), boxes);
    }
}
