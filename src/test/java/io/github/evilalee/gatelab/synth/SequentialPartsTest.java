package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.model.Circuit;
import io.github.evilalee.gatelab.model.Gate;
import io.github.evilalee.gatelab.model.GateType;
import io.github.evilalee.gatelab.model.InputPin;
import io.github.evilalee.gatelab.model.OutputProbe;
import io.github.evilalee.gatelab.model.Pin;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SequenceRunner;
import io.github.evilalee.gatelab.sim.Simulator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequentialPartsTest {
    @Test
    void dffCapturesKnownZeroAndOneFromUnknownStart() {
        for (Value expected : List.of(Value.ZERO, Value.ONE)) {
            Circuit circuit = new Circuit();
            InputPin data = input(circuit, "data");
            InputPin clock = input(circuit, "clock");
            SequentialParts.Outputs flipFlop =
                    SequentialParts.dff(circuit, "dff", data.output(), clock.output());
            OutputProbe q = probe(circuit, "q", flipFlop.q());

            SequenceRunner runner = runner(
                    circuit, clock, Map.of("D", data), Map.of("Q", q));
            SequenceRunner.SequenceResult result = runner.run(List.of(
                    new SequenceRunner.Step(
                            "capture " + expected,
                            Map.of("D", expected),
                            1,
                            Map.of("Q", expected))));

            assertTrue(result.passed(), result.toString());
            assertEquals(expected, result.steps().getFirst().observedOutputs().get("Q"));
        }
    }

    @Test
    void unsetDAndUnresetDividerRemainUnknown() {
        Circuit unsetCircuit = new Circuit();
        InputPin unsetData = input(unsetCircuit, "unset-data");
        InputPin unsetClock = input(unsetCircuit, "unset-clock");
        SequentialParts.Outputs unsetFlipFlop =
                SequentialParts.dff(unsetCircuit, "unset-dff",
                        unsetData.output(), unsetClock.output());
        OutputProbe unsetQ = probe(unsetCircuit, "unset-q", unsetFlipFlop.q());

        SequenceRunner unsetRunner = runner(
                unsetCircuit, unsetClock, Map.of("D", unsetData), Map.of("Q", unsetQ));
        SequenceRunner.SequenceResult unsetResult = unsetRunner.run(List.of(
                new SequenceRunner.Step(
                        "unset data",
                        Map.of(),
                        4,
                        Map.of("Q", Value.X))));
        assertTrue(unsetResult.passed(), unsetResult.toString());

        Circuit divider = new Circuit();
        InputPin clock = input(divider, "clock");
        Gate invertedQ = gate(divider, "invert-q", GateType.NOT, 1);
        SequentialParts.Outputs flipFlop =
                SequentialParts.dff(divider, "divider", invertedQ.output(), clock.output());
        divider.connect(flipFlop.q(), invertedQ.input(0));
        OutputProbe q = probe(divider, "q", flipFlop.q());

        SequenceRunner dividerRunner = runner(
                divider, clock, Map.of(), Map.of("Q", q));
        SequenceRunner.SequenceResult dividerResult = dividerRunner.run(List.of(
                new SequenceRunner.Step(
                        "no reset",
                        Map.of(),
                        6,
                        Map.of("Q", Value.X))));
        assertTrue(dividerResult.passed(), dividerResult.toString());
    }

    @Test
    void resetDividerStartsKnownThenTogglesOncePerCycle() {
        Circuit circuit = new Circuit();
        InputPin resetN = input(circuit, "reset-n");
        InputPin clock = input(circuit, "clock");
        Gate invertedQ = gate(circuit, "invert-q", GateType.NOT, 1);
        Gate resetGate = gate(circuit, "reset-d", GateType.AND, 2);
        circuit.connect(invertedQ.output(), resetGate.input(0));
        circuit.connect(resetN.output(), resetGate.input(1));

        SequentialParts.Outputs flipFlop =
                SequentialParts.dff(circuit, "divider", resetGate.output(), clock.output());
        circuit.connect(flipFlop.q(), invertedQ.input(0));
        OutputProbe q = probe(circuit, "q", flipFlop.q());

        SequenceRunner runner = runner(
                circuit, clock, Map.of("RESET_N", resetN), Map.of("Q", q));
        SequenceRunner.SequenceResult result = runner.run(List.of(
                step("reset", Map.of("RESET_N", Value.ZERO), Value.ZERO),
                step("toggle 1", Map.of("RESET_N", Value.ONE), Value.ONE),
                step("toggle 2", Map.of("RESET_N", Value.ONE), Value.ZERO),
                step("toggle 3", Map.of("RESET_N", Value.ONE), Value.ONE),
                step("toggle 4", Map.of("RESET_N", Value.ONE), Value.ZERO)));

        assertTrue(result.passed(), result.toString());
        assertEquals(
                List.of(Value.ZERO, Value.ONE, Value.ZERO, Value.ONE, Value.ZERO),
                result.steps().stream()
                        .map(observation -> observation.observedOutputs().get("Q"))
                        .toList());
    }

    @Test
    void twoBitCounterCountsZeroOneTwoThreeThenWrapsToZero() {
        Circuit circuit = new Circuit();
        InputPin resetN = input(circuit, "reset-n");
        InputPin clock = input(circuit, "clock");

        Gate invertedQ0 = gate(circuit, "invert-q0", GateType.NOT, 1);
        Gate toggleQ1 = gate(circuit, "toggle-q1", GateType.XOR, 2);
        Gate resetD0 = gate(circuit, "reset-d0", GateType.AND, 2);
        Gate resetD1 = gate(circuit, "reset-d1", GateType.AND, 2);
        circuit.connect(invertedQ0.output(), resetD0.input(0));
        circuit.connect(resetN.output(), resetD0.input(1));
        circuit.connect(toggleQ1.output(), resetD1.input(0));
        circuit.connect(resetN.output(), resetD1.input(1));

        SequentialParts.Outputs bit0 =
                SequentialParts.dff(circuit, "bit0", resetD0.output(), clock.output());
        SequentialParts.Outputs bit1 =
                SequentialParts.dff(circuit, "bit1", resetD1.output(), clock.output());
        circuit.connect(bit0.q(), invertedQ0.input(0));
        circuit.connect(bit0.q(), toggleQ1.input(0));
        circuit.connect(bit1.q(), toggleQ1.input(1));

        OutputProbe q0 = probe(circuit, "q0", bit0.q());
        OutputProbe q1 = probe(circuit, "q1", bit1.q());
        SequenceRunner runner = runner(
                circuit, clock, Map.of("RESET_N", resetN), Map.of("Q0", q0, "Q1", q1));

        SequenceRunner.SequenceResult result = runner.run(List.of(
                counterStep("reset to 00", Value.ZERO, Value.ZERO, Value.ZERO),
                counterStep("count 01", Value.ONE, Value.ZERO, Value.ONE),
                counterStep("count 10", Value.ONE, Value.ONE, Value.ZERO),
                counterStep("count 11", Value.ONE, Value.ONE, Value.ONE),
                counterStep("wrap to 00", Value.ONE, Value.ZERO, Value.ZERO)));

        assertTrue(result.passed(), result.toString());
        assertEquals(
                List.of("00", "01", "10", "11", "00"),
                result.steps().stream()
                        .map(observation -> "" + asBit(observation.observedOutputs().get("Q1"))
                                + asBit(observation.observedOutputs().get("Q0")))
                        .toList());
    }

    @Test
    void threeBitShiftRegisterMovesOnePlacePerCycle() {
        Circuit circuit = new Circuit();
        InputPin resetN = input(circuit, "reset-n");
        InputPin data = input(circuit, "serial-data");
        InputPin clock = input(circuit, "clock");

        Gate d0 = gate(circuit, "d0-reset", GateType.AND, 2);
        Gate d1 = gate(circuit, "d1-reset", GateType.AND, 2);
        Gate d2 = gate(circuit, "d2-reset", GateType.AND, 2);
        circuit.connect(data.output(), d0.input(0));
        circuit.connect(resetN.output(), d0.input(1));

        SequentialParts.Outputs bit0 =
                SequentialParts.dff(circuit, "shift0", d0.output(), clock.output());
        SequentialParts.Outputs bit1 =
                SequentialParts.dff(circuit, "shift1", d1.output(), clock.output());
        SequentialParts.Outputs bit2 =
                SequentialParts.dff(circuit, "shift2", d2.output(), clock.output());

        circuit.connect(bit0.q(), d1.input(0));
        circuit.connect(resetN.output(), d1.input(1));
        circuit.connect(bit1.q(), d2.input(0));
        circuit.connect(resetN.output(), d2.input(1));

        OutputProbe q0 = probe(circuit, "q0", bit0.q());
        OutputProbe q1 = probe(circuit, "q1", bit1.q());
        OutputProbe q2 = probe(circuit, "q2", bit2.q());
        SequenceRunner runner = runner(
                circuit, clock, Map.of("RESET_N", resetN, "DATA", data),
                Map.of("Q0", q0, "Q1", q1, "Q2", q2));

        SequenceRunner.SequenceResult result = runner.run(List.of(
                shiftStep("reset to 000", Value.ZERO, Value.ZERO, "000"),
                shiftStep("insert bit", Value.ONE, Value.ONE, "001"),
                shiftStep("shift to middle", Value.ONE, Value.ZERO, "010"),
                shiftStep("shift to high", Value.ONE, Value.ZERO, "100"),
                shiftStep("shift out", Value.ONE, Value.ZERO, "000")));

        assertTrue(result.passed(), result.toString());
        assertEquals(
                List.of("000", "001", "010", "100", "000"),
                result.steps().stream()
                        .map(observation -> "" + asBit(observation.observedOutputs().get("Q2"))
                                + asBit(observation.observedOutputs().get("Q1"))
                                + asBit(observation.observedOutputs().get("Q0")))
                        .toList());
    }

    @Test
    void tffFromUnknownRemainsUnknownAndJkffCanForceZeroFromUnknown() {
        Circuit toggleCircuit = new Circuit();
        InputPin toggle = input(toggleCircuit, "toggle");
        InputPin toggleClock = input(toggleCircuit, "toggle-clock");
        SequentialParts.Outputs tff =
                SequentialParts.tff(toggleCircuit, "tff", toggle.output(), toggleClock.output());
        OutputProbe toggleQ = probe(toggleCircuit, "toggle-q", tff.q());
        SequenceRunner toggleRunner = runner(
                toggleCircuit, toggleClock, Map.of("T", toggle), Map.of("Q", toggleQ));

        SequenceRunner.SequenceResult toggleResult = toggleRunner.run(List.of(
                new SequenceRunner.Step(
                        "TFF without reset",
                        Map.of("T", Value.ONE),
                        5,
                        Map.of("Q", Value.X))));
        assertTrue(toggleResult.passed(), toggleResult.toString());

        Circuit jkCircuit = new Circuit();
        InputPin j = input(jkCircuit, "j");
        InputPin k = input(jkCircuit, "k");
        InputPin jkClock = input(jkCircuit, "jk-clock");
        SequentialParts.Outputs jkff = SequentialParts.jkff(
                jkCircuit, "jkff", j.output(), k.output(), jkClock.output());
        OutputProbe jkQ = probe(jkCircuit, "jk-q", jkff.q());
        SequenceRunner jkRunner = runner(
                jkCircuit, jkClock, Map.of("J", j, "K", k), Map.of("Q", jkQ));

        SequenceRunner.SequenceResult jkResult = jkRunner.run(List.of(
                new SequenceRunner.Step(
                        "JK reset from unknown",
                        Map.of("J", Value.ZERO, "K", Value.ONE),
                        1,
                        Map.of("Q", Value.ZERO))));
        assertTrue(jkResult.passed(), jkResult.toString());
        assertEquals(Value.ZERO, jkResult.steps().getFirst().observedOutputs().get("Q"));
    }

    @Test
    void dLatchIsTransparentWhenEnabledAndHoldsWhenDisabled() {
        Circuit circuit = new Circuit();
        InputPin data = input(circuit, "data");
        InputPin enable = input(circuit, "enable");
        SequentialParts.Outputs latch =
                SequentialParts.dLatch(circuit, "latch", data.output(), enable.output());
        OutputProbe q = probe(circuit, "q", latch.q());
        OutputProbe qn = probe(circuit, "qn", latch.qn());

        Simulator simulator = new Simulator(circuit);
        simulator.setInputValue(enable, Value.ZERO);
        simulator.settle();
        assertEquals(Value.X, q.value());
        assertEquals(Value.X, qn.value());

        simulator.setInputValue(data, Value.ZERO);
        simulator.setInputValue(enable, Value.ONE);
        simulator.settle();
        assertEquals(Value.ZERO, q.value());
        assertEquals(Value.ONE, qn.value());

        simulator.setInputValue(data, Value.ONE);
        simulator.settle();
        assertEquals(Value.ONE, q.value());
        assertEquals(Value.ZERO, qn.value());

        simulator.setInputValue(enable, Value.ZERO);
        simulator.setInputValue(data, Value.ZERO);
        simulator.settle();
        assertEquals(Value.ONE, q.value());
        assertEquals(Value.ZERO, qn.value());

        simulator.setInputValue(enable, Value.ONE);
        simulator.settle();
        assertEquals(Value.ZERO, q.value());
        assertEquals(Value.ONE, qn.value());
    }

    private static SequenceRunner.Step step(String name, Map<String, Value> inputs, Value q) {
        return new SequenceRunner.Step(name, inputs, 1, Map.of("Q", q));
    }

    private static SequenceRunner.Step counterStep(
            String name, Value resetN, Value q1, Value q0) {
        return new SequenceRunner.Step(
                name,
                Map.of("RESET_N", resetN),
                1,
                Map.of("Q1", q1, "Q0", q0));
    }

    private static SequenceRunner.Step shiftStep(
            String name, Value resetN, Value data, String expected) {
        return new SequenceRunner.Step(
                name,
                Map.of("RESET_N", resetN, "DATA", data),
                1,
                Map.of(
                        "Q2", bit(expected.charAt(0)),
                        "Q1", bit(expected.charAt(1)),
                        "Q0", bit(expected.charAt(2))));
    }

    private static char asBit(Value value) {
        return switch (value) {
            case ZERO -> '0';
            case ONE -> '1';
            case X -> 'X';
        };
    }

    private static Value bit(char value) {
        return value == '1' ? Value.ONE : Value.ZERO;
    }

    private static SequenceRunner runner(
            Circuit circuit,
            InputPin clock,
            Map<String, InputPin> otherInputs,
            Map<String, OutputProbe> outputs) {
        Map<String, InputPin> namedInputs = new LinkedHashMap<>(otherInputs);
        namedInputs.put("CLK", clock);
        return new SequenceRunner(circuit, namedInputs, "CLK", outputs);
    }

    private static InputPin input(Circuit circuit, String id) {
        InputPin input = new InputPin(id);
        circuit.addComponent(input);
        return input;
    }

    private static OutputProbe probe(Circuit circuit, String id, Pin source) {
        OutputProbe probe = new OutputProbe(id);
        circuit.addComponent(probe);
        circuit.connect(source, probe.input());
        return probe;
    }

    private static Gate gate(Circuit circuit, String id, GateType type, int inputs) {
        Gate gate = new Gate(id, type, inputs);
        circuit.addComponent(gate);
        return gate;
    }
}
