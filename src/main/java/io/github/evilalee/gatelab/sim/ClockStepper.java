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

    /**
     * Flips all Clock components once, then settles while restoring previous
     * gate outputs so feedback circuits retain their established state.
     */
    public SchematicCompiler.SimulationResult step(
            Schematic schematic, SchematicCompiler.SimulationResult previous) {
        Objects.requireNonNull(schematic, "schematic");
        schematic.toggleClocks();
        SchematicCompiler.SimulationResult result =
                SchematicCompiler.compileAndSettle(schematic, previous);
        stepCount = Math.incrementExact(stepCount);
        return result;
    }

    /**
     * Resets user inputs and Clock sources, discards all prior gate state, and
     * performs a fresh settle. Feedback circuits therefore return to X.
     */
    public SchematicCompiler.SimulationResult reset(Schematic schematic) {
        Objects.requireNonNull(schematic, "schematic");
        schematic.resetSimulationSources();
        stepCount = 0;
        return SchematicCompiler.compileAndSettle(schematic);
    }
}
