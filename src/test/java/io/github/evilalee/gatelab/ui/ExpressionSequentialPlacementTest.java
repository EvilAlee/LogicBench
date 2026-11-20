package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SignalRecorder;
import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import io.github.evilalee.gatelab.synth.layout.SchematicLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionSequentialPlacementTest {
    @Test
    void placementNamesClockResetsStateAndRecordsDefaultWaveformSignals() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile("""
                Q0 = DFF(RESET_N & NOT(Q0), CLK)
                Q1 = DFF(RESET_N & (Q1 ^ Q0), CLK)
                """, true);
        SchematicLayout.LayoutResult layout =
                new SchematicLayout(GateLibrary.load().all())
                        .layoutFeedback(compilation.feedbackExtraction().orElseThrow());

        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        SignalRecorder recorder = new SignalRecorder();
        canvas.setSignalRecorderCallbacks(
                recorder::clear,
                () -> recorder.record(WaveformSampler.sample(
                        canvas.schematic(), canvas.simulationResult())));

        canvas.replaceWithSequentialCircuit(layout.schematic());

        String clockId = layout.clockComponentIds().get("CLK");
        PlacedComponent placedClock = canvas.schematic().components().stream()
                .filter(component -> component.id().equals(clockId))
                .findFirst().orElseThrow();
        assertEquals("CLK", placedClock.label());
        assertFalse(canvas.schematic().clockValue(clockId));
        assertEquals("Step 0, cycle 0", canvas.simulationStatusText());
        assertEquals(Value.X, canvas.simulationResult().outputValues()
                .get(layout.outputComponentIds().get("Q0")));
        assertEquals(Value.X, canvas.simulationResult().outputValues()
                .get(layout.outputComponentIds().get("Q1")));

        assertTrue(recorder.labels().containsAll(List.of("RESET_N", "CLK", "Q0", "Q1")));
        assertEquals(1, recorder.sampleCount());
        assertEquals(ExpressionDialog.SEQUENTIAL_STATUS,
                "Sequential circuit: use Tick Once; start with RESET_N = 0 for one cycle.");

        assertTrue(canvas.canUndo());
        assertTrue(canvas.undo());
        assertTrue(canvas.schematic().components().isEmpty());
    }
}
