package io.github.evilalee.gatelab.synth.layout;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.WireSegment;
import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import io.github.evilalee.gatelab.synth.FeedbackCircuitExtractor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class FeedbackLayoutBaselineMetricsTest {
    private record Metrics(int width, int height, long area, long wireLength) {}

    @Test
    void reportBaselineFeedbackLayoutMetrics() {
        report("NOR SR latch", """
                Q = R NOR Qbar
                Qbar = S NOR Q
                """, true);
        report("4-NAND D latch", "Q = DLATCH(D, EN)", false);
        report("master-slave D flip-flop", "Q = DFF(D, CLK)", false);
        report("divide-by-2", "Q = DFF(NOT(Q), CLK)", false);
        report("2-bit counter", """
                Q0 = DFF(RESET_N & NOT(Q0), CLK)
                Q1 = DFF(RESET_N & (Q1 ^ Q0), CLK)
                """, false);
    }

    @Test
    void reportCombinationalLayoutFingerprints() throws Exception {
        reportFingerprint("full adder", """
                Sum = A ^ B ^ Cin
                Cout = (A & B) | (Cin & (A ^ B))
                """);
        reportFingerprint("majority", """
                M = (A & B) | (A & C) | (B & C)
                """);
    }

    private static void reportFingerprint(String name, String expression) throws Exception {
        Schematic schematic = new SchematicLayout(GateLibrary.load().all())
                .layout(ExpressionCompiler.compile(expression).netlist()).schematic();
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
        System.out.println("BASELINE_COMBINATIONAL " + name + " sha256="
                + java.util.HexFormat.of().formatHex(digest));
    }

    private static void report(String name, String expression, boolean rawFeedback) {
        ExpressionCompiler.Compilation compilation =
                ExpressionCompiler.compile(expression, rawFeedback);
        FeedbackCircuitExtractor.Extraction extraction =
                compilation.feedbackExtraction().orElseGet(() ->
                        FeedbackCircuitExtractor.extract(compilation.netlist()).orElseThrow());
        Schematic schematic = new SchematicLayout(GateLibrary.load().all())
                .layoutFeedback(extraction).schematic();
        Metrics metrics = metrics(schematic);
        assertNotNull(metrics);
        System.out.println("BASELINE_LAYOUT " + name + " width=" + metrics.width()
                + " height=" + metrics.height() + " area=" + metrics.area()
                + " wireLength=" + metrics.wireLength());
    }

    private static Metrics metrics(Schematic schematic) {
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
            wireLength += Math.abs(wire.x2() - wire.x1()) + Math.abs(wire.y2() - wire.y1());
        }
        int width = maxX - minX;
        int height = maxY - minY;
        return new Metrics(width, height, (long) width * height, wireLength);
    }
}
