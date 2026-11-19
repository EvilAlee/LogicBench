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
import io.github.evilalee.gatelab.model.UndefinedInputMode;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.WireSegment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        Set<PinKey> barePins = new HashSet<>();
        for (Connectivity.Net net : topology.nets()) {
            if (net.segments().isEmpty() && net.pins().size() == 1) {
                Connectivity.PinPosition onlyPin = net.pins().iterator().next();
                barePins.add(new PinKey(onlyPin.componentId(), onlyPin.pinId()));
            }
        }

        Circuit circuit = new Circuit();
        Map<PinKey, Pin> pinsByKey = new LinkedHashMap<>();
        Map<String, InputPin> inputs = new LinkedHashMap<>();
        Set<String> clockIds = new LinkedHashSet<>();
        Map<String, OutputProbe> outputs = new LinkedHashMap<>();
        Map<String, Gate> gates = new LinkedHashMap<>();
        Set<String> gateIds = new LinkedHashSet<>();
        Set<PinKey> ignoredInputs = new HashSet<>();
        boolean ignoreBareInputs =
                schematic.undefinedInputMode() == UndefinedInputMode.IGNORE;

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
                case "clock" -> {
                    InputPin clock = new InputPin(placed.id());
                    circuit.addComponent(clock);
                    inputs.put(placed.id(), clock);
                    clockIds.add(placed.id());
                    pinsByKey.put(new PinKey(placed.id(), "out"), clock.output());
                }
                default -> {
                    gateIds.add(placed.id());
                    List<Integer> retainedInputIndexes = new ArrayList<>();
                    for (int index = 0; index < definition.inputs().size(); index++) {
                        PinKey key = new PinKey(placed.id(), "in" + index);
                        if (ignoreBareInputs && supportsUnconnectedInputPruning(placed.typeId())
                                && barePins.contains(key)) {
                            ignoredInputs.add(key);
                        } else {
                            retainedInputIndexes.add(index);
                        }
                    }

                    if (retainedInputIndexes.isEmpty()) {
                        // Preserve the gate's output net as a passive terminal, without
                        // introducing a driver for a gate that has no defined inputs.
                        OutputProbe undrivenOutput = new OutputProbe(placed.id());
                        circuit.addComponent(undrivenOutput);
                        pinsByKey.put(new PinKey(placed.id(), "out"), undrivenOutput.input());
                    } else {
                        GateType gateType = GateType.valueOf(
                                placed.typeId().toUpperCase(java.util.Locale.ROOT));
                        if (retainedInputIndexes.size() == 1
                                && supportsUnconnectedInputPruning(placed.typeId())) {
                            gateType = singleInputEquivalent(gateType);
                        }
                        Gate gate = new Gate(
                                placed.id(), gateType, retainedInputIndexes.size());
                        circuit.addComponent(gate);
                        gates.put(placed.id(), gate);
                        for (int engineIndex = 0;
                                engineIndex < retainedInputIndexes.size(); engineIndex++) {
                            int schematicIndex = retainedInputIndexes.get(engineIndex);
                            pinsByKey.put(new PinKey(placed.id(), "in" + schematicIndex),
                                    gate.input(engineIndex));
                        }
                        pinsByKey.put(new PinKey(placed.id(), "out"), gate.output());
                    }
                }
            }
        }

        for (Connectivity.Net net : topology.nets()) {
            Pin first = null;
            for (Connectivity.PinPosition position : net.pins()) {
                PinKey key = new PinKey(position.componentId(), position.pinId());
                Pin current = pinsByKey.get(key);
                if (current == null && ignoredInputs.contains(key)) {
                    continue;
                }
                if (current == null) {
                    throw new IllegalStateException("no engine pin for " + position);
                }
                if (first == null) {
                    first = current;
                } else if (first != current) {
                    circuit.connect(first, current);
                }
            }
        }

        Simulator simulator = new Simulator(circuit);
        simulator.setInitialGateOutputValues(
                previous == null ? Map.of() : previous.gateOutputValues());
        for (Map.Entry<String, InputPin> entry : inputs.entrySet()) {
            boolean high = clockIds.contains(entry.getKey())
                    ? schematic.clockValue(entry.getKey())
                    : schematic.inputValue(entry.getKey());
            simulator.setInputValue(entry.getValue(), high ? Value.ONE : Value.ZERO);
        }
        simulator.settle();

        Map<Connectivity.Net, Value> netValues = new LinkedHashMap<>();
        Map<WireSegment, Value> wireValues = new LinkedHashMap<>();
        for (Connectivity.Net net : topology.nets()) {
            Pin representative = null;
            for (Connectivity.PinPosition position : net.pins()) {
                representative = pinsByKey.get(new PinKey(position.componentId(), position.pinId()));
                if (representative != null) {
                    break;
                }
            }
            Value value = representative == null ? Value.X : simulator.getNetValue(representative);
            netValues.put(net, value);
            for (WireSegment segment : net.segments()) {
                wireValues.put(segment, value);
            }
        }

        Map<String, Value> inputValues = new LinkedHashMap<>();
        for (String id : inputs.keySet()) {
            boolean high = clockIds.contains(id)
                    ? schematic.clockValue(id) : schematic.inputValue(id);
            inputValues.put(id, high ? Value.ONE : Value.ZERO);
        }
        Map<String, Value> outputValues = new LinkedHashMap<>();
        for (Map.Entry<String, OutputProbe> entry : outputs.entrySet()) {
            outputValues.put(entry.getKey(), entry.getValue().value());
        }
        Map<String, Value> gateOutputValues = new LinkedHashMap<>();
        for (String id : gateIds) {
            Gate gate = gates.get(id);
            gateOutputValues.put(id,
                    gate == null ? Value.X : simulator.getGateOutputValue(gate));
        }

        return new SimulationResult(topology, netValues, wireValues, outputValues,
                inputValues, gateOutputValues, simulator.isOscillating());
    }

    private static boolean supportsUnconnectedInputPruning(String typeId) {
        return switch (typeId) {
            case "and", "or", "nand", "nor", "xor", "xnor" -> true;
            default -> false;
        };
    }

    private static GateType singleInputEquivalent(GateType type) {
        return switch (type) {
            case AND, OR, XOR -> GateType.BUFFER;
            case NAND, NOR, XNOR -> GateType.NOT;
            default -> throw new IllegalArgumentException(
                    "no single-input equivalent for " + type);
        };
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
            return wireValues.getOrDefault(
                    Objects.requireNonNull(wire, "wire"), Value.X);
        }
    }
}
