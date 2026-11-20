package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Extracts only reachable feedback circuits for the feedback-aware Tidy path.
 * The ordinary combinational extractor remains the source of truth for DAGs.
 */
public final class FeedbackCircuitExtractor {
    private record PinKey(String componentId, String pinId) {
    }

    private record GateInputEdge(
            String sourceSignal,
            String targetSignal,
            int targetInputIndex) {
    }

    public record FeedbackEdge(
            String sourceGate,
            String targetGate,
            int targetInputIndex,
            String placeholderSignal) {
        public FeedbackEdge {
            Objects.requireNonNull(sourceGate, "sourceGate");
            Objects.requireNonNull(targetGate, "targetGate");
            Objects.requireNonNull(placeholderSignal, "placeholderSignal");
            if (targetInputIndex < 0) {
                throw new IllegalArgumentException("targetInputIndex must not be negative");
            }
        }
    }

    public record Extraction(
            Netlist dagNetlist,
            List<FeedbackEdge> feedbackEdges,
            Map<String, String> inputComponentIds,
            Map<String, String> clockComponentIds,
            Map<String, String> outputComponentIds,
            Map<String, String> gateComponentIds) {
        public Extraction {
            Objects.requireNonNull(dagNetlist, "dagNetlist");
            feedbackEdges = List.copyOf(Objects.requireNonNull(feedbackEdges, "feedbackEdges"));
            if (feedbackEdges.isEmpty()) {
                throw new IllegalArgumentException("feedback extraction requires at least one edge");
            }
            inputComponentIds = immutableMap(inputComponentIds);
            clockComponentIds = immutableMap(clockComponentIds);
            outputComponentIds = immutableMap(outputComponentIds);
            gateComponentIds = immutableMap(gateComponentIds);
        }

        public Set<String> feedbackInputSignals() {
            return feedbackEdges.stream().map(FeedbackEdge::placeholderSignal)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        /** Reconstructs the cyclic source graph by restoring every cut feedback input. */
        public Netlist sourceNetlist() {
            Map<String, FeedbackEdge> edgeByPlaceholder = new HashMap<>();
            for (FeedbackEdge edge : feedbackEdges) {
                edgeByPlaceholder.put(edge.placeholderSignal(), edge);
            }
            List<Netlist.Gate> sourceGates = new ArrayList<>();
            for (Netlist.Gate gate : dagNetlist.gates()) {
                List<String> inputs = new ArrayList<>(gate.inputs());
                for (int index = 0; index < inputs.size(); index++) {
                    FeedbackEdge edge = edgeByPlaceholder.get(inputs.get(index));
                    if (edge != null) {
                        inputs.set(index, edge.sourceGate());
                    }
                }
                sourceGates.add(new Netlist.Gate(gate.id(), gate.type(), inputs));
            }
            List<String> primaryInputs = dagNetlist.inputs().stream()
                    .filter(name -> !edgeByPlaceholder.containsKey(name))
                    .toList();
            return new Netlist(primaryInputs, dagNetlist.outputs(), sourceGates, true);
        }

        private static Map<String, String> immutableMap(Map<String, String> values) {
            return java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(values, "values")));
        }
    }

    private FeedbackCircuitExtractor() {
    }

    /**
     * Returns an extraction only when an Input/Clock-rooted DFS finds a back edge.
     * Inputs and clocks are visited by component id; each gate's consumers are
     * visited by component id and then target pin index.
     */
    public static Optional<Extraction> extract(Schematic schematic) {
        Objects.requireNonNull(schematic, "schematic");
        List<PlacedComponent> components = schematic.components();
        Map<String, PlacedComponent> byId = new LinkedHashMap<>();
        List<PlacedComponent> inputs = new ArrayList<>();
        List<PlacedComponent> clocks = new ArrayList<>();
        List<PlacedComponent> outputs = new ArrayList<>();
        List<PlacedComponent> gates = new ArrayList<>();

        for (PlacedComponent component : components) {
            byId.put(component.id(), component);
            switch (component.typeId()) {
                case "input" -> inputs.add(component);
                case "clock" -> clocks.add(component);
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
        clocks.sort(visualOrder);
        outputs.sort(visualOrder);
        gates.sort(visualOrder);
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Tidy circuit requires at least one Output component.");
        }

        Set<String> reservedSignals = new HashSet<>();
        Map<String, String> inputNames = assignNames(
                inputs, "In", true, reservedSignals);
        Map<String, String> clockNames = assignNames(
                clocks, "Clk", false, reservedSignals);
        Map<String, String> outputNames = assignNames(
                outputs, "Out", false, new HashSet<>());

        Map<String, String> inputIds = reverseNames(inputNames);
        Map<String, String> clockIds = reverseNames(clockNames);
        Map<String, String> outputIds = reverseNames(outputNames);

        Map<String, String> gateSignals = new LinkedHashMap<>();
        Map<String, String> gateIds = new LinkedHashMap<>();
        int nextGate = 1;
        for (PlacedComponent gate : gates) {
            String signal;
            do {
                signal = "g" + nextGate++;
            } while (!reservedSignals.add(signal));
            gateSignals.put(gate.id(), signal);
            gateIds.put(signal, gate.id());
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
                case "clock" -> clockNames.get(component.id());
                default -> gateSignals.get(component.id());
            };
            if (signal == null) {
                throw new IllegalStateException(
                        "Could not assign a signal to driver " + component.id());
            }
            signalByNet.put(entry.getKey(), signal);
        }

        Map<String, List<String>> gateInputs = new LinkedHashMap<>();
        List<GateInputEdge> gateEdges = new ArrayList<>();
        for (PlacedComponent gate : gates) {
            List<String> sources = new ArrayList<>();
            for (int index = 0; index < schematic.definitionFor(gate).inputs().size(); index++) {
                String source = requireDrivenSignal(
                        netByPin.get(new PinKey(gate.id(), "in" + index)),
                        signalByNet,
                        gate.typeId() + " " + gate.id() + " input " + index);
                sources.add(source);
                gateEdges.add(new GateInputEdge(
                        source, gateSignals.get(gate.id()), index));
            }
            gateInputs.put(gateSignals.get(gate.id()), List.copyOf(sources));
        }

        List<Netlist.Output> netlistOutputs = new ArrayList<>();
        for (PlacedComponent output : outputs) {
            String source = requireDrivenSignal(
                    netByPin.get(new PinKey(output.id(), "in0")),
                    signalByNet,
                    "Output " + output.id());
            netlistOutputs.add(new Netlist.Output(outputNames.get(output.id()), source));
        }

        Map<String, String> componentIdByGateSignal = new HashMap<>();
        gateSignals.forEach((componentId, signal) ->
                componentIdByGateSignal.put(signal, componentId));
        Map<String, List<GateInputEdge>> consumersBySource = new HashMap<>();
        for (GateInputEdge edge : gateEdges) {
            consumersBySource.computeIfAbsent(edge.sourceSignal(), ignored -> new ArrayList<>())
                    .add(edge);
        }
        Comparator<GateInputEdge> edgeOrder = Comparator
                .comparing((GateInputEdge edge) -> componentIdByGateSignal.get(edge.targetSignal()))
                .thenComparingInt(GateInputEdge::targetInputIndex);
        consumersBySource.values().forEach(edges -> edges.sort(edgeOrder));

        Map<String, Integer> state = new HashMap<>();
        List<GateInputEdge> backEdges = new ArrayList<>();
        List<PlacedComponent> rootSources = new ArrayList<>(inputs);
        rootSources.addAll(clocks);
        rootSources.sort(Comparator.comparing(PlacedComponent::id));
        Map<String, String> sourceSignalByComponent = new HashMap<>();
        inputNames.forEach((id, name) -> sourceSignalByComponent.put(id, name));
        clockNames.forEach((id, name) -> sourceSignalByComponent.put(id, name));
        for (PlacedComponent source : rootSources) {
            String signal = sourceSignalByComponent.get(source.id());
            for (GateInputEdge edge : consumersBySource.getOrDefault(signal, List.of())) {
                visit(edge, consumersBySource, state, backEdges);
            }
        }

        if (backEdges.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Map<Integer, String>> placeholdersByTarget = new HashMap<>();
        List<FeedbackEdge> feedbackEdges = new ArrayList<>();
        int feedbackNumber = 1;
        for (GateInputEdge edge : backEdges) {
            String placeholder;
            do {
                placeholder = "Feedback" + feedbackNumber++;
            } while (!reservedSignals.add(placeholder));
            placeholdersByTarget.computeIfAbsent(
                    edge.targetSignal(), ignored -> new HashMap<>())
                    .put(edge.targetInputIndex(), placeholder);
            feedbackEdges.add(new FeedbackEdge(
                    edge.sourceSignal(), edge.targetSignal(),
                    edge.targetInputIndex(), placeholder));
        }

        List<String> primarySignals = new ArrayList<>();
        rootSources.forEach(source ->
                primarySignals.add(sourceSignalByComponent.get(source.id())));
        primarySignals.addAll(feedbackEdges.stream()
                .map(FeedbackEdge::placeholderSignal).toList());

        List<Netlist.Gate> dagGates = new ArrayList<>();
        for (PlacedComponent gate : gates) {
            String signal = gateSignals.get(gate.id());
            List<String> sources = new ArrayList<>(gateInputs.get(signal));
            Map<Integer, String> replacements = placeholdersByTarget.get(signal);
            if (replacements != null) {
                replacements.forEach(sources::set);
            }
            GateOperator operator = GateOperator.valueOf(
                    gate.typeId().toUpperCase(java.util.Locale.ROOT));
            dagGates.add(new Netlist.Gate(signal, operator, sources));
        }

        Netlist dagNetlist = new Netlist(primarySignals, netlistOutputs, dagGates);
        Map<String, String> gateComponentIds = new LinkedHashMap<>();
        gateSignals.forEach((componentId, signal) -> gateComponentIds.put(signal, componentId));
        return Optional.of(new Extraction(
                dagNetlist, feedbackEdges, inputIds, clockIds,
                outputIds, gateComponentIds));
    }

    /**
     * Extracts back-edges from an expression netlist without requiring a
     * schematic round-trip. Unlike the Tidy overload, every gate is a DFS root
     * because expression equations may have no primary input dependency.
     */
    public static Optional<Extraction> extract(Netlist netlist) {
        Objects.requireNonNull(netlist, "netlist");
        Map<String, Netlist.Gate> gatesById = new LinkedHashMap<>();
        for (Netlist.Gate gate : netlist.gates()) {
            gatesById.put(gate.id(), gate);
        }

        Map<String, List<GateInputEdge>> consumersBySource = new HashMap<>();
        for (Netlist.Gate gate : netlist.gates()) {
            for (int index = 0; index < gate.inputs().size(); index++) {
                String source = gate.inputs().get(index);
                if (gatesById.containsKey(source)) {
                    consumersBySource.computeIfAbsent(source, ignored -> new ArrayList<>())
                            .add(new GateInputEdge(source, gate.id(), index));
                }
            }
        }
        Comparator<GateInputEdge> edgeOrder = Comparator
                .comparing(GateInputEdge::targetSignal)
                .thenComparingInt(GateInputEdge::targetInputIndex);
        consumersBySource.values().forEach(edges -> edges.sort(edgeOrder));

        Map<String, Integer> state = new HashMap<>();
        List<GateInputEdge> backEdges = new ArrayList<>();
        for (Netlist.Gate gate : netlist.gates()) {
            if (state.getOrDefault(gate.id(), 0) == 0) {
                visitGate(gate.id(), consumersBySource, state, backEdges);
            }
        }
        if (backEdges.isEmpty()) {
            return Optional.empty();
        }

        Set<String> reservedSignals = new HashSet<>(netlist.inputs());
        gatesById.keySet().forEach(reservedSignals::add);
        netlist.outputs().stream().map(Netlist.Output::name).forEach(reservedSignals::add);
        Map<String, Map<Integer, String>> placeholdersByTarget = new HashMap<>();
        List<FeedbackEdge> feedbackEdges = new ArrayList<>();
        int feedbackNumber = 1;
        for (GateInputEdge edge : backEdges) {
            String placeholder;
            do {
                placeholder = "Feedback" + feedbackNumber++;
            } while (!reservedSignals.add(placeholder));
            placeholdersByTarget.computeIfAbsent(
                    edge.targetSignal(), ignored -> new HashMap<>())
                    .put(edge.targetInputIndex(), placeholder);
            feedbackEdges.add(new FeedbackEdge(
                    edge.sourceSignal(), edge.targetSignal(),
                    edge.targetInputIndex(), placeholder));
        }

        List<String> primaryInputs = new ArrayList<>(netlist.inputs());
        feedbackEdges.stream().map(FeedbackEdge::placeholderSignal)
                .forEach(primaryInputs::add);
        List<Netlist.Gate> dagGates = new ArrayList<>();
        for (Netlist.Gate gate : netlist.gates()) {
            List<String> inputs = new ArrayList<>(gate.inputs());
            Map<Integer, String> replacements = placeholdersByTarget.get(gate.id());
            if (replacements != null) {
                replacements.forEach(inputs::set);
            }
            dagGates.add(new Netlist.Gate(gate.id(), gate.type(), inputs));
        }

        Netlist dagNetlist = new Netlist(primaryInputs, netlist.outputs(), dagGates);
        Map<String, String> gateComponentIds = new LinkedHashMap<>();
        gatesById.keySet().forEach(id -> gateComponentIds.put(id, id));
        return Optional.of(new Extraction(
                dagNetlist, feedbackEdges, Map.of(), Map.of(), Map.of(), gateComponentIds));
    }

    private static void visitGate(
            String gateId,
            Map<String, List<GateInputEdge>> consumersBySource,
            Map<String, Integer> state,
            List<GateInputEdge> backEdges) {
        state.put(gateId, 1);
        for (GateInputEdge edge : consumersBySource.getOrDefault(gateId, List.of())) {
            int targetState = state.getOrDefault(edge.targetSignal(), 0);
            if (targetState == 1) {
                backEdges.add(edge);
            } else if (targetState == 0) {
                visitGate(edge.targetSignal(), consumersBySource, state, backEdges);
            }
        }
        state.put(gateId, 2);
    }

    /** Returns true only for a feedback edge reachable from an Input or Clock source. */
    public static boolean hasFeedback(Schematic schematic) {
        try {
            return extract(schematic).isPresent();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return false;
        }
    }

    private static void visit(
            GateInputEdge edge,
            Map<String, List<GateInputEdge>> consumersBySource,
            Map<String, Integer> state,
            List<GateInputEdge> backEdges) {
        int current = state.getOrDefault(edge.targetSignal(), 0);
        if (current == 1) {
            backEdges.add(edge);
            return;
        }
        if (current == 2) {
            return;
        }

        state.put(edge.targetSignal(), 1);
        for (GateInputEdge next : consumersBySource.getOrDefault(edge.targetSignal(), List.of())) {
            visit(next, consumersBySource, state, backEdges);
        }
        state.put(edge.targetSignal(), 2);
    }

    private static Map<String, String> assignNames(
            List<PlacedComponent> components,
            String generatedPrefix,
            boolean allowLiteralConstantLabels,
            Set<String> used) {
        Map<String, String> names = new LinkedHashMap<>();
        for (PlacedComponent component : components) {
            if (component.label().isBlank()) {
                continue;
            }
            String label = component.label().trim();
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

    private static Map<String, String> reverseNames(Map<String, String> namesByComponent) {
        Map<String, String> result = new LinkedHashMap<>();
        namesByComponent.forEach((componentId, signal) -> result.put(signal, componentId));
        return result;
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
}
