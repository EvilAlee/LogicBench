package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.model.Circuit;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Gate;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateType;
import io.github.evilalee.gatelab.model.InputPin;
import io.github.evilalee.gatelab.model.OutputProbe;
import io.github.evilalee.gatelab.model.Pin;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.WireSegment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Compiles geometric schematic nets into a fresh engine pin graph. */
public final class SchematicCompiler {
    private SchematicCompiler() {
    }

    public static SimulationResult compileAndSettle(Schematic schematic) {
        return compileAndSettle(schematic, null);
    }

    /**
     * Every call creates a fresh Circuit and Simulator. Previous settled gate
     * drivers seed only the new graph, allowing feedback latches to hold state.
     */
    public static SimulationResult compileAndSettle(
            Schematic schematic, SimulationResult previous) {
        Objects.requireNonNull(schematic, "schematic");

        Circuit circuit = new Circuit();
        Map<PinKey, Pin> pinsByKey = new LinkedHashMap<>();
        Map<String, InputPin> inputs = new LinkedHashMap<>();
        Map<String, OutputProbe> outputs = new LinkedHashMap<>();
        Map<String, Gate> gates = new LinkedHashMap<>();

        for (PlacedComponent placed : schematic.components()) {
            GateDef definition = schematic.definitionFor(placed);
            switch (placed.typeId()) {
                case "input" -> {
                    InputPin input = new InputPin(placed.id());
                    circuit.addComponent(input);
                    inputs.put(placed.id(), input);
                    pinsByKey.put(new PinKey(placed.id(), "out"), input.output());
                }
                case "output" -> {
                    OutputProbe output = new OutputProbe(placed.id());
                    circuit.addComponent(output);
                    outputs.put(placed.id(), output);
                    pinsByKey.put(new PinKey(placed.id(), "in0"), output.input());
                }
                default -> {
                    Gate gate = new Gate(placed.id(),
                            GateType.valueOf(placed.typeId().toUpperCase(java.util.Locale.ROOT)),
                            definition.inputs().size());
                    circuit.addComponent(gate);
                    gates.put(placed.id(), gate);
                    for (int i=0; i<gate.inputs().size(); i++) {
                        pinsByKey.put(new PinKey(placed.id(), "in"+i), gate.input(i));
                    }
                    pinsByKey.put(new PinKey(placed.id(), "out"), gate.output());
                }
            }
        }

        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        for (Connectivity.Net net : topology.nets()) {
            Pin first = null;
            for (Connectivity.PinPosition position : net.pins()) {
                Pin current = pinsByKey.get(new PinKey(position.componentId(), position.pinId()));
                if (current == null) throw new IllegalStateException("no engine pin for "+position);
                if (first == null) first = current;
                else if (first != current) circuit.connect(first, current);
            }
        }

        Simulator simulator = new Simulator(circuit);
        simulator.setInitialGateOutputValues(
                previous == null ? Map.of() : previous.gateOutputValues());
        for (Map.Entry<String, InputPin> entry : inputs.entrySet()) {
            simulator.setInputValue(entry.getValue(),
                    schematic.inputValue(entry.getKey()) ? Value.ONE : Value.ZERO);
        }
        simulator.settle();

        Map<Connectivity.Net, Value> netValues = new LinkedHashMap<>();
        Map<WireSegment, Value> wireValues = new LinkedHashMap<>();
        for (Connectivity.Net net : topology.nets()) {
            Pin representative = null;
            for (Connectivity.PinPosition position : net.pins()) {
                representative = pinsByKey.get(new PinKey(position.componentId(), position.pinId()));
                if (representative != null) break;
            }
            Value value = representative == null ? Value.X : simulator.getNetValue(representative);
            netValues.put(net, value);
            for (WireSegment segment : net.segments()) wireValues.put(segment, value);
        }

        Map<String, Value> inputValues = new LinkedHashMap<>();
        for (String id : inputs.keySet()) {
            inputValues.put(id, schematic.inputValue(id) ? Value.ONE : Value.ZERO);
        }
        Map<String, Value> outputValues = new LinkedHashMap<>();
        for (Map.Entry<String, OutputProbe> entry : outputs.entrySet()) {
            outputValues.put(entry.getKey(), entry.getValue().value());
        }
        Map<String, Value> gateOutputValues = new LinkedHashMap<>();
        for (Map.Entry<String, Gate> entry : gates.entrySet()) {
            gateOutputValues.put(entry.getKey(), simulator.getGateOutputValue(entry.getValue()));
        }

        return new SimulationResult(topology, netValues, wireValues, outputValues,
                inputValues, gateOutputValues, simulator.isOscillating());
    }

    private record PinKey(String componentId, String pinId) { }

    /** Immutable values rendered by the UI and reused for stateful rebuilds. */
    public record SimulationResult(
            Connectivity.Result topology,
            Map<Connectivity.Net, Value> netValues,
            Map<WireSegment, Value> wireValues,
            Map<String, Value> outputValues,
            Map<String, Value> inputValues,
            Map<String, Value> gateOutputValues,
            boolean oscillating) {
        public SimulationResult {
            Objects.requireNonNull(topology, "topology");
            netValues = Map.copyOf(Objects.requireNonNull(netValues, "netValues"));
            wireValues = Map.copyOf(Objects.requireNonNull(wireValues, "wireValues"));
            outputValues = Map.copyOf(Objects.requireNonNull(outputValues, "outputValues"));
            inputValues = Map.copyOf(Objects.requireNonNull(inputValues, "inputValues"));
            gateOutputValues = Map.copyOf(Objects.requireNonNull(gateOutputValues, "gateOutputValues"));
        }
        public Value valueOf(WireSegment wire) {
            return wireValues.getOrDefault(Objects.requireNonNull(wire, "wire"), Value.X);
        }
    }
}
