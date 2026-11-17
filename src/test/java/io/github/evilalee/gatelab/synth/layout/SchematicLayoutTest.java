package io.github.evilalee.gatelab.synth.layout;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import io.github.evilalee.gatelab.synth.Netlist;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SchematicLayoutTest {
    private static final int LEGACY_ROW_GAP = 40;
    private static final int LEGACY_LAYER_GAP = 60;
    private static final int LEGACY_TRUNK_MARGIN = 20;
    private static final int LEGACY_TRUNK_PITCH = 20;

    private final SchematicLayout layout = new SchematicLayout(GateLibrary.load().all());

    @TestFactory
    List<DynamicTest> handWrittenExpressionsLayoutAndVerifyThroughPlacedSchematic() {
        List<String> examples = List.of(
                "F = A & B",
                "F = A | B",
                "F = A ^ B",
                "F = A XNOR B",
                "F = A NAND B",
                "F = A NOR B",
                "F = !(A & B)",
                "F = ~(A | (B & C))",
                "F = A' AND B",
                "F = A * B + C",
                "F = NOT (A XOR B) OR C",
                "F = (A & B) | (C & D)",
                "F = A",
                "F = 0",
                "F = 1",
                """
                Sum = A ^ B ^ Cin
                Carry = (A & B) | (Cin & (A ^ B))
                """,
                "F = A ^ B ^ C ^ D");
        return examples.stream().map(source -> DynamicTest.dynamicTest(source, () -> {
            ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile(source);
            SchematicLayout.LayoutResult placed = layout.layout(compilation.netlist());
            SchematicExpressionVerifier.VerificationResult verification =
                    SchematicExpressionVerifier.verify(compilation, placed);
            assertTrue(verification.verified(),
                    source + " mismatches=" + verification.mismatches());
            assertEquals(1 << compilation.netlist().inputs().size(), verification.rowCount());
            assertNoComponentOverlaps(placed.schematic());
            assertLabelsMatchSignalNames(compilation.netlist(), placed);
        })).toList();
    }

    @Test
    void longEdgeInExpressionCreatesOneDummyForEachSkippedLayer() {
        ExpressionCompiler.Compilation compilation =
                ExpressionCompiler.compile("F = A & (B & (C & D))");
        SchematicLayout.LayoutResult placed = layout.layout(compilation.netlist());

        assertTrue(placed.dummyNodeCount() >= 2,
                "the A-to-depth-3 edge must reserve one dummy in each skipped column");
        assertTrue(SchematicExpressionVerifier.verify(compilation, placed).verified());
    }

    @Test
    void oneInputNetFansOutToFourGateInputsOnOneGeometricNet() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile("""
                F1 = A & B
                F2 = A | C
                F3 = A ^ D
                F4 = A NAND E
                """);
        SchematicLayout.LayoutResult placed = layout.layout(compilation.netlist());
        String inputId = placed.inputComponentIds().get("A");
        Connectivity.Result topology = Connectivity.analyze(
                placed.schematic().pinPositions(), placed.schematic().wires());
        Connectivity.Net fanout = topology.nets().stream()
                .filter(net -> net.pins().stream().anyMatch(pin ->
                        pin.componentId().equals(inputId) && pin.pinId().equals("out")))
                .findFirst().orElseThrow();

        assertEquals(5, fanout.pins().size(), "input plus four gate sinks");
        assertTrue(SchematicExpressionVerifier.verify(compilation, placed).verified());
    }

    @Test
    void layoutIsDeterministicAndComponentBoundsNeverOverlap() {
        Netlist netlist = ExpressionCompiler.compile("""
                Sum = A ^ B ^ Cin
                Carry = (A & B) | (Cin & (A ^ B))
                F3 = A NAND D
                """).netlist();

        SchematicLayout.LayoutResult first = layout.layout(netlist);
        SchematicLayout.LayoutResult second = layout.layout(netlist);

        assertEquals(first.schematic().components(), second.schematic().components());
        assertEquals(first.schematic().wires(), second.schematic().wires());
        assertNoComponentOverlaps(first.schematic());
        assertNoComponentOverlaps(second.schematic());
    }

    @TestFactory
    List<DynamicTest> twoHundredFixedSeedRandomExpressionsVerifyAfterPlacement() {
        Random random = new Random(0x5EEDC0DEL);
        List<String> examples = new java.util.ArrayList<>();
        for (int index = 0; index < 200; index++) {
            examples.add("F = " + randomExpression(random, 4));
        }
        return java.util.stream.IntStream.range(0, examples.size())
                .mapToObj(index -> {
                    String source = examples.get(index);
                    return DynamicTest.dynamicTest("random " + index + ": " + source, () -> {
                        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile(source);
                        SchematicLayout.LayoutResult placed = layout.layout(compilation.netlist());
                        SchematicExpressionVerifier.VerificationResult verification =
                                SchematicExpressionVerifier.verify(compilation, placed);

                        assertTrue(verification.verified(),
                                source + " mismatches=" + verification.mismatches());
                        assertTrue(compilation.netlist().inputs().size() <= 5);
                        assertNoComponentOverlaps(placed.schematic());

                        LayoutMetrics actual = dimensions(placed.schematic());
                        LayoutMetrics legacy = legacyMetrics(compilation.netlist());
                        assertTrue(actual.height() <= legacy.height(),
                                "became taller: " + source
                                        + "; old=" + legacy + ", new=" + actual);
                    });
                }).toList();
    }

    @Test
    void atLeastOneHundredFiftyOfTwoHundredRandomLayoutsAreStrictlyShorter() {
        Random random = new Random(0x5EEDC0DEL);
        int strictlyShorter = 0;
        for (int index = 0; index < 200; index++) {
            String source = "F = " + randomExpression(random, 4);
            Netlist netlist = ExpressionCompiler.compile(source).netlist();
            LayoutMetrics actual = dimensions(layout.layout(netlist).schematic());
            if (actual.height() < legacyMetrics(netlist).height()) {
                strictlyShorter++;
            }
        }
        assertTrue(strictlyShorter >= 150,
                "only " + strictlyShorter + " of 200 random layouts became shorter");
    }

    @Test
    void fullAdderCompactsIntoIndependentColumnsAndKeepsDepthAlignment() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile("""
                Sum = A ^ B ^ Cin
                Cout = (A & B) | (Cin & (A ^ B))
                """);
        SchematicLayout.LayoutResult placed = layout.layout(compilation.netlist());
        LayoutMetrics actual = dimensions(placed.schematic());
        LayoutMetrics legacy = legacyMetrics(compilation.netlist());

        Map<String, Integer> depths = gateDepths(compilation.netlist());
        Map<Integer, Integer> xByDepth = new LinkedHashMap<>();
        for (Netlist.Gate gate : compilation.netlist().gates()) {
            PlacedComponent gateComponent = component(
                    placed.schematic(), "expr-gate-" + gate.id());
            int bodyX = bounds(placed.schematic(), gateComponent).x();
            Integer previousX = xByDepth.putIfAbsent(depths.get(gate.id()), bodyX);
            assertTrue(previousX == null || previousX == bodyX,
                    "gates at depth " + depths.get(gate.id()) + " must share a column");
        }

        assertTrue(actual.height() <= legacy.maxNodesInColumn() * 80 + 40,
                "height " + actual.height() + " exceeds compact-column bound for "
                        + legacy.maxNodesInColumn() + " nodes");
        assertTrue(actual.width() >= actual.height(),
                "full-adder width/height ratio must be at least 1: " + actual);
        assertTrue(actual.height() < legacy.height(),
                "full adder should be shorter than its old banded layout: old="
                        + legacy + ", new=" + actual);
        assertTrue(SchematicExpressionVerifier.verify(compilation, placed).verified());
    }

    @Test
    void fourVariableXorChainIsShorterThanTheOldBandedLayout() {
        ExpressionCompiler.Compilation compilation =
                ExpressionCompiler.compile("F = A ^ B ^ C ^ D");
        LayoutMetrics actual = dimensions(layout.layout(compilation.netlist()).schematic());
        LayoutMetrics legacy = legacyMetrics(compilation.netlist());

        assertTrue(actual.height() < legacy.height(),
                "four-variable XOR chain should be shorter: old=" + legacy + ", new=" + actual);
    }

    @Test
    void constantsUseLabeledInputComponentsAndKeepTheirInitialValues() {
        ExpressionCompiler.Compilation compilation =
                ExpressionCompiler.compile("Zero = 0\nOne = 1");
        SchematicLayout.LayoutResult placed = layout.layout(compilation.netlist());
        Schematic schematic = placed.schematic();

        assertEquals(Set.of("0", "1"), placed.constantComponentIds().keySet());
        for (Map.Entry<String, String> entry : placed.constantComponentIds().entrySet()) {
            PlacedComponent component = component(schematic, entry.getValue());
            assertEquals("input", component.typeId());
            assertEquals(entry.getKey(), component.label());
            assertEquals(entry.getKey().equals("1"), schematic.inputValue(component.id()));
        }
        assertTrue(SchematicExpressionVerifier.verify(compilation, placed).verified());
    }

    private record LayoutMetrics(int width, int height, int maxNodesInColumn) {
    }

    private static LayoutMetrics dimensions(Schematic schematic) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (PlacedComponent component : schematic.components()) {
            GateGeometry.Bounds bounds = bounds(schematic, component);
            minX = Math.min(minX, bounds.x());
            minY = Math.min(minY, bounds.y());
            maxX = Math.max(maxX, bounds.x() + bounds.width());
            maxY = Math.max(maxY, bounds.y() + bounds.height());
        }
        for (var wire : schematic.wires()) {
            minX = Math.min(minX, Math.min(wire.x1(), wire.x2()));
            minY = Math.min(minY, Math.min(wire.y1(), wire.y2()));
            maxX = Math.max(maxX, Math.max(wire.x1(), wire.x2()));
            maxY = Math.max(maxY, Math.max(wire.y1(), wire.y2()));
        }
        assertTrue(minX != Integer.MAX_VALUE && minY != Integer.MAX_VALUE,
                "generated schematic must have a measurable bounding box");
        return new LayoutMetrics(maxX - minX, maxY - minY, 0);
    }

    /**
     * Recreates only the previous coordinate dimensions from the former
     * global-band algorithm. This is a test fixture, not production code.
     */
    private static LayoutMetrics legacyMetrics(Netlist netlist) {
        Map<String, Integer> depths = new LinkedHashMap<>();
        netlist.inputs().forEach(input -> depths.put(input, 0));
        LinkedHashSet<String> constants = new LinkedHashSet<>();
        for (Netlist.Output output : netlist.outputs()) {
            if (output.source().equals("0") || output.source().equals("1")) {
                constants.add(output.source());
                depths.putIfAbsent(output.source(), 0);
            }
        }

        int lastGateDepth = 0;
        for (Netlist.Gate gate : netlist.topologicalGates()) {
            int depth = 1;
            for (String input : gate.inputs()) {
                depth = Math.max(depth, depths.get(input) + 1);
            }
            depths.put(gate.id(), depth);
            lastGateDepth = Math.max(lastGateDepth, depth);
        }
        int outputColumn = lastGateDepth + 1;
        int columnCount = outputColumn + 1;
        int[] counts = new int[columnCount];
        int[] heights = new int[columnCount];
        int[] widths = new int[columnCount];

        GateLibrary library = GateLibrary.load();
        GateDef inputDefinition = library.defaultDefinition("input");
        GateDef outputDefinition = library.defaultDefinition("output");
        for (String ignored : netlist.inputs()) {
            addLegacyNode(0, inputDefinition, counts, heights, widths);
        }
        for (String ignored : constants) {
            addLegacyNode(0, inputDefinition, counts, heights, widths);
        }
        for (Netlist.Gate gate : netlist.topologicalGates()) {
            String family = gate.type().name().toLowerCase(java.util.Locale.ROOT);
            addLegacyNode(depths.get(gate.id()),
                    library.defaultDefinition(family), counts, heights, widths);
        }
        for (Netlist.Output ignored : netlist.outputs()) {
            addLegacyNode(outputColumn, outputDefinition, counts, heights, widths);
        }

        Map<Integer, Set<String>> signalsByChannel = new HashMap<>();
        for (Netlist.Gate gate : netlist.topologicalGates()) {
            for (String signal : gate.inputs()) {
                addLegacyConnection(signal, depths.get(signal), depths.get(gate.id()),
                        counts, heights, signalsByChannel);
            }
        }
        for (Netlist.Output output : netlist.outputs()) {
            addLegacyConnection(output.source(), depths.get(output.source()), outputColumn,
                    counts, heights, signalsByChannel);
        }

        int height = 0;
        int maxNodes = 0;
        for (int column = 0; column < columnCount; column++) {
            int columnHeight = heights[column];
            if (counts[column] > 1) {
                columnHeight += LEGACY_ROW_GAP * (counts[column] - 1);
            }
            height += columnHeight;
            maxNodes = Math.max(maxNodes, counts[column]);
        }
        height += LEGACY_LAYER_GAP * (columnCount - 1);

        int width = 0;
        for (int column = 0; column < columnCount; column++) {
            width += widths[column];
            if (column < outputColumn) {
                int trunks = signalsByChannel.getOrDefault(column, Set.of()).size();
                width += LEGACY_TRUNK_MARGIN * 2 + trunks * LEGACY_TRUNK_PITCH;
            }
        }
        return new LayoutMetrics(width, height, maxNodes);
    }

    private static void addLegacyNode(
            int column, GateDef definition,
            int[] counts, int[] heights, int[] widths) {
        counts[column]++;
        heights[column] += GateGeometry.bounds(definition, Facing.EAST).height();
        widths[column] = Math.max(widths[column], definition.width());
    }

    private static void addLegacyConnection(
            String signal,
            int sourceColumn,
            int targetColumn,
            int[] counts,
            int[] heights,
            Map<Integer, Set<String>> signalsByChannel) {
        for (int column = sourceColumn + 1; column < targetColumn; column++) {
            counts[column]++;
            heights[column] += 20;
        }
        for (int channel = sourceColumn; channel < targetColumn; channel++) {
            signalsByChannel.computeIfAbsent(channel, ignored -> new LinkedHashSet<>())
                    .add(signal);
        }
    }

    private static Map<String, Integer> gateDepths(Netlist netlist) {
        Map<String, Integer> depths = new LinkedHashMap<>();
        netlist.inputs().forEach(input -> depths.put(input, 0));
        for (Netlist.Output output : netlist.outputs()) {
            if (output.source().equals("0") || output.source().equals("1")) {
                depths.putIfAbsent(output.source(), 0);
            }
        }
        for (Netlist.Gate gate : netlist.topologicalGates()) {
            int depth = 1;
            for (String input : gate.inputs()) {
                depth = Math.max(depth, depths.get(input) + 1);
            }
            depths.put(gate.id(), depth);
        }
        return depths;
    }

    private static void assertLabelsMatchSignalNames(
            Netlist netlist, SchematicLayout.LayoutResult layout) {
        for (String input : netlist.inputs()) {
            PlacedComponent component = component(layout.schematic(),
                    layout.inputComponentIds().get(input));
            assertEquals(input, component.label());
        }
        for (Netlist.Output output : netlist.outputs()) {
            PlacedComponent component = component(layout.schematic(),
                    layout.outputComponentIds().get(output.name()));
            assertEquals(output.name(), component.label());
        }
    }

    private static PlacedComponent component(Schematic schematic, String id) {
        return schematic.components().stream()
                .filter(value -> value.id().equals(id))
                .findFirst().orElseThrow();
    }

    private static void assertNoComponentOverlaps(Schematic schematic) {
        List<PlacedComponent> components = schematic.components();
        for (int first = 0; first < components.size(); first++) {
            GateGeometry.Bounds a = bounds(schematic, components.get(first));
            for (int second = first + 1; second < components.size(); second++) {
                GateGeometry.Bounds b = bounds(schematic, components.get(second));
                boolean overlap = a.x() < b.x() + b.width()
                        && b.x() < a.x() + a.width()
                        && a.y() < b.y() + b.height()
                        && b.y() < a.y() + a.height();
                assertFalse(overlap, components.get(first).id()
                        + " overlaps " + components.get(second).id());
            }
        }
    }

    private static GateGeometry.Bounds bounds(Schematic schematic, PlacedComponent component) {
        GateGeometry.Bounds local = GateGeometry.bounds(
                schematic.definitionFor(component), component.facing());
        return new GateGeometry.Bounds(
                component.x() + local.x(), component.y() + local.y(),
                local.width(), local.height());
    }

    private static String randomExpression(Random random, int depth) {
        if (depth == 0 || random.nextInt(5) == 0) {
            int leaf = random.nextInt(7);
            if (leaf == 5) {
                return "0";
            }
            if (leaf == 6) {
                return "1";
            }
            return Character.toString((char) ('A' + leaf));
        }

        return switch (random.nextInt(8)) {
            case 0 -> "!(" + randomExpression(random, depth - 1) + ")";
            case 1 -> "~(" + randomExpression(random, depth - 1) + ")";
            case 2 -> "(" + randomExpression(random, depth - 1) + ")'";
            default -> {
                String[] operators = {" & ", " | ", " ^ ", " NAND ",
                        " NOR ", " XNOR ", " AND ", " OR "};
                String left = randomExpression(random, depth - 1);
                String right = randomExpression(random, depth - 1);
                String operator = operators[random.nextInt(operators.length)];
                yield "(" + left + operator + right + ")";
            }
        };
    }
}
