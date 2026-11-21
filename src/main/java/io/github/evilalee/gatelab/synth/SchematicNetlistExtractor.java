package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Converts the current geometric schematic into a validated combinational netlist. */
public final class SchematicNetlistExtractor {
    private record PinKey(String componentId, String pinId) {
    }

    public record Extraction(
            Netlist netlist,
            Map<String, String> inputComponentIds,
            Map<String, String> outputComponentIds) {
        public Extraction {
            Objects.requireNonNull(netlist, "netlist");
            inputComponentIds = orderedCopy(inputComponentIds);
            outputComponentIds = orderedCopy(outputComponentIds);
        }

        private static Map<String, String> orderedCopy(Map<String, String> source) {
            return java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(source, "source")));
        }
    }

    private SchematicNetlistExtractor() {
    }

    /**
     * Extracts inputs and outputs in their current visual order. Every gate input
     * and output probe must have exactly one driver; gate outputs may be unused.
     */
    public static Extraction extract(Schematic schematic) {
        Objects.requireNonNull(schematic, "schematic");
        if (!schematic.componentInstances().isEmpty()) {
            throw new IllegalArgumentException(
                    "Expression analysis does not support component instances.");
        }
        List<PlacedComponent> components = schematic.components();
        Map<String, PlacedComponent> byId = new LinkedHashMap<>();
        List<PlacedComponent> inputs = new ArrayList<>();
        List<PlacedComponent> outputs = new ArrayList<>();
        List<PlacedComponent> gates = new ArrayList<>();

        for (PlacedComponent component : components) {
            byId.put(component.id(), component);
            switch (component.typeId()) {
                case "input" -> inputs.add(component);
                case "output" -> outputs.add(component);
                default -> {
                    GateOperator operator;
                    try {
                        operator = GateOperator.valueOf(
                                component.typeId().toUpperCase(java.util.Locale.ROOT));
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalArgumentException(
                                "Unsupported component: " + component.typeId());
                    }
                    int actualInputs = schematic.definitionFor(component).inputs().size();
                    if (actualInputs != operator.inputCount()) {
                        throw new IllegalArgumentException(
                                "Unsupported input count for " + component.typeId()
                                        + ": expected " + operator.inputCount()
                                        + ", found " + actualInputs);
                    }
                    gates.add(component);
                }
            }
        }

        Comparator<PlacedComponent> visualOrder = Comparator
                .comparingInt(PlacedComponent::y)
                .thenComparingInt(PlacedComponent::x)
                .thenComparing(PlacedComponent::id);
        inputs.sort(visualOrder);
        outputs.sort(visualOrder);
        gates.sort(visualOrder);
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Tidy circuit requires at least one Output component.");
        }

        Map<String, String> inputNames = assignNames(inputs, "In", true);
        Map<String, String> outputNames = assignNames(outputs, "Out", false);
        Map<String, String> inputIds = new LinkedHashMap<>();
        Map<String, String> outputIds = new LinkedHashMap<>();
        for (PlacedComponent input : inputs) {
            inputIds.put(inputNames.get(input.id()), input.id());
        }
        for (PlacedComponent output : outputs) {
            outputIds.put(outputNames.get(output.id()), output.id());
        }

        Map<String, String> gateSignals = new LinkedHashMap<>();
        Set<String> reservedSignals = new HashSet<>(inputNames.values());
        int nextGate = 1;
        for (PlacedComponent gate : gates) {
            String signal;
            do {
                signal = "g" + nextGate++;
            } while (!reservedSignals.add(signal));
            gateSignals.put(gate.id(), signal);
        }

        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        Map<PinKey, Connectivity.Net> netByPin = new HashMap<>();
        Map<Connectivity.Net, List<Connectivity.PinPosition>> driversByNet =
                new LinkedHashMap<>();
        for (Connectivity.Net net : topology.nets()) {
            List<Connectivity.PinPosition> drivers = new ArrayList<>();
            for (Connectivity.PinPosition pin : net.pins()) {
                PlacedComponent component = byId.get(pin.componentId());
                if (component == null) {
                    throw new IllegalStateException(
                            "Connectivity references an unknown component: " + pin.componentId());
                }
                netByPin.put(new PinKey(pin.componentId(), pin.pinId()), net);
                if (pin.pinId().equals("out") && !component.typeId().equals("output")) {
                    drivers.add(pin);
                }
            }
            if (drivers.size() > 1) {
                throw new IllegalArgumentException(
                        "Net has more than one driver: "
                                + drivers.stream().map(pin -> pin.componentId() + "."
                                        + pin.pinId()).toList());
            }
            driversByNet.put(net, drivers);
        }

        Map<Connectivity.Net, String> signalByNet = new HashMap<>();
        for (Map.Entry<Connectivity.Net, List<Connectivity.PinPosition>> entry
                : driversByNet.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            Connectivity.PinPosition driver = entry.getValue().getFirst();
            PlacedComponent component = byId.get(driver.componentId());
            String signal = switch (component.typeId()) {
                case "input" -> inputNames.get(component.id());
                default -> gateSignals.get(component.id());
            };
            if (signal == null) {
                throw new IllegalStateException(
                        "Could not assign a signal to driver " + component.id());
            }
            signalByNet.put(entry.getKey(), signal);
        }

        List<Netlist.Gate> netlistGates = new ArrayList<>();
        for (PlacedComponent gate : gates) {
            GateOperator operator = GateOperator.valueOf(
                    gate.typeId().toUpperCase(java.util.Locale.ROOT));
            List<String> sources = new ArrayList<>();
            for (int index = 0; index < schematic.definitionFor(gate).inputs().size(); index++) {
                sources.add(requireDrivenSignal(
                        netByPin.get(new PinKey(gate.id(), "in" + index)),
                        signalByNet,
                        gate.typeId() + " " + gate.id() + " input " + index));
            }
            netlistGates.add(new Netlist.Gate(gateSignals.get(gate.id()), operator, sources));
        }

        List<Netlist.Output> netlistOutputs = new ArrayList<>();
        for (PlacedComponent output : outputs) {
            String source = requireDrivenSignal(
                    netByPin.get(new PinKey(output.id(), "in0")),
                    signalByNet,
                    "Output " + output.id());
            netlistOutputs.add(new Netlist.Output(outputNames.get(output.id()), source));
        }

        Netlist netlist = new Netlist(
                inputs.stream().map(input -> inputNames.get(input.id())).toList(),
                netlistOutputs,
                netlistGates);
        return new Extraction(netlist, inputIds, outputIds);
    }

    private static String requireDrivenSignal(
            Connectivity.Net net,
            Map<Connectivity.Net, String> signalByNet,
            String pinDescription) {
        if (net == null || net.pins().isEmpty()) {
            throw new IllegalArgumentException(pinDescription + " is not connected.");
        }
        if (net.segments().isEmpty() && net.pins().size() == 1) {
            throw new IllegalArgumentException(pinDescription + " is bare (unconnected).");
        }
        String signal = signalByNet.get(net);
        if (signal == null) {
            throw new IllegalArgumentException(
                    pinDescription + " is connected to a net with no driver.");
        }
        return signal;
    }

    private static Map<String, String> assignNames(
            List<PlacedComponent> components,
            String generatedPrefix,
            boolean allowLiteralConstantLabels) {
        Map<String, String> names = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (PlacedComponent component : components) {
            if (component.label().isBlank()) {
                continue;
            }
            String label = component.label().trim();
            // Expression layouts use ordinary input switches labeled 0 and 1 as constant sources.
            if (allowLiteralConstantLabels && (label.equals("0") || label.equals("1"))) {
                continue;
            }
            if (!label.matches("[A-Za-z][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException(
                        "Label '" + component.label()
                                + "' must be a valid signal name (letters, digits and underscores).");
            }
            if (!used.add(label)) {
                throw new IllegalArgumentException("Duplicate component label: " + label);
            }
            names.put(component.id(), label);
        }
        int generated = 1;
        for (PlacedComponent component : components) {
            if (names.containsKey(component.id())) {
                continue;
            }
            String name;
            do {
                name = generatedPrefix + generated++;
            } while (!used.add(name));
            names.put(component.id(), name);
        }
        return names;
    }
}
