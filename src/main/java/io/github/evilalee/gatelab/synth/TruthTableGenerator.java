package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.model.Circuit;
import io.github.evilalee.gatelab.model.Gate;
import io.github.evilalee.gatelab.model.GateType;
import io.github.evilalee.gatelab.model.InputPin;
import io.github.evilalee.gatelab.model.OutputProbe;
import io.github.evilalee.gatelab.model.Pin;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.Simulator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds an engine Circuit directly from a netlist and enumerates all input assignments. */
public final class TruthTableGenerator {
    private TruthTableGenerator() {
    }

    public static TruthTable generate(Netlist netlist) {
        Objects.requireNonNull(netlist, "netlist");

        Circuit circuit = new Circuit();
        Map<String, InputPin> inputComponents = new LinkedHashMap<>();
        Map<String, Pin> signalOutputs = new LinkedHashMap<>();
        for (String inputName : netlist.inputs()) {
            InputPin input = new InputPin(inputName);
            circuit.addComponent(input);
            inputComponents.put(inputName, input);
            signalOutputs.put(inputName, input.output());
        }

        for (Netlist.Gate definition : netlist.topologicalGates()) {
            Gate gate = new Gate(
                    definition.id(),
                    GateType.valueOf(definition.type().name()),
                    definition.inputs().size());
            circuit.addComponent(gate);
            for (int index = 0; index < definition.inputs().size(); index++) {
                Pin source = signalOutputs.get(definition.inputs().get(index));
                circuit.connect(source, gate.input(index));
            }
            signalOutputs.put(definition.id(), gate.output());
        }

        List<OutputProbe> probes = new ArrayList<>(netlist.outputs().size());
        for (int index = 0; index < netlist.outputs().size(); index++) {
            Netlist.Output definition = netlist.outputs().get(index);
            if (definition.source().equals("0") || definition.source().equals("1")) {
                probes.add(null);
            } else {
                // The prefix is not a valid netlist identifier, so it cannot collide with a signal id.
                OutputProbe probe = new OutputProbe("_synth_probe_" + index);
                circuit.addComponent(probe);
                circuit.connect(signalOutputs.get(definition.source()), probe.input());
                probes.add(probe);
            }
        }

        Simulator simulator = new Simulator(circuit);
        int rowCount = 1 << netlist.inputs().size();
        List<TruthTable.Row> rows = new ArrayList<>(rowCount);
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            StringBuilder bits = new StringBuilder(netlist.inputs().size());
            for (int inputIndex = 0; inputIndex < netlist.inputs().size(); inputIndex++) {
                int shift = netlist.inputs().size() - 1 - inputIndex;
                boolean high = ((rowIndex >>> shift) & 1) != 0;
                bits.append(high ? '1' : '0');
                simulator.setInputValue(inputComponents.get(netlist.inputs().get(inputIndex)),
                        high ? Value.ONE : Value.ZERO);
            }

            simulator.settle();
            List<Value> outputValues = new ArrayList<>(netlist.outputs().size());
            for (int outputIndex = 0; outputIndex < netlist.outputs().size(); outputIndex++) {
                String source = netlist.outputs().get(outputIndex).source();
                Value value;
                if (source.equals("0")) {
                    value = Value.ZERO;
                } else if (source.equals("1")) {
                    value = Value.ONE;
                } else {
                    value = simulator.getProbeValue(probes.get(outputIndex));
                }
                outputValues.add(value);
            }
            rows.add(new TruthTable.Row(
                    rowIndex, bits.toString(), outputValues, simulator.isOscillating()));
        }

        return new TruthTable(
                netlist.inputs(),
                netlist.outputs().stream().map(Netlist.Output::name).toList(),
                rows);
    }
}
