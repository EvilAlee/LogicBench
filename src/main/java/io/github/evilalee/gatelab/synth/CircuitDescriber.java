package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private record SignalMappings(
            Map<String, String> inputs,
            Map<String, String> outputs,
            Map<String, String> gates,
            Map<String, String> constants) {
        private SignalMappings {
            inputs = Map.copyOf(Objects.requireNonNull(inputs, "inputs"));
            outputs = Map.copyOf(Objects.requireNonNull(outputs, "outputs"));
            gates = Map.copyOf(Objects.requireNonNull(gates, "gates"));
            constants = Map.copyOf(Objects.requireNonNull(constants, "constants"));
        }
    }

    private record GraphSource(Netlist netlist, SignalMappings mappings) {
    }

    private record BoxExport(
            ComponentInstance instance,
            ComponentDefinition definition,
            String expressionName,
            String sourceIdPrefix,
            Map<String, String> sourceNamesByPort,
            Map<String, String> sinkNamesByPort,
            Map<String, String> sourceIdsByName,
            Map<String, String> sinkIdsByName) {
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
            return describeWithInstances(schematic);
        }

        long gateCount = schematic.components().stream()
                .filter(component -> !isBoundaryOrConstant(component.typeId()))
                .count();
        requireGateLimit(gateCount);

        PortNames names = assignPortNames(schematic);
        Schematic normalized = copyWithNames(schematic, names.byComponentId(), false);

        GraphSource source;
        var feedback = FeedbackCircuitExtractor.extractForExpression(normalized);
        if (feedback.isPresent()) {
            FeedbackCircuitExtractor.Extraction extraction = feedback.orElseThrow();
            source = new GraphSource(
                    extraction.sourceNetlist(),
                    new SignalMappings(
                            merged(extraction.inputComponentIds(), extraction.clockComponentIds()),
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
            Map<String, String> constants = constantIdsForDag(dagCopy, extraction.netlist());
            source = new GraphSource(
                    extraction.netlist(),
                    new SignalMappings(
                            extraction.inputComponentIds(), extraction.outputComponentIds(),
                            gateIds, constants));
        }
        return describeGraph(source.netlist(), source.mappings());
    }

    /**
     * Converts each box boundary into temporary Input/Output terminals, describes
     * the surrounding gate graph with the existing exporter, then reconstructs
     * reusable calls around those boundary expressions.
     */
    private static Description describeWithInstances(Schematic source) {
        List<ComponentInstance> instances = source.componentInstances().stream()
                .sorted(Comparator.comparing(ComponentInstance::id)).toList();
        long gateCount = source.components().stream()
                .filter(component -> !isBoundaryOrConstant(component.typeId()))
                .count();
        for (ComponentInstance instance : instances) {
            ComponentDefinition definition = source.componentDefinitionFor(instance);
            gateCount += definition.internalCircuit().components().stream()
                    .filter(component -> !isBoundaryOrConstant(component.typeId())).count();
        }
        requireGateLimit(gateCount);

        PortNames ordinaryPorts = assignPortNames(source);
        Set<String> usedNames = new LinkedHashSet<>(ordinaryPorts.byComponentId().values());
        Map<String, String> usedInstanceNames = new LinkedHashMap<>();
        List<BoxExport> boxExports = new ArrayList<>();
        Set<String> usedComponentIds = source.components().stream()
                .map(PlacedComponent::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        int nextUnnamedInstance = 1;
        for (int index = 0; index < instances.size(); index++) {
            ComponentInstance instance = instances.get(index);
            ComponentDefinition definition = source.componentDefinitionFor(instance);
            String rawName = instance.label().isBlank()
                    ? "U" + nextUnnamedInstance++ : instance.label();
            String expressionName = uniqueName(sanitizeIdentifier(rawName), usedNames);
            usedInstanceNames.put(instance.id(), expressionName);

            Map<String, String> sourceNames = new LinkedHashMap<>();
            Map<String, String> sinkNames = new LinkedHashMap<>();
            Map<String, String> sourceIds = new LinkedHashMap<>();
            Map<String, String> sinkIds = new LinkedHashMap<>();
            for (ComponentDefinition.Port port : definition.outputPorts()) {
                String pseudoName = uniqueName(
                        sanitizeIdentifier("BoxSource" + index + expressionName + port.name()),
                        usedNames);
                String pseudoId = uniqueSyntheticId(
                        "export-box-source-" + index + "-" + port.name(), usedComponentIds);
                sourceNames.put(port.name(), pseudoName);
                sourceIds.put(pseudoName, pseudoId);
            }
            for (ComponentDefinition.Port port : definition.inputPorts()) {
                String pseudoName = uniqueName(
                        sanitizeIdentifier("BoxSink" + index + expressionName + port.name()),
                        usedNames);
                String pseudoId = uniqueSyntheticId(
                        "export-box-sink-" + index + "-" + port.name(), usedComponentIds);
                sinkNames.put(port.name(), pseudoName);
                sinkIds.put(pseudoName, pseudoId);
            }
            boxExports.add(new BoxExport(
                    instance, definition, expressionName, "Box" + index,
                    Map.copyOf(sourceNames), Map.copyOf(sinkNames),
                    Map.copyOf(sourceIds), Map.copyOf(sinkIds)));
        }

        Schematic temporary = new Schematic(source.gateDefinitions());
        temporary.setUndefinedInputMode(source.undefinedInputMode());
        for (PlacedComponent component : source.components()) {
            temporary.add(component);
            if (component.typeId().equals("input") && source.inputValue(component.id())) {
                temporary.setInputValue(component.id(), true);
            } else if (component.typeId().equals("clock") && source.clockValue(component.id())) {
                temporary.setClockValue(component.id(), true);
            }
        }

        GateDef inputDefinition = findNarrowDefinition(source, "input", 0);
        GateDef outputDefinition = findNarrowDefinition(source, "output", 1);
        for (BoxExport box : boxExports) {
            Map<String, ComponentInstance.PinGeometry> geometryByName = new LinkedHashMap<>();
            box.instance().geometry(box.definition()).pins().forEach(pin ->
                    geometryByName.put(pin.name(), pin));
            for (ComponentDefinition.Port port : box.definition().outputPorts()) {
                ComponentInstance.PinGeometry pin = geometryByName.get(port.name());
                PlacedComponent pseudo = sourceAtPin(
                        box.sourceIdsByName().get(box.sourceNamesByPort().get(port.name())),
                        inputDefinition, pin.x(), pin.y(), box.sourceNamesByPort().get(port.name()));
                temporary.add(pseudo);
            }
            for (ComponentDefinition.Port port : box.definition().inputPorts()) {
                ComponentInstance.PinGeometry pin = geometryByName.get(port.name());
                PlacedComponent pseudo = sinkAtPin(
                        box.sinkIdsByName().get(box.sinkNamesByPort().get(port.name())),
                        outputDefinition, pin.x(), pin.y(), box.sinkNamesByPort().get(port.name()));
                temporary.add(pseudo);
            }
        }
        for (WireSegment wire : source.wires()) {
            temporary.addWire(wire);
        }

        Description boundaryDescription = describe(temporary);
        Map<String, String> replacements = new LinkedHashMap<>();
        Set<String> syntheticSinkIds = new HashSet<>();
        Map<String, Map<String, String>> argumentsByInstance = new LinkedHashMap<>();
        for (BoxExport box : boxExports) {
            Map<String, String> arguments = new LinkedHashMap<>();
            argumentsByInstance.put(box.expressionName(), arguments);
            for (Map.Entry<String, String> sourceName : box.sourceNamesByPort().entrySet()) {
                replacements.put(sourceName.getValue(),
                        box.expressionName() + "." + sourceName.getKey());
            }
            for (String sinkId : box.sinkIdsByName().values()) {
                syntheticSinkIds.add(sinkId);
            }
        }

        Map<String, String> emittedNameByComponentId = new HashMap<>();
        boundaryDescription.componentIds().forEach((name, id) ->
                emittedNameByComponentId.putIfAbsent(id, name));
        Map<String, String> sinkExpressionById = new HashMap<>();
        for (String line : boundaryDescription.text().split("\\R")) {
            int separator = line.indexOf(" = ");
            if (separator < 0) {
                continue;
            }
            String left = line.substring(0, separator);
            String right = line.substring(separator + 3);
            String componentId = boundaryDescription.componentIds().get(left);
            if (componentId != null && syntheticSinkIds.contains(componentId)) {
                sinkExpressionById.put(componentId, replaceSignalNames(right, replacements));
            }
        }

        List<String> callLines = new ArrayList<>();
        for (BoxExport box : boxExports) {
            List<String> arguments = new ArrayList<>();
            for (ComponentDefinition.Port port : box.definition().inputPorts()) {
                String sinkId = box.sinkIdsByName().get(box.sinkNamesByPort().get(port.name()));
                String argument = sinkExpressionById.get(sinkId);
                if (argument == null) {
                    throw new IllegalArgumentException(
                            "cannot export component instance " + box.expressionName()
                                    + ": no driven expression found for input " + port.name());
                }
                arguments.add(argument);
            }
            callLines.add(box.expressionName() + " := " + box.definition().name()
                    + "(" + String.join(", ", arguments) + ")");
        }

        List<String> expressionLines = new ArrayList<>();
        for (String line : boundaryDescription.text().split("\\R")) {
            int separator = line.indexOf(" = ");
            if (separator >= 0) {
                String left = line.substring(0, separator);
                String componentId = boundaryDescription.componentIds().get(left);
                if (componentId != null && syntheticSinkIds.contains(componentId)) {
                    continue;
                }
            }
            expressionLines.add(replaceSignalNames(line, replacements));
        }
        String text = String.join("\n", concat(callLines, expressionLines));
        Map<String, String> componentIds = new LinkedHashMap<>();
        boundaryDescription.componentIds().forEach((name, id) -> {
            if (!usedSyntheticId(boxExports, id)) {
                componentIds.put(name, id);
            }
        });
        for (BoxExport box : boxExports) {
            componentIds.put(box.expressionName(), box.instance().id());
        }
        return new Description(text, componentIds);
    }

    private static boolean usedSyntheticId(List<BoxExport> boxes, String id) {
        return boxes.stream().anyMatch(box ->
                box.sourceIdsByName().containsValue(id) || box.sinkIdsByName().containsValue(id));
    }

    private static String uniqueSyntheticId(String base, Set<String> used) {
        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return result;
    }

    private static String replaceSignalNames(String expression, Map<String, String> replacements) {
        String result = expression;
        List<String> names = replacements.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
        for (String name : names) {
            Pattern pattern = Pattern.compile(
                    "(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])");
            result = pattern.matcher(result).replaceAll(
                    Matcher.quoteReplacement(replacements.get(name)));
        }
        return result;
    }

    private static GateDef findNarrowDefinition(
            Schematic schematic, String family, int inputCount) {
        return schematic.gateDefinitions().stream()
                .filter(definition -> definition.family().equals(family)
                        && definition.size().equals("narrow")
                        && definition.inputCount() == inputCount)
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "gate catalogue is missing " + family + " with " + inputCount + " inputs"));
    }

    private static PlacedComponent sourceAtPin(
            String id, GateDef definition, int pinX, int pinY, String label) {
        GateGeometry.Offset pin = GateGeometry.outputOffset(definition, Facing.EAST);
        return new PlacedComponent(
                id, definition.family(), pinX - pin.x(), pinY - pin.y(),
                Facing.EAST, Map.of(), label);
    }

    private static PlacedComponent sinkAtPin(
            String id, GateDef definition, int pinX, int pinY, String label) {
        GateGeometry.Offset pin = GateGeometry.inputOffsets(definition, Facing.EAST).getFirst();
        return new PlacedComponent(
                id, definition.family(), pinX - pin.x(), pinY - pin.y(),
                Facing.EAST, Map.of(), label);
    }

    /** Describes a netlist directly; its original signal ids are retained in the component map. */
    public static Description describe(Netlist netlist) {
        Objects.requireNonNull(netlist, "netlist");
        requireGateLimit(netlist.gates().size());

        Map<String, String> inputIds = new LinkedHashMap<>();
        netlist.inputs().forEach(input -> inputIds.put(input, input));
        Map<String, String> outputIds = new LinkedHashMap<>();
        netlist.outputs().forEach(output -> outputIds.put(output.name(), output.name()));
        Map<String, String> gateIds = new LinkedHashMap<>();
        netlist.gates().forEach(gate -> gateIds.put(gate.id(), gate.id()));
        return describeGraph(netlist,
                new SignalMappings(inputIds, outputIds, gateIds, Map.of()));
    }

    private static Description describeGraph(Netlist netlist, SignalMappings mappings) {
        Map<String, String> inputNames = new LinkedHashMap<>();
        Map<String, String> outputNames = new LinkedHashMap<>();
        Set<String> used = new LinkedHashSet<>();
        for (String input : netlist.inputs()) {
            inputNames.put(input, uniqueName(sanitizeIdentifier(input), used));
        }
        for (Netlist.Output output : netlist.outputs()) {
            outputNames.put(output.name(), uniqueName(sanitizeIdentifier(output.name()), used));
        }
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
        inputNames.forEach((original, emitted) ->
                putMapped(emittedComponentIds, emitted, mappings.inputs().get(original)));
        outputNames.forEach((original, emitted) ->
                putMapped(emittedComponentIds, emitted, mappings.outputs().get(original)));
        internalNames.forEach((gateId, emitted) ->
                putMapped(emittedComponentIds, emitted, mappings.gates().get(gateId)));
        for (String literal : List.of("0", "1")) {
            putMapped(emittedComponentIds, literal, mappings.constants().get(literal));
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

    private static Map<String, String> merged(
            Map<String, String> first, Map<String, String> second) {
        Map<String, String> result = new LinkedHashMap<>(first);
        second.forEach(result::put);
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
