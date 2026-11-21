package io.github.evilalee.gatelab.synth.layout;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.SchematicSnapshot;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.WireSegment;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import io.github.evilalee.gatelab.synth.FeedbackCircuitExtractor;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FeedbackCircuitLayoutMetricsTest {
    private static final List<io.github.evilalee.gatelab.model.GateDef> DEFINITIONS =
            GateLibrary.load().all();

    private record Metrics(int width, int height, long area, long wireLength) {
        String display() {
            return width + "x" + height + " area=" + area + " wire=" + wireLength;
        }
    }

    @TestFactory
    List<DynamicTest> feedbackExamplesAreCompactDeterministicAndConnected() {
        return List.of(
                feedbackMetrics("NOR SR latch", """
                        Q = R NOR Qbar
                        Qbar = S NOR Q
                        """, true),
                feedbackMetrics("4-NAND D latch", "Q = DLATCH(D, EN)", false),
                feedbackMetrics("master-slave D flip-flop", "Q = DFF(D, CLK)", false),
                feedbackMetrics("divide-by-2", "Q = DFF(NOT(Q), CLK)", false),
                feedbackMetrics("2-bit counter", """
                        Q0 = DFF(RESET_N & NOT(Q0), CLK)
                        Q1 = DFF(RESET_N & (Q1 ^ Q0), CLK)
                        """, false));
    }

    private static DynamicTest feedbackMetrics(
            String name, String source, boolean allowRawFeedback) {
        try {
            ExpressionCompiler.Compilation compilation =
                    ExpressionCompiler.compile(source, allowRawFeedback);
            FeedbackCircuitExtractor.Extraction extraction =
                    compilation.feedbackExtraction().orElseGet(() ->
                            FeedbackCircuitExtractor.extract(compilation.netlist()).orElseThrow());
            SchematicLayout layout = new SchematicLayout(DEFINITIONS);
            SchematicLayout.FeedbackLayoutRun compactRun = layout.layoutFeedbackRun(
                    extraction, SchematicLayout.FeedbackLayoutMode.COMPACT_WITH_FALLBACK);
            SchematicLayout.FeedbackLayoutRun legacyRun = layout.layoutFeedbackRun(
                    extraction, SchematicLayout.FeedbackLayoutMode.LEGACY);
            Schematic compact = compactRun.result().schematic();
            Schematic legacy = legacyRun.result().schematic();
            Metrics actual = measure(compact);
            Metrics baseline = measure(legacy);
            String description = name + " legacy=" + baseline.display()
                    + " compact=" + actual.display()
                    + " legacyFallback=" + compactRun.legacyFallbackUsed();

            return DynamicTest.dynamicTest(description, () -> {
                SchematicLayout.FeedbackLayoutRun repeated = layout.layoutFeedbackRun(
                        extraction, SchematicLayout.FeedbackLayoutMode.COMPACT_WITH_FALLBACK);
                assertEquals(SchematicSnapshot.capture(compact),
                        SchematicSnapshot.capture(repeated.result().schematic()),
                        "feedback layout must be deterministic across runs");
                assertEquals(compactRun.legacyFallbackUsed(), repeated.legacyFallbackUsed(),
                        "fallback selection must be deterministic");
                assertNoOverlaps(compact);
                Connectivity.Result topology = Connectivity.analyze(
                        compact.pinPositions(), compact.wires());
                assertFalse(topology.nets().isEmpty(),
                        "connectivity self-check must produce nets");
                assertTrue(compact.components().stream()
                        .anyMatch(component -> component.typeId().equals("output")));
                assertTrue(actual.width() > 0 && actual.height() > 0);
                assertTrue(actual.wireLength() > 0);
                if (compactRun.legacyFallbackUsed()) {
                    assertEquals(baseline.area(), actual.area(),
                            "legacy fallback must retain legacy bounding-box area");
                    assertEquals(baseline.wireLength(), actual.wireLength(),
                            "legacy fallback must retain legacy wire length");
                } else {
                    assertTrue(actual.area() < baseline.area(),
                            "compact layout must be strictly smaller than main's legacy layout; "
                                    + description);
                }
            });
        } catch (RuntimeException exception) {
            return DynamicTest.dynamicTest(
                    name + " feedback routing failed: " + exception.getMessage(),
                    () -> { throw exception; });
        }
    }

    @Test
    void forcedCompactFailureFallsBackToTheLegacyLayout() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile("""
                Q = R NOR Qbar
                Qbar = S NOR Q
                """, true);
        FeedbackCircuitExtractor.Extraction extraction =
                compilation.feedbackExtraction().orElseThrow();
        SchematicLayout layout = new SchematicLayout(DEFINITIONS);
        Schematic expectedLegacy = layout.layoutFeedbackRun(
                extraction, SchematicLayout.FeedbackLayoutMode.LEGACY).result().schematic();

        SchematicLayout.FeedbackLayoutRun forced = layout.layoutFeedbackRun(
                extraction, SchematicLayout.FeedbackLayoutMode.COMPACT_WITH_FALLBACK, true);

        assertTrue(forced.legacyFallbackUsed(),
                "a failed compact attempt and doubled-spacing retry must select legacy mode");
        assertTrue(forced.fallbackReason().contains("forced compact failure"));
        assertEquals(SchematicSnapshot.capture(expectedLegacy),
                SchematicSnapshot.capture(forced.result().schematic()),
                "fallback schematic must be the preserved legacy layout");
    }

    @Test
    void norSrLatchStillSetsHoldsAndResetsAfterLayout() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile("""
                Q = R NOR Qbar
                Qbar = S NOR Q
                """, true);
        FeedbackCircuitExtractor.Extraction extraction =
                compilation.feedbackExtraction().orElseThrow();
        SchematicLayout.LayoutResult layout =
                new SchematicLayout(DEFINITIONS).layoutFeedback(extraction);
        Schematic schematic = layout.schematic();
        String q = layout.outputComponentIds().get("Q");
        String qBar = layout.outputComponentIds().get("Qbar");

        SchematicCompiler.SimulationResult simulation =
                SchematicCompiler.compileAndSettle(schematic);
        assertFalse(simulation.oscillating());
        assertEquals(Value.X, simulation.outputValues().get(q));
        assertEquals(Value.X, simulation.outputValues().get(qBar));

        schematic.setInputValue(layout.inputComponentIds().get("S"), true);
        simulation = SchematicCompiler.compileAndSettle(schematic, simulation);
        assertEquals(Value.ONE, simulation.outputValues().get(q));
        assertEquals(Value.ZERO, simulation.outputValues().get(qBar));

        schematic.setInputValue(layout.inputComponentIds().get("S"), false);
        simulation = SchematicCompiler.compileAndSettle(schematic, simulation);
        assertEquals(Value.ONE, simulation.outputValues().get(q), "released set must hold");
        assertEquals(Value.ZERO, simulation.outputValues().get(qBar));

        schematic.setInputValue(layout.inputComponentIds().get("R"), true);
        simulation = SchematicCompiler.compileAndSettle(schematic, simulation);
        assertEquals(Value.ZERO, simulation.outputValues().get(q));
        assertEquals(Value.ONE, simulation.outputValues().get(qBar));
        assertFalse(simulation.oscillating());
    }

    @TestFactory
    List<DynamicTest> reportCurrentCombinationalLayoutFingerprints() throws Exception {
        return List.of(
                DynamicTest.dynamicTest(
                        "CURRENT_COMBINATIONAL full adder sha256=" + fingerprint("""
                                Sum = A ^ B ^ Cin
                                Cout = (A & B) | (Cin & (A ^ B))
                                """), () -> {}),
                DynamicTest.dynamicTest(
                        "CURRENT_COMBINATIONAL majority sha256=" + fingerprint("""
                                M = (A & B) | (A & C) | (B & C)
                                """), () -> {}));
    }

    @Test
    void fullAdderAndMajorityCombinationalCoordinatesRemainByteForByteStable()
            throws Exception {
        assertEquals("40aaa6bcdaa0e1a991e83bc8e136c97928423055cf6e36e11d0eb8390ebbf756",
                fingerprint("""
                        Sum = A ^ B ^ Cin
                        Cout = (A & B) | (Cin & (A ^ B))
                        """));
        assertEquals("0ae7d7814b070b84f5da7fe05ac94eb5278433736dffe707aac506ea9cbfc8b9",
                fingerprint("""
                        M = (A & B) | (A & C) | (B & C)
                        """));
    }

    private static String fingerprint(String source) throws Exception {
        Schematic schematic = new SchematicLayout(DEFINITIONS)
                .layout(ExpressionCompiler.compile(source).netlist()).schematic();
        String components = schematic.components().stream()
                .map(component -> component.id() + ":" + component.typeId() + ":"
                        + component.x() + ":" + component.y() + ":" + component.facing()
                        + ":" + component.attributes() + ":" + component.label())
                .sorted().collect(java.util.stream.Collectors.joining("\\n"));
        String wires = schematic.wires().stream()
                .map(wire -> wire.x1() + "," + wire.y1() + "," + wire.x2() + "," + wire.y2())
                .sorted().collect(java.util.stream.Collectors.joining("\\n"));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (components + "\\n--WIRES--\\n" + wires).getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    private static Metrics measure(Schematic schematic) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (PlacedComponent component : schematic.components()) {
            GateGeometry.Bounds local = GateGeometry.bounds(
                    schematic.definitionFor(component), component.facing());
            int x = component.x() + local.x();
            int y = component.y() + local.y();
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + local.width());
            maxY = Math.max(maxY, y + local.height());
        }
        long wireLength = 0;
        for (WireSegment wire : schematic.wires()) {
            minX = Math.min(minX, Math.min(wire.x1(), wire.x2()));
            minY = Math.min(minY, Math.min(wire.y1(), wire.y2()));
            maxX = Math.max(maxX, Math.max(wire.x1(), wire.x2()));
            maxY = Math.max(maxY, Math.max(wire.y1(), wire.y2()));
            wireLength += Math.abs(wire.x2() - wire.x1())
                    + Math.abs(wire.y2() - wire.y1());
        }
        int width = maxX - minX;
        int height = maxY - minY;
        return new Metrics(width, height, (long) width * height, wireLength);
    }

    private static void assertNoOverlaps(Schematic schematic) {
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
                schematic.definitionFor(component), Facing.EAST);
        return new GateGeometry.Bounds(
                component.x() + local.x(), component.y() + local.y(),
                local.width(), local.height());
    }
}
