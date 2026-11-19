package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import org.junit.jupiter.api.Test;

import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClockPokeTest {
    @Test
    void pokeModeTogglesInputsButLeavesClockLevelsAppControlled() {
        CanvasPanel canvas = new CanvasPanel();
        PlacedComponent clock = canvas.schematic().add("clock", 0, 0, Facing.EAST);
        PlacedComponent input = canvas.schematic().add("input", 0, 100, Facing.EAST);
        canvas.setPokeMode();

        clickComponentCenter(canvas, clock);
        assertFalse(canvas.schematic().clockValue(clock.id()));

        clickComponentCenter(canvas, input);
        assertTrue(canvas.schematic().inputValue(input.id()));
    }

    @Test
    void replacingTheDocumentPreservesItsSavedClockLevelAndRestartsProgress() {
        CanvasPanel canvas = new CanvasPanel();
        var replacement = new io.github.evilalee.gatelab.model.Schematic(
                io.github.evilalee.gatelab.io.GateLibrary.load().all());
        PlacedComponent clock = replacement.add("clock", 0, 0, Facing.EAST);
        replacement.setClockValue(clock.id(), true);

        canvas.stepClock();
        canvas.replaceSchematic(replacement);

        assertTrue(canvas.schematic().clockValue(clock.id()));
        assertTrue(canvas.simulationStatusText().equals("Step 0, cycle 0"));
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
