package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SignalRecorder;
import org.junit.jupiter.api.Test;

import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaveformRecordingCallbacksTest {
    @Test
    void stepPokeAndResetRecordOnlyTheRequestedSimulationEvents() {
        CanvasPanel canvas = new CanvasPanel();
        SignalRecorder recorder = new SignalRecorder();
        canvas.setSignalRecorderCallbacks(recorder::clear, () ->
                recorder.record(WaveformSampler.sample(
                        canvas.schematic(), canvas.simulationResult())));

        PlacedComponent input = canvas.schematic().add("input", 0, 0, Facing.EAST);
        // Component/wire changes recompute the view but are not sampling events.
        assertEquals(0, recorder.sampleCount());

        canvas.stepClock();
        assertEquals(1, recorder.sampleCount());
        assertEquals(Value.ZERO, recorder.valueAt("Input 1", 0));

        canvas.setPokeMode();
        clickComponentCenter(canvas, input);
        assertEquals(2, recorder.sampleCount());
        assertEquals(Value.ONE, recorder.valueAt("Input 1", 1));

        canvas.schematic().addWire(400, 400, 440, 400);
        assertEquals(2, recorder.sampleCount());

        canvas.resetSimulation();
        assertEquals(1, recorder.sampleCount());
        assertEquals(Value.ZERO, recorder.valueAt("Input 1", 0));
    }

    @Test
    void replacingADocumentAfterClearingStartsANewHistoryWithItsFirstSample() {
        CanvasPanel canvas = new CanvasPanel();
        SignalRecorder recorder = new SignalRecorder();
        canvas.setSignalRecorderCallbacks(recorder::clear, () ->
                recorder.record(WaveformSampler.sample(
                        canvas.schematic(), canvas.simulationResult())));

        canvas.schematic().add("input", 0, 0, Facing.EAST);
        canvas.stepClock();
        assertEquals(1, recorder.sampleCount());

        canvas.clearSignalHistory();
        Schematic loaded = new Schematic(GateLibrary.load().all());
        loaded.add("input", 0, 100, Facing.EAST);
        canvas.replaceSchematic(loaded);

        assertEquals(1, recorder.sampleCount());
        assertEquals("Input 1", recorder.labels().getFirst());
        assertEquals(Value.ZERO, recorder.valueAt("Input 1", 0));
    }

    private static void clickComponentCenter(CanvasPanel canvas, PlacedComponent component) {
        GateGeometry.Bounds bounds = GateGeometry.bounds(
                canvas.schematic().definitionFor(component), component.facing());
        Point2D.Double screen = canvas.viewTransform().worldToScreen(
                component.x() + bounds.x() + bounds.width() / 2.0,
                component.y() + bounds.y() + bounds.height() / 2.0);
        canvas.dispatchEvent(new MouseEvent(
                canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0,
                (int) Math.round(screen.x), (int) Math.round(screen.y),
                1, false, MouseEvent.BUTTON1));
    }
}
