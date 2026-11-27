package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.io.ComponentLibrary;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.synth.ComponentExpressionCompiler;
import io.github.evilalee.gatelab.synth.TruthTable;
import io.github.evilalee.gatelab.util.PerfLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class UiPerformanceBaselineTest {
    @TempDir Path directory;

    private static final String FOUR_BIT_ADDER = """
            P0 := A0 ^ B0
            P1 := A1 ^ B1
            P2 := A2 ^ B2
            P3 := A3 ^ B3
            G0 := A0 & B0
            G1 := A1 & B1
            G2 := A2 & B2
            G3 := A3 & B3
            C1 := G0 | (P0 & CIN)
            C2 := G1 | (P1 & C1)
            C3 := G2 | (P2 & C2)
            C4 := G3 | (P3 & C3)
            S0 = P0 ^ CIN
            S1 = P1 ^ C1
            S2 = P2 ^ C2
            S3 = P3 ^ C3
            COUT = C4
            """;

    private static final String FOUR_FA_ADDER = """
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

    @Test
    void reportsHeadlessBaselineForExpressionAndCanvasCases() throws Exception {
        String previous = System.getProperty("logicbench.perf");
        System.setProperty("logicbench.perf", "true");
        try {
            List<io.github.evilalee.gatelab.model.GateDef> gates = GateLibrary.load().all();
            ComponentLibrary components = ComponentLibrary.load(gates, directory);

            measureExpression("20-line expression text", twentyLineExpression(),
                    ComponentExpressionCompiler.CompileOption.LEGACY_SEQUENTIAL_GATE_EXPANSION,
                    gates, components.all());
            measureExpression("typed 4-bit adder", FOUR_BIT_ADDER,
                    ComponentExpressionCompiler.CompileOption.LEGACY_SEQUENTIAL_GATE_EXPANSION,
                    gates, components.all());
            ComponentExpressionCompiler.Compilation boxed = measureExpression(
                    "4-FA adder", FOUR_FA_ADDER,
                    ComponentExpressionCompiler.CompileOption.BOXED_INSTANCES,
                    gates, components.all());

            assertEquals(4, boxed.schematic().componentInstances().size());
            assertEquals(9, boxed.inputComponentIds().size());

            AtomicReference<CanvasPanel> canvasReference = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                CanvasPanel canvas = new CanvasPanel();
                canvas.setSize(1024, 768);
                canvas.replaceWithGeneratedCircuit(boxed.schematic());
                canvas.viewTransform().resetToDefault();
                canvasReference.set(canvas);
                reportPaints("4-FA adder at 100% zoom", canvas);
                canvas.viewTransform().zoomAt(512, 384, 0.5);
                reportPaints("4-FA adder zoomed out", canvas);
            });
        } finally {
            if (previous == null) {
                System.clearProperty("logicbench.perf");
            } else {
                System.setProperty("logicbench.perf", previous);
            }
        }
    }

    private static ComponentExpressionCompiler.Compilation measureExpression(
            String label,
            String source,
            ComponentExpressionCompiler.CompileOption option,
            List<io.github.evilalee.gatelab.model.GateDef> gates,
            List<io.github.evilalee.gatelab.model.ComponentDefinition> components) {
        long started = System.nanoTime();
        ComponentExpressionCompiler.Compilation compilation =
                ComponentExpressionCompiler.compile(source, gates, components, option, true, false);
        if (!compilation.hasSequentialInstances() && !compilation.hasFeedback()) {
            List<String> inputs = compilation.inputComponentIds().keySet().stream().sorted().toList();
            List<String> outputs = compilation.outputComponentIds().keySet().stream().sorted().toList();
            if (inputs.size() <= 10) {
                TruthTable table = ExpressionDialog.buildEnginePreview(compilation, inputs, outputs);
                assertFalse(table.rows().isEmpty());
            }
        }
        double elapsed = (System.nanoTime() - started) / 1_000_000.0;
        System.err.printf(Locale.ROOT,
                "LogicBench perf baseline: %s total=%.3f ms inputs=%d outputs=%d boxes=%d%n",
                label, elapsed, compilation.inputComponentIds().size(),
                compilation.outputComponentIds().size(),
                compilation.schematic().componentInstances().size());
        return compilation;
    }

    private static void reportPaints(String label, CanvasPanel canvas) {
        BufferedImage image = new BufferedImage(
                canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            canvas.paint(graphics);
            long started = System.nanoTime();
            int samples = 6;
            for (int index = 0; index < samples; index++) {
                canvas.paint(graphics);
            }
            double average = (System.nanoTime() - started)
                    / (double) samples / 1_000_000.0;
            System.err.printf(Locale.ROOT,
                    "LogicBench perf baseline: %s averagePaint=%.3f ms samples=%d%n",
                    label, average, samples);
        } finally {
            graphics.dispose();
        }
    }

    private static String twentyLineExpression() {
        List<String> lines = new ArrayList<>(List.of(
                "I0 := A & B",
                "I1 := B ^ C",
                "I2 := C | D",
                "I3 := D & A",
                "I4 := I0 ^ I1",
                "I5 := I1 | I2",
                "I6 := I2 & I3",
                "I7 := I3 ^ I0",
                "I8 := I4 | I5",
                "I9 := I5 & I6",
                "I10 := I6 ^ I7",
                "I11 := I7 | I4",
                "O0 = I8 ^ I9",
                "O1 = I9 | I10",
                "O2 = I10 & I11",
                "O3 = I11 ^ I8",
                "O4 = (I8 & I10) | (I9 & I11)",
                "O5 = (I8 ^ I10) ^ (I9 ^ I11)",
                "O6 = (I8 | I9) & (I10 | I11)",
                "O7 = (I8 & I9) | (I10 & I11)"));
        assertEquals(20, lines.size());
        return String.join("\n", lines);
    }
}
