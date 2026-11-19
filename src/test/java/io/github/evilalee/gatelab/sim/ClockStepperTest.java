package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClockStepperTest {
    private static final List<GateDef> DEFINITIONS = GateLibrary.load().all();

    @Test
    void clockDrivesOutputAndTwoStepsReturnToTheStartingLevel() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent clock = schematic.add("clock", 0, 0, Facing.EAST);
        PlacedComponent secondClock = schematic.add("clock", 0, 100, Facing.EAST);
        PlacedComponent output = schematic.add("output", 100, 0, Facing.EAST);
        connect(schematic, clock, "out", output, "in0");

        ClockStepper stepper = new ClockStepper();
        SchematicCompiler.SimulationResult result = SchematicCompiler.compileAndSettle(schematic);
        assertEquals(Value.ZERO, result.outputValues().get(output.id()));
        assertFalse(schematic.clockValue(clock.id()));

        result = stepper.step(schematic, result);
        assertEquals(Value.ONE, result.outputValues().get(output.id()));
        assertTrue(schematic.clockValue(clock.id()));
        assertTrue(schematic.clockValue(secondClock.id()));
        assertEquals(1, stepper.stepCount());

        result = stepper.step(schematic, result);
        assertEquals(Value.ZERO, result.outputValues().get(output.id()));
        assertFalse(schematic.clockValue(clock.id()));
        assertFalse(schematic.clockValue(secondClock.id()));
        assertEquals(2, stepper.stepCount());
    }

    @Test
    void clockRisingEdgeSetsNorLatchLowPhaseHoldsAndResetRestoresUnknown() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent reset = schematic.add("input", 50, -10, Facing.EAST);
        PlacedComponent clock = schematic.add("clock", 150, -10, Facing.EAST);
        PlacedComponent qGate = schematic.add("nor", 100, 0, Facing.EAST);
        PlacedComponent qBarGate = schematic.add("nor", 200, 0, Facing.EAST);
        PlacedComponent q = schematic.add("output", 120, 0, Facing.EAST);
        PlacedComponent qBar = schematic.add("output", 220, 0, Facing.EAST);

        connect(schematic, reset, "out", qGate, "in0");
        connect(schematic, clock, "out", qBarGate, "in0");
        addPath(schematic, 200, 0, 200, -40, 30, -40, 30, 10, 60, 10);
        addPath(schematic, 100, 0, 100, 40, 180, 40, 180, 10, 160, 10);
        connect(schematic, qGate, "out", q, "in0");
        connect(schematic, qBarGate, "out", qBar, "in0");

        ClockStepper stepper = new ClockStepper();
        SchematicCompiler.SimulationResult result = SchematicCompiler.compileAndSettle(schematic);
        assertEquals(Value.X, result.outputValues().get(q.id()));
        assertEquals(Value.X, result.outputValues().get(qBar.id()));

        result = stepper.step(schematic, result);
        assertTrue(schematic.clockValue(clock.id()));
        assertEquals(Value.ONE, result.outputValues().get(q.id()));
        assertEquals(Value.ZERO, result.outputValues().get(qBar.id()));

        result = stepper.step(schematic, result);
        assertFalse(schematic.clockValue(clock.id()));
        assertEquals(Value.ONE, result.outputValues().get(q.id()));
        assertEquals(Value.ZERO, result.outputValues().get(qBar.id()));

        schematic.setInputValue(reset.id(), true);
        result = stepper.reset(schematic);
        assertFalse(schematic.clockValue(clock.id()));
        assertFalse(schematic.inputValue(reset.id()));
        assertEquals(0, stepper.stepCount());
        assertEquals(Value.X, result.outputValues().get(q.id()));
        assertEquals(Value.X, result.outputValues().get(qBar.id()));
        assertEquals(Value.X, result.gateOutputValues().get(qGate.id()));
        assertEquals(Value.X, result.gateOutputValues().get(qBarGate.id()));
    }

    private static void connect(Schematic schematic, PlacedComponent first, String firstPin,
            PlacedComponent second, String secondPin) {
        Connectivity.PinPosition a = pin(schematic, first, firstPin);
        Connectivity.PinPosition b = pin(schematic, second, secondPin);
        if (a.x() == b.x() || a.y() == b.y()) {
            schematic.addWire(a.x(), a.y(), b.x(), b.y());
        } else {
            schematic.addWire(a.x(), a.y(), b.x(), a.y());
            schematic.addWire(b.x(), a.y(), b.x(), b.y());
        }
    }

    private static Connectivity.PinPosition pin(Schematic schematic, PlacedComponent component, String pinId) {
        return schematic.pinPositions().stream()
                .filter(position -> position.componentId().equals(component.id())
                        && position.pinId().equals(pinId))
                .findFirst().orElseThrow();
    }

    private static void addPath(Schematic schematic, int... coordinates) {
        for (int i = 0; i + 3 < coordinates.length; i += 2) {
            schematic.addWire(coordinates[i], coordinates[i + 1], coordinates[i + 2], coordinates[i + 3]);
        }
    }
}
