package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.model.Circuit;
import io.github.evilalee.gatelab.model.Gate;
import io.github.evilalee.gatelab.model.GateType;
import io.github.evilalee.gatelab.model.InputPin;
import io.github.evilalee.gatelab.model.OutputProbe;
import io.github.evilalee.gatelab.model.Value;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatorTest {
    @Test
    void norLatchCanSetHoldAndReset() {
        Circuit circuit = new Circuit();
        InputPin set = new InputPin("set");
        InputPin reset = new InputPin("reset");
        Gate qGate = new Gate("qNor", GateType.NOR, 2);
        Gate qBarGate = new Gate("qBarNor", GateType.NOR, 2);
        OutputProbe q = new OutputProbe("q");
        OutputProbe qBar = new OutputProbe("qBar");

        for (var component : List.of(set, reset, qGate, qBarGate, q, qBar)) {
            circuit.addComponent(component);
        }

        circuit.connect(reset.output(), qGate.input(0));
        circuit.connect(qBarGate.output(), qGate.input(1));
        circuit.connect(set.output(), qBarGate.input(0));
        circuit.connect(qGate.output(), qBarGate.input(1));
        circuit.connect(qGate.output(), q.input());
        circuit.connect(qBarGate.output(), qBar.input());

        Simulator simulator = new Simulator(circuit);
        simulator.setInputValue(set, Value.ONE);
        simulator.setInputValue(reset, Value.ZERO);
        simulator.settle();
        assertEquals(Value.ONE, q.value());
        assertEquals(Value.ZERO, qBar.value());

        simulator.setInputValue(set, Value.ZERO);
        simulator.settle();
        assertEquals(Value.ONE, simulator.getProbeValue(q));
        assertEquals(Value.ZERO, simulator.getProbeValue(qBar));

        simulator.setInputValue(reset, Value.ONE);
        simulator.settle();
        assertEquals(Value.ZERO, q.value());
        assertEquals(Value.ONE, qBar.value());
    }

    @Test
    void threeInverterRingReturnsAtTheTickLimitAndReportsOscillation() {
        Circuit circuit = new Circuit();
        List<Gate> gates = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Gate gate = new Gate("not" + index, GateType.NOT, 1);
            gates.add(gate);
            circuit.addComponent(gate);
        }
        for (int index = 0; index < gates.size(); index++) {
            Gate current = gates.get(index);
            Gate next = gates.get((index + 1) % gates.size());
            circuit.connect(current.output(), next.input(0));
        }

        int tickLimit = 32;
        Simulator simulator = new Simulator(circuit, tickLimit);
        simulator.settle();

        assertTrue(simulator.isOscillating());
        assertEquals(tickLimit, simulator.ticksUsed());

        simulator.settle();
        assertTrue(simulator.isOscillating(), "pending oscillation events must survive settle()");
    }

    @Test
    void threeFourAndEightInputGatesPropagateThroughTheEngine() {
        for (int inputCount : new int[]{3, 4, 8}) {
            Circuit circuit = new Circuit();
            List<InputPin> inputs = new ArrayList<>();
            Gate gate = new Gate("and" + inputCount, GateType.AND, inputCount);
            OutputProbe output = new OutputProbe("out" + inputCount);
            circuit.addComponent(gate);
            circuit.addComponent(output);

            for (int index = 0; index < inputCount; index++) {
                InputPin input = new InputPin("in" + inputCount + "_" + index);
                inputs.add(input);
                circuit.addComponent(input);
                circuit.connect(input.output(), gate.input(index));
            }
            circuit.connect(gate.output(), output.input());

            Simulator simulator = new Simulator(circuit);
            inputs.forEach(input -> simulator.setInputValue(input, Value.ONE));
            simulator.settle();
            assertEquals(Value.ONE, output.value());

            simulator.setInputValue(inputs.get(0), Value.X);
            simulator.settle();
            assertEquals(Value.X, output.value());

            simulator.setInputValue(inputs.get(0), Value.ZERO);
            simulator.settle();
            assertEquals(Value.ZERO, output.value());
        }
    }

    @Test
    void opposingInputDriversResolveAsUnknown() {
        Circuit circuit = new Circuit();
        InputPin low = new InputPin("low");
        InputPin high = new InputPin("high");
        OutputProbe probe = new OutputProbe("probe");
        circuit.addComponent(low);
        circuit.addComponent(high);
        circuit.addComponent(probe);
        circuit.connect(low.output(), high.output());
        circuit.connect(high.output(), probe.input());

        Simulator simulator = new Simulator(circuit);
        simulator.setInputValue(low, Value.ZERO);
        simulator.setInputValue(high, Value.ONE);
        simulator.settle();

        assertEquals(Value.X, probe.value());
    }
}
