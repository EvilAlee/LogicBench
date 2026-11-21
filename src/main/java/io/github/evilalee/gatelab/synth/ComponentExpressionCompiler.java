package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.GateType;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.WireSegment;
import io.github.evilalee.gatelab.synth.layout.SchematicLayout;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compiles typed Boolean expressions containing reusable library calls into a
 * schematic containing ordinary gates and first-class boxed component instances.
 *
 * <p>The resulting schematic is always simulated by SchematicCompiler. This class
 * is only an expression-to-schematic compiler and deterministic layout engine.
 */
public final class ComponentExpressionCompiler {
    public enum CompileOption {
        /** Library calls, including DLATCH/DFF/TFF/JKFF, become boxed instances. */
        BOXED_INSTANCES,
        /** Preserve the older gate-expanding sequential-parts compiler path. */
        LEGACY_SEQUENTIAL_GATE_EXPANSION
    }

    public record Compilation(
            Schematic schematic,
            Map<String, String> inputComponentIds,
            Map<String, String> clockComponentIds,
            Map<String, String> outputComponentIds,
            boolean hasSequentialInstances,
            boolean hasFeedback,
            Optional<ExpressionCompiler.Compilation> legacyCompilation) {
        public Compilation {
            Objects.requireNonNull(schematic, "schematic");
            inputComponentIds = Map.copyOf(Objects.requireNonNull(inputComponentIds));
            clockComponentIds = Map.copyOf(Objects.requireNonNull(clockComponentIds));
            outputComponentIds = Map.copyOf(Objects.requireNonNull(outputComponentIds));
            legacyCompilation = Objects.requireNonNull(legacyCompilation);
        }

        public boolean usesBoxedInstances() {
            return !schematic.componentInstances().isEmpty();
        }
    }

    private enum AssignmentKind {
        OUTPUT,
        INTERNAL
    }

    private record Statement(String name, AssignmentKind kind, Expression expression) {
    }

    private record PinKey(String componentId, String pinId) {
    }

    private record OutputSink(String id, String name, String signal) {
    }

    private enum NodeKind {
        INPUT,
        CONSTANT,
        GATE,
        INSTANCE
    }

    private static final class Node {
        private final String id;
        private final NodeKind kind;
        private final List<String> inputs = new ArrayList<>();
        private final Map<String, String> outputs = new LinkedHashMap<>();
        private GateType gateType;
        private ComponentDefinition componentDefinition;
        private String sourceName;
        private boolean clock;
        private boolean constantValue;
        private int depth;
        private int row;
        private int x;
        private int y;
        private GateDef visualDefinition;
        private GateGeometry.Bounds bounds;

        private Node(String id, NodeKind kind) {
            this.id = id;
            this.kind = kind;
        }
    }

    private record FeedbackInput(String targetId, int inputIndex) {
    }

    private record Edge(
            String signal,
            Node source,
            String sourcePin,
            String targetId,
            String targetPin,
            int targetColumn,
            int targetLeft,
            boolean feedback) {
    }

    private static final class InstanceAlias {
        private final Node node;
        private final Map<String, String> outputSignals;

        private InstanceAlias(Node node) {
            this.node = node;
            this.outputSignals = node.outputs;
        }
    }

    private static final class LayoutFailure extends IllegalStateException {
        private LayoutFailure(String message) {
            super(message);
        }
    }

    private ComponentExpressionCompiler() {
    }

    public static Compilation compile(
            String source,
            Collection<GateDef> gateDefinitions,
            Collection<ComponentDefinition> componentDefinitions) {
        return compile(source, gateDefinitions, componentDefinitions,
                CompileOption.BOXED_INSTANCES, true, false);
    }

    public static Compilation compile(
            String source,
            Collection<GateDef> gateDefinitions,
            Collection<ComponentDefinition> componentDefinitions,
            CompileOption option) {
        return compile(source, gateDefinitions, componentDefinitions, option, true, false);
    }

    public static Compilation compile(
            String source,
            Collection<GateDef> gateDefinitions,
            Collection<ComponentDefinition> componentDefinitions,
            CompileOption option,
            boolean allowRawFeedback,
            boolean invertingGates) {
        Objects.requireNonNull(source, "source");
        List<GateDef> gates = List.copyOf(Objects.requireNonNull(gateDefinitions));
        List<ComponentDefinition> definitions =
                List.copyOf(Objects.requireNonNull(componentDefinitions));
        Objects.requireNonNull(option, "option");

        if (option == CompileOption.LEGACY_SEQUENTIAL_GATE_EXPANSION) {
            ExpressionCompiler.Compilation legacy =
                    ExpressionCompiler.compile(source, allowRawFeedback, invertingGates);
            SchematicLayout layoutEngine = new SchematicLayout(gates);
            SchematicLayout.LayoutResult layout = legacy.hasFeedback()
                    ? layoutEngine.layoutFeedback(legacy.feedbackExtraction().orElseThrow())
                    : layoutEngine.layout(legacy.netlist());
            return new Compilation(layout.schematic(),
                    layout.inputComponentIds(), layout.clockComponentIds(),
                    layout.outputComponentIds(), legacy.hasSequentialParts(),
                    legacy.hasFeedback(), Optional.of(legacy));
        }

        return new Builder(gates, definitions, allowRawFeedback).compile(source);
    }

    private static final class Builder {
        private final List<GateDef> gateDefinitions;
        private final Map<String, GateDef> narrowGates = new LinkedHashMap<>();
        private final Map<String, ComponentDefinition> definitions = new LinkedHashMap<>();
        private final boolean allowRawFeedback;
        private final Map<String, Statement> assignments = new LinkedHashMap<>();
        private final Map<String, Integer> assignmentState = new HashMap<>();
        private final Map<String, String> assignmentSignals = new HashMap<>();
        private final Map<String, InstanceAlias> aliases = new LinkedHashMap<>();
        private final List<Node> nodes = new ArrayList<>();
        private final Map<String, Node> nodesById = new LinkedHashMap<>();
        private final Map<String, Node> inputNodes = new LinkedHashMap<>();
        private final Map<String, String> outputComponentIds = new LinkedHashMap<>();
        private final List<OutputSink> outputs = new ArrayList<>();
        private final Set<String> usedComponentIds = new LinkedHashSet<>();
        private final Set<String> usedSignals = new LinkedHashSet<>();
        private final Set<FeedbackInput> feedbackInputs = new LinkedHashSet<>();
        private int nextGate = 1;
        private int nextInstance = 1;
        private int nextOutput = 1;
        private boolean hasSequentialInstances;
        private int activeComponentCallDepth;

        private Builder(
                List<GateDef> gateDefinitions,
                List<ComponentDefinition> componentDefinitions,
                boolean allowRawFeedback) {
            this.gateDefinitions = gateDefinitions;
            this.allowRawFeedback = allowRawFeedback;
            for (GateDef definition : gateDefinitions) {
                if (definition.size().equals("narrow")) {
                    narrowGates.putIfAbsent(
                            definition.family() + "#" + definition.inputCount(), definition);
                }
            }
            for (ComponentDefinition definition : componentDefinitions) {
                if (definitions.putIfAbsent(definition.name(), definition) != null) {
                    throw new IllegalArgumentException(
                            "duplicate component definition in expression library: " + definition.name());
                }
            }
            if (!narrowGates.containsKey("input#0")
                    || !narrowGates.containsKey("output#1")) {
                throw new IllegalArgumentException(
                        "gate catalogue must define input and output components");
            }
        }

        private Compilation compile(String source) {
            parseStatements(source);
            List<Statement> outputAssignments = assignments.values().stream()
                    .filter(statement -> statement.kind() == AssignmentKind.OUTPUT)
                    .toList();
            if (outputAssignments.isEmpty()) {
                throw new IllegalArgumentException("expression list must define at least one output");
            }

            for (Statement output : outputAssignments) {
                String signal = resolveAssignment(output.name());
                if (signal == null) {
                    throw multiOutputScalarError(output.name(), output.expression());
                }
                outputs.add(new OutputSink(
                        uniqueComponentId("exprOutput_" + safe(output.name())),
                        output.name(), signal));
            }

            Schematic schematic = layoutAndRoute();
            verifyConnectivity(schematic);
            Map<String, String> inputs = new LinkedHashMap<>();
            Map<String, String> clocks = new LinkedHashMap<>();
            inputNodes.values().stream()
                    .sorted(Comparator.comparing(node -> node.sourceName))
                    .forEach(node -> {
                        if (node.clock) {
                            clocks.put(node.sourceName, node.id);
                        } else {
                            inputs.put(node.sourceName, node.id);
                        }
                    });
            return new Compilation(
                    schematic, inputs, clocks, outputComponentIds,
                    hasSequentialInstances, !feedbackInputs.isEmpty(), Optional.empty());
        }

        private void parseStatements(String source) {
            List<String> rawStatements = splitStatements(source);
            if (rawStatements.isEmpty()) {
                throw new ParseException(0, "expression list is empty");
            }
            int unnamed = 0;
            for (String raw : rawStatements) {
                String text = raw.trim();
                if (text.isEmpty()) {
                    continue;
                }
                int internal = text.indexOf(":=");
                int equals = text.indexOf('=');
                AssignmentKind kind;
                int split;
                int operatorWidth;
                if (internal >= 0 && (equals < 0 || internal <= equals)) {
                    kind = AssignmentKind.INTERNAL;
                    split = internal;
                    operatorWidth = 2;
                } else if (equals >= 0) {
                    kind = AssignmentKind.OUTPUT;
                    split = equals;
                    operatorWidth = 1;
                } else {
                    kind = AssignmentKind.OUTPUT;
                    split = -1;
                    operatorWidth = 0;
                }

                String name;
                String expressionText;
                if (split < 0) {
                    name = "F" + (++unnamed);
                    expressionText = text;
                } else {
                    name = text.substring(0, split).trim();
                    expressionText = text.substring(split + operatorWidth).trim();
                    if (!name.matches("[A-Za-z][A-Za-z0-9_]*")) {
                        throw new IllegalArgumentException(
                                "invalid expression assignment name: " + name);
                    }
                    if (expressionText.isEmpty()) {
                        throw new ParseException(split + operatorWidth,
                                "expected an expression after assignment");
                    }
                }
                Expression expression = ExpressionParser.parse(expressionText);
                Statement previous = assignments.putIfAbsent(
                        name, new Statement(name, kind, expression));
                if (previous != null) {
                    throw new IllegalArgumentException(
                            kind == AssignmentKind.OUTPUT
                                    ? "duplicate output or signal name: " + name
                                    : "duplicate signal definition: " + name);
                }
            }
        }

        private String resolveAssignment(String name) {
            Statement statement = assignments.get(name);
            if (statement == null) {
                return inputSignal(name, false);
            }
            int state = assignmentState.getOrDefault(name, 0);
            if (state == 2) {
                String value = assignmentSignals.get(name);
                if (value == null) {
                    throw multiOutputScalarError(name, statement.expression());
                }
                return value;
            }
            if (state == 1) {
                String reserved = assignmentSignals.get(name);
                if (reserved != null) {
                    return reserved;
                }
                if (allowRawFeedback || activeComponentCallDepth > 0) {
                    return assignmentSignal(name);
                }
                throw new IllegalArgumentException(
                        "raw feedback is disabled; cycle reaches expression " + name);
            }

            assignmentState.put(name, 1);
            Expression expression = statement.expression();
            String result;
            if (expression instanceof Expression.ComponentCall call) {
                result = instantiate(call, name, statement.kind(), name);
            } else {
                String reserved = assignmentSignal(name);
                assignmentSignals.put(name, reserved);
                result = buildExpression(expression, reserved, false);
                assignmentSignals.put(name, result);
            }
            assignmentState.put(name, 2);
            if (result == null && statement.kind() == AssignmentKind.OUTPUT) {
                throw multiOutputScalarError(name, expression);
            }
            return result;
        }

        private String buildExpression(
                Expression expression, String preferredOutput, boolean clockArgument) {
            if (clockArgument) {
                if (!(expression instanceof Expression.Variable variable)) {
                    throw new IllegalArgumentException(
                            "clock input must be a plain identifier, not an expression");
                }
                if (assignments.containsKey(variable.name())) {
                    throw new IllegalArgumentException(
                            "clock identifier '" + variable.name()
                                    + "' cannot be defined by an expression assignment");
                }
                return inputSignal(variable.name(), true);
            }

            return switch (expression) {
                case Expression.Constant constant ->
                        constantSignal(constant.value());
                case Expression.Variable variable ->
                        resolveAssignment(variable.name());
                case Expression.Unary unary -> {
                    String operand = buildExpression(unary.operand(), null, false);
                    yield addGate(GateType.NOT, List.of(operand), preferredOutput);
                }
                case Expression.Binary binary -> {
                    String left = buildExpression(binary.left(), null, false);
                    String right = buildExpression(binary.right(), null, false);
                    yield addGate(GateType.valueOf(binary.operator().name()),
                            List.of(left, right), preferredOutput);
                }
                case Expression.ComponentCall call ->
                        instantiate(call, null, null, null);
                case Expression.ComponentOutput output ->
                        instanceOutputSignal(output.instanceName(), output.outputName());
                case Expression.PartCall call -> throw new IllegalArgumentException(
                        "legacy sequential part node cannot be used in boxed-instance mode: " + call.kind());
            };
        }

        private String instantiate(
                Expression.ComponentCall call,
                String assignedInstanceName,
                AssignmentKind assignmentKind,
                String assignmentName) {
            ComponentDefinition definition = definitions.get(call.componentName());
            if (definition == null) {
                List<String> valid = definitions.keySet().stream().sorted().toList();
                throw new IllegalArgumentException(
                        "unknown component '" + call.componentName()
                                + "'; valid component names: " + valid);
            }

            List<ComponentDefinition.Port> inputPorts = definition.inputPorts();
            if (call.arguments().size() != inputPorts.size()) {
                throw new IllegalArgumentException(
                        definition.name() + " requires " + inputPorts.size()
                                + " arguments for input ports "
                                + inputPorts.stream().map(ComponentDefinition.Port::name).toList()
                                + ", got " + call.arguments().size());
            }
            List<ComponentDefinition.Port> outputPorts = definition.outputPorts();
            if (outputPorts.isEmpty()) {
                throw new IllegalArgumentException(
                        "component " + definition.name() + " has no output pins");
            }
            if (scalarOutputPort(definition) == null
                    && (assignmentName == null || assignmentKind == AssignmentKind.OUTPUT)) {
                throw multiOutputDirectCallError(definition);
            }

            String instanceId = assignedInstanceName == null
                    ? nextInstanceId() : uniqueComponentId(assignedInstanceName);
            Node node = new Node(instanceId, NodeKind.INSTANCE);
            node.componentDefinition = definition;
            for (ComponentDefinition.Port port : outputPorts) {
                node.outputs.put(port.name(),
                        uniqueSignal("sig_" + safe(instanceId) + "_" + safe(port.name())));
            }
            if (aliases.putIfAbsent(instanceId, new InstanceAlias(node)) != null) {
                throw new IllegalArgumentException(
                        "duplicate component instance name: " + instanceId);
            }

            String primary = scalarOutputPort(definition);
            String scalarSignal = primary == null ? null : node.outputs.get(primary);
            if (assignmentName != null) {
                assignmentSignals.put(assignmentName, scalarSignal);
            }

            activeComponentCallDepth++;
            try {
                for (int index = 0; index < inputPorts.size(); index++) {
                    ComponentDefinition.Port port = inputPorts.get(index);
                    String signal = buildExpression(
                            call.arguments().get(index), null, port.isClock());
                    node.inputs.add(signal);
                }
            } finally {
                activeComponentCallDepth--;
            }
            addNode(node);
            if (definition.category().toLowerCase(java.util.Locale.ROOT).contains("sequential")
                    || inputPorts.stream().anyMatch(ComponentDefinition.Port::isClock)) {
                hasSequentialInstances = true;
            }
            return scalarSignal;
        }

        private String scalarOutputPort(ComponentDefinition definition) {
            if (definition.primaryOutput() != null) {
                return definition.primaryOutput();
            }
            List<ComponentDefinition.Port> ports = definition.outputPorts();
            return ports.size() == 1 ? ports.getFirst().name() : null;
        }

        private String instanceOutputSignal(String instanceName, String outputName) {
            InstanceAlias alias = aliases.get(instanceName);
            if (alias == null) {
                Statement statement = assignments.get(instanceName);
                if (statement != null && statement.expression() instanceof Expression.ComponentCall) {
                    resolveAssignment(instanceName);
                    alias = aliases.get(instanceName);
                }
            }
            if (alias == null) {
                List<String> valid = aliases.keySet().stream().sorted().toList();
                throw new IllegalArgumentException(
                        "unknown component instance '" + instanceName
                                + "'; valid instance names: " + valid);
            }
            String signal = alias.outputSignals.get(outputName);
            if (signal == null) {
                List<String> valid = alias.node.componentDefinition.outputPorts().stream()
                        .map(ComponentDefinition.Port::name).toList();
                throw new IllegalArgumentException(
                        "unknown output pin '" + outputName + "' on " + instanceName
                                + "; valid output pins: " + valid);
            }
            return signal;
        }

        private IllegalArgumentException multiOutputDirectCallError(
                ComponentDefinition definition) {
            return new IllegalArgumentException(
                    "component " + definition.name() + " has multiple outputs "
                            + definition.outputPorts().stream()
                                    .map(ComponentDefinition.Port::name).toList()
                            + "; bind it with an internal assignment such as U0 := "
                            + definition.name() + "(...) and select U0.<pin>");
        }

        private IllegalArgumentException multiOutputScalarError(
                String name, Expression expression) {
            InstanceAlias alias = aliases.get(name);
            if (alias != null) {
                List<String> valid = alias.node.componentDefinition.outputPorts().stream()
                        .map(ComponentDefinition.Port::name).toList();
                return new IllegalArgumentException(
                        "multi-output component " + name + " must be selected by output pin: "
                                + valid);
            }
            if (expression instanceof Expression.ComponentCall call) {
                ComponentDefinition definition = definitions.get(call.componentName());
                if (definition != null) {
                    return multiOutputDirectCallError(definition);
                }
            }
            return new IllegalArgumentException(
                    "internal component " + name + " does not have a scalar output");
        }

        private String inputSignal(String name, boolean clock) {
            Node existing = inputNodes.get(name);
            if (existing != null) {
                if (clock) {
                    existing.clock = true;
                }
                return existing.outputs.get("out");
            }
            Node node = new Node(
                    uniqueComponentId("exprInput_" + safe(name)), NodeKind.INPUT);
            node.sourceName = name;
            node.clock = clock;
            node.outputs.put("out", uniqueSignal("sig_input_" + safe(name)));
            inputNodes.put(name, node);
            addNode(node);
            return node.outputs.get("out");
        }

        private String constantSignal(boolean value) {
            Optional<Node> existing = nodes.stream()
                    .filter(node -> node.kind == NodeKind.CONSTANT
                            && node.constantValue == value)
                    .findFirst();
            if (existing.isPresent()) {
                return existing.orElseThrow().outputs.get("out");
            }
            Node node = new Node(
                    uniqueComponentId(value ? "exprConst1" : "exprConst0"), NodeKind.CONSTANT);
            node.constantValue = value;
            node.outputs.put("out", uniqueSignal(value ? "sig_constant_1" : "sig_constant_0"));
            addNode(node);
            return node.outputs.get("out");
        }

        private String addGate(GateType type, List<String> inputs, String preferredOutput) {
            String id = uniqueComponentId("exprGate" + nextGate++);
            Node node = new Node(id, NodeKind.GATE);
            node.gateType = Objects.requireNonNull(type, "type");
            node.inputs.addAll(inputs);
            String output = preferredOutput == null
                    ? uniqueSignal("sig_" + id) : preferredOutput;
            node.outputs.put("out", output);
            addNode(node);
            return output;
        }

        private String assignmentSignal(String name) {
            return uniqueSignal("sig_assign_" + safe(name));
        }

        private String nextInstanceId() {
            String candidate;
            do {
                candidate = "U" + nextInstance++;
            } while (usedComponentIds.contains(candidate) || assignments.containsKey(candidate));
            return uniqueComponentId(candidate);
        }

        private void addNode(Node node) {
            if (nodesById.putIfAbsent(node.id, node) != null) {
                throw new IllegalArgumentException("duplicate compiled component id: " + node.id);
            }
            for (String signal : node.outputs.values()) {
                if (!usedSignals.contains(signal)) {
                    usedSignals.add(signal);
                }
            }
            nodes.add(node);
        }

        private String uniqueComponentId(String base) {
            String candidate = base;
            int suffix = 2;
            while (!usedComponentIds.add(candidate)) {
                candidate = base + "_" + suffix++;
            }
            return candidate;
        }

        private String uniqueSignal(String base) {
            String candidate = base;
            int suffix = 2;
            while (!usedSignals.add(candidate)) {
                candidate = base + "_" + suffix++;
            }
            return candidate;
        }

        private Schematic layoutAndRoute() {
            Map<String, Node> producerBySignal = new LinkedHashMap<>();
            for (Node node : nodes) {
                for (String signal : node.outputs.values()) {
                    Node old = producerBySignal.putIfAbsent(signal, node);
                    if (old != null) {
                        throw new IllegalStateException(
                                "signal " + signal + " has multiple drivers: " + old.id + " and " + node.id);
                    }
                }
            }

            Map<String, Integer> state = new HashMap<>();
            for (Node node : nodes) {
                depth(node, producerBySignal, state, new ArrayList<>());
            }
            int maxDepth = nodes.stream().mapToInt(node -> node.depth).max().orElse(0);

            Map<Integer, List<Node>> columns = new TreeMap<>();
            for (Node node : nodes) {
                columns.computeIfAbsent(node.depth, ignored -> new ArrayList<>()).add(node);
            }
            columns.values().forEach(column ->
                    column.sort(Comparator.comparing(node -> node.id)));

            int outputColumn = maxDepth + 1;
            int edgeBudget = nodes.stream().mapToInt(node -> node.inputs.size()).sum()
                    + outputs.size();
            int columnPitch = Math.max(440, 300 + edgeBudget * 20);
            int outputLeft = 100 + outputColumn * columnPitch;
            List<Edge> edges = buildEdges(producerBySignal, maxDepth, outputLeft);
            Map<Integer, Integer> outgoingCounts = new HashMap<>();
            Map<Integer, Integer> incomingCounts = new HashMap<>();
            for (Edge edge : edges) {
                outgoingCounts.merge(edge.source().depth, 1, Integer::sum);
                incomingCounts.merge(edge.targetColumn(), 1, Integer::sum);
            }
            Map<Integer, Integer> columnRowStep = new HashMap<>();
            for (Map.Entry<Integer, List<Node>> entry : columns.entrySet()) {
                int tallest = entry.getValue().stream()
                        .mapToInt(node -> visualHeight(node)).max().orElse(40);
                columnRowStep.put(entry.getKey(), tallest + 80);
            }

            Schematic schematic = new Schematic(gateDefinitions);
            for (Map.Entry<Integer, List<Node>> entry : columns.entrySet()) {
                int column = entry.getKey();
                int row = 0;
                for (Node node : entry.getValue()) {
                    node.row = row;
                    node.x = 100 + column * columnPitch;
                    node.y = 100 + row * columnRowStep.get(column);
                    placeNode(schematic, node);
                    row++;
                }
            }

            int outputRowStep = narrowGate("output", 1).height() + 60;
            for (int index = 0; index < outputs.size(); index++) {
                OutputSink output = outputs.get(index);
                GateDef definition = narrowGate("output", 1);
                int top = 100 + index * outputRowStep;
                PlacedComponent component = placedAtTopLeft(
                        output.id(), definition, outputLeft, top, output.name());
                schematic.add(component);
                outputComponentIds.put(output.name(), output.id());
            }

            Map<PinKey, Connectivity.PinPosition> pinByKey = new LinkedHashMap<>();
            for (Connectivity.PinPosition pin : schematic.pinPositions()) {
                pinByKey.put(new PinKey(pin.componentId(), pin.pinId()), pin);
            }
            Map<String, PinKey> driverPinBySignal = new LinkedHashMap<>();
            Map<PinKey, String> expectedSignalByPin = new LinkedHashMap<>();
            for (Node node : nodes) {
                for (Map.Entry<String, String> output : node.outputs.entrySet()) {
                    PinKey key = new PinKey(node.id, output.getKey());
                    driverPinBySignal.put(output.getValue(), key);
                    expectedSignalByPin.put(key, output.getValue());
                }
                for (int index = 0; index < node.inputs.size(); index++) {
                    String pinName = node.kind == NodeKind.INSTANCE
                            ? node.componentDefinition.inputPorts().get(index).name()
                            : "in" + index;
                    expectedSignalByPin.put(
                            new PinKey(node.id, pinName), node.inputs.get(index));
                }
            }
            for (OutputSink output : outputs) {
                expectedSignalByPin.put(new PinKey(output.id(), "in0"), output.signal());
            }

            List<RouteDescription> routes = new ArrayList<>();
            Map<Integer, Integer> sourceTrackIndex = new HashMap<>();
            Map<Integer, Integer> targetTrackIndex = new HashMap<>();
            int baseLane = schematic.pinPositions().stream()
                    .mapToInt(Connectivity.PinPosition::y).max().orElse(100) + 140;
            int routeIndex = 0;
            for (Edge edge : edges) {
                PinKey sourceKey = driverPinBySignal.get(edge.signal());
                if (sourceKey == null) {
                    throw new IllegalStateException(
                            "no driver pin for signal " + edge.signal()
                                    + " feeding " + edge.targetId() + "." + edge.targetPin());
                }
                Connectivity.PinPosition sourcePin = pinByKey.get(sourceKey);
                Connectivity.PinPosition targetPin =
                        pinByKey.get(new PinKey(edge.targetId(), edge.targetPin()));
                if (sourcePin == null || targetPin == null) {
                    throw new IllegalStateException(
                            "layout pin missing for route " + edge.signal()
                                    + " from " + sourceKey + " to "
                                    + edge.targetId() + "." + edge.targetPin());
                }

                int sourceIndex = sourceTrackIndex.merge(edge.source().depth, 1, Integer::sum) - 1;
                int targetIndex = targetTrackIndex.merge(edge.targetColumn(), 1, Integer::sum) - 1;
                int sourceRight = nodeBounds(edge.source()).x()
                        + nodeBounds(edge.source()).width();
                int targetLeft = edge.targetLeft();
                int sourceTrackX = sourceRight + 20 + sourceIndex * 10;
                int targetTrackX = targetLeft - 20 - targetIndex * 10;
                int laneY = baseLane + routeIndex++ * 20;

                addWire(schematic, sourcePin.x(), sourcePin.y(), sourceTrackX, sourcePin.y());
                addWire(schematic, sourceTrackX, sourcePin.y(), sourceTrackX, laneY);
                addWire(schematic, sourceTrackX, laneY, targetTrackX, laneY);
                addWire(schematic, targetTrackX, laneY, targetTrackX, targetPin.y());
                addWire(schematic, targetTrackX, targetPin.y(), targetPin.x(), targetPin.y());

                routes.add(new RouteDescription(
                        edge.signal(), sourceKey, sourcePin.x(), sourcePin.y(),
                        edge.targetId(), edge.targetPin(), targetPin.x(), targetPin.y(),
                        sourceTrackX, targetTrackX, laneY, edge.feedback()));
            }
            currentRoutes = List.copyOf(routes);

            for (OutputSink output : outputs) {
                PinKey targetKey = new PinKey(output.id(), "in0");
                if (!driverPinBySignal.containsKey(output.signal())) {
                    throw new IllegalStateException(
                            "output " + output.name() + " has no signal driver: " + output.signal());
                }
            }

            verifyConnectivity(schematic, expectedSignalByPin, routes);
            return schematic;
        }

        private record RouteDescription(
                String signal, PinKey sourceKey, int sourceX, int sourceY,
                String targetId, String targetPin, int targetX, int targetY,
                int sourceTrackX, int targetTrackX, int laneY, boolean feedback) {
            @Override
            public String toString() {
                return signal + ": " + sourceKey + "@" + sourceX + "," + sourceY
                        + " -> " + targetId + "." + targetPin + "@" + targetX + "," + targetY
                        + " tracks=" + sourceTrackX + "," + targetTrackX
                        + " laneY=" + laneY + " feedback=" + feedback;
            }
        }

        private List<Edge> buildEdges(
                Map<String, Node> producerBySignal, int maxDepth, int outputLeft) {
            List<Edge> result = new ArrayList<>();
            for (Node node : nodes) {
                for (int index = 0; index < node.inputs.size(); index++) {
                    String signal = node.inputs.get(index);
                    Node producer = producerBySignal.get(signal);
                    if (producer == null) {
                        throw new IllegalStateException(
                                "signal " + signal + " has no producer for "
                                        + node.id + " input " + index);
                    }
                    String pinName = node.kind == NodeKind.INSTANCE
                            ? node.componentDefinition.inputPorts().get(index).name()
                            : "in" + index;
                    boolean feedback = producer.depth >= node.depth
                            || feedbackInputs.contains(new FeedbackInput(node.id, index));
                    result.add(new Edge(signal, producer, producerOutputPin(producer, signal),
                            node.id, pinName, node.depth, node.bounds.x(), feedback));
                }
            }

            int outputColumn = maxDepth + 1;
            for (OutputSink output : outputs) {
                Node producer = producerBySignal.get(output.signal());
                if (producer == null) {
                    throw new IllegalStateException(
                            "output " + output.name() + " has no producer: " + output.signal());
                }
                int left = outputLeft;
                result.add(new Edge(
                        output.signal(), producer, producerOutputPin(producer, output.signal()),
                        output.id(), "in0", outputColumn, left,
                        producer.depth >= outputColumn));
            }
            return result;
        }

        private String producerOutputPin(Node producer, String signal) {
            return producer.outputs.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(signal))
                    .map(Map.Entry::getKey)
                    .findFirst().orElseThrow();
        }

        private int depth(
                Node node,
                Map<String, Node> producerBySignal,
                Map<String, Integer> state,
                List<String> path) {
            int current = state.getOrDefault(node.id, 0);
            if (current == 2) {
                return node.depth;
            }
            if (current == 1) {
                return 0;
            }
            if (node.kind == NodeKind.INPUT || node.kind == NodeKind.CONSTANT) {
                node.depth = 0;
                state.put(node.id, 2);
                return 0;
            }

            state.put(node.id, 1);
            path.add(node.id);
            int value = 1;
            for (int index = 0; index < node.inputs.size(); index++) {
                String signal = node.inputs.get(index);
                Node source = producerBySignal.get(signal);
                if (source == null) {
                    throw new IllegalStateException(
                            "unresolved signal " + signal + " at " + node.id);
                }
                if (state.getOrDefault(source.id, 0) == 1) {
                    feedbackInputs.add(new FeedbackInput(node.id, index));
                    continue;
                }
                value = Math.max(value, depth(source, producerBySignal, state, path) + 1);
            }
            path.removeLast();
            node.depth = value;
            state.put(node.id, 2);
            return value;
        }

        private void placeNode(Schematic schematic, Node node) {
            if (node.kind == NodeKind.INSTANCE) {
                ComponentInstance instance = new ComponentInstance(
                        node.componentDefinition.name(), node.componentDefinition.hash(),
                        node.id, "", node.x, node.y, Facing.EAST);
                schematic.addInstance(instance, node.componentDefinition);
                node.bounds = instance.bounds(node.componentDefinition);
                return;
            }
            GateDef definition = visualDefinition(node);
            node.visualDefinition = definition;
            PlacedComponent component = placedAtTopLeft(
                    node.id, definition, node.x, node.y, node.sourceName == null ? "" : node.sourceName);
            schematic.add(component);
            GateGeometry.Bounds bounds = GateGeometry.bounds(definition, Facing.EAST);
            node.bounds = new GateGeometry.Bounds(
                    component.x() + bounds.x(), component.y() + bounds.y(),
                    bounds.width(), bounds.height());
        }

        private PlacedComponent placedAtTopLeft(
                String id, GateDef definition, int left, int top, String label) {
            GateGeometry.Offset origin = definition.origin();
            return new PlacedComponent(
                    id, definition.family(), left + origin.x(), top + origin.y(),
                    Facing.EAST, Map.of(), label);
        }

        private GateDef visualDefinition(Node node) {
            return switch (node.kind) {
                case INPUT -> narrowGate(node.clock ? "clock" : "input", 0);
                case CONSTANT -> narrowGate("constant", 0);
                case GATE -> narrowGate(
                        node.gateType.name().toLowerCase(java.util.Locale.ROOT), node.inputs.size());
                case INSTANCE -> throw new IllegalStateException("instance has no gate artwork");
            };
        }

        private int visualHeight(Node node) {
            if (node.kind == NodeKind.INSTANCE) {
                return node.componentDefinition.inputPorts().size() > node.componentDefinition.outputPorts().size()
                        ? node.componentDefinition.inputPorts().size() * ComponentInstance.PORT_SPACING + 20
                        : node.componentDefinition.outputPorts().size() * ComponentInstance.PORT_SPACING + 20;
            }
            return GateGeometry.bounds(visualDefinition(node), Facing.EAST).height();
        }

        private GateGeometry.Bounds nodeBounds(Node node) {
            return Objects.requireNonNull(node.bounds, "node bounds");
        }

        private GateDef narrowGate(String family, int inputs) {
            GateDef definition = narrowGates.get(family + "#" + inputs);
            if (definition == null) {
                throw new IllegalArgumentException(
                        "gate catalogue has no narrow " + family + " component with "
                                + inputs + " inputs");
            }
            return definition;
        }

        private void addWire(Schematic schematic, int x1, int y1, int x2, int y2) {
            if (x1 == x2 && y1 == y2) {
                return;
            }
            schematic.addWire(new WireSegment(x1, y1, x2, y2));
        }

        private void verifyConnectivity(Schematic schematic) {
            Map<PinKey, String> expected = new LinkedHashMap<>();
            Map<String, Node> producerBySignal = new LinkedHashMap<>();
            for (Node node : nodes) {
                for (Map.Entry<String, String> output : node.outputs.entrySet()) {
                    expected.put(new PinKey(node.id, output.getKey()), output.getValue());
                    producerBySignal.put(output.getValue(), node);
                }
                for (int index = 0; index < node.inputs.size(); index++) {
                    String pinName = node.kind == NodeKind.INSTANCE
                            ? node.componentDefinition.inputPorts().get(index).name()
                            : "in" + index;
                    expected.put(new PinKey(node.id, pinName), node.inputs.get(index));
                }
            }
            for (OutputSink output : outputs) {
                expected.put(new PinKey(output.id(), "in0"), output.signal());
            }

            List<RouteDescription> routes = currentRoutes;
            verifyConnectivity(schematic, expected, routes);
        }

        private List<RouteDescription> currentRoutes = List.of();

        private void verifyConnectivity(
                Schematic schematic,
                Map<PinKey, String> expected,
                List<RouteDescription> routes) {
            Connectivity.Result topology = Connectivity.analyze(
                    schematic.pinPositions(), schematic.wires());
            Map<PinKey, Connectivity.PinPosition> actualPins = new LinkedHashMap<>();
            for (Connectivity.PinPosition pin : schematic.pinPositions()) {
                actualPins.put(new PinKey(pin.componentId(), pin.pinId()), pin);
            }

            List<String> failures = new ArrayList<>();
            Map<String, Integer> netBySignal = new HashMap<>();
            int netIndex = 0;
            for (Connectivity.Net net : topology.nets()) {
                Set<String> signals = new TreeSet<>();
                for (Connectivity.PinPosition pin : net.pins()) {
                    String signal = expected.get(new PinKey(pin.componentId(), pin.pinId()));
                    if (signal == null) {
                        failures.add("unexpected pin " + pin.componentId() + "." + pin.pinId()
                                + "@" + pin.x() + "," + pin.y());
                    } else {
                        signals.add(signal);
                    }
                }
                if (signals.size() > 1) {
                    failures.add("net " + netIndex + " joins different signals " + signals
                            + " pins=" + net.pins().stream()
                                    .map(pin -> pin.componentId() + "." + pin.pinId()
                                            + "@" + pin.x() + "," + pin.y()).toList());
                }
                for (String signal : signals) {
                    Integer previous = netBySignal.putIfAbsent(signal, netIndex);
                    if (previous != null && previous != netIndex) {
                        failures.add("signal " + signal + " split between nets "
                                + previous + " and " + netIndex);
                    }
                }
                netIndex++;
            }
            for (Map.Entry<PinKey, String> entry : expected.entrySet()) {
                if (!actualPins.containsKey(entry.getKey())) {
                    failures.add("missing pin " + entry.getKey() + " for " + entry.getValue());
                }
            }

            if (!failures.isEmpty()) {
                String pinReport = actualPins.values().stream()
                        .map(pin -> pin.componentId() + "." + pin.pinId()
                                + "@" + pin.x() + "," + pin.y()
                                + " signal=" + expected.get(new PinKey(
                                        pin.componentId(), pin.pinId())))
                        .sorted().toList().toString();
                throw new LayoutFailure(
                        "Component-instance layout connectivity self-check failed.\n"
                                + "Failures: " + failures + "\n"
                                + "Pin positions: " + pinReport + "\n"
                                + "Route lanes: " + routes + "\n"
                                + "Schematic wires: " + schematic.wires());
            }
        }

        private static List<String> splitStatements(String source) {
            List<String> result = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            int depth = 0;
            for (int index = 0; index < source.length(); index++) {
                char ch = source.charAt(index);
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                    if (depth < 0) {
                        throw new ParseException(index, "unexpected ')'");
                    }
                }
                if ((ch == ';' || ch == '\n' || ch == '\r') && depth == 0) {
                    if (!current.toString().isBlank()) {
                        result.add(current.toString());
                    }
                    current.setLength(0);
                } else {
                    current.append(ch);
                }
            }
            if (depth != 0) {
                throw new ParseException(source.length(), "unclosed '(' in expression list");
            }
            if (!current.toString().isBlank()) {
                result.add(current.toString());
            }
            return List.copyOf(result);
        }

        private static String safe(String value) {
            String result = value.replaceAll("[^A-Za-z0-9_]", "_");
            if (result.isEmpty() || !Character.isLetter(result.charAt(0))) {
                result = "X_" + result;
            }
            return result;
        }
    }
}
