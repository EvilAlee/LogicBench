package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.model.Circuit;
import io.github.evilalee.gatelab.model.Component;
import io.github.evilalee.gatelab.model.Gate;
import io.github.evilalee.gatelab.model.GateType;
import io.github.evilalee.gatelab.model.InputPin;
import io.github.evilalee.gatelab.model.OutputProbe;
import io.github.evilalee.gatelab.model.Pin;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SchematicCompiler.CompiledCircuit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitParallelEvaluatorTest {
    @Test
    @Tag("slow")
    void differentialRandomDagCircuitsMatchTheEngineOnEveryRow() {
        SplittableRandom random = new SplittableRandom(0x16B17L);
        for (int trial = 0; trial < 40; trial++) {
            int inputCount = 1 + random.nextInt(8);
            int gateCount = random.nextInt(1, 31);
            Circuit circuit = new Circuit();
            Map<String, InputPin> inputComponents = new LinkedHashMap<>();
            Map<String, Pin> signals = new LinkedHashMap<>();
            Map<InputPin, Value> fixed = new IdentityHashMap<>();

            for (int index = 0; index < inputCount; index++) {
                String name = "A" + index;
                InputPin input = new InputPin(name);
                circuit.addComponent(input);
                inputComponents.put(name, input);
                signals.put(name, input.output());
            }
            InputPin zero = new InputPin("_zero");
            zero.setValue(Value.ZERO);
            circuit.addComponent(zero);
            fixed.put(zero, Value.ZERO);
            signals.put("0", zero.output());
            InputPin one = new InputPin("_one");
            one.setValue(Value.ONE);
            circuit.addComponent(one);
            fixed.put(one, Value.ONE);
            signals.put("1", one.output());

            GateType[] types = GateType.values();
            for (int index = 0; index < gateCount; index++) {
                GateType type = types[random.nextInt(types.length)];
                int count = type.minimumInputs() == type.maximumInputs()
                        ? type.minimumInputs()
                        : type.minimumInputs() + random.nextInt(
                                type.maximumInputs() - type.minimumInputs() + 1);
                Gate gate = new Gate("g" + index, type, count);
                circuit.addComponent(gate);
                List<String> available = List.copyOf(signals.keySet());
                for (int input = 0; input < count; input++) {
                    String source = available.get(random.nextInt(available.size()));
                    circuit.connect(signals.get(source), gate.input(input));
                }
                signals.put(gate.id(), gate.output());
            }

            Map<String, OutputProbe> outputs = new LinkedHashMap<>();
            Map<String, String> inputIds = new LinkedHashMap<>();
            inputComponents.keySet().forEach(name -> inputIds.put(name, name));
            Map<String, String> outputIds = new LinkedHashMap<>();
            int outputCount = 1 + random.nextInt(4);
            for (int index = 0; index < outputCount; index++) {
                String name = "Y" + index;
                OutputProbe probe = new OutputProbe("probe" + index);
                circuit.addComponent(probe);
                String source = List.copyOf(signals.keySet())
                        .get(random.nextInt(signals.size()));
                circuit.connect(signals.get(source), probe.input());
                outputs.put(probe.id(), probe);
                outputIds.put(name, probe.id());
            }

            CompiledCircuit compiled = new CompiledCircuit(
                    circuit, inputComponents, java.util.Set.of(), outputs, fixed);
            BitParallelEvaluator.Result parallel =
                    BitParallelEvaluator.evaluate(compiled, inputIds, outputIds);
            Simulator engine = new Simulator(circuit);
            int rows = 1 << inputCount;
            assertEquals(rows, parallel.rowCount(), "trial " + trial);
            for (int row = 0; row < rows; row++) {
                for (int index = 0; index < inputCount; index++) {
                    String name = "A" + index;
                    boolean high = ((row >>> (inputCount - index - 1)) & 1) != 0;
                    engine.setInputValue(inputComponents.get(name), high ? Value.ONE : Value.ZERO);
                }
                engine.settle();
                for (Map.Entry<String, String> entry : outputIds.entrySet()) {
                    OutputProbe probe = outputs.get(entry.getValue());
                    assertEquals(engine.getProbeValue(probe), parallel.outputValue(entry.getKey(), row),
                            "trial " + trial + ", row " + row + ", " + entry.getKey());
                }
            }
        }
    }

    @Test
    void constantsAndUnknownUnconnectedNetsUseThreeValuedSemantics() {
        Circuit circuit = new Circuit();
        InputPin input = new InputPin("A");
        circuit.addComponent(input);
        Gate and = new Gate("g0", GateType.AND, 2);
        circuit.addComponent(and);
        circuit.connect(input.output(), and.input(0));
        InputPin zero = new InputPin("_zero");
        zero.setValue(Value.ZERO);
        circuit.addComponent(zero);
        circuit.connect(zero.output(), and.input(1));
        OutputProbe output = new OutputProbe("Y");
        circuit.addComponent(output);
        circuit.connect(and.output(), output.input());
        CompiledCircuit compiled = new CompiledCircuit(
                circuit, Map.of("A", input), java.util.Set.of(), Map.of("Y", output),
                Map.of(zero, Value.ZERO));

        BitParallelEvaluator.Result parallel =
                BitParallelEvaluator.evaluate(compiled, Map.of("A", "A"), Map.of("Y", "Y"));
        assertEquals(Value.ZERO, parallel.outputValue("Y", 0));
        assertEquals(Value.ZERO, parallel.outputValue("Y", 1));
        assertTrue(parallel.isKnown("Y", 0));
    }
}
