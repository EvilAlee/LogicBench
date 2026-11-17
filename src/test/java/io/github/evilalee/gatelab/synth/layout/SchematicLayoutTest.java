package io.github.evilalee.gatelab.synth.layout;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import io.github.evilalee.gatelab.synth.Netlist;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SchematicLayoutTest {
    private final SchematicLayout layout = new SchematicLayout(GateLibrary.load().all());

    @Test
    void handWrittenExpressionsLayoutAndVerifyThroughPlacedSchematic() {
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
        for (String source : examples) {
            ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile(source);
            SchematicLayout.LayoutResult placed = layout.layout(compilation.netlist());
            SchematicExpressionVerifier.VerificationResult verification =
                    SchematicExpressionVerifier.verify(compilation, placed);
            assertTrue(verification.verified(),
                    source + " mismatches=" + verification.mismatches());
            assertEquals(1 << compilation.netlist().inputs().size(), verification.rowCount());
            assertNoComponentOverlaps(placed.schematic());
            assertLabelsMatchSignalNames(compilation.netlist(), placed);
        }
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

    @Test
    void twoHundredFixedSeedRandomExpressionsVerifyAfterPlacement() {
        Random random = new Random(0x5EEDC0DEL);
        for (int index = 0; index < 200; index++) {
            String source = "F = " + randomExpression(random, 4);
            ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile(source);
            SchematicLayout.LayoutResult placed = layout.layout(compilation.netlist());
            SchematicExpressionVerifier.VerificationResult verification =
                    SchematicExpressionVerifier.verify(compilation, placed);

            assertTrue(verification.verified(),
                    "random expression " + index + ": " + source
                            + " mismatches=" + verification.mismatches());
            assertTrue(compilation.netlist().inputs().size() <= 5);
            assertNoComponentOverlaps(placed.schematic());
        }
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
