package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.model.Schematic;

import java.util.Objects;

/**
 * Advances schematic Clock sources in lockstep and carries settled gate state
 * between logical steps. It deliberately has no wall-clock or UI dependency.
 */
public final class ClockStepper {
    private long stepCount;

    public long stepCount() {
        return stepCount;
    }

    /** Resets only the progress counter without changing persisted source levels. */
    public void resetStepCount() {
        stepCount = 0;
    }

    /**
     * Flips all Clock components once, then settles while restoring previous
     * gate outputs so feedback circuits retain their established state.
     */
    public SchematicCompiler.SimulationResult step(
            Schematic schematic, SchematicCompiler.SimulationResult previous) {
        return step(schematic, previous, true);
    }

    public SchematicCompiler.SimulationResult step(
            Schematic schematic, SchematicCompiler.SimulationResult previous,
            boolean chipsNeedPower) {
        Objects.requireNonNull(schematic, "schematic");
        schematic.toggleClocks();
        SchematicCompiler.SimulationResult result =
                SchematicCompiler.compileAndSettle(schematic, previous, chipsNeedPower);
        stepCount = Math.incrementExact(stepCount);
        return result;
    }

    /**
     * Resets user inputs and Clock sources, discards all prior gate state, and
     * performs a fresh settle. Feedback circuits therefore return to X.
     */
    public SchematicCompiler.SimulationResult reset(Schematic schematic) {
        return reset(schematic, true);
    }

    public SchematicCompiler.SimulationResult reset(
            Schematic schematic, boolean chipsNeedPower) {
        Objects.requireNonNull(schematic, "schematic");
        schematic.resetSimulationSources();
        stepCount = 0;
        return SchematicCompiler.compileAndSettle(schematic, null, chipsNeedPower);
    }
}
