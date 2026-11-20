package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.model.Circuit;
import io.github.evilalee.gatelab.model.InputPin;
import io.github.evilalee.gatelab.model.OutputProbe;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.synth.SequentialParts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequenceRunnerTest {
    @Test
    void runnerStartsAllInputsAtUnknownAndReportsUnknownAgainstConcreteExpectedValue() {
        Circuit circuit = new Circuit();
        InputPin data = new InputPin("data");
        InputPin clock = new InputPin("clock");
        data.setValue(Value.ONE);
        circuit.addComponent(data);
        circuit.addComponent(clock);

        SequentialParts.Outputs flipFlop =
                SequentialParts.dff(circuit, "dff", data.output(), clock.output());
        OutputProbe q = new OutputProbe("q");
        circuit.addComponent(q);
        circuit.connect(flipFlop.q(), q.input());

        SequenceRunner runner = new SequenceRunner(
                circuit,
                Map.of("D", data, "CLK", clock),
                "CLK",
                Map.of("Q", q));
        SequenceRunner.SequenceResult result = runner.run(List.of(
                new SequenceRunner.Step(
                        "unknown is not zero",
                        Map.of(),
                        1,
                        Map.of("Q", Value.ZERO)),
                new SequenceRunner.Step(
                        "unknown matches unknown",
                        Map.of(),
                        2,
                        Map.of("Q", Value.X))));

        assertFalse(result.passed());
        assertEquals(1, result.mismatches().size());
        SequenceRunner.Mismatch mismatch = result.firstMismatch().orElseThrow();
        assertEquals(0, mismatch.stepIndex());
        assertEquals("unknown is not zero", mismatch.stepName());
        assertEquals("Q", mismatch.outputName());
        assertEquals(Value.ZERO, mismatch.expected());
        assertEquals(Value.X, mismatch.observed());
        assertTrue(result.steps().get(1).mismatches().isEmpty());
    }

    @Test
    void stepCannotDriveClockAndUnknownNamesAreRejectedBeforeSimulationAdvances() {
        Circuit circuit = new Circuit();
        InputPin data = new InputPin("data");
        InputPin clock = new InputPin("clock");
        circuit.addComponent(data);
        circuit.addComponent(clock);
        SequentialParts.Outputs flipFlop =
                SequentialParts.dff(circuit, "dff", data.output(), clock.output());
        OutputProbe q = new OutputProbe("q");
        circuit.addComponent(q);
        circuit.connect(flipFlop.q(), q.input());

        SequenceRunner runner = new SequenceRunner(
                circuit, Map.of("D", data, "CLK", clock), "CLK", Map.of("Q", q));

        assertThrows(IllegalArgumentException.class, () -> runner.run(List.of(
                new SequenceRunner.Step(
                        "invalid clock assignment",
                        Map.of("CLK", Value.ONE),
                        1,
                        Map.of("Q", Value.X)))));

        assertThrows(IllegalArgumentException.class, () -> runner.run(List.of(
                new SequenceRunner.Step(
                        "unknown input",
                        Map.of("MISSING", Value.ONE),
                        1,
                        Map.of("Q", Value.X)))));
    }

    @Test
    void stepRejectsNegativeCycleCount() {
        assertThrows(IllegalArgumentException.class, () ->
                new SequenceRunner.Step("invalid", Map.of(), -1, Map.of()));
    }
}
