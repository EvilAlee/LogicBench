package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.model.Circuit;
import io.github.evilalee.gatelab.model.Gate;
import io.github.evilalee.gatelab.model.GateType;
import io.github.evilalee.gatelab.model.InputPin;
import io.github.evilalee.gatelab.model.OutputProbe;
import io.github.evilalee.gatelab.model.Pin;
import io.github.evilalee.gatelab.model.Value;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatorTest {
    @Test
    void unknownNorLatchCanSetHoldAndResetWithoutFalseOscillation() {
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
        simulator.setInputValue(set, Value.ZERO);
        simulator.setInputValue(reset, Value.ZERO);

        // Building the topology seeds gate drivers to X, not an arbitrary zero.
        assertEquals(Value.X, simulator.getNetValue(qGate.output()));
        assertEquals(Value.X, simulator.getNetValue(qBarGate.output()));
        simulator.settle();
        assertEquals(Value.X, q.value());
        assertEquals(Value.X, qBar.value());
        assertFalse(simulator.isOscillating());

        simulator.setInputValue(set, Value.ONE);
        simulator.settle();
        assertEquals(Value.ONE, q.value());
        assertEquals(Value.ZERO, qBar.value());
        assertFalse(simulator.isOscillating());

        simulator.setInputValue(set, Value.ZERO);
        simulator.settle();
        assertEquals(Value.ONE, q.value());
        assertEquals(Value.ZERO, qBar.value());
        assertFalse(simulator.isOscillating());

        simulator.setInputValue(reset, Value.ONE);
        simulator.settle();
        assertEquals(Value.ZERO, q.value());
        assertEquals(Value.ONE, qBar.value());
        assertFalse(simulator.isOscillating());
    }

    @Test
    void nandAndTwoInverterRingSettlesWithZeroControlThenOscillatesWithOne() {
        Circuit circuit = new Circuit();
        InputPin control = new InputPin("control");
        Gate nand = new Gate("nand", GateType.NAND, 2);
        Gate firstInverter = new Gate("not1", GateType.NOT, 1);
        Gate secondInverter = new Gate("not2", GateType.NOT, 1);

        circuit.addComponent(control);
        circuit.addComponent(nand);
        circuit.addComponent(firstInverter);
        circuit.addComponent(secondInverter);

        circuit.connect(secondInverter.output(), nand.input(0));
        circuit.connect(control.output(), nand.input(1));
        circuit.connect(nand.output(), firstInverter.input(0));
        circuit.connect(firstInverter.output(), secondInverter.input(0));

        int tickLimit = 32;
        Simulator simulator = new Simulator(circuit, tickLimit);
        simulator.setInputValue(control, Value.ZERO);
        simulator.settle();

        assertFalse(simulator.isOscillating());
        assertEquals(Value.ONE, simulator.getNetValue(nand.output()));
        assertEquals(Value.ZERO, simulator.getNetValue(firstInverter.output()));
        assertEquals(Value.ONE, simulator.getNetValue(secondInverter.output()));

        simulator.setInputValue(control, Value.ONE);
        simulator.settle();
        assertTrue(simulator.isOscillating());
        assertEquals(tickLimit, simulator.ticksUsed());
    }

    @Test
    void longBufferChainUsesASizeScaledDefaultTickLimit() {
        Circuit circuit = new Circuit();
        InputPin input = new InputPin("input");
        OutputProbe output = new OutputProbe("output");
        circuit.addComponent(input);

        Pin previousOutput = input.output();
        int gateCount = 500;
        for (int index = 0; index < gateCount; index++) {
            Gate buffer = new Gate("buffer" + index, GateType.BUFFER, 1);
            circuit.addComponent(buffer);
            circuit.connect(previousOutput, buffer.input(0));
            previousOutput = buffer.output();
        }

        circuit.addComponent(output);
        circuit.connect(previousOutput, output.input());

        Simulator simulator = new Simulator(circuit);
        simulator.setInputValue(input, Value.ONE);
        simulator.settle();

        assertEquals(Value.ONE, output.value());
        assertFalse(simulator.isOscillating());
        assertEquals(gateCount, simulator.ticksUsed());
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
