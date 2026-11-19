package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.ClockStepper;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WaveformSamplerTest {
    @Test
    void usesComponentLabelsAndStablePerTypeFallbackNamesInSchematicOrder() {
        Schematic schematic = new Schematic(GateLibrary.load().all());
        PlacedComponent namedInput = schematic.add("input", 0, 0, Facing.EAST);
        schematic.setLabel(namedInput.id(), "Data");
        schematic.add("input", 0, 40, Facing.EAST);
        schematic.add("clock", 0, 80, Facing.EAST);
        schematic.add("output", 200, 0, Facing.EAST);
        PlacedComponent namedOutput = schematic.add("output", 200, 40, Facing.EAST);
        schematic.setLabel(namedOutput.id(), "Result");

        Map<String, Value> sample = WaveformSampler.sample(
                schematic, SchematicCompiler.compileAndSettle(schematic));

        assertEquals(List.of("Data", "Input 2", "Clock 1", "Output 1", "Result"),
                List.copyOf(sample.keySet()));
        assertEquals(5, sample.size());
    }

    @Test
    void disambiguatesDuplicateDisplayLabelsWithoutDroppingSignals() {
        Schematic schematic = new Schematic(GateLibrary.load().all());
        PlacedComponent first = schematic.add("input", 0, 0, Facing.EAST);
        PlacedComponent second = schematic.add("input", 0, 40, Facing.EAST);
        schematic.setLabel(first.id(), "Ready");
        schematic.setLabel(second.id(), "Ready");

        Map<String, Value> sample = WaveformSampler.sample(
                schematic, SchematicCompiler.compileAndSettle(schematic));

        assertEquals(List.of("Ready", "Ready (2)"), List.copyOf(sample.keySet()));
    }

    @Test
    void recordsClockAndOutputValuesAfterAStepAndInputAndOutputValuesAfterAPoke() {
        Schematic schematic = new Schematic(GateLibrary.load().all());
        PlacedComponent input = schematic.add("input", 0, 0, Facing.EAST);
        PlacedComponent clock = schematic.add("clock", 0, 100, Facing.EAST);
        PlacedComponent inputOutput = schematic.add("output", 300, 0, Facing.EAST);
        PlacedComponent clockOutput = schematic.add("output", 300, 100, Facing.EAST);
        schematic.addWire(20, 10, 300, 10);
        schematic.addWire(20, 110, 300, 110);

        SchematicCompiler.SimulationResult simulation =
                SchematicCompiler.compileAndSettle(schematic);
        Map<String, Value> before = WaveformSampler.sample(schematic, simulation);
        assertEquals(Value.ZERO, before.get("Input 1"));
        assertEquals(Value.ZERO, before.get("Clock 1"));
        assertEquals(Value.ZERO, before.get("Output 1"));
        assertEquals(Value.ZERO, before.get("Output 2"));

        simulation = new ClockStepper().step(schematic, simulation);
        Map<String, Value> afterStep = WaveformSampler.sample(schematic, simulation);
        assertEquals(Value.ZERO, afterStep.get("Input 1"));
        assertEquals(Value.ONE, afterStep.get("Clock 1"));
        assertEquals(Value.ZERO, afterStep.get("Output 1"));
        assertEquals(Value.ONE, afterStep.get("Output 2"));

        schematic.toggleInput(input.id());
        simulation = SchematicCompiler.compileAndSettle(schematic, simulation);
        Map<String, Value> afterPoke = WaveformSampler.sample(schematic, simulation);
        assertEquals(Value.ONE, afterPoke.get("Input 1"));
        assertEquals(Value.ONE, afterPoke.get("Clock 1"));
        assertEquals(Value.ONE, afterPoke.get("Output 1"));
        assertEquals(Value.ONE, afterPoke.get("Output 2"));
    }

    @Test
    void samplingRequiresASettledSimulationSnapshot() {
        Schematic schematic = new Schematic(GateLibrary.load().all());
        assertThrows(NullPointerException.class, () -> WaveformSampler.sample(schematic, null));
    }
}
