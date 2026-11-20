package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.model.Circuit;
import io.github.evilalee.gatelab.model.Gate;
import io.github.evilalee.gatelab.model.GateType;
import io.github.evilalee.gatelab.model.InputPin;
import io.github.evilalee.gatelab.model.OutputProbe;
import io.github.evilalee.gatelab.model.Pin;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.synth.SequentialParts;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

class SequentialTraceDiagnosticTest {
    @TestFactory
    Stream<DynamicTest> counterTrace() {
        Circuit circuit = new Circuit();
        InputPin reset = input(circuit, "reset");
        InputPin clock = input(circuit, "clock");
        Gate notQ0 = gate(circuit, "not-q0", GateType.NOT, 1);
        Gate xor = gate(circuit, "xor", GateType.XOR, 2);
        Gate d0 = gate(circuit, "d0", GateType.AND, 2);
        Gate d1 = gate(circuit, "d1", GateType.AND, 2);
        circuit.connect(notQ0.output(), d0.input(0));
        circuit.connect(reset.output(), d0.input(1));
        circuit.connect(xor.output(), d1.input(0));
        circuit.connect(reset.output(), d1.input(1));
        SequentialParts.Outputs q0 = SequentialParts.dff(circuit, "ff0", d0.output(), clock.output());
        SequentialParts.Outputs q1 = SequentialParts.dff(circuit, "ff1", d1.output(), clock.output());
        circuit.connect(q0.q(), notQ0.input(0));
        circuit.connect(q0.q(), xor.input(0));
        circuit.connect(q1.q(), xor.input(1));
        OutputProbe out0 = probe(circuit, "out0", q0.q());
        OutputProbe out1 = probe(circuit, "out1", q1.q());
        SequenceRunner runner = new SequenceRunner(circuit,
                Map.of("RESET_N", reset, "CLK", clock), "CLK",
                Map.of("Q0", out0, "Q1", out1));
        SequenceRunner.SequenceResult result = runner.run(List.of(
                step("reset", Value.ZERO, Value.ZERO, Value.ZERO),
                step("one", Value.ONE, Value.ZERO, Value.ONE),
                step("two", Value.ONE, Value.ONE, Value.ZERO),
                step("three", Value.ONE, Value.ONE, Value.ONE),
                step("zero", Value.ONE, Value.ZERO, Value.ZERO)));
        String observed = result.steps().stream()
                .map(s -> s.observedOutputs().get("Q1").toString()
                        + s.observedOutputs().get("Q0").toString())
                .toList().toString();
        return Stream.of(dynamicTest("OBSERVED_COUNTER_TRACE=" + observed,
                () -> assertTrue(!observed.isEmpty())));
    }

    @TestFactory
    Stream<DynamicTest> shiftTrace() {
        Circuit circuit = new Circuit();
        InputPin reset = input(circuit, "reset");
        InputPin data = input(circuit, "data");
        InputPin clock = input(circuit, "clock");
        Gate d0 = gate(circuit, "d0", GateType.AND, 2);
        Gate d1 = gate(circuit, "d1", GateType.AND, 2);
        Gate d2 = gate(circuit, "d2", GateType.AND, 2);
        circuit.connect(data.output(), d0.input(0));
        circuit.connect(reset.output(), d0.input(1));
        SequentialParts.Outputs q0 = SequentialParts.dff(circuit, "ff0", d0.output(), clock.output());
        SequentialParts.Outputs q1 = SequentialParts.dff(circuit, "ff1", d1.output(), clock.output());
        SequentialParts.Outputs q2 = SequentialParts.dff(circuit, "ff2", d2.output(), clock.output());
        circuit.connect(q0.q(), d1.input(0));
        circuit.connect(reset.output(), d1.input(1));
        circuit.connect(q1.q(), d2.input(0));
        circuit.connect(reset.output(), d2.input(1));
        OutputProbe out0 = probe(circuit, "out0", q0.q());
        OutputProbe out1 = probe(circuit, "out1", q1.q());
        OutputProbe out2 = probe(circuit, "out2", q2.q());
        SequenceRunner runner = new SequenceRunner(circuit,
                Map.of("RESET_N", reset, "DATA", data, "CLK", clock), "CLK",
                Map.of("Q0", out0, "Q1", out1, "Q2", out2));
        SequenceRunner.SequenceResult result = runner.run(List.of(
                new SequenceRunner.Step("reset", Map.of("RESET_N", Value.ZERO, "DATA", Value.ZERO), 1,
                        Map.of("Q2", Value.ZERO, "Q1", Value.ZERO, "Q0", Value.ZERO)),
                new SequenceRunner.Step("one", Map.of("RESET_N", Value.ONE, "DATA", Value.ONE), 1,
                        Map.of("Q2", Value.ZERO, "Q1", Value.ZERO, "Q0", Value.ONE)),
                new SequenceRunner.Step("two", Map.of("RESET_N", Value.ONE, "DATA", Value.ZERO), 1,
                        Map.of("Q2", Value.ZERO, "Q1", Value.ONE, "Q0", Value.ZERO)),
                new SequenceRunner.Step("four", Map.of("RESET_N", Value.ONE, "DATA", Value.ZERO), 1,
                        Map.of("Q2", Value.ONE, "Q1", Value.ZERO, "Q0", Value.ZERO)),
                new SequenceRunner.Step("zero", Map.of("RESET_N", Value.ONE, "DATA", Value.ZERO), 1,
                        Map.of("Q2", Value.ZERO, "Q1", Value.ZERO, "Q0", Value.ZERO))));
        String observed = result.steps().stream()
                .map(s -> s.observedOutputs().get("Q2").toString()
                        + s.observedOutputs().get("Q1").toString()
                        + s.observedOutputs().get("Q0").toString())
                .toList().toString();
        return Stream.of(dynamicTest("OBSERVED_SHIFT_TRACE=" + observed,
                () -> assertTrue(!observed.isEmpty())));
    }

    private static SequenceRunner.Step step(String name, Value reset, Value q1, Value q0) {
        return new SequenceRunner.Step(name, Map.of("RESET_N", reset), 1, Map.of("Q1", q1, "Q0", q0));
    }

    private static InputPin input(Circuit circuit, String id) {
        InputPin input = new InputPin(id);
        circuit.addComponent(input);
        return input;
    }

    private static Gate gate(Circuit circuit, String id, GateType type, int inputs) {
        Gate gate = new Gate(id, type, inputs);
        circuit.addComponent(gate);
        return gate;
    }

    private static OutputProbe probe(Circuit circuit, String id, Pin source) {
        OutputProbe probe = new OutputProbe(id);
        circuit.addComponent(probe);
        circuit.connect(source, probe.input());
        return probe;
    }
}
