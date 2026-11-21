package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.WireSegment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

/** Converts a gate schematic or netlist into deterministic named Boolean expressions. */
public final class CircuitDescriber {
    public static final int MAX_GATES = 400;

    private static final Set<String> RESERVED_WORDS = Set.of(
            "NOT", "AND", "NAND", "XOR", "XNOR", "OR", "NOR");

    /** Expression text and the source component/signal represented by each emitted name. */
    public record Description(String text, Map<String, String> componentIds) {
        public Description {
            Objects.requireNonNull(text, "text");
            componentIds = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(componentIds, "componentIds")));
        }

        /** A descriptive alias for callers that prefer the mapping's meaning in the method name. */
        public Map<String, String> signalToComponentId() {
            return componentIds;
        }
    }

    private record PortNames(
            Map<String, String> byComponentId,
            List<PlacedComponent> inputs,
            List<PlacedComponent> clocks,
            List<PlacedComponent> outputs) {
    }

    private record GraphSource(
            Netlist netlist,
            Map<String, String> componentIds) {
    }

    private record StrongComponent(List<String> gateIds) {
        private StrongComponent {
            gateIds = List.copyOf(gateIds);
        }

        private String firstId() {
            return gateIds.getFirst();
        }
    }

    private CircuitDescriber() {
    }

    /** Describes a placed schematic without changing its labels, wires, or source values. */
    public static Description describe(Schematic schematic) {
        Objects.requireNonNull(schematic, "schematic");
        if (!schematic.componentInstances().isEmpty()) {
            throw new IllegalArgumentException(
                    "Circuit export does not support component instances yet.");
        }

        long gateCount = schematic.components().stream()
                .filter(component -> !isBoundaryOrConstant(component.typeId()))
                .count();
        requireGateLimit(gateCount);

        PortNames names = assignPortNames(schematic);
        Schematic normalized = copyWithNames(schematic, names.byComponentId(), false);

        GraphSource source;
        var feedback = FeedbackCircuitExtractor.extract(normalized);
        if (feedback.isPresent()) {
            FeedbackCircuitExtractor.Extraction extraction = feedback.orElseThrow();
            source = new GraphSource(
                    extraction.sourceNetlist(),
                    componentIds(extraction.inputComponentIds(),
                            extraction.clockComponentIds(),
                            extraction.outputComponentIds(),
                            extraction.gateComponentIds(),
                            extraction.constantComponentIds()));
        } else {
            // Clock is an externally driven Boolean signal in an expression. Use a
            // temporary Input-shaped copy so the existing DAG extractor remains unchanged.
            Schematic dagCopy = copyWithNames(schematic, names.byComponentId(), true);
            SchematicNetlistExtractor.Extraction extraction =
                    SchematicNetlistExtractor.extract(dagCopy);
            Map<String, String> gateIds = gateIdsForDag(dagCopy, extraction.netlist());
            Map<String, String> constants = constantIdsFor(dagCopy, extraction.netlist());
            source = new GraphSource(
                    extraction.netlist(),
                    componentIds(extraction.inputComponentIds(), Map.of(),
                            extraction.outputComponentIds(), gateIds, constants));
        }
        return describeGraph(source.netlist(), source.componentIds());
    }

    /** Describes a netlist directly; its original signal ids are retained in the component map. */
    public static Description describe(Netlist netlist) {
        Objects.requireNonNull(netlist, "netlist");
        requireGateLimit(netlist.gates().size());

        Set<String> used = new LinkedHashSet<>();
        Map<String, String> inputNames = new LinkedHashMap<>();
        Map<String, String> outputNames = new LinkedHashMap<>();
        Map<String, String> componentIds = new LinkedHashMap<>();

        for (String input : netlist.inputs()) {
            String name = uniqueName(sanitizeIdentifier(input), used);
            inputNames.put(input, name);
            componentIds.put(name, input);
        }
        for (Netlist.Output output : netlist.outputs()) {
            String name = uniqueName(sanitizeIdentifier(output.name()), used);
            outputNames.put(output.name(), name);
            componentIds.put(name, output.name());
        }
        for (Netlist.Gate gate : netlist.gates()) {
            componentIds.putIfAbsent(gate.id(), gate.id());
        }

        return describeGraph(netlist, componentIds, inputNames, outputNames);
    }

    private static Description describeGraph(
            Netlist netlist, Map<String, String> sourceComponentIds) {
        Map<String, String> inputNames = new LinkedHashMap<>();
        Map<String, String> outputNames = new LinkedHashMap<>();
        Set<String> used = new LinkedHashSet<>();
        for (String input : netlist.inputs()) {
            inputNames.put(input, uniqueName(sanitizeIdentifier(input), used));
        }
        for (Netlist.Output output : netlist.outputs()) {
            outputNames.put(output.name(), uniqueName(sanitizeIdentifier(output.name()), used));
        }
        return describeGraph(netlist, sourceComponentIds, inputNames, outputNames);
    }

    private static Description describeGraph(
            Netlist netlist,
            Map<String, String> sourceComponentIds,
            Map<String, String> inputNames,
            Map<String, String> outputNames) {
        Map<String, Netlist.Gate> gatesById = new LinkedHashMap<>();
        for (Netlist.Gate gate : netlist.gates()) {
            gatesById.put(gate.id(), gate);
        }

        Set<String> reachable = reachableGates(netlist, gatesById);
        if (reachable.size() > MAX_GATES) {
            requireGateLimit(reachable.size());
        }

        Map<String, Integer> fanout = new HashMap<>();
        reachable.forEach(id -> fanout.put(id, 0));
        for (String id : reachable) {
            Netlist.Gate consumer = gatesById.get(id);
            for (String input : consumer.inputs()) {
                if (reachable.contains(input)) {
                    fanout.merge(input, 1, Integer::sum);
                }
            }
        }
        for (Netlist.Output output : netlist.outputs()) {
            if (reachable.contains(output.source())) {
                fanout.merge(output.source(), 1, Integer::sum);
            }
        }

        List<StrongComponent> components = stronglyConnectedComponents(reachable, gatesById);
        Map<String, Integer> componentIndex = new HashMap<>();
        for (int index = 0; index < components.size(); index++) {
            for (String gateId : components.get(index).gateIds()) {
                componentIndex.put(gateId, index);
            }
        }
        Set<String> cyclicGates = cyclicGates(components, gatesById);
        List<Netlist.Gate> orderedGates =
                topologicalGateOrder(components, componentIndex, gatesById);

        Set<String> usedNames = new LinkedHashSet<>();
        usedNames.addAll(inputNames.values());
        usedNames.addAll(outputNames.values());
        Map<String, String> internalNames = new LinkedHashMap<>();
        int nextInternal = 1;
        for (Netlist.Gate gate : orderedGates) {
            if (fanout.getOrDefault(gate.id(), 0) > 1 || cyclicGates.contains(gate.id())) {
                String name;
                do {
                    name = "n" + nextInternal++;
                } while (!usedNames.add(name));
                internalNames.put(gate.id(), name);
            }
        }

        Map<String, String> emittedComponentIds = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : inputNames.entrySet()) {
            putMapped(emittedComponentIds, entry.getValue(),
                    sourceComponentIds.getOrDefault(entry.getValue(), entry.getKey()));
        }
        // Preserve the exact original component mapping where a signal was renamed.
        for (Map.Entry<String, String> entry : inputNames.entrySet()) {
            String oldSignal = entry.getKey();
            String newSignal = entry.getValue();
            String componentId = sourceComponentIds.get(oldSignal);
            if (componentId != null) {
                emittedComponentIds.put(newSignal, componentId);
            }
        }
        for (Map.Entry<String, String> entry : outputNames.entrySet()) {
            String componentId = sourceComponentIds.get(entry.getValue());
            if (componentId == null) {
                componentId = sourceComponentIds.get(entry.getKey());
            }
            if (componentId != null) {
                emittedComponentIds.put(entry.getValue(), componentId);
            }
        }
        for (Map.Entry<String, String> entry : internalNames.entrySet()) {
            String componentId = sourceComponentIds.get(entry.getKey());
            if (componentId != null) {
                emittedComponentIds.put(entry.getValue(), componentId);
            }
        }
        if (sourceComponentIds.containsKey("0")) {
            emittedComponentIds.putIfAbsent("0", sourceComponentIds.get("0"));
        }
        if (sourceComponentIds.containsKey("1")) {
            emittedComponentIds.putIfAbsent("1", sourceComponentIds.get("1"));
        }

        StringBuilder text = new StringBuilder();
        for (Netlist.Gate gate : orderedGates) {
            String name = internalNames.get(gate.id());
            if (name != null) {
                appendLine(text, name + " := " + renderGate(
                        gate, gatesById, inputNames, internalNames, new HashSet<>()));
            }
        }
        for (Netlist.Output output : netlist.outputs()) {
            String name = outputNames.get(output.name());
            appendLine(text, name + " = " + renderSignal(
                    output.source(), gatesById, inputNames, internalNames, new HashSet<>()));
        }
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Circuit export requires at least one Output component.");
        }
        return new Description(text.toString(), emittedComponentIds);
    }

    private static Map<String, String> componentIds(
            Map<String, String> inputs,
            Map<String, String> clocks,
            Map<String, String> outputs,
            Map<String, String> gates,
            Map<String, String> constants) {
        Map<String, String> result = new LinkedHashMap<>();
        inputs.forEach(result::put);
        clocks.forEach(result::put);
        outputs.forEach(result::put);
        gates.forEach(result::put);
        constants.forEach(result::putIfAbsent);
        return result;
    }

    private static void putMapped(Map<String, String> target, String name, String value) {
        if (name != null && value != null) {
            target.putIfAbsent(name, value);
        }
    }

    private static Map<String, String> gateIdsForDag(
            Schematic schematic, Netlist netlist) {
        List<PlacedComponent> gates = schematic.components().stream()
                .filter(component -> !isBoundaryOrConstant(component.typeId()))
                .sorted(Comparator.comparingInt(PlacedComponent::y)
                        .thenComparingInt(PlacedComponent::x)
                        .thenComparing(PlacedComponent::id))
                .toList();
        Set<String> used = new HashSet<>(netlist.inputs());
        Map<String, String> result = new LinkedHashMap<>();
        int next = 1;
        for (PlacedComponent gate : gates) {
            String signal;
            do {
                signal = "g" + next++;
            } while (!used.add(signal));
            result.put(signal, gate.id());
        }
        return result;
    }

    private static Map<String, String> constantIdsForDag(
            Schematic schematic, Netlist netlist) {
        Set<String> used = new HashSet<>();
        netlist.outputs().stream().map(Netlist.Output::source)
                .filter(CircuitDescriber::isConstant).forEach(used::add);
        netlist.gates().stream().flatMap(gate -> gate.inputs().stream())
                .filter(CircuitDescriber::isConstant).forEach(used::add);
        Map<String, String> result = new LinkedHashMap<>();
        schematic.components().stream()
                .filter(component -> component.typeId().equals("constant"))
                .sorted(Comparator.comparing(PlacedComponent::id))
                .forEach(component -> {
                    if (used.contains(component.label())) {
                        result.putIfAbsent(component.label(), component.id());
                    }
                });
        return result;
    }

    private static boolean isConstant(String signal) {
        return signal.equals("0") || signal.equals("1");
    }

    private static boolean isBoundaryOrConstant(String typeId) {
        return typeId.equals("input") || typeId.equals("clock")
                || typeId.equals("output") || typeId.equals("constant");
    }

    private static void requireGateLimit(long gateCount) {
        if (gateCount > MAX_GATES) {
            throw new IllegalArgumentException(
                    "Circuit has " + gateCount + " gates; maximum is " + MAX_GATES + ".");
        }
    }

    private static PortNames assignPortNames(Schematic schematic) {
        Comparator<PlacedComponent> byId = Comparator.comparing(PlacedComponent::id);
        List<PlacedComponent> inputs = schematic.components().stream()
                .filter(component -> component.typeId().equals("input"))
                .sorted(byId).toList();
        List<PlacedComponent> outputs = schematic.components().stream()
                .filter(component -> component.typeId().equals("output"))
                .sorted(byId).toList();
        List<PlacedComponent> clocks = schematic.components().stream()
                .filter(component -> component.typeId().equals("clock"))
                .sorted(byId).toList();

        Set<String> used = new LinkedHashSet<>();
        Map<String, String> names = new LinkedHashMap<>();
        assignExplicitNames(inputs, names, used);
        assignExplicitNames(outputs, names, used);
        assignExplicitNames(clocks, names, used);
        assignFallbackNames(inputs, "In", names, used);
        assignFallbackNames(outputs, "Out", names, used);
        assignFallbackNames(clocks, "Clk", names, used);
        return new PortNames(
                Collections.unmodifiableMap(names), inputs, clocks, outputs);
    }

    private static void assignExplicitNames(
            List<PlacedComponent> components, Map<String, String> names, Set<String> used) {
        for (PlacedComponent component : components) {
            if (!component.label().isBlank()) {
                String base = sanitizeIdentifier(component.label());
                names.put(component.id(), uniqueName(base, used));
            }
        }
    }

    private static void assignFallbackNames(
            List<PlacedComponent> components,
            String prefix,
            Map<String, String> names,
            Set<String> used) {
        int next = 1;
        for (PlacedComponent component : components) {
            if (!names.containsKey(component.id())) {
                names.put(component.id(), uniqueName(prefix + next++, used));
            }
        }
    }

    private static String sanitizeIdentifier(String raw) {
        String sanitized = raw.trim().replaceAll("[^A-Za-z0-9_]+", "_");
        if (sanitized.isEmpty()) {
            sanitized = "N";
        }
        char first = sanitized.charAt(0);
        if (!isAsciiLetter(first)) {
            sanitized = "N" + sanitized;
        }
        if (RESERVED_WORDS.contains(sanitized.toUpperCase(Locale.ROOT))) {
            sanitized += "_";
        }
        return sanitized;
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
    }

    private static String uniqueName(String base, Set<String> used) {
        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }

    private static Schematic copyWithNames(
            Schematic source, Map<String, String> names, boolean clocksAsInputs) {
        Schematic copy = new Schematic(source.gateDefinitions());
        for (PlacedComponent component : source.components()) {
            String typeId = clocksAsInputs && component.typeId().equals("clock")
                    ? "input" : component.typeId();
            String label = names.getOrDefault(component.id(), component.label());
            copy.add(new PlacedComponent(
                    component.id(), typeId, component.x(), component.y(),
                    component.facing(), component.attributes(), label));
        }
        for (WireSegment wire : source.wires()) {
            copy.addWire(wire.x1(), wire.y1(), wire.x2(), wire.y2());
        }
        return copy;
    }

    private static Set<String> reachableGates(
            Netlist netlist, Map<String, Netlist.Gate> gatesById) {
        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        for (Netlist.Output output : netlist.outputs()) {
            if (gatesById.containsKey(output.source())) {
                pending.addLast(output.source());
            }
        }
        while (!pending.isEmpty()) {
            String id = pending.removeLast();
            if (!reachable.add(id)) {
                continue;
            }
            for (String input : gatesById.get(id).inputs()) {
                if (gatesById.containsKey(input)) {
                    pending.addLast(input);
                }
            }
        }
        return reachable;
    }

    private static List<StrongComponent> stronglyConnectedComponents(
            Set<String> reachable, Map<String, Netlist.Gate> gatesById) {
        Map<String, Integer> indexByGate = new HashMap<>();
        Map<String, Integer> lowLinkByGate = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        List<StrongComponent> result = new ArrayList<>();
        int[] nextIndex = {0};
        List<String> orderedIds = reachable.stream().sorted().toList();
        for (String id : orderedIds) {
            if (!indexByGate.containsKey(id)) {
                strongConnect(id, reachable, gatesById, indexByGate,
                        lowLinkByGate, stack, onStack, result, nextIndex);
            }
        }
        return List.copyOf(result);
    }

    private static void strongConnect(
            String id,
            Set<String> reachable,
            Map<String, Netlist.Gate> gatesById,
            Map<String, Integer> indexByGate,
            Map<String, Integer> lowLinkByGate,
            Deque<String> stack,
            Set<String> onStack,
            List<StrongComponent> result,
            int[] nextIndex) {
        int index = nextIndex[0]++;
        indexByGate.put(id, index);
        lowLinkByGate.put(id, index);
        stack.push(id);
        onStack.add(id);

        Set<String> dependencies = new TreeSet<>();
        for (String input : gatesById.get(id).inputs()) {
            if (reachable.contains(input)) {
                dependencies.add(input);
            }
        }
        for (String dependency : dependencies) {
            if (!indexByGate.containsKey(dependency)) {
                strongConnect(dependency, reachable, gatesById, indexByGate,
                        lowLinkByGate, stack, onStack, result, nextIndex);
                lowLinkByGate.put(id, Math.min(
                        lowLinkByGate.get(id), lowLinkByGate.get(dependency)));
            } else if (onStack.contains(dependency)) {
                lowLinkByGate.put(id, Math.min(
                        lowLinkByGate.get(id), indexByGate.get(dependency)));
            }
        }

        if (lowLinkByGate.get(id).equals(indexByGate.get(id))) {
            List<String> members = new ArrayList<>();
            String member;
            do {
                member = stack.pop();
                onStack.remove(member);
                members.add(member);
            } while (!member.equals(id));
            members.sort(String::compareTo);
            result.add(new StrongComponent(members));
        }
    }

    private static Set<String> cyclicGates(
            List<StrongComponent> components, Map<String, Netlist.Gate> gatesById) {
        Set<String> cyclic = new HashSet<>();
        for (StrongComponent component : components) {
            if (component.gateIds().size() > 1) {
                cyclic.addAll(component.gateIds());
            } else {
                String id = component.firstId();
                if (gatesById.get(id).inputs().contains(id)) {
                    cyclic.add(id);
                }
            }
        }
        return cyclic;
    }

    private static List<Netlist.Gate> topologicalGateOrder(
            List<StrongComponent> components,
            Map<String, Integer> componentIndex,
            Map<String, Netlist.Gate> gatesById) {
        List<Set<Integer>> consumers = new ArrayList<>();
        int[] indegree = new int[components.size()];
        for (int index = 0; index < components.size(); index++) {
            consumers.add(new HashSet<>());
        }
        for (Netlist.Gate gate : gatesById.values()) {
            Integer consumerIndex = componentIndex.get(gate.id());
            if (consumerIndex == null) {
                continue;
            }
            for (String input : gate.inputs()) {
                Integer dependencyIndex = componentIndex.get(input);
                if (dependencyIndex != null && !dependencyIndex.equals(consumerIndex)
                        && consumers.get(dependencyIndex).add(consumerIndex)) {
                    indegree[consumerIndex]++;
                }
            }
        }

        Comparator<Integer> order = Comparator.comparing(index ->
                components.get(index).firstId());
        PriorityQueue<Integer> ready = new PriorityQueue<>(order);
        for (int index = 0; index < indegree.length; index++) {
            if (indegree[index] == 0) {
                ready.add(index);
            }
        }

        List<Netlist.Gate> result = new ArrayList<>(gatesById.size());
        while (!ready.isEmpty()) {
            int index = ready.remove();
            for (String gateId : components.get(index).gateIds()) {
                result.add(gatesById.get(gateId));
            }
            for (int consumer : consumers.get(index)) {
                if (--indegree[consumer] == 0) {
                    ready.add(consumer);
                }
            }
        }
        if (result.size() != componentIndex.size()) {
            throw new IllegalStateException("Could not order the expression dependency graph.");
        }
        return List.copyOf(result);
    }

    private static String renderGate(
            Netlist.Gate gate,
            Map<String, Netlist.Gate> gatesById,
            Map<String, String> inputNames,
            Map<String, String> internalNames,
            Set<String> rendering) {
        List<String> inputs = gate.inputs().stream()
                .map(input -> renderSignal(input, gatesById, inputNames,
                        internalNames, rendering))
                .toList();
        return switch (gate.type()) {
            case AND -> "(" + inputs.get(0) + " & " + inputs.get(1) + ")";
            case OR -> "(" + inputs.get(0) + " | " + inputs.get(1) + ")";
            case XOR -> "(" + inputs.get(0) + " ^ " + inputs.get(1) + ")";
            case NAND -> "(" + inputs.get(0) + " NAND " + inputs.get(1) + ")";
            case NOR -> "(" + inputs.get(0) + " NOR " + inputs.get(1) + ")";
            case XNOR -> "(" + inputs.get(0) + " XNOR " + inputs.get(1) + ")";
            case NOT -> "(NOT " + inputs.getFirst() + ")";
            case BUFFER -> "(" + inputs.getFirst() + ")";
        };
    }

    private static String renderSignal(
            String signal,
            Map<String, Netlist.Gate> gatesById,
            Map<String, String> inputNames,
            Map<String, String> internalNames,
            Set<String> rendering) {
        if (isConstant(signal)) {
            return signal;
        }
        String inputName = inputNames.get(signal);
        if (inputName != null) {
            return inputName;
        }
        String internalName = internalNames.get(signal);
        if (internalName != null) {
            return internalName;
        }
        Netlist.Gate gate = gatesById.get(signal);
        if (gate == null) {
            throw new IllegalArgumentException("Unknown expression source: " + signal);
        }
        if (!rendering.add(signal)) {
            throw new IllegalStateException(
                    "A cyclic gate was not assigned an internal expression name: " + signal);
        }
        try {
            return renderGate(gate, gatesById, inputNames, internalNames, rendering);
        } finally {
            rendering.remove(signal);
        }
    }

    private static void appendLine(StringBuilder target, String line) {
        if (!target.isEmpty()) {
            target.append('\n');
        }
        target.append(line);
    }
}
