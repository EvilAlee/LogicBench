package io.github.evilalee.gatelab.synth.layout;

import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.GateType;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.SchematicSnapshot;
import io.github.evilalee.gatelab.model.WireSegment;
import io.github.evilalee.gatelab.synth.Netlist;
import io.github.evilalee.gatelab.synth.FeedbackCircuitExtractor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministically converts a validated expression netlist into ordinary,
 * editable Schematic components and orthogonal geometric wires.
 *
 * <p>Every layer is aligned to its left body edge. Channel width grows with
 * its private vertical trunk count. Each column receives its own compact
 * vertical coordinate range: primary inputs use a fixed pitch, while later
 * columns are aligned to the actual source and sink pin rows over three
 * alternating sweeps. Real component bounds come from GateDef/GateGeometry;
 * dummy nodes reserve a 20-unit slot. The fixed vertical clearance is 40
 * units. All spacing dimensions are doubled on the single retry after a
 * failed connectivity self-check.
 */
public final class SchematicLayout {
    /*
     * Spacing audit for the 10-unit schematic grid (all values are multiples
     * of PlacedComponent.GRID_SPACING). Each fixed clearance is the minimum
     * supported clearance plus one grid cell:
     *
     *   ROW_GAP              40 = 30 minimum pin-row separation + 10 safety
     *   DUMMY_SLOT_HEIGHT    20 = 10 minimum dummy slot + 10 safety
     *   TRUNK_START_MARGIN   20 = 10 minimum body clearance + 10 safety
     *   TRUNK_PITCH          20 = 10 minimum distinct-track spacing + 10 safety
     *   TRUNK_END_MARGIN     20 = 10 minimum body clearance + 10 safety
     *
     * Normal column gap is 20 + 20*trackCount: one start margin, one end
     * margin, and one pitch per routed signal. Feedback-only gaps reserve
     * 20*(sourceTracks + targetTracks + 2) for the same two margins and
     * every dedicated source/target trunk. These are already at min + 1 cell;
     * reducing them would violate the closed-lane clearance rule.
     */
    private static final int ROW_GAP = 40;
    private static final int DUMMY_SLOT_HEIGHT = 20;
    private static final int TRUNK_START_MARGIN = 20;
    private static final int TRUNK_PITCH = 20;
    private static final int TRUNK_END_MARGIN = 20;

    private final List<GateDef> definitions;
    private final Map<String, GateDef> definitionsByFamily;

    public SchematicLayout(Collection<GateDef> definitions) {
        this.definitions = List.copyOf(Objects.requireNonNull(definitions, "definitions"));
        Map<String, GateDef> byFamily = new LinkedHashMap<>();
        for (GateDef definition : this.definitions) {
            Objects.requireNonNull(definition, "definition");
            if (definition.size().equals("narrow")) {
                byFamily.putIfAbsent(definition.family(), definition);
            }
        }
        definitionsByFamily = Map.copyOf(byFamily);
        if (!definitionsByFamily.containsKey("input")
                || !definitionsByFamily.containsKey("output")) {
            throw new IllegalArgumentException("catalogue must define input and output components");
        }
    }

    /** Result includes the component-id mapping needed by placed-schematic verification. */
    public record LayoutResult(
            Schematic schematic,
            Map<String, String> inputComponentIds,
            Map<String, String> outputComponentIds,
            Map<String, String> constantComponentIds,
            int dummyNodeCount,
            Map<String, String> clockComponentIds,
            Map<String, String> gateComponentIds) {
        public LayoutResult {
            Objects.requireNonNull(schematic, "schematic");
            inputComponentIds = immutableOrderedMap(inputComponentIds);
            outputComponentIds = immutableOrderedMap(outputComponentIds);
            constantComponentIds = immutableOrderedMap(constantComponentIds);
            clockComponentIds = immutableOrderedMap(clockComponentIds);
            gateComponentIds = immutableOrderedMap(gateComponentIds);
            if (dummyNodeCount < 0) {
                throw new IllegalArgumentException("dummyNodeCount must not be negative");
            }
        }

        public LayoutResult(
                Schematic schematic,
                Map<String, String> inputComponentIds,
                Map<String, String> outputComponentIds,
                Map<String, String> constantComponentIds,
                int dummyNodeCount) {
            this(schematic, inputComponentIds, outputComponentIds,
                    constantComponentIds, dummyNodeCount, Map.of(), Map.of());
        }

        private static Map<String, String> immutableOrderedMap(Map<String, String> values) {
            return java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(values, "values")));
        }
    }

    private enum NodeKind {
        INPUT,
        CLOCK,
        FEEDBACK,
        CONSTANT,
        GATE,
        BOX,
        OUTPUT,
        DUMMY
    }

    private static final class Node {
        private final String key;
        private final NodeKind kind;
        private int column;
        private final int slotHeight;
        private final String signal;
        private final String componentId;
        private final GateDef definition;
        private final ComponentDefinition boxDefinition;
        private final List<String> inputPinNames;
        private final Map<String, String> outputSignalsByPin;
        private final List<Node> previous = new ArrayList<>();
        private final List<Node> next = new ArrayList<>();
        private int x;
        private int y;

        private Node(
                String key,
                NodeKind kind,
                int column,
                int slotHeight,
                String signal,
                String componentId,
                GateDef definition) {
            this.key = key;
            this.kind = kind;
            this.column = column;
            this.slotHeight = slotHeight;
            this.signal = signal;
            this.componentId = componentId;
            this.definition = definition;
            this.boxDefinition = null;
            this.inputPinNames = List.of();
            this.outputSignalsByPin = signal == null ? Map.of() : Map.of("out", signal);
        }

        private Node(
                String key,
                NodeKind kind,
                int column,
                int slotHeight,
                String componentId,
                GateDef definition,
                ComponentDefinition boxDefinition,
                List<String> inputPinNames,
                Map<String, String> outputSignalsByPin) {
            this.key = key;
            this.kind = kind;
            this.column = column;
            this.slotHeight = slotHeight;
            this.signal = null;
            this.componentId = componentId;
            this.definition = definition;
            this.boxDefinition = boxDefinition;
            this.inputPinNames = List.copyOf(inputPinNames);
            this.outputSignalsByPin = Map.copyOf(outputSignalsByPin);
        }

        private boolean isBox() {
            return boxDefinition != null;
        }

        private boolean isDummy() {
            return kind == NodeKind.DUMMY;
        }
    }

    private record RouteStep(
            String signal,
            int channel,
            Node source,
            Node target,
            int targetInputIndex,
            String targetPinName) {
        private RouteStep(
                String signal, int channel, Node source, Node target, int targetInputIndex) {
            this(signal, channel, source, target, targetInputIndex, null);
        }
    }

    private record ChannelSignal(int channel, String signal) {
    }

    private enum CycleBreakOrdering {
        SOURCE_EXTRACTION,
        INPUT_ROOTED_ASCENDING,
        INPUT_ROOTED_DESCENDING,
        INPUT_ROOTED_REVERSED_CONSUMERS,
        INPUT_ROOTED_HIGH_FANOUT_FIRST,
        INPUT_ROOTED_LOW_FANOUT_FIRST
    }

    private record LayoutGateInputEdge(
            String sourceSignal, String targetGate, int targetInputIndex) {
    }

    private enum FeedbackOrdering {
        DRIVER_BARYCENTER,
        REVERSED_DRIVER_BARYCENTER,
        CONSUMER_BARYCENTER,
        DAG_SWEEPS,
        FEEDBACK_WIRE_AWARE
    }

    private record LayoutMeasurements(
            int width, int height, long area, long wireLength) {
        String display() {
            return width + "x" + height + " area=" + area + " wire=" + wireLength;
        }
    }

    private record CompactCandidate(
            LayoutResult result,
            CycleBreakOrdering cycleBreak,
            FeedbackOrdering ordering,
            LayoutMeasurements measurements) {
        String description() {
            return "selected compact cycleBreak=" + cycleBreak
                    + " ordering=" + ordering + " " + measurements.display();
        }
    }

    private record PinKey(String componentId, String pinId) {
    }

    private static final class LayoutCheckException extends IllegalStateException {
        private final Set<String> involvedNets;

        private LayoutCheckException(String message, Collection<String> involvedNets) {
            super(message);
            this.involvedNets = Set.copyOf(involvedNets);
        }
    }

    /**
     * Builds, routes and checks the schematic. A failed self-check gets exactly
     * one retry with doubled spacing before a diagnostic exception is returned.
     */
    public LayoutResult layout(Netlist netlist) {
        Objects.requireNonNull(netlist, "netlist");
        try {
            return build(netlist, 1, null);
        } catch (LayoutCheckException firstFailure) {
            try {
                return build(netlist, 2, null);
            } catch (LayoutCheckException retryFailure) {
                Set<String> involved = new TreeSet<>(firstFailure.involvedNets);
                involved.addAll(retryFailure.involvedNets);
                String names = involved.isEmpty() ? "unknown" : String.join(", ", involved);
                throw new IllegalStateException(
                        "Expression layout failed its connectivity self-check after one "
                                + "doubled-spacing retry. Involved nets: " + names
                                + ". First failure: " + firstFailure.getMessage()
                                + "; retry failure: " + retryFailure.getMessage(),
                        retryFailure);
            }
        }
    }


    /** Kinds accepted by the shared layered layout for component-call graphs. */
    public enum ComponentNodeType {
        INPUT, CLOCK, CONSTANT, GATE, BOX
    }

    /**
     * A source, gate or reusable component node. Non-box nodes have one
     * output named "out"; boxes retain their declared named output pins.
     */
    public record ComponentNodeSpec(
            String id,
            ComponentNodeType type,
            List<String> inputSignals,
            Map<String, String> outputSignals,
            GateType gateType,
            ComponentDefinition boxDefinition,
            String label) {
        public ComponentNodeSpec {
            id = Objects.requireNonNull(id, "id");
            type = Objects.requireNonNull(type, "type");
            inputSignals = List.copyOf(Objects.requireNonNull(inputSignals, "inputSignals"));
            outputSignals = java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(outputSignals, "outputSignals")));
            label = Objects.requireNonNullElse(label, "");
            if (id.isBlank() || outputSignals.isEmpty()) {
                throw new IllegalArgumentException("component layout nodes need an id and output pin");
            }
            if (type == ComponentNodeType.BOX) {
                Objects.requireNonNull(boxDefinition, "boxDefinition");
                if (gateType != null || !outputSignals.keySet().equals(
                        boxDefinition.outputPorts().stream()
                                .map(ComponentDefinition.Port::name)
                                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)))) {
                    throw new IllegalArgumentException("box output pins do not match its definition");
                }
                if (inputSignals.size() != boxDefinition.inputPorts().size()) {
                    throw new IllegalArgumentException("box input signals do not match its definition");
                }
            } else {
                if (boxDefinition != null || outputSignals.size() != 1
                        || !outputSignals.containsKey("out")) {
                    throw new IllegalArgumentException(
                            "non-box layout nodes require one 'out' pin and no box definition");
                }
                if ((type == ComponentNodeType.GATE) != (gateType != null)) {
                    throw new IllegalArgumentException("only gate nodes have a gate type");
                }
            }
        }
    }

    /** A visible output probe attached immediately after its producing node. */
    public record ComponentOutputSpec(String id, String name, String signal) {
        public ComponentOutputSpec {
            id = Objects.requireNonNull(id, "id");
            name = Objects.requireNonNull(name, "name");
            signal = Objects.requireNonNull(signal, "signal");
            if (id.isBlank() || name.isBlank() || signal.isBlank()) {
                throw new IllegalArgumentException("output layout specs cannot be blank");
            }
        }
    }

    private record ComponentEdgeKey(String targetId, int inputIndex) {
    }

    private record ComponentFeedbackEdge(
            String signal, Node source, Node target, int targetInputIndex, String targetPinName) {
        private RouteStep routeStep() {
            return new RouteStep(signal, target.column - 1, source, target,
                    targetInputIndex, targetPinName);
        }
    }

    /**
     * Places an expression graph containing boxes through the same layered
     * ordering, pin-row compaction and per-gap channel router used by the
     * ordinary gate layout. The legacy Netlist path remains separate so its
     * instance-free output is byte-for-byte unchanged.
     */
    public LayoutResult layoutComponents(
            Collection<ComponentNodeSpec> componentNodeSpecs,
            Collection<ComponentOutputSpec> componentOutputSpecs) {
        List<ComponentNodeSpec> specs = Objects.requireNonNull(componentNodeSpecs, "componentNodeSpecs")
                .stream().sorted(Comparator.comparing(ComponentNodeSpec::id)).toList();
        List<ComponentOutputSpec> outputSpecs = List.copyOf(
                Objects.requireNonNull(componentOutputSpecs, "componentOutputSpecs"));
        if (specs.stream().noneMatch(spec -> spec.type() == ComponentNodeType.BOX)) {
            throw new IllegalArgumentException("layoutComponents requires at least one box node");
        }

        Map<String, ComponentNodeSpec> specsById = new LinkedHashMap<>();
        Map<String, Node> nodesById = new LinkedHashMap<>();
        Map<String, Node> producerBySignal = new LinkedHashMap<>();
        Map<String, List<String>> inputSignalsById = new LinkedHashMap<>();
        Map<String, List<String>> inputPinNamesById = new LinkedHashMap<>();
        Map<String, String> labelsById = new LinkedHashMap<>();
        Map<String, String> inputIds = new LinkedHashMap<>();
        Map<String, String> clockIds = new LinkedHashMap<>();
        Map<String, String> outputIds = new LinkedHashMap<>();
        Map<String, String> constantIds = new LinkedHashMap<>();
        Map<String, String> gateIds = new LinkedHashMap<>();

        for (ComponentNodeSpec spec : specs) {
            if (specsById.putIfAbsent(spec.id(), spec) != null) {
                throw new IllegalArgumentException("duplicate component layout id: " + spec.id());
            }
            Node node;
            if (spec.type() == ComponentNodeType.BOX) {
                ComponentDefinition definition = spec.boxDefinition();
                List<String> inputNames = definition.inputPorts().stream()
                        .map(ComponentDefinition.Port::name).toList();
                node = new Node("box:" + spec.id(), NodeKind.BOX, 0,
                        boxHeight(definition), spec.id(), null, definition,
                        inputNames, spec.outputSignals());
                inputPinNamesById.put(spec.id(), inputNames);
                labelsById.put(spec.id(), spec.label());
            } else {
                String family = switch (spec.type()) {
                    case INPUT -> "input";
                    case CLOCK -> "clock";
                    case CONSTANT -> "constant";
                    case GATE -> spec.gateType().name().toLowerCase(Locale.ROOT);
                    case BOX -> throw new IllegalStateException("box node handled above");
                };
                GateDef definition = definitionFor(family);
                String signal = spec.outputSignals().get("out");
                NodeKind kind = switch (spec.type()) {
                    case INPUT -> NodeKind.INPUT;
                    case CLOCK -> NodeKind.CLOCK;
                    case CONSTANT -> NodeKind.CONSTANT;
                    case GATE -> NodeKind.GATE;
                    case BOX -> throw new IllegalStateException("box node handled above");
                };
                node = new Node("component:" + spec.id(), kind, 0,
                        GateGeometry.bounds(definition, Facing.EAST).height(),
                        signal, spec.id(), definition);
                List<String> pinNames = new ArrayList<>();
                for (int index = 0; index < spec.inputSignals().size(); index++) {
                    pinNames.add("in" + index);
                }
                inputPinNamesById.put(spec.id(), List.copyOf(pinNames));
                labelsById.put(spec.id(), spec.label());
                switch (spec.type()) {
                    case INPUT -> inputIds.put(spec.label(), spec.id());
                    case CLOCK -> clockIds.put(spec.label(), spec.id());
                    case CONSTANT -> constantIds.put(spec.label(), spec.id());
                    case GATE -> gateIds.put(signal, spec.id());
                    case BOX -> throw new IllegalStateException("box node handled above");
                }
            }
            inputSignalsById.put(spec.id(), spec.inputSignals());
            nodesById.put(spec.id(), node);
            for (String signal : spec.outputSignals().values()) {
                Node previous = producerBySignal.putIfAbsent(signal, node);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "signal " + signal + " has multiple component drivers: "
                                    + previous.componentId + " and " + spec.id());
                }
                if (spec.type() == ComponentNodeType.BOX) {
                    gateIds.put(signal, spec.id());
                }
            }
        }

        Map<String, Integer> depthState = new HashMap<>();
        Set<ComponentEdgeKey> feedbackKeys = new LinkedHashSet<>();
        for (Node node : nodesById.values()) {
            componentNodeDepth(node, producerBySignal, inputSignalsById,
                    depthState, feedbackKeys);
        }

        // Keep input sources beside their earliest consumer, not at the
        // absolute left edge. A fan-out source remains a single node.
        for (ComponentNodeSpec spec : specs) {
            if (spec.type() != ComponentNodeType.INPUT
                    && spec.type() != ComponentNodeType.CLOCK
                    && spec.type() != ComponentNodeType.CONSTANT) {
                continue;
            }
            Node source = nodesById.get(spec.id());
            int firstConsumer = Integer.MAX_VALUE;
            for (ComponentNodeSpec targetSpec : specs) {
                List<String> targetInputs = targetSpec.inputSignals();
                for (String signal : targetInputs) {
                    if (signal.equals(spec.outputSignals().get("out"))) {
                        firstConsumer = Math.min(
                                firstConsumer, nodesById.get(targetSpec.id()).column);
                    }
                }
            }
            if (firstConsumer != Integer.MAX_VALUE) {
                source.column = Math.max(0, firstConsumer - 1);
            }
        }

        Map<String, Node> outputNodes = new LinkedHashMap<>();
        GateDef outputDefinition = definitionFor("output");
        for (ComponentOutputSpec output : outputSpecs) {
            if (outputIds.putIfAbsent(output.name(), output.id()) != null) {
                throw new IllegalArgumentException("duplicate component output name: " + output.name());
            }
            Node producer = producerBySignal.get(output.signal());
            if (producer == null) {
                throw new IllegalArgumentException(
                        "output " + output.name() + " has no producer for " + output.signal());
            }
            if (nodesById.containsKey(output.id())) {
                throw new IllegalArgumentException("output id collides with component: " + output.id());
            }
            Node target = new Node("output:" + output.id(), NodeKind.OUTPUT,
                    producer.column + 1, GateGeometry.bounds(outputDefinition, Facing.EAST).height(),
                    output.signal(), output.id(), outputDefinition);
            outputNodes.put(output.id(), target);
            inputSignalsById.put(output.id(), List.of(output.signal()));
            inputPinNamesById.put(output.id(), List.of("in0"));
            labelsById.put(output.id(), output.name());
        }

        List<Node> allComponentNodes = new ArrayList<>(nodesById.values());
        allComponentNodes.addAll(outputNodes.values());
        int maxColumn = allComponentNodes.stream().mapToInt(node -> node.column).max().orElse(0);
        List<List<Node>> columns = new ArrayList<>(maxColumn + 1);
        for (int column = 0; column <= maxColumn; column++) {
            columns.add(new ArrayList<>());
        }
        for (Node node : allComponentNodes) {
            columns.get(node.column).add(node);
        }

        List<RouteStep> routeSteps = new ArrayList<>();
        List<ComponentFeedbackEdge> feedbackEdges = new ArrayList<>();
        for (ComponentNodeSpec targetSpec : specs) {
            Node target = nodesById.get(targetSpec.id());
            List<String> targetInputs = targetSpec.inputSignals();
            List<String> targetPinNames = inputPinNamesById.get(targetSpec.id());
            for (int index = 0; index < targetInputs.size(); index++) {
                String signal = targetInputs.get(index);
                Node source = producerBySignal.get(signal);
                if (source == null) {
                    throw new IllegalArgumentException(
                            "signal " + signal + " has no producer for " + target.componentId);
                }
                String pinName = targetPinNames.get(index);
                if (feedbackKeys.contains(new ComponentEdgeKey(targetSpec.id(), index))
                        || source.column >= target.column) {
                    feedbackEdges.add(new ComponentFeedbackEdge(
                            signal, source, target, index, pinName));
                } else {
                    expandConnection(signal, source, target, index, columns, routeSteps);
                }
            }
        }
        for (ComponentOutputSpec output : outputSpecs) {
            Node source = producerBySignal.get(output.signal());
            Node target = outputNodes.get(output.id());
            expandConnection(output.signal(), source, target, 0, columns, routeSteps);
        }

        for (List<Node> column : columns) {
            column.sort(Comparator.comparing(SchematicLayout::nodeTieBreakId));
            int nextTop = 0;
            for (Node node : column) {
                node.y = roundToGrid(nextTop - nodeTopOffset(node));
                nextTop = node.y + nodeBottomOffset(node) + ROW_GAP;
            }
        }
        orderColumnsByPinRows(columns, routeSteps);
        optimizeComponentVerticalPositions(
                columns, routeSteps, ROW_GAP, feedbackEdges, TRUNK_PITCH);

        int laneStart = TRUNK_START_MARGIN;
        int lanePitch = TRUNK_PITCH;
        int laneEnd = TRUNK_END_MARGIN;
        int[] columnLeft = new int[columns.size()];
        int[] columnWidths = new int[columns.size()];
        for (int column = 0; column < columns.size(); column++) {
            for (Node node : columns.get(column)) {
                if (!node.isDummy()) {
                    columnWidths[column] = Math.max(columnWidths[column], nodeWidth(node));
                }
            }
        }

        Map<Integer, Set<String>> signalsByChannel = new LinkedHashMap<>();
        for (RouteStep step : routeSteps) {
            signalsByChannel.computeIfAbsent(step.channel(), ignored -> new TreeSet<>())
                    .add(step.signal());
        }
        Map<Integer, List<ComponentFeedbackEdge>> feedbackSourcesByChannel = new LinkedHashMap<>();
        Map<Integer, List<ComponentFeedbackEdge>> feedbackTargetsByChannel = new LinkedHashMap<>();
        for (ComponentFeedbackEdge edge : feedbackEdges) {
            feedbackSourcesByChannel.computeIfAbsent(edge.source().column, ignored -> new ArrayList<>())
                    .add(edge);
            feedbackTargetsByChannel.computeIfAbsent(edge.target().column - 1, ignored -> new ArrayList<>())
                    .add(edge);
        }

        Map<Integer, Integer> normalGapByChannel = new LinkedHashMap<>();
        Map<Integer, Integer> feedbackGapByChannel = new LinkedHashMap<>();
        for (int channel = 0; channel < columns.size() - 1; channel++) {
            int count = signalsByChannel.getOrDefault(channel, Set.of()).size();
            int sourceTracks = feedbackSourcesByChannel.getOrDefault(channel, List.of()).size();
            int targetTracks = feedbackTargetsByChannel.getOrDefault(channel, List.of()).size();
            int normalGap = laneStart + laneEnd + (count > 0 ? (count - 1) * lanePitch : 0);
            int feedbackGap = lanePitch * (sourceTracks + targetTracks + 2);
            normalGapByChannel.put(channel, normalGap);
            feedbackGapByChannel.put(channel, feedbackGap);
            columnLeft[channel + 1] = columnLeft[channel]
                    + columnWidths[channel] + normalGap + feedbackGap;
        }

        for (Node node : allNodes(columns)) {
            if (node.isDummy() || node.isBox()) {
                node.x = columnLeft[node.column];
            } else {
                node.x = columnLeft[node.column] + node.definition.origin().x();
            }
        }

        LinkedHashSet<WireSegment> wires = route(
                routeSteps, columnLeft, columnWidths, signalsByChannel, laneStart, lanePitch);
        LinkedHashSet<WireSegment> feedbackWires = routeComponentFeedbackEdges(
                feedbackEdges, feedbackSourcesByChannel, feedbackTargetsByChannel,
                normalGapByChannel, lanePitch, columnLeft, columnWidths, allNodes(columns));
        wires.addAll(feedbackWires);
        wires = normalizeDrawing(allNodes(columns), wires);

        Schematic schematic = new Schematic(definitions);
        for (Node node : allNodes(columns)) {
            if (node.isDummy()) {
                continue;
            }
            if (node.isBox()) {
                schematic.addInstance(new ComponentInstance(
                        node.boxDefinition.name(), node.boxDefinition.hash(),
                        node.componentId, labelsById.getOrDefault(node.componentId, ""),
                        node.x, node.y, Facing.EAST), node.boxDefinition);
            } else {
                schematic.add(new PlacedComponent(
                        node.componentId, node.definition.family(), node.x, node.y,
                        Facing.EAST, Map.of(), labelsById.getOrDefault(node.componentId, "")));
            }
        }
        for (WireSegment wire : wires) {
            schematic.addWire(wire);
        }
        checkComponentNodeOverlaps(allNodes(columns));

        int dummyCount = (int) allNodes(columns).stream().filter(Node::isDummy).count();
        return new LayoutResult(
                schematic, inputIds, outputIds, constantIds, dummyCount, clockIds, gateIds);
    }

    private static int componentNodeDepth(
            Node node,
            Map<String, Node> producerBySignal,
            Map<String, List<String>> inputSignalsById,
            Map<String, Integer> state,
            Set<ComponentEdgeKey> feedbackKeys) {
        int current = state.getOrDefault(node.componentId, 0);
        if (current == 2) {
            return node.column;
        }
        if (current == 1) {
            return 0;
        }
        if (node.kind == NodeKind.INPUT || node.kind == NodeKind.CLOCK
                || node.kind == NodeKind.CONSTANT) {
            node.column = 0;
            state.put(node.componentId, 2);
            return 0;
        }

        state.put(node.componentId, 1);
        int depth = 1;
        List<String> inputs = inputSignalsById.getOrDefault(node.componentId, List.of());
        for (int index = 0; index < inputs.size(); index++) {
            Node source = producerBySignal.get(inputs.get(index));
            if (source == null) {
                throw new IllegalArgumentException(
                        "unresolved signal " + inputs.get(index) + " at " + node.componentId);
            }
            if (state.getOrDefault(source.componentId, 0) == 1) {
                feedbackKeys.add(new ComponentEdgeKey(node.componentId, index));
                continue;
            }
            depth = Math.max(depth, componentNodeDepth(
                    source, producerBySignal, inputSignalsById, state, feedbackKeys) + 1);
        }
        node.column = depth;
        state.put(node.componentId, 2);
        return depth;
    }

    private static void orderColumnsByPinRows(
            List<List<Node>> columns, List<RouteStep> routeSteps) {
        for (int pass = 0; pass < 4; pass++) {
            for (int column = 1; column < columns.size(); column++) {
                columns.get(column).sort(Comparator
                        .comparingDouble((Node node) -> componentDriverBarycenter(node, routeSteps))
                        .thenComparing(SchematicLayout::nodeTieBreakId));
            }
            for (int column = columns.size() - 2; column >= 0; column--) {
                columns.get(column).sort(Comparator
                        .comparingDouble((Node node) -> componentConsumerBarycenter(node, routeSteps))
                        .thenComparing(SchematicLayout::nodeTieBreakId));
            }
        }
    }

    private static double componentDriverBarycenter(Node node, List<RouteStep> routeSteps) {
        long total = 0L;
        int count = 0;
        for (RouteStep step : routeSteps) {
            if (step.target() == node) {
                total += sourcePinY(step.source(), step.signal());
                count++;
            }
        }
        return count == 0 ? node.y : total / (double) count;
    }

    private static double componentConsumerBarycenter(Node node, List<RouteStep> routeSteps) {
        long total = 0L;
        int count = 0;
        for (RouteStep step : routeSteps) {
            if (step.source() == node) {
                total += targetPinY(step);
                count++;
            }
        }
        return count == 0 ? node.y : total / (double) count;
    }

    private static void optimizeComponentVerticalPositions(
            List<List<Node>> columns,
            List<RouteStep> routeSteps,
            int rowGap,
            List<ComponentFeedbackEdge> feedbackEdges,
            int feedbackTrackPitch) {
        int lastColumn = columns.size() - 1;
        int[] bestPositions = null;
        long bestLength = Long.MAX_VALUE;
        for (int sweep = 0; sweep < 3; sweep++) {
            if (sweep == 1) {
                for (int column = lastColumn - 1; column >= 1; column--) {
                    assignColumn(columns.get(column), routeSteps, rowGap,
                            false, column, true, true);
                }
            } else {
                for (int column = 1; column <= lastColumn; column++) {
                    assignColumn(columns.get(column), routeSteps, rowGap,
                            true, column - 1, false, true);
                }
            }
            if (!allChannelsHaveConflictFreePinRows(routeSteps, columns.size())) {
                continue;
            }
            long length = totalVerticalWireLength(routeSteps)
                    + totalComponentFeedbackVerticalLength(
                            feedbackEdges, feedbackTrackPitch, allNodes(columns));
            if (length < bestLength) {
                bestLength = length;
                bestPositions = captureVerticalPositions(columns);
            }
        }
        if (bestPositions == null) {
            throw new IllegalStateException("no conflict-free box layout candidate was produced");
        }
        restoreVerticalPositions(columns, bestPositions);

        int[] before = captureVerticalPositions(columns);
        for (List<Node> column : columns) {
            for (int index = 1; index < column.size(); index++) {
                Node previous = column.get(index - 1);
                Node current = column.get(index);
                int packedY = previous.y + minimumVerticalSeparation(
                        previous, current, rowGap, true);
                if (current.y > packedY) {
                    current.y = packedY;
                }
            }
        }
        if (!allChannelsHaveConflictFreePinRows(routeSteps, columns.size())) {
            restoreVerticalPositions(columns, before);
        }
    }

    private static long totalComponentFeedbackVerticalLength(
            List<ComponentFeedbackEdge> edges, int trackPitch, List<Node> allNodes) {
        if (edges.isEmpty()) {
            return 0L;
        }
        Map<ComponentFeedbackEdge, Integer> lanes = assignComponentFeedbackLanes(edges);
        long total = 0L;
        for (ComponentFeedbackEdge edge : edges) {
            RouteStep step = edge.routeStep();
            int sourceY = sourcePinY(edge.source(), edge.signal());
            int targetY = targetPinY(step);
            int laneY = componentFeedbackLaneY(edge, lanes.get(edge), trackPitch, allNodes);
            total += Math.abs(sourceY - laneY) + Math.abs(targetY - laneY);
        }
        return total;
    }

    private static int componentFeedbackLaneY(
            ComponentFeedbackEdge edge, int lane, int trackPitch, List<Node> allNodes) {
        int minTop = Integer.MAX_VALUE;
        int maxBottom = Integer.MIN_VALUE;
        // Reserve the return row beyond every ordinary pin/dummy row, not
        // only the feedback interval's boxes. A different column's channel
        // route can span this interval and would otherwise share the same Y.
        for (Node node : allNodes) {
            minTop = Math.min(minTop, node.y + nodeTopOffset(node));
            maxBottom = Math.max(maxBottom, node.y + nodeBottomOffset(node));
        }
        if (minTop == Integer.MAX_VALUE) {
            throw new IllegalStateException("feedback interval contains no component rows");
        }
        int sourceY = sourcePinY(edge.source(), edge.signal());
        int targetY = targetPinY(edge.routeStep());
        int aboveY = minTop - (lane + 1) * trackPitch;
        int belowY = maxBottom + (lane + 1) * trackPitch;
        int aboveLength = Math.abs(sourceY - aboveY) + Math.abs(targetY - aboveY);
        int belowLength = Math.abs(sourceY - belowY) + Math.abs(targetY - belowY);
        return aboveLength <= belowLength ? aboveY : belowY;
    }

    private static Map<ComponentFeedbackEdge, Integer> assignComponentFeedbackLanes(
            List<ComponentFeedbackEdge> edges) {
        List<ComponentFeedbackEdge> ordered = edges.stream().sorted(Comparator
                .comparingInt((ComponentFeedbackEdge edge) -> edge.target().column)
                .thenComparingInt(edge -> edge.source().column)
                .thenComparing(edge -> edge.source().componentId)
                .thenComparing(edge -> edge.target().componentId)
                .thenComparingInt(ComponentFeedbackEdge::targetInputIndex)).toList();
        List<Integer> lastEndByLane = new ArrayList<>();
        Map<ComponentFeedbackEdge, Integer> result = new LinkedHashMap<>();
        for (ComponentFeedbackEdge edge : ordered) {
            int start = edge.target().column;
            int end = edge.source().column;
            int lane = 0;
            while (lane < lastEndByLane.size() && lastEndByLane.get(lane) >= start) {
                lane++;
            }
            if (lane == lastEndByLane.size()) {
                lastEndByLane.add(end);
            } else {
                lastEndByLane.set(lane, end);
            }
            result.put(edge, lane);
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static LinkedHashSet<WireSegment> routeComponentFeedbackEdges(
            List<ComponentFeedbackEdge> edges,
            Map<Integer, List<ComponentFeedbackEdge>> sourcesByChannel,
            Map<Integer, List<ComponentFeedbackEdge>> targetsByChannel,
            Map<Integer, Integer> normalGapByChannel,
            int trackPitch,
            int[] columnLeft,
            int[] columnWidths,
            List<Node> allNodes) {
        LinkedHashSet<WireSegment> wires = new LinkedHashSet<>();
        Map<ComponentFeedbackEdge, Integer> lanes = assignComponentFeedbackLanes(edges);
        for (ComponentFeedbackEdge edge : edges) {
            Node source = edge.source();
            Node target = edge.target();
            List<ComponentFeedbackEdge> sourceEdges =
                    sourcesByChannel.getOrDefault(source.column, List.of());
            List<ComponentFeedbackEdge> targetEdges =
                    targetsByChannel.getOrDefault(target.column - 1, List.of());
            int sourceTrackIndex = sourceEdges.indexOf(edge);
            int targetTrackIndex = targetEdges.indexOf(edge);
            if (sourceTrackIndex < 0 || targetTrackIndex < 0) {
                throw new IllegalStateException("feedback lane reservation is missing");
            }
            int sourceX = columnLeft[source.column] + columnWidths[source.column]
                    + normalGapByChannel.get(source.column)
                    + (sourceTrackIndex + 1) * trackPitch;
            RouteStep step = edge.routeStep();
            GateGeometry.Offset targetOffset = targetPinOffset(step);
            int targetPinX = target.x + targetOffset.x();
            int targetTrackX = targetPinX
                    - (targetEdges.size() + 1 - targetTrackIndex) * trackPitch;
            int sourcePinX = sourcePinX(source, edge.signal());
            int sourceY = sourcePinY(source, edge.signal());
            int targetY = targetPinY(step);
            int laneY = componentFeedbackLaneY(
                    edge, lanes.get(edge), trackPitch, allNodes);
            if (sourceX <= sourcePinX || sourceX <= targetTrackX) {
                throw new IllegalStateException(
                        "feedback source channel is too narrow for " + source.componentId
                                + " -> " + target.componentId);
            }
            addWire(wires, sourcePinX, sourceY, sourceX, sourceY);
            addWire(wires, sourceX, sourceY, sourceX, laneY);
            addWire(wires, targetTrackX, laneY, sourceX, laneY);
            addWire(wires, targetTrackX, laneY, targetTrackX, targetY);
            addWire(wires, targetTrackX, targetY, targetPinX, targetY);
        }
        return wires;
    }

    private static void checkComponentNodeOverlaps(List<Node> nodes) {
        List<Node> components = nodes.stream().filter(node -> !node.isDummy()).toList();
        for (int first = 0; first < components.size(); first++) {
            Node a = components.get(first);
            int ax = a.isBox() ? a.x : a.x + GateGeometry.bounds(a.definition, Facing.EAST).x();
            int ay = a.isBox() ? a.y : a.y + GateGeometry.bounds(a.definition, Facing.EAST).y();
            int aw = nodeWidth(a);
            int ah = nodeBottomOffset(a) - nodeTopOffset(a);
            for (int second = first + 1; second < components.size(); second++) {
                Node b = components.get(second);
                int bx = b.isBox() ? b.x : b.x + GateGeometry.bounds(b.definition, Facing.EAST).x();
                int by = b.isBox() ? b.y : b.y + GateGeometry.bounds(b.definition, Facing.EAST).y();
                int bw = nodeWidth(b);
                int bh = nodeBottomOffset(b) - nodeTopOffset(b);
                if (ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah) {
                    throw new IllegalStateException(
                            "box-aware layout overlaps " + a.componentId + " and " + b.componentId);
                }
            }
        }
    }

    enum FeedbackLayoutMode {
        COMPACT_WITH_FALLBACK,
        COMPACT_ONLY,
        LEGACY
    }

    record FeedbackLayoutRun(
            LayoutResult result,
            boolean legacyFallbackUsed,
            String selectionDescription) {
        FeedbackLayoutRun {
            Objects.requireNonNull(result, "result");
            selectionDescription = Objects.requireNonNull(selectionDescription, "selectionDescription");
        }
    }

    /** Builds a feedback-aware layout, retaining the original main layout as a fallback. */
    public LayoutResult layoutFeedback(FeedbackCircuitExtractor.Extraction extraction) {
        return layoutFeedbackRun(
                extraction, FeedbackLayoutMode.COMPACT_WITH_FALLBACK).result();
    }

    FeedbackLayoutRun layoutFeedbackRun(
            FeedbackCircuitExtractor.Extraction extraction, FeedbackLayoutMode mode) {
        return layoutFeedbackRun(extraction, mode, false);
    }

    /**
     * Package-private failure injection keeps the compact-to-legacy fallback test
     * deterministic without weakening or bypassing the production self-check.
     */
    FeedbackLayoutRun layoutFeedbackRun(
            FeedbackCircuitExtractor.Extraction extraction,
            FeedbackLayoutMode mode,
            boolean forceCompactFailure) {
        Objects.requireNonNull(extraction, "extraction");
        Objects.requireNonNull(mode, "mode");

        if (mode == FeedbackLayoutMode.LEGACY) {
            return new FeedbackLayoutRun(
                    legacyLayout(extraction), true, "legacy layout selected for comparison");
        }

        RuntimeException firstFailure;
        try {
            if (forceCompactFailure) {
                throw new IllegalStateException("forced compact failure (test hook)");
            }
            CompactCandidate candidate = bestCompactCandidate(extraction, 1);
            return selectCompactCandidate(extraction, mode, candidate);
        } catch (RuntimeException failure) {
            firstFailure = failure;
        }

        RuntimeException retryFailure;
        try {
            if (forceCompactFailure) {
                throw new IllegalStateException(
                        "forced doubled-spacing compact failure (test hook)");
            }
            CompactCandidate candidate = bestCompactCandidate(extraction, 2);
            return selectCompactCandidate(extraction, mode, candidate);
        } catch (RuntimeException failure) {
            retryFailure = failure;
        }

        if (mode == FeedbackLayoutMode.COMPACT_ONLY) {
            throw compactFailure(firstFailure, retryFailure);
        }

        try {
            LayoutResult legacy = legacyLayout(extraction);
            return new FeedbackLayoutRun(
                    legacy, true,
                    "compact spacing 1 failed: " + firstFailure.getMessage()
                            + "; compact spacing 2 failed: " + retryFailure.getMessage());
        } catch (RuntimeException legacyFailure) {
            throw new IllegalStateException(
                    "Feedback layout failed both compact candidates and the legacy layout. "
                            + "Compact spacing 1: " + firstFailure.getMessage()
                            + "; compact spacing 2: " + retryFailure.getMessage()
                            + "; legacy failure: " + legacyFailure.getMessage(),
                    legacyFailure);
        }
    }

    /**
     * Never replace the preserved main layout with a connected but larger
     * compact drawing. A compact candidate is used only when it strictly
     * improves the same circuit's legacy bounding-box area. If the legacy
     * comparison itself fails, the already self-checked compact result remains
     * usable; "no valid layout" is still reserved for failure of both paths.
     */
    private FeedbackLayoutRun selectCompactCandidate(
            FeedbackCircuitExtractor.Extraction extraction,
            FeedbackLayoutMode mode,
            CompactCandidate candidate) {
        if (mode == FeedbackLayoutMode.COMPACT_ONLY) {
            return new FeedbackLayoutRun(
                    candidate.result(), false, candidate.description());
        }
        LayoutResult legacy;
        try {
            legacy = legacyLayout(extraction);
        } catch (RuntimeException legacyFailure) {
            return new FeedbackLayoutRun(
                    candidate.result(), false,
                    candidate.description()
                            + "; legacy comparison failed, retained self-checked compact result: "
                            + legacyFailure.getMessage());
        }
        LayoutMeasurements legacyMeasurements = measureLayout(legacy.schematic());
        if (candidate.measurements().area() >= legacyMeasurements.area()) {
            return new FeedbackLayoutRun(
                    legacy, true,
                    "legacy selected because best compact candidate was not strictly smaller: "
                            + candidate.description() + "; legacy "
                            + legacyMeasurements.display());
        }
        return new FeedbackLayoutRun(
                candidate.result(), false, candidate.description()
                        + "; legacy " + legacyMeasurements.display());
    }

    private LayoutResult legacyLayout(FeedbackCircuitExtractor.Extraction extraction) {
        LegacySchematicLayout.LayoutResult legacy =
                new LegacySchematicLayout(definitions).layoutFeedback(extraction);
        return new LayoutResult(
                legacy.schematic(),
                legacy.inputComponentIds(),
                legacy.outputComponentIds(),
                legacy.constantComponentIds(),
                legacy.dummyNodeCount(),
                legacy.clockComponentIds(),
                legacy.gateComponentIds());
    }

    /**
     * Builds every deterministic feedback-ordering variant. Each call to build()
     * runs the production overlap and Connectivity.analyze self-checks; failed
     * variants are excluded rather than repaired or scored. The best valid
     * candidate is selected by full schematic bounding-box area, then Manhattan
     * wire length, with enum order as a stable final tie-breaker.
     */
    private CompactCandidate bestCompactCandidate(
            FeedbackCircuitExtractor.Extraction extraction, int spacingFactor) {
        List<CompactCandidate> valid = new ArrayList<>();
        List<RuntimeException> failures = new ArrayList<>();
        for (CycleBreakOrdering cycleBreak : CycleBreakOrdering.values()) {
            FeedbackCircuitExtractor.Extraction candidateExtraction;
            try {
                candidateExtraction = cycleBreak == CycleBreakOrdering.SOURCE_EXTRACTION
                        ? extraction
                        : inputRootedExtraction(extraction, cycleBreak);
            } catch (RuntimeException failure) {
                failures.add(new IllegalStateException(
                        "cycle-break " + cycleBreak + " failed: " + failure.getMessage(),
                        failure));
                continue;
            }
            for (FeedbackOrdering ordering : FeedbackOrdering.values()) {
                try {
                    LayoutResult result = build(candidateExtraction.dagNetlist(),
                            spacingFactor, candidateExtraction, ordering);
                    valid.add(new CompactCandidate(
                            result, cycleBreak, ordering,
                            measureLayout(result.schematic())));
                } catch (RuntimeException failure) {
                    failures.add(new IllegalStateException(
                            "cycle-break " + cycleBreak + " / ordering " + ordering
                                    + " failed at spacing factor " + spacingFactor + ": "
                                    + failure.getMessage(),
                            failure));
                }
            }
        }
        if (valid.isEmpty()) {
            IllegalStateException failure = new IllegalStateException(
                    "No valid compact feedback ordering at spacing factor "
                            + spacingFactor + " (" + failures.size()
                            + " deterministic variants tried)");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }

        valid.sort(Comparator
                .comparingLong((CompactCandidate candidate) ->
                        candidate.measurements().area())
                .thenComparingLong(candidate -> candidate.measurements().wireLength())
                .thenComparing(candidate -> candidate.cycleBreak().ordinal())
                .thenComparing(candidate -> candidate.ordering().ordinal()));
        return valid.getFirst();
    }

    /**
     * Builds an alternate DAG by cutting DFS back edges reached from primary
     * input/clock roots. Different deterministic root/consumer orders expose
     * alternative valid cut sets without modifying the source netlist. The
     * resulting Extraction reconstructs the exact same source graph.
     */
    private static FeedbackCircuitExtractor.Extraction inputRootedExtraction(
            FeedbackCircuitExtractor.Extraction original,
            CycleBreakOrdering ordering) {
        Netlist source = original.sourceNetlist();
        Map<String, Netlist.Gate> gatesById = new LinkedHashMap<>();
        for (Netlist.Gate gate : source.gates()) {
            gatesById.put(gate.id(), gate);
        }

        Map<String, List<LayoutGateInputEdge>> consumersBySource = new LinkedHashMap<>();
        for (Netlist.Gate gate : source.gates()) {
            for (int inputIndex = 0; inputIndex < gate.inputs().size(); inputIndex++) {
                consumersBySource.computeIfAbsent(
                        gate.inputs().get(inputIndex), ignored -> new ArrayList<>())
                        .add(new LayoutGateInputEdge(
                                gate.inputs().get(inputIndex), gate.id(), inputIndex));
            }
        }
        Comparator<LayoutGateInputEdge> ascendingConsumerOrder =
                Comparator.comparing(LayoutGateInputEdge::targetGate)
                        .thenComparingInt(LayoutGateInputEdge::targetInputIndex);
        final Comparator<LayoutGateInputEdge> consumerOrder =
                ordering == CycleBreakOrdering.INPUT_ROOTED_REVERSED_CONSUMERS
                        ? ascendingConsumerOrder.reversed() : ascendingConsumerOrder;
        consumersBySource.values().forEach(edges -> edges.sort(consumerOrder));

        List<String> roots = new ArrayList<>(source.inputs());
        roots.sort(String::compareTo);
        if (ordering == CycleBreakOrdering.INPUT_ROOTED_DESCENDING) {
            roots.sort(Comparator.reverseOrder());
        } else if (ordering == CycleBreakOrdering.INPUT_ROOTED_HIGH_FANOUT_FIRST) {
            roots.sort(Comparator
                    .comparingInt((String signal) ->
                            consumersBySource.getOrDefault(signal, List.of()).size())
                    .reversed()
                    .thenComparing(String::compareTo));
        } else if (ordering == CycleBreakOrdering.INPUT_ROOTED_LOW_FANOUT_FIRST) {
            roots.sort(Comparator
                    .comparingInt((String signal) ->
                            consumersBySource.getOrDefault(signal, List.of()).size())
                    .thenComparing(String::compareTo));
        }

        Map<String, Integer> state = new HashMap<>();
        List<LayoutGateInputEdge> backEdges = new ArrayList<>();
        for (String root : roots) {
            for (LayoutGateInputEdge edge
                    : consumersBySource.getOrDefault(root, List.of())) {
                visitLayoutEdge(edge, consumersBySource, state, backEdges);
            }
        }

        // Expression graphs may contain a closed feedback group with no path
        // from an input/clock. Visit any remaining gates deterministically so
        // removing DFS back edges still leaves a DAG for every gate.
        List<String> remainingRoots = new ArrayList<>(gatesById.keySet());
        remainingRoots.sort(String::compareTo);
        if (ordering == CycleBreakOrdering.INPUT_ROOTED_DESCENDING) {
            remainingRoots.sort(Comparator.reverseOrder());
        }
        for (String gateId : remainingRoots) {
            if (state.getOrDefault(gateId, 0) == 0) {
                visitLayoutGate(gateId, consumersBySource, state, backEdges);
            }
        }
        if (backEdges.isEmpty()) {
            throw new IllegalStateException(
                    "alternate input-rooted DFS did not find a feedback back edge");
        }

        Set<String> reservedSignals = new HashSet<>(source.inputs());
        reservedSignals.addAll(gatesById.keySet());
        source.outputs().stream().map(Netlist.Output::name).forEach(reservedSignals::add);
        Map<String, Map<Integer, String>> placeholdersByTarget = new HashMap<>();
        List<FeedbackCircuitExtractor.FeedbackEdge> feedbackEdges = new ArrayList<>();
        int feedbackNumber = 1;
        for (LayoutGateInputEdge edge : backEdges) {
            String placeholder;
            do {
                placeholder = "LayoutFeedback" + feedbackNumber++;
            } while (!reservedSignals.add(placeholder));
            placeholdersByTarget.computeIfAbsent(edge.targetGate(), ignored -> new HashMap<>())
                    .put(edge.targetInputIndex(), placeholder);
            feedbackEdges.add(new FeedbackCircuitExtractor.FeedbackEdge(
                    edge.sourceSignal(), edge.targetGate(),
                    edge.targetInputIndex(), placeholder));
        }

        List<String> dagInputs = new ArrayList<>(source.inputs());
        feedbackEdges.stream().map(FeedbackCircuitExtractor.FeedbackEdge::placeholderSignal)
                .forEach(dagInputs::add);
        List<Netlist.Gate> dagGates = new ArrayList<>();
        for (Netlist.Gate gate : source.gates()) {
            List<String> inputs = new ArrayList<>(gate.inputs());
            Map<Integer, String> replacements = placeholdersByTarget.get(gate.id());
            if (replacements != null) {
                replacements.forEach(inputs::set);
            }
            dagGates.add(new Netlist.Gate(gate.id(), gate.type(), inputs));
        }

        Netlist dag = new Netlist(dagInputs, source.outputs(), dagGates);
        return new FeedbackCircuitExtractor.Extraction(
                dag, feedbackEdges,
                original.inputComponentIds(), original.clockComponentIds(),
                original.outputComponentIds(), original.gateComponentIds());
    }

    private static void visitLayoutEdge(
            LayoutGateInputEdge edge,
            Map<String, List<LayoutGateInputEdge>> consumersBySource,
            Map<String, Integer> state,
            List<LayoutGateInputEdge> backEdges) {
        int targetState = state.getOrDefault(edge.targetGate(), 0);
        if (targetState == 1) {
            backEdges.add(edge);
        } else if (targetState == 0) {
            visitLayoutGate(edge.targetGate(), consumersBySource, state, backEdges);
        }
    }

    private static void visitLayoutGate(
            String gateId,
            Map<String, List<LayoutGateInputEdge>> consumersBySource,
            Map<String, Integer> state,
            List<LayoutGateInputEdge> backEdges) {
        state.put(gateId, 1);
        for (LayoutGateInputEdge edge
                : consumersBySource.getOrDefault(gateId, List.of())) {
            visitLayoutEdge(edge, consumersBySource, state, backEdges);
        }
        state.put(gateId, 2);
    }

    private static LayoutMeasurements measureLayout(Schematic schematic) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (PlacedComponent component : schematic.components()) {
            GateGeometry.Bounds bounds = worldBounds(schematic, component);
            minX = Math.min(minX, bounds.x());
            minY = Math.min(minY, bounds.y());
            maxX = Math.max(maxX, bounds.x() + bounds.width());
            maxY = Math.max(maxY, bounds.y() + bounds.height());
        }
        long wireLength = 0L;
        for (WireSegment wire : schematic.wires()) {
            minX = Math.min(minX, Math.min(wire.x1(), wire.x2()));
            minY = Math.min(minY, Math.min(wire.y1(), wire.y2()));
            maxX = Math.max(maxX, Math.max(wire.x1(), wire.x2()));
            maxY = Math.max(maxY, Math.max(wire.y1(), wire.y2()));
            wireLength += Math.abs(wire.x2() - wire.x1())
                    + Math.abs(wire.y2() - wire.y1());
        }
        if (minX == Integer.MAX_VALUE) {
            return new LayoutMeasurements(0, 0, 0L, wireLength);
        }
        int width = maxX - minX;
        int height = maxY - minY;
        return new LayoutMeasurements(
                width, height, (long) width * height, wireLength);
    }

    private static IllegalStateException compactFailure(
            RuntimeException firstFailure, RuntimeException retryFailure) {
        Set<String> involved = new TreeSet<>();
        if (firstFailure instanceof LayoutCheckException checkFailure) {
            involved.addAll(checkFailure.involvedNets);
        }
        if (retryFailure instanceof LayoutCheckException checkFailure) {
            involved.addAll(checkFailure.involvedNets);
        }
        String names = involved.isEmpty() ? "none reported" : String.join(", ", involved);
        return new IllegalStateException(
                "Feedback compact layout failed both compact attempts. Involved nets: " + names
                        + ". First failure: " + firstFailure.getMessage()
                        + "; retry failure: " + retryFailure.getMessage(),
                retryFailure);
    }

    private LayoutResult build(
            Netlist netlist, int spacingFactor,
            FeedbackCircuitExtractor.Extraction feedback) {
        return build(netlist, spacingFactor, feedback, FeedbackOrdering.DRIVER_BARYCENTER);
    }

    private LayoutResult build(
            Netlist netlist,
            int spacingFactor,
            FeedbackCircuitExtractor.Extraction feedback,
            FeedbackOrdering feedbackOrdering) {
        Map<String, Integer> depthBySignal = new LinkedHashMap<>();
        netlist.inputs().forEach(name -> depthBySignal.put(name, 0));

        LinkedHashSet<String> constantsUsed = new LinkedHashSet<>();
        for (Netlist.Output output : netlist.outputs()) {
            if (output.source().equals("0") || output.source().equals("1")) {
                constantsUsed.add(output.source());
                depthBySignal.putIfAbsent(output.source(), 0);
            }
        }
        for (Netlist.Gate gate : netlist.gates()) {
            for (String input : gate.inputs()) {
                if (input.equals("0") || input.equals("1")) {
                    constantsUsed.add(input);
                    depthBySignal.putIfAbsent(input, 0);
                }
            }
        }

        List<Netlist.Gate> topological = netlist.topologicalGates();
        int lastGateDepth = 0;
        for (Netlist.Gate gate : topological) {
            int depth = 1;
            for (String input : gate.inputs()) {
                Integer sourceDepth = depthBySignal.get(input);
                if (sourceDepth == null) {
                    throw new IllegalArgumentException(
                            "no layout depth for signal " + input + " used by gate " + gate.id());
                }
                depth = Math.max(depth, sourceDepth + 1);
            }
            depthBySignal.put(gate.id(), depth);
            lastGateDepth = Math.max(lastGateDepth, depth);
        }
        int outputColumn = lastGateDepth + 1;
        List<List<Node>> columns = new ArrayList<>(outputColumn + 1);
        for (int column = 0; column <= outputColumn; column++) {
            columns.add(new ArrayList<>());
        }

        Schematic schematic = new Schematic(definitions);
        Map<String, String> inputIds = new LinkedHashMap<>();
        Map<String, String> clockIds = new LinkedHashMap<>();
        Map<String, String> feedbackIds = new LinkedHashMap<>();
        Map<String, String> connectivityInputIds = new LinkedHashMap<>();
        Map<String, String> outputIds = new LinkedHashMap<>();
        Map<String, String> constantIds = new LinkedHashMap<>();
        Map<String, Node> signalNodes = new LinkedHashMap<>();
        Map<String, Node> gateNodes = new LinkedHashMap<>();
        Map<String, Node> outputNodes = new LinkedHashMap<>();
        List<Node> inputColumnNodes = new ArrayList<>();

        Set<String> clockSignals = feedback == null
                ? Set.of() : feedback.clockComponentIds().keySet();
        Set<String> feedbackSignals = feedback == null
                ? Set.of() : feedback.feedbackInputSignals();
        for (String input : netlist.inputs()) {
            Node node;
            String id;
            if (clockSignals.contains(input)) {
                id = "expr-clock-" + input;
                node = componentNode(
                        "clock:" + input, NodeKind.CLOCK, 0, input, id, "clock");
                clockIds.put(input, id);
            } else if (feedbackSignals.contains(input)) {
                id = "expr-feedback-" + input;
                node = componentNode(
                        "feedback:" + input, NodeKind.FEEDBACK, 0, input, id, "input");
                feedbackIds.put(input, id);
            } else {
                id = "expr-input-" + input;
                node = componentNode(
                        "input:" + input, NodeKind.INPUT, 0, input, id, "input");
                inputIds.put(input, id);
            }
            columns.get(0).add(node);
            inputColumnNodes.add(node);
            signalNodes.put(input, node);
            connectivityInputIds.put(input, id);
        }

        for (String constant : constantsUsed) {
            String id = "expr-constant-" + constant;
            Node node = componentNode(
                    "constant:" + constant, NodeKind.CONSTANT, 0,
                    constant, id, "constant");
            columns.get(0).add(node);
            inputColumnNodes.add(node);
            signalNodes.put(constant, node);
            constantIds.put(constant, id);
        }

        for (Netlist.Gate gate : topological) {
            int column = depthBySignal.get(gate.id());
            String type = gate.type().name().toLowerCase(Locale.ROOT);
            String id = "expr-gate-" + gate.id();
            Node node = componentNode(
                    "gate:" + gate.id(), NodeKind.GATE, column,
                    gate.id(), id, type);
            columns.get(column).add(node);
            signalNodes.put(gate.id(), node);
            gateNodes.put(gate.id(), node);
        }

        for (int index = 0; index < netlist.outputs().size(); index++) {
            Netlist.Output output = netlist.outputs().get(index);
            String id = "expr-output-" + output.name();
            Node node = componentNode(
                    "output:" + index + ":" + output.name(), NodeKind.OUTPUT,
                    outputColumn, output.source(), id, "output");
            columns.get(outputColumn).add(node);
            outputNodes.put(output.name(), node);
            outputIds.put(output.name(), id);
        }

        if (feedback != null) {
            relocateFeedbackInputs(netlist, columns, signalNodes, gateNodes,
                    inputColumnNodes, outputColumn);
        }

        List<RouteStep> routeSteps = new ArrayList<>();
        int dummyCount = 0;
        for (Netlist.Gate gate : topological) {
            Node target = gateNodes.get(gate.id());
            for (int inputIndex = 0; inputIndex < gate.inputs().size(); inputIndex++) {
                String sourceName = gate.inputs().get(inputIndex);
                Node source = signalNodes.get(sourceName);
                dummyCount += expandConnection(
                        sourceName, source, target, inputIndex, columns, routeSteps);
            }
        }
        for (Netlist.Output output : netlist.outputs()) {
            Node source = signalNodes.get(output.source());
            Node target = outputNodes.get(output.name());
            dummyCount += expandConnection(
                    output.source(), source, target, 0, columns, routeSteps);
        }

        int rowGap = ROW_GAP * spacingFactor;
        if (feedback == null) {
            orderColumns(columns);
            assignInputColumn(inputColumnNodes, rowGap);
        } else if (feedbackOrdering == FeedbackOrdering.DAG_SWEEPS) {
            // This candidate deliberately reuses the ordinary four-pass
            // neighbor ordering. The production DAG path remains unchanged;
            // any feedback topology it invalidates is discarded by the
            // candidate self-check before selection.
            orderColumns(columns);
            assignFeedbackInputColumns(inputColumnNodes, rowGap);
        } else {
            assignFeedbackInputColumns(inputColumnNodes, rowGap);
            switch (feedbackOrdering) {
                case DRIVER_BARYCENTER -> orderFeedbackColumns(columns);
                case REVERSED_DRIVER_BARYCENTER ->
                        orderFeedbackColumns(columns, true);
                case CONSUMER_BARYCENTER ->
                        orderFeedbackColumnsByConsumers(columns, routeSteps);
                case FEEDBACK_WIRE_AWARE -> orderFeedbackColumns(columns);
                case DAG_SWEEPS -> throw new IllegalStateException(
                        "DAG sweep ordering is handled before input seeding");
            }
        }
        optimizeVerticalPositions(
                columns, outputNodes, routeSteps, rowGap, feedback != null,
                feedback, gateNodes, TRUNK_PITCH * spacingFactor,
                feedbackOrdering == FeedbackOrdering.FEEDBACK_WIRE_AWARE);

        int laneStart = TRUNK_START_MARGIN * spacingFactor;
        int lanePitch = TRUNK_PITCH * spacingFactor;
        int laneEnd = TRUNK_END_MARGIN * spacingFactor;
        int[] columnLeft = new int[columns.size()];
        int[] columnWidths = new int[columns.size()];
        for (int column = 0; column < columns.size(); column++) {
            for (Node node : columns.get(column)) {
                if (!node.isDummy()) {
                    columnWidths[column] = Math.max(
                            columnWidths[column], nodeWidth(node));
                }
            }
        }

        Map<Integer, Set<String>> signalsByChannel = new LinkedHashMap<>();
        for (RouteStep step : routeSteps) {
            signalsByChannel.computeIfAbsent(step.channel(), ignored -> new TreeSet<>())
                    .add(step.signal());
        }
        Map<Integer, List<FeedbackCircuitExtractor.FeedbackEdge>> feedbackSourcesByChannel =
                new LinkedHashMap<>();
        Map<Integer, List<FeedbackCircuitExtractor.FeedbackEdge>> feedbackTargetsByChannel =
                new LinkedHashMap<>();
        if (feedback != null) {
            for (FeedbackCircuitExtractor.FeedbackEdge edge : feedback.feedbackEdges()) {
                Node source = gateNodes.get(edge.sourceGate());
                Node target = gateNodes.get(edge.targetGate());
                if (source == null || target == null || source.column < target.column) {
                    throw new IllegalArgumentException(
                            "feedback edge does not return to the same or an earlier layout column: "
                                    + edge.sourceGate() + " -> " + edge.targetGate());
                }
                feedbackSourcesByChannel.computeIfAbsent(
                        source.column, ignored -> new ArrayList<>()).add(edge);
                feedbackTargetsByChannel.computeIfAbsent(
                        target.column - 1, ignored -> new ArrayList<>()).add(edge);
            }
        }
        int feedbackTrackPitch = TRUNK_PITCH * spacingFactor;
        Map<Integer, Integer> normalGapByChannel = new LinkedHashMap<>();
        Map<Integer, Integer> feedbackGapByChannel = new LinkedHashMap<>();
        for (int channel = 0; channel < columns.size() - 1; channel++) {
            int count = signalsByChannel.getOrDefault(channel, Set.of()).size();
            int sourceTracks = feedbackSourcesByChannel.getOrDefault(
                    channel, List.of()).size();
            int targetTracks = feedbackTargetsByChannel.getOrDefault(
                    channel, List.of()).size();
            // Size each feedback-layout gap from the tracks crossing this gap.
            // The established DAG formula remains unchanged.
            int normalGap = feedback == null
                    ? laneStart + laneEnd + (count > 0 ? (count - 1) * lanePitch : 0)
                    : laneEnd + lanePitch * count;
            int feedbackGap = feedback == null ? 0
                    : feedbackTrackPitch * (sourceTracks + targetTracks + 2);
            normalGapByChannel.put(channel, normalGap);
            feedbackGapByChannel.put(channel, feedbackGap);
            columnLeft[channel + 1] = columnLeft[channel]
                    + columnWidths[channel] + normalGap + feedbackGap;
        }

        for (Node node : allNodes(columns)) {
            if (node.isDummy()) {
                node.x = columnLeft[node.column];
            } else if (node.isBox()) {
                node.x = columnLeft[node.column];
            } else {
                node.x = columnLeft[node.column] + node.definition.origin().x();
            }
        }

        LinkedHashSet<WireSegment> wires = route(
                routeSteps, columnLeft, columnWidths, signalsByChannel,
                laneStart, lanePitch);
        wires = normalizeDrawing(allNodes(columns), wires);

        for (Node node : allNodes(columns)) {
            if (!node.isDummy()) {
                String label = node.kind == NodeKind.INPUT || node.kind == NodeKind.CLOCK
                        || node.kind == NodeKind.FEEDBACK || node.kind == NodeKind.CONSTANT
                        ? node.signal
                        : node.kind == NodeKind.OUTPUT ? outputLabelFor(node, netlist) : "";
                if (node.isBox()) {
                    schematic.addInstance(new ComponentInstance(
                            node.boxDefinition.name(), node.boxDefinition.hash(),
                            node.componentId, "", node.x, node.y, Facing.EAST),
                            node.boxDefinition);
                } else {
                    schematic.add(new PlacedComponent(
                            node.componentId, node.definition.family(), node.x, node.y,
                            Facing.EAST, Map.of(), label));
                }
            }
        }

        for (WireSegment wire : wires) {
            schematic.addWire(wire);
        }

        checkNoComponentOverlaps(schematic);
        checkConnectivity(netlist, schematic, connectivityInputIds, constantIds,
                gateNodes, outputIds);

        if (feedback == null) {
            return new LayoutResult(
                    schematic, inputIds, outputIds, constantIds, dummyCount);
        }

        Set<WireSegment> feedbackPlaceholderWires = new LinkedHashSet<>();
        Connectivity.Result dagTopology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        for (String placeholder : feedback.feedbackInputSignals()) {
            String componentId = feedbackIds.get(placeholder);
            Connectivity.PinPosition outputPin = schematic.pinPositions().stream()
                    .filter(pin -> pin.componentId().equals(componentId)
                            && pin.pinId().equals("out"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "feedback placeholder output is missing: " + placeholder));
            Connectivity.Net placeholderNet = dagTopology.nets().stream()
                    .filter(net -> net.pins().contains(outputPin))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "feedback placeholder has no generated net: " + placeholder));
            feedbackPlaceholderWires.addAll(placeholderNet.segments());
        }

        LinkedHashSet<WireSegment> feedbackWires = routeFeedbackEdges(
                feedback, gateNodes, feedbackSourcesByChannel, feedbackTargetsByChannel,
                normalGapByChannel, feedbackTrackPitch, allNodes(columns));
        List<PlacedComponent> finalComponents = schematic.components().stream()
                .filter(component -> !feedbackIds.containsValue(component.id()))
                .toList();
        LinkedHashSet<WireSegment> finalWires = new LinkedHashSet<>(schematic.wires());
        finalWires.removeAll(feedbackPlaceholderWires);
        finalWires.addAll(feedbackWires);

        Schematic feedbackSchematic = new Schematic(definitions);
        feedbackSchematic.restoreSnapshot(new SchematicSnapshot(
                finalComponents, List.copyOf(finalWires)));
        checkNoComponentOverlaps(feedbackSchematic);
        Map<String, String> primaryInputIds = new LinkedHashMap<>(inputIds);
        primaryInputIds.putAll(clockIds);
        checkConnectivity(feedback.sourceNetlist(), feedbackSchematic,
                primaryInputIds, constantIds, gateNodes, outputIds);

        Map<String, String> gateIds = new LinkedHashMap<>();
        gateNodes.forEach((signal, node) -> gateIds.put(signal, node.componentId));
        return new LayoutResult(
                feedbackSchematic, inputIds, outputIds, constantIds, dummyCount,
                clockIds, gateIds);
    }

    private Node componentNode(
            String key,
            NodeKind kind,
            int column,
            String signal,
            String componentId,
            String family) {
        GateDef definition = definitionFor(family);
        int height = GateGeometry.bounds(definition, Facing.EAST).height();
        return new Node(key, kind, column, height, signal, componentId, definition);
    }

    private GateDef definitionFor(String family) {
        GateDef definition = definitionsByFamily.get(family);
        if (definition == null) {
            throw new IllegalArgumentException("gates.json has no narrow component for: " + family);
        }
        return definition;
    }

    private static String outputLabelFor(Node node, Netlist netlist) {
        for (Netlist.Output output : netlist.outputs()) {
            if (("expr-output-" + output.name()).equals(node.componentId)) {
                return output.name();
            }
        }
        throw new IllegalStateException("missing label for output component " + node.componentId);
    }

    private static int expandConnection(
            String signal,
            Node source,
            Node target,
            int targetInputIndex,
            List<List<Node>> columns,
            List<RouteStep> routeSteps) {
        if (source == null || target == null) {
            throw new IllegalArgumentException("cannot lay out a connection with a missing endpoint");
        }
        if (target.column <= source.column) {
            throw new IllegalArgumentException(
                    "netlist connection does not advance through layers: "
                            + source.key + " -> " + target.key);
        }

        Node previous = source;
        int dummyCount = 0;
        for (int column = source.column + 1; column < target.column; column++) {
            String dummyKey = "dummy:" + signal + ":" + target.key + ":"
                    + targetInputIndex + ":" + column;
            Node dummy = new Node(
                    dummyKey, NodeKind.DUMMY, column, DUMMY_SLOT_HEIGHT,
                    signal, null, null);
            columns.get(column).add(dummy);
            connectAdjacent(previous, dummy);
            routeSteps.add(new RouteStep(signal, column - 1, previous, dummy, -1));
            previous = dummy;
            dummyCount++;
        }
        connectAdjacent(previous, target);
        routeSteps.add(new RouteStep(
                signal, target.column - 1, previous, target, targetInputIndex));
        return dummyCount;
    }

    private static void connectAdjacent(Node source, Node target) {
        source.next.add(target);
        target.previous.add(source);
    }

    private static void orderColumns(List<List<Node>> columns) {
        for (int pass = 0; pass < 4; pass++) {
            for (int column = 1; column < columns.size(); column++) {
                reorderByNeighbors(columns, column, true);
            }
            for (int column = columns.size() - 2; column >= 0; column--) {
                reorderByNeighbors(columns, column, false);
            }
        }
    }

    private static void reorderByNeighbors(
            List<List<Node>> columns, int column, boolean usePrevious) {
        List<Node> layer = columns.get(column);
        if (layer.size() < 2) {
            return;
        }
        List<Node> neighborLayer = columns.get(usePrevious ? column - 1 : column + 1);
        Map<Node, Integer> positions = new HashMap<>();
        for (int index = 0; index < neighborLayer.size(); index++) {
            positions.put(neighborLayer.get(index), index);
        }
        Map<Node, Integer> currentPositions = new HashMap<>();
        for (int index = 0; index < layer.size(); index++) {
            currentPositions.put(layer.get(index), index);
        }

        Comparator<Node> comparator = Comparator
                .comparingDouble((Node node) -> barycenter(
                        usePrevious ? node.previous : node.next,
                        positions, currentPositions.get(node)))
                .thenComparing(node -> node.key);
        layer.sort(comparator);
    }

    private static double barycenter(
            List<Node> neighbors, Map<Node, Integer> positions, int fallback) {
        if (neighbors.isEmpty()) {
            return fallback;
        }
        double sum = 0.0;
        int count = 0;
        for (Node neighbor : neighbors) {
            Integer position = positions.get(neighbor);
            if (position != null) {
                sum += position;
                count++;
            }
        }
        return count == 0 ? fallback : sum / count;
    }

    private static void relocateFeedbackInputs(
            Netlist netlist,
            List<List<Node>> columns,
            Map<String, Node> signalNodes,
            Map<String, Node> gateNodes,
            List<Node> inputColumnNodes,
            int outputColumn) {
        for (Node input : inputColumnNodes) {
            if (input.kind != NodeKind.INPUT && input.kind != NodeKind.CLOCK) {
                continue;
            }
            int firstConsumerColumn = outputColumn;
            boolean consumed = false;
            for (Netlist.Gate gate : netlist.gates()) {
                if (gate.inputs().contains(input.signal)) {
                    firstConsumerColumn = Math.min(
                            firstConsumerColumn, gateNodes.get(gate.id()).column);
                    consumed = true;
                }
            }
            for (Netlist.Output output : netlist.outputs()) {
                if (output.source().equals(input.signal)) {
                    consumed = true;
                }
            }
            if (consumed) {
                int destination = Math.max(0, firstConsumerColumn - 1);
                if (destination != input.column) {
                    columns.get(input.column).remove(input);
                    input.column = destination;
                    columns.get(destination).add(input);
                }
            }
        }
    }

    private static void assignFeedbackInputColumns(List<Node> inputNodes, int rowGap) {
        Map<Integer, List<Node>> byColumn = new LinkedHashMap<>();
        for (Node node : inputNodes) {
            byColumn.computeIfAbsent(node.column, ignored -> new ArrayList<>()).add(node);
        }
        for (List<Node> nodes : byColumn.values()) {
            nodes.sort(Comparator.comparing((Node node) -> node.kind.name())
                    .thenComparing(node -> node.signal));
            int tallest = nodes.stream()
                    .mapToInt(node -> nodeBottomOffset(node) - nodeTopOffset(node))
                    .max().orElse(DUMMY_SLOT_HEIGHT);
            int pitch = roundUpToGrid(tallest + rowGap);
            for (int index = 0; index < nodes.size(); index++) {
                nodes.get(index).y = index * pitch;
            }
        }
    }

    private static void orderFeedbackColumns(List<List<Node>> columns) {
        orderFeedbackColumns(columns, false);
    }

    private static void orderFeedbackColumns(
            List<List<Node>> columns, boolean reverseDriverOrder) {
        for (int column = 1; column < columns.size(); column++) {
            List<Node> layer = columns.get(column);
            Comparator<Node> comparator =
                    Comparator.comparingDouble(SchematicLayout::driverBarycenter)
                            .thenComparing(SchematicLayout::nodeTieBreakId);
            layer.sort(reverseDriverOrder
                    ? comparator.reversed()
                    : comparator);
        }
    }

    private static void orderFeedbackColumnsByConsumers(
            List<List<Node>> columns, List<RouteStep> routeSteps) {
        for (int column = 1; column < columns.size(); column++) {
            List<Node> layer = columns.get(column);
            layer.sort(Comparator
                    .comparingDouble((Node node) ->
                            consumerBarycenter(node, routeSteps))
                    .thenComparing(SchematicLayout::nodeTieBreakId));
        }
    }

    private static double consumerBarycenter(Node node, List<RouteStep> routeSteps) {
        long total = 0L;
        int count = 0;
        for (RouteStep step : routeSteps) {
            if (step.source() == node) {
                total += targetPinY(step);
                count++;
            }
        }
        return count == 0 ? node.y : total / (double) count;
    }

    private static double driverBarycenter(Node node) {
        if (node.previous.isEmpty()) {
            return node.y;
        }
        long total = 0L;
        for (Node driver : node.previous) {
            total += sourcePinY(driver);
        }
        return total / (double) node.previous.size();
    }

    private static String nodeTieBreakId(Node node) {
        return node.componentId == null ? node.key : node.componentId;
    }

    private static void assignInputColumn(List<Node> inputNodes, int rowGap) {
        if (inputNodes.isEmpty()) {
            return;
        }
        int tallest = inputNodes.stream()
                .mapToInt(node -> nodeBottomOffset(node) - nodeTopOffset(node))
                .max().orElse(DUMMY_SLOT_HEIGHT);
        int pitch = roundUpToGrid(tallest + rowGap);
        for (int index = 0; index < inputNodes.size(); index++) {
            inputNodes.get(index).y = index * pitch;
        }
    }

    /**
     * Runs the requested forward/backward/forward compaction passes. The
     * candidate with the shortest actual vertical trunk geometry wins; ties
     * retain the earlier pass, making the choice stable.
     */
    private static void optimizeVerticalPositions(
            List<List<Node>> columns,
            Map<String, Node> outputNodes,
            List<RouteStep> routeSteps,
            int rowGap,
            boolean feedbackPacking,
            FeedbackCircuitExtractor.Extraction feedback,
            Map<String, Node> gateNodes,
            int feedbackTrackPitch,
            boolean includeFeedbackWireLength) {
        int outputColumn = columns.size() - 1;
        int[] bestPositions = null;
        long bestLength = Long.MAX_VALUE;

        for (int sweep = 0; sweep < 3; sweep++) {
            if (sweep == 1) {
                for (int column = outputColumn - 1; column >= 1; column--) {
                    assignColumn(
                            columns.get(column), routeSteps, rowGap,
                            false, column, true, feedbackPacking);
                }
            } else {
                for (int column = 1; column <= outputColumn; column++) {
                    List<Node> ordered = column == outputColumn && !feedbackPacking
                            && !outputNodes.isEmpty()
                            ? new ArrayList<>(outputNodes.values())
                            : columns.get(column);
                    assignColumn(
                            ordered, routeSteps, rowGap,
                            true, column - 1, false, feedbackPacking);
                }
            }

            if (!allChannelsHaveConflictFreePinRows(routeSteps, columns.size())) {
                continue;
            }
            long length = totalVerticalWireLength(routeSteps);
            if (includeFeedbackWireLength) {
                length += totalFeedbackVerticalWireLength(
                        feedback, gateNodes, feedbackTrackPitch);
            }
            if (length < bestLength) {
                bestLength = length;
                bestPositions = captureVerticalPositions(columns);
            }
        }

        if (bestPositions == null) {
            throw new IllegalStateException("no vertical layout candidate was produced");
        }
        restoreVerticalPositions(columns, bestPositions);
        if (feedbackPacking) {
            compactFeedbackRows(columns, routeSteps, rowGap);
        }
    }

    /**
     * Removes unused vertical row space after the barycentric ordering pass.
     * This only packs feedback layouts; if the tighter rows create a foreign
     * pin-row collision, the complete pre-sweep positions are restored.
     */
    private static void compactFeedbackRows(
            List<List<Node>> columns, List<RouteStep> routeSteps, int rowGap) {
        int[] before = captureVerticalPositions(columns);
        for (List<Node> column : columns) {
            for (int index = 1; index < column.size(); index++) {
                Node previous = column.get(index - 1);
                Node current = column.get(index);
                int packedY = previous.y + minimumVerticalSeparation(
                        previous, current, rowGap, true);
                if (current.y > packedY) {
                    current.y = packedY;
                }
            }
        }
        if (!allChannelsHaveConflictFreePinRows(routeSteps, columns.size())) {
            restoreVerticalPositions(columns, before);
        }
    }

    /**
     * Places one already-ordered column. In a forward pass each node follows
     * the average of its incoming source pins; in a backward pass it follows
     * the average of its outgoing destination pins.
     */
    private static void assignColumn(
            List<Node> ordered,
            List<RouteStep> routeSteps,
            int rowGap,
            boolean fromPredecessors,
            int conflictChannel,
            boolean movingSources,
            boolean feedbackPacking) {
        if (ordered.isEmpty()) {
            return;
        }

        double[] desired = new double[ordered.size()];
        double desiredTotal = 0.0;
        for (int index = 0; index < ordered.size(); index++) {
            Node node = ordered.get(index);
            double pinTotal = 0.0;
            int pinCount = 0;
            for (RouteStep step : routeSteps) {
                if (fromPredecessors && step.target() == node) {
                    pinTotal += sourcePinY(step.source(), step.signal());
                    pinCount++;
                } else if (!fromPredecessors && step.source() == node) {
                    pinTotal += targetPinY(step);
                    pinCount++;
                }
            }
            desired[index] = pinCount == 0 ? node.y : pinTotal / pinCount;
            desiredTotal += desired[index];
        }

        for (int index = 0; index < ordered.size(); index++) {
            Node node = ordered.get(index);
            int y = roundToGrid(desired[index]);
            if (index > 0) {
                Node previous = ordered.get(index - 1);
                y = Math.max(y, previous.y + minimumVerticalSeparation(
                        previous, node, rowGap, feedbackPacking));
            }
            node.y = y;
        }

        double currentMean = 0.0;
        for (Node node : ordered) {
            currentMean += node.y;
        }
        currentMean /= ordered.size();
        double desiredMean = desiredTotal / ordered.size();
        int preferredShift = roundToGrid(desiredMean - currentMean);
        int shift = closestConflictFreeShift(
                routeSteps, conflictChannel, movingSources, preferredShift);
        if (shift != 0) {
            for (Node node : ordered) {
                node.y += shift;
            }
        }
    }

    private record PinRow(String signal, int y) {
    }

    /**
     * A horizontal branch crosses other lanes in its channel. A pin-row
     * equality between different signals would turn that crossing into a
     * T-junction at the other trunk endpoint (or a pin-on-wire connection).
     * Select the closest grid shift that keeps those rows distinct.
     */
    /**
     * A candidate from a reverse sweep may have changed the source side of
     * a channel that was made safe earlier in that sweep. Only score snapshots
     * whose every adjacent-column interface remains cross-signal conflict-free.
     */
    private static boolean allChannelsHaveConflictFreePinRows(
            List<RouteStep> routeSteps, int columnCount) {
        for (int channel = 0; channel < columnCount - 1; channel++) {
            List<PinRow> sourceRows = new ArrayList<>();
            List<PinRow> targetRows = new ArrayList<>();
            for (RouteStep step : routeSteps) {
                if (step.channel() == channel) {
                    sourceRows.add(new PinRow(
                            step.signal(), sourcePinY(step.source(), step.signal())));
                    targetRows.add(new PinRow(
                            step.signal(), targetPinY(step)));
                }
            }
            if (hasForeignRowCollision(targetRows, sourceRows, 0)) {
                return false;
            }
        }
        return true;
    }

    private static int closestConflictFreeShift(
            List<RouteStep> routeSteps,
            int channel,
            boolean movingSources,
            int preferredShift) {
        List<PinRow> fixedRows = new ArrayList<>();
        List<PinRow> movingRows = new ArrayList<>();
        for (RouteStep step : routeSteps) {
            if (step.channel() != channel) {
                continue;
            }
            if (movingSources) {
                fixedRows.add(new PinRow(step.signal(), targetPinY(step)));
                movingRows.add(new PinRow(step.signal(), sourcePinY(step.source(), step.signal())));
            } else {
                fixedRows.add(new PinRow(step.signal(), sourcePinY(step.source(), step.signal())));
                movingRows.add(new PinRow(step.signal(), targetPinY(step)));
            }
        }

        if (movingRows.isEmpty() || fixedRows.isEmpty()) {
            return preferredShift;
        }

        for (int distance = 0; ; distance++) {
            int lower = preferredShift - distance * PlacedComponent.GRID_SPACING;
            if (!hasForeignRowCollision(movingRows, fixedRows, lower)) {
                return lower;
            }
            if (distance > 0) {
                int upper = preferredShift + distance * PlacedComponent.GRID_SPACING;
                if (!hasForeignRowCollision(movingRows, fixedRows, upper)) {
                    return upper;
                }
            }
        }
    }

    private static boolean hasForeignRowCollision(
            List<PinRow> movingRows, List<PinRow> fixedRows, int shift) {
        for (PinRow moving : movingRows) {
            int translatedY = moving.y() + shift;
            for (PinRow fixed : fixedRows) {
                if (!moving.signal().equals(fixed.signal())
                        && translatedY == fixed.y()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int minimumVerticalSeparation(
            Node previous, Node current, int rowGap, boolean feedbackPacking) {
        int requiredGap = rowGap;
        if (feedbackPacking) {
            int previousPins = previous.isDummy() ? 1 : nodePinCount(previous);
            int currentPins = current.isDummy() ? 1 : nodePinCount(current);
            int pinBasedGap = 10 + 10 * Math.max(previousPins, currentPins);
            requiredGap = Math.max(requiredGap, pinBasedGap);
        }
        int required = nodeBottomOffset(previous) - nodeTopOffset(current) + requiredGap;
        return roundUpToGrid(required);
    }

    private static int nodeTopOffset(Node node) {
        if (node.isDummy()) {
            return -DUMMY_SLOT_HEIGHT / 2;
        }
        return node.isBox() ? 0
                : GateGeometry.bounds(node.definition, Facing.EAST).y();
    }

    private static int nodeBottomOffset(Node node) {
        if (node.isDummy()) {
            return DUMMY_SLOT_HEIGHT / 2;
        }
        if (node.isBox()) {
            return boxHeight(node.boxDefinition);
        }
        GateGeometry.Bounds bounds = GateGeometry.bounds(node.definition, Facing.EAST);
        return bounds.y() + bounds.height();
    }

    private static int sourcePinY(Node source) {
        return sourcePinY(source, source.signal);
    }

    private static int sourcePinY(Node source, String signal) {
        if (source.isDummy()) {
            return source.y;
        }
        if (source.isBox()) {
            return source.y + boxPinOffset(
                    source.boxDefinition, outputPinForSignal(source, signal)).y();
        }
        GateGeometry.Offset offset = GateGeometry.outputOffset(source.definition, Facing.EAST);
        return source.y + (offset == null ? 0 : offset.y());
    }

    private static int sourcePinX(Node source, String signal) {
        if (source.isDummy()) {
            return source.x;
        }
        if (source.isBox()) {
            return source.x + boxPinOffset(
                    source.boxDefinition, outputPinForSignal(source, signal)).x();
        }
        GateGeometry.Offset offset = GateGeometry.outputOffset(source.definition, Facing.EAST);
        return source.x + (offset == null ? 0 : offset.x());
    }

    private static GateGeometry.Offset targetPinOffset(RouteStep step) {
        Node target = step.target();
        if (target.isBox()) {
            String name = step.targetPinName() != null
                    ? step.targetPinName()
                    : target.inputPinNames.get(step.targetInputIndex());
            return boxPinOffset(target.boxDefinition, name);
        }
        int inputIndex = step.targetInputIndex();
        return GateGeometry.pinOffset(
                target.definition, target.definition.inputs().get(inputIndex), Facing.EAST);
    }

    private static int targetPinY(RouteStep step) {
        Node target = step.target();
        return target.isDummy() ? target.y : target.y + targetPinOffset(step).y();
    }

    private static int nodeWidth(Node node) {
        return node.isBox() ? ComponentInstance.BOX_WIDTH : node.definition.width();
    }

    private static int nodePinCount(Node node) {
        return node.isBox()
                ? node.boxDefinition.ports().size()
                : node.definition.inputs().size() + 1;
    }

    private static int boxHeight(ComponentDefinition definition) {
        return Math.max(definition.inputPorts().size(), definition.outputPorts().size())
                * ComponentInstance.PORT_SPACING + ComponentInstance.PORT_SPACING;
    }

    private static GateGeometry.Offset boxPinOffset(
            ComponentDefinition definition, String pinName) {
        int height = boxHeight(definition);
        List<ComponentDefinition.Port> ports = definition.ports();
        int inputCount = definition.inputPorts().size();
        for (int index = 0; index < inputCount; index++) {
            ComponentDefinition.Port port = definition.inputPorts().get(index);
            if (port.name().equals(pinName)) {
                int firstY = (height - (inputCount - 1) * ComponentInstance.PORT_SPACING) / 2;
                return new GateGeometry.Offset(
                        0, firstY + index * ComponentInstance.PORT_SPACING);
            }
        }
        List<ComponentDefinition.Port> outputs = definition.outputPorts();
        for (int index = 0; index < outputs.size(); index++) {
            if (outputs.get(index).name().equals(pinName)) {
                int firstY = (height - (outputs.size() - 1) * ComponentInstance.PORT_SPACING) / 2;
                return new GateGeometry.Offset(
                        ComponentInstance.BOX_WIDTH,
                        firstY + index * ComponentInstance.PORT_SPACING);
            }
        }
        throw new IllegalArgumentException(
                "component " + definition.name() + " has no port " + pinName);
    }

    private static String outputPinForSignal(Node node, String signal) {
        return node.outputSignalsByPin.entrySet().stream()
                .filter(entry -> entry.getValue().equals(signal))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "component " + node.componentId + " has no output for signal " + signal));
    }

    private static int roundToGrid(double coordinate) {
        return (int) (Math.round(coordinate / PlacedComponent.GRID_SPACING)
                * PlacedComponent.GRID_SPACING);
    }

    private static int roundUpToGrid(int distance) {
        return (int) (Math.ceil(distance / (double) PlacedComponent.GRID_SPACING)
                * PlacedComponent.GRID_SPACING);
    }

    private static long totalVerticalWireLength(List<RouteStep> routeSteps) {
        Map<ChannelSignal, int[]> ranges = new LinkedHashMap<>();
        for (RouteStep step : routeSteps) {
            int sourceY = sourcePinY(step.source(), step.signal());
            int targetY = targetPinY(step);
            int[] range = ranges.computeIfAbsent(
                    new ChannelSignal(step.channel(), step.signal()),
                    ignored -> new int[]{Math.min(sourceY, targetY), Math.max(sourceY, targetY)});
            range[0] = Math.min(range[0], Math.min(sourceY, targetY));
            range[1] = Math.max(range[1], Math.max(sourceY, targetY));
        }
        long total = 0L;
        for (int[] range : ranges.values()) {
            total += (long) range[1] - range[0];
        }
        return total;
    }

    /**
     * Exact vertical contribution of feedback returns for the current node
     * positions, using the same interval lane assignment and above/below lane
     * choice as routeFeedbackEdges(). This is an optional candidate score;
     * it does not change routing or relax the connectivity check.
     */
    private static long totalFeedbackVerticalWireLength(
            FeedbackCircuitExtractor.Extraction extraction,
            Map<String, Node> gateNodes,
            int trackPitch) {
        if (extraction == null || extraction.feedbackEdges().isEmpty()) {
            return 0L;
        }
        Map<String, Integer> columnsByGate = new LinkedHashMap<>();
        gateNodes.forEach((signal, node) -> columnsByGate.put(signal, node.column));
        Map<FeedbackCircuitExtractor.FeedbackEdge, Integer> laneByEdge =
                assignFeedbackLanes(extraction.feedbackEdges(), columnsByGate);
        long total = 0L;
        for (FeedbackCircuitExtractor.FeedbackEdge edge : extraction.feedbackEdges()) {
            Node source = gateNodes.get(edge.sourceGate());
            Node target = gateNodes.get(edge.targetGate());
            if (source == null || target == null) {
                throw new IllegalStateException("feedback route references a missing gate");
            }
            GateGeometry.Offset targetInputOffset = GateGeometry.pinOffset(
                    target.definition,
                    target.definition.inputs().get(edge.targetInputIndex()),
                    Facing.EAST);
            int sourceY = sourcePinY(source);
            int targetY = target.y + targetInputOffset.y();
            int minGateTop = Integer.MAX_VALUE;
            int maxGateBottom = Integer.MIN_VALUE;
            for (Node gate : gateNodes.values()) {
                if (gate.column >= target.column && gate.column <= source.column) {
                    GateGeometry.Bounds bounds = GateGeometry.bounds(
                            gate.definition, Facing.EAST);
                    minGateTop = Math.min(minGateTop, gate.y + bounds.y());
                    maxGateBottom = Math.max(
                            maxGateBottom, gate.y + bounds.y() + bounds.height());
                }
            }
            if (minGateTop == Integer.MAX_VALUE) {
                throw new IllegalStateException(
                        "feedback interval contains no gate rows for " + edge);
            }
            int lane = laneByEdge.get(edge);
            int aboveY = minGateTop - (lane + 1) * trackPitch;
            int belowY = maxGateBottom + (lane + 1) * trackPitch;
            int aboveLength = Math.abs(sourceY - aboveY) + Math.abs(targetY - aboveY);
            int belowLength = Math.abs(sourceY - belowY) + Math.abs(targetY - belowY);
            int laneY = aboveLength <= belowLength ? aboveY : belowY;
            total += Math.abs(sourceY - laneY) + Math.abs(targetY - laneY);
        }
        return total;
    }

    private static int[] captureVerticalPositions(List<List<Node>> columns) {
        List<Node> nodes = allNodes(columns);
        int[] positions = new int[nodes.size()];
        for (int index = 0; index < nodes.size(); index++) {
            positions[index] = nodes.get(index).y;
        }
        return positions;
    }

    private static void restoreVerticalPositions(List<List<Node>> columns, int[] positions) {
        List<Node> nodes = allNodes(columns);
        if (nodes.size() != positions.length) {
            throw new IllegalStateException("vertical layout snapshot does not match its nodes");
        }
        for (int index = 0; index < nodes.size(); index++) {
            nodes.get(index).y = positions[index];
        }
    }

    /**
     * Translates components and wires together so their geometric bounding
     * box starts at the same (0, 0) world origin used by the original layout.
     */
    private static LinkedHashSet<WireSegment> normalizeDrawing(
            List<Node> nodes, Collection<WireSegment> wires) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        for (Node node : nodes) {
            if (!node.isDummy()) {
                GateGeometry.Bounds bounds = node.isBox()
                        ? new GateGeometry.Bounds(
                                node.x, node.y, ComponentInstance.BOX_WIDTH,
                                boxHeight(node.boxDefinition))
                        : GateGeometry.bounds(node.definition, Facing.EAST);
                minX = Math.min(minX, node.x + (node.isBox() ? 0 : bounds.x()));
                minY = Math.min(minY, node.y + (node.isBox() ? 0 : bounds.y()));
            }
        }
        for (WireSegment wire : wires) {
            minX = Math.min(minX, Math.min(wire.x1(), wire.x2()));
            minY = Math.min(minY, Math.min(wire.y1(), wire.y2()));
        }
        if (minX == Integer.MAX_VALUE || (minX == 0 && minY == 0)) {
            return new LinkedHashSet<>(wires);
        }

        int deltaX = -minX;
        int deltaY = -minY;
        for (Node node : nodes) {
            node.x += deltaX;
            node.y += deltaY;
        }
        LinkedHashSet<WireSegment> translated = new LinkedHashSet<>();
        for (WireSegment wire : wires) {
            translated.add(translateWire(wire, deltaX, deltaY));
        }
        return translated;
    }

    private static WireSegment translateWire(
            WireSegment wire, int deltaX, int deltaY) {
        return new WireSegment(
                wire.x1() + deltaX, wire.y1() + deltaY,
                wire.x2() + deltaX, wire.y2() + deltaY);
    }

    private static List<Node> allNodes(List<List<Node>> columns) {
        List<Node> result = new ArrayList<>();
        for (List<Node> column : columns) {
            result.addAll(column);
        }
        return result;
    }

    private static LinkedHashSet<WireSegment> route(
            List<RouteStep> steps,
            int[] columnLeft,
            int[] columnWidths,
            Map<Integer, Set<String>> signalsByChannel,
            int laneStart,
            int lanePitch) {
        Map<ChannelSignal, List<RouteStep>> groups = new LinkedHashMap<>();
        for (RouteStep step : steps) {
            groups.computeIfAbsent(
                    new ChannelSignal(step.channel(), step.signal()),
                    ignored -> new ArrayList<>()).add(step);
        }

        LinkedHashSet<WireSegment> wires = new LinkedHashSet<>();
        for (int channel = 0; channel < columnLeft.length - 1; channel++) {
            List<String> signals = signalsByChannel.getOrDefault(channel, Set.of())
                    .stream().sorted().toList();
            for (int lane = 0; lane < signals.size(); lane++) {
                String signal = signals.get(lane);
                int trunkX = columnLeft[channel] + columnWidths[channel]
                        + laneStart + lane * lanePitch;
                List<RouteStep> channelSteps = groups.get(
                        new ChannelSignal(channel, signal));
                channelSteps.sort(Comparator
                        .comparing((RouteStep step) -> step.source().key)
                        .thenComparing(step -> step.target().key)
                        .thenComparingInt(RouteStep::targetInputIndex));

                LinkedHashSet<Node> sources = new LinkedHashSet<>();
                int minY = Integer.MAX_VALUE;
                int maxY = Integer.MIN_VALUE;
                for (RouteStep step : channelSteps) {
                    sources.add(step.source());
                    int sourceY = sourcePinY(step.source(), step.signal());
                    int[] target = targetPoint(step, columnLeft);
                    minY = Math.min(minY, Math.min(sourceY, target[1]));
                    maxY = Math.max(maxY, Math.max(sourceY, target[1]));
                }

                List<Node> orderedSources = sources.stream()
                        .sorted(Comparator.comparing(node -> node.key)).toList();
                for (Node source : orderedSources) {
                    int sourceX = source.isDummy()
                            ? columnLeft[channel] : sourcePinX(source, signal);
                    int sourceY = sourcePinY(source, signal);
                    addWire(wires, sourceX, sourceY, trunkX, sourceY);
                }

                addWire(wires, trunkX, minY, trunkX, maxY);
                for (RouteStep step : channelSteps) {
                    int[] target = targetPoint(step, columnLeft);
                    addWire(wires, trunkX, target[1], target[0], target[1]);
                }
            }
        }
        return wires;
    }

    private static int[] targetPoint(RouteStep step, int[] columnLeft) {
        Node target = step.target();
        if (target.isDummy()) {
            return new int[]{columnLeft[target.column], target.y};
        }
        GateGeometry.Offset offset = targetPinOffset(step);
        return new int[]{target.x + offset.x(), target.y + offset.y()};
    }

    /**
     * Greedy interval coloring. Edges whose [target, source] column intervals
     * do not overlap reuse a lane; sorting and the first-free-lane choice make
     * the assignment stable across runs.
     */
    static Map<FeedbackCircuitExtractor.FeedbackEdge, Integer> assignFeedbackLanes(
            List<FeedbackCircuitExtractor.FeedbackEdge> edges,
            Map<String, Integer> columnsByGate) {
        List<FeedbackCircuitExtractor.FeedbackEdge> ordered = edges.stream()
                .sorted(Comparator
                        .comparingInt((FeedbackCircuitExtractor.FeedbackEdge edge) ->
                                requiredColumn(columnsByGate, edge.targetGate()))
                        .thenComparingInt(edge ->
                                requiredColumn(columnsByGate, edge.sourceGate()))
                        .thenComparing(FeedbackCircuitExtractor.FeedbackEdge::sourceGate)
                        .thenComparing(FeedbackCircuitExtractor.FeedbackEdge::targetGate)
                        .thenComparingInt(FeedbackCircuitExtractor.FeedbackEdge::targetInputIndex))
                .toList();
        List<Integer> lastEndByLane = new ArrayList<>();
        Map<FeedbackCircuitExtractor.FeedbackEdge, Integer> assignments = new LinkedHashMap<>();
        for (FeedbackCircuitExtractor.FeedbackEdge edge : ordered) {
            int start = requiredColumn(columnsByGate, edge.targetGate());
            int end = requiredColumn(columnsByGate, edge.sourceGate());
            if (end < start) {
                throw new IllegalArgumentException(
                        "feedback interval runs forwards: " + edge.sourceGate()
                                + " -> " + edge.targetGate());
            }
            int lane = 0;
            while (lane < lastEndByLane.size() && lastEndByLane.get(lane) >= start) {
                lane++;
            }
            if (lane == lastEndByLane.size()) {
                lastEndByLane.add(end);
            } else {
                lastEndByLane.set(lane, end);
            }
            assignments.put(edge, lane);
        }
        return java.util.Collections.unmodifiableMap(assignments);
    }

    private static int requiredColumn(Map<String, Integer> columnsByGate, String gate) {
        Integer column = columnsByGate.get(gate);
        if (column == null) {
            throw new IllegalArgumentException("missing feedback gate column: " + gate);
        }
        return column;
    }

    /**
     * Routes feedback around only the gate rows covered by its source/target
     * interval. Each interval uses the left-edge lane allocation above, so
     * disjoint column spans share the same logical lane.
     */
    private static LinkedHashSet<WireSegment> routeFeedbackEdges(
            FeedbackCircuitExtractor.Extraction extraction,
            Map<String, Node> gateNodes,
            Map<Integer, List<FeedbackCircuitExtractor.FeedbackEdge>> sourcesByChannel,
            Map<Integer, List<FeedbackCircuitExtractor.FeedbackEdge>> targetsByChannel,
            Map<Integer, Integer> normalGapByChannel,
            int trackPitch,
            List<Node> allNodes) {
        LinkedHashSet<WireSegment> wires = new LinkedHashSet<>();
        Map<String, Integer> columnsByGate = new LinkedHashMap<>();
        gateNodes.forEach((signal, node) -> columnsByGate.put(signal, node.column));
        Map<FeedbackCircuitExtractor.FeedbackEdge, Integer> laneByEdge =
                assignFeedbackLanes(extraction.feedbackEdges(), columnsByGate);

        for (FeedbackCircuitExtractor.FeedbackEdge edge : extraction.feedbackEdges()) {
            Node source = gateNodes.get(edge.sourceGate());
            Node target = gateNodes.get(edge.targetGate());
            if (source == null || target == null) {
                throw new IllegalStateException("feedback edge references a missing gate");
            }

            List<FeedbackCircuitExtractor.FeedbackEdge> sourceEdges =
                    sourcesByChannel.getOrDefault(source.column, List.of());
            List<FeedbackCircuitExtractor.FeedbackEdge> targetEdges =
                    targetsByChannel.getOrDefault(target.column - 1, List.of());
            int sourceTrackIndex = sourceEdges.indexOf(edge);
            int targetTrackIndex = targetEdges.indexOf(edge);
            if (sourceTrackIndex < 0 || targetTrackIndex < 0) {
                throw new IllegalStateException("feedback route is missing a reserved track");
            }

            int normalSourceGap = normalGapByChannel.get(source.column);
            int normalTargetGap = normalGapByChannel.get(target.column - 1);
            int sourceX = source.x + normalSourceGap
                    + (sourceTrackIndex + 1) * trackPitch;
            GateGeometry.Offset targetInputOffset = GateGeometry.pinOffset(
                    target.definition,
                    target.definition.inputs().get(edge.targetInputIndex()),
                    Facing.EAST);
            int targetPinX = target.x + targetInputOffset.x();
            int targetTrackX = targetPinX
                    - (targetEdges.size() + 1 - targetTrackIndex) * trackPitch;

            int sourceY = sourcePinY(source);
            int targetY = target.y + targetInputOffset.y();
            int startColumn = target.column;
            int endColumn = source.column;
            int minGateTop = Integer.MAX_VALUE;
            int maxGateBottom = Integer.MIN_VALUE;
            for (Node gate : gateNodes.values()) {
                if (gate.column >= startColumn && gate.column <= endColumn) {
                    GateGeometry.Bounds bounds = GateGeometry.bounds(
                            gate.definition, Facing.EAST);
                    minGateTop = Math.min(minGateTop, gate.y + bounds.y());
                    maxGateBottom = Math.max(
                            maxGateBottom, gate.y + bounds.y() + bounds.height());
                }
            }
            if (minGateTop == Integer.MAX_VALUE) {
                throw new IllegalStateException("feedback interval contains no gate rows");
            }

            int lane = laneByEdge.get(edge);
            int aboveY = minGateTop - (lane + 1) * trackPitch;
            int belowY = maxGateBottom + (lane + 1) * trackPitch;
            int aboveLength = Math.abs(sourceY - aboveY) + Math.abs(targetY - aboveY);
            int belowLength = Math.abs(sourceY - belowY) + Math.abs(targetY - belowY);
            int laneY = aboveLength <= belowLength ? aboveY : belowY;

            if (sourceX <= targetTrackX) {
                throw new IllegalStateException(
                        "feedback route tracks are not ordered from source to target: "
                                + edge.sourceGate() + " -> " + edge.targetGate());
            }
            addWire(wires, source.x, sourceY, sourceX, sourceY);
            addWire(wires, sourceX, sourceY, sourceX, laneY);
            addWire(wires, targetTrackX, laneY, sourceX, laneY);
            addWire(wires, targetTrackX, laneY, targetTrackX, targetY);
            addWire(wires, targetTrackX, targetY, targetPinX, targetY);
        }
        return wires;
    }

    private static void addWire(
            Set<WireSegment> wires, int x1, int y1, int x2, int y2) {
        if (x1 == x2 && y1 == y2) {
            return;
        }
        wires.add(new WireSegment(x1, y1, x2, y2));
    }

    private static void checkNoComponentOverlaps(Schematic schematic) {
        List<PlacedComponent> components = schematic.components();
        for (int first = 0; first < components.size(); first++) {
            PlacedComponent a = components.get(first);
            GateGeometry.Bounds aBounds = worldBounds(schematic, a);
            for (int second = first + 1; second < components.size(); second++) {
                PlacedComponent b = components.get(second);
                GateGeometry.Bounds bBounds = worldBounds(schematic, b);
                boolean overlaps = aBounds.x() < bBounds.x() + bBounds.width()
                        && bBounds.x() < aBounds.x() + aBounds.width()
                        && aBounds.y() < bBounds.y() + bBounds.height()
                        && bBounds.y() < aBounds.y() + aBounds.height();
                if (overlaps) {
                    throw new LayoutCheckException(
                            "components overlap: " + a.id() + " and " + b.id(),
                            List.of(a.id(), b.id()));
                }
            }
        }
    }

    private static GateGeometry.Bounds worldBounds(
            Schematic schematic, PlacedComponent component) {
        GateGeometry.Bounds bounds = GateGeometry.bounds(
                schematic.definitionFor(component), component.facing());
        return new GateGeometry.Bounds(
                component.x() + bounds.x(), component.y() + bounds.y(),
                bounds.width(), bounds.height());
    }

    /**
     * Stable pin key shared by production self-check diagnostics and tests.
     */
    static String diagnosticPinKey(String componentId, String pinId) {
        return componentId + "::" + pinId;
    }

    /**
     * Dumps the actual Connectivity.analyze partition together with all wire
     * segments and geometric events that can join different expected signals.
     * Lane/track indices are the exact grid coordinates of vertical/horizontal
     * runs, so the diagnostic remains meaningful after route normalization.
     */
    static String diagnosticDump(
            Schematic schematic, Map<String, String> expectedSignalsByPin) {
        Objects.requireNonNull(schematic, "schematic");
        Objects.requireNonNull(expectedSignalsByPin, "expectedSignalsByPin");
        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        return diagnosticDump(schematic, expectedSignalsByPin, topology);
    }

    private static String diagnosticDump(
            Schematic schematic,
            Map<String, String> expectedSignalsByPin,
            Connectivity.Result topology) {
        StringBuilder report = new StringBuilder();
        report.append("\n--- COMPACT LAYOUT GEOMETRY DIAGNOSTIC ---\n");
        report.append("COMPONENT RECTANGLES (world coordinates):\n");
        for (PlacedComponent component : schematic.components().stream()
                .sorted(Comparator.comparing(PlacedComponent::id)).toList()) {
            GateGeometry.Bounds bounds = worldBounds(schematic, component);
            report.append("  ").append(component.id()).append(" type=")
                    .append(component.typeId()).append(" rect=[x=").append(bounds.x())
                    .append(", y=").append(bounds.y()).append(", width=")
                    .append(bounds.width()).append(", height=").append(bounds.height())
                    .append("]\n");
        }

        List<Connectivity.Net> nets = topology.nets();
        int joinCount = 0;
        report.append("CONNECTIVITY NETS (Connectivity.analyze):\n");
        for (int netIndex = 0; netIndex < nets.size(); netIndex++) {
            Connectivity.Net net = nets.get(netIndex);
            List<Connectivity.PinPosition> pins = net.pins().stream()
                    .sorted(Comparator.comparing(Connectivity.PinPosition::componentId)
                            .thenComparing(Connectivity.PinPosition::pinId))
                    .toList();
            List<WireSegment> segments = net.segments().stream()
                    .sorted(Comparator.comparingInt(WireSegment::x1)
                            .thenComparingInt(WireSegment::y1)
                            .thenComparingInt(WireSegment::x2)
                            .thenComparingInt(WireSegment::y2))
                    .toList();
            Set<String> signalSet = new TreeSet<>();
            for (Connectivity.PinPosition pin : pins) {
                String signal = expectedSignalsByPin.get(
                        diagnosticPinKey(pin.componentId(), pin.pinId()));
                if (signal != null) {
                    signalSet.add(signal);
                }
            }
            List<String> signals = List.copyOf(signalSet);
            report.append("  NET ").append(netIndex).append(" expectedSignals=")
                    .append(signals).append(" pins=").append(pins.size())
                    .append(" segments=").append(segments.size()).append("\n");
            for (Connectivity.PinPosition pin : pins) {
                report.append("    PIN ").append(pin.componentId()).append('.')
                        .append(pin.pinId()).append(" signal=")
                        .append(expectedSignalsByPin.getOrDefault(
                                diagnosticPinKey(pin.componentId(), pin.pinId()), "UNMAPPED"))
                        .append(" at (").append(pin.x()).append(",").append(pin.y())
                        .append(")\n");
            }
            for (WireSegment segment : segments) {
                report.append("    ").append(describeDiagnosticSegment(segment)).append("\n");
            }
            if (signals.size() < 2) {
                continue;
            }

            int beforeNetJoins = joinCount;
            for (int first = 0; first < segments.size(); first++) {
                for (int second = first + 1; second < segments.size(); second++) {
                    WireSegment a = segments.get(first);
                    WireSegment b = segments.get(second);
                    for (GeometryJoin join : diagnosticSegmentJoins(a, b)) {
                        for (int aSignal = 0; aSignal < signals.size(); aSignal++) {
                            for (int bSignal = aSignal + 1; bSignal < signals.size(); bSignal++) {
                                report.append("    DIFFERENT-NET JOIN netA=")
                                        .append(signals.get(aSignal)).append(" netB=")
                                        .append(signals.get(bSignal)).append(" rule=")
                                        .append(join.rule()).append(" point=(")
                                        .append(join.point().x()).append(',')
                                        .append(join.point().y()).append(") segmentA=")
                                        .append(describeDiagnosticSegment(a))
                                        .append(" segmentB=").append(describeDiagnosticSegment(b))
                                        .append("\n");
                                joinCount++;
                            }
                        }
                    }
                }
            }

            for (Connectivity.PinPosition pin : pins) {
                String pinSignal = expectedSignalsByPin.get(
                        diagnosticPinKey(pin.componentId(), pin.pinId()));
                if (pinSignal == null) {
                    continue;
                }
                Connectivity.Point point = new Connectivity.Point(pin.x(), pin.y());
                for (WireSegment interior : segments) {
                    if (!diagnosticStrictlyContains(interior, point)) {
                        continue;
                    }
                    List<WireSegment> stubs = segments.stream()
                            .filter(candidate -> candidate != interior)
                            .filter(candidate -> diagnosticIsEndpoint(candidate, point))
                            .toList();
                    if (stubs.isEmpty()) {
                        report.append("    DIFFERENT-NET JOIN netA=").append(pinSignal)
                                .append(" netB=").append(firstDifferentSignal(signals, pinSignal))
                                .append(" rule=PIN_ON_WIRE_INTERIOR point=(")
                                .append(point.x()).append(',').append(point.y())
                                .append(") segmentA=PIN-STUB(").append(pin.componentId())
                                .append('.').append(pin.pinId()).append(" at ")
                                .append(point.x()).append(',').append(point.y())
                                .append("; no explicit stub segment) segmentB=")
                                .append(describeDiagnosticSegment(interior)).append("\n");
                        joinCount++;
                    } else {
                        for (WireSegment stub : stubs) {
                            report.append("    DIFFERENT-NET JOIN netA=").append(pinSignal)
                                    .append(" netB=").append(firstDifferentSignal(signals, pinSignal))
                                    .append(" rule=PIN_ON_WIRE_INTERIOR point=(")
                                    .append(point.x()).append(',').append(point.y())
                                    .append(") segmentA=").append(describeDiagnosticSegment(stub))
                                    .append(" segmentB=").append(describeDiagnosticSegment(interior))
                                    .append("\n");
                            joinCount++;
                        }
                    }
                }
            }

            for (int first = 0; first < pins.size(); first++) {
                Connectivity.PinPosition a = pins.get(first);
                String aSignal = expectedSignalsByPin.get(
                        diagnosticPinKey(a.componentId(), a.pinId()));
                for (int second = first + 1; second < pins.size(); second++) {
                    Connectivity.PinPosition b = pins.get(second);
                    String bSignal = expectedSignalsByPin.get(
                            diagnosticPinKey(b.componentId(), b.pinId()));
                    if (a.x() == b.x() && a.y() == b.y()
                            && aSignal != null && bSignal != null && !aSignal.equals(bSignal)) {
                        report.append("    DIFFERENT-NET JOIN netA=").append(aSignal)
                                .append(" netB=").append(bSignal)
                                .append(" rule=PIN_COORDINATE_COINCIDENCE point=(")
                                .append(a.x()).append(',').append(a.y())
                                .append(") segmentA=PIN-STUB(").append(a.componentId()).append('.')
                                .append(a.pinId()).append(") segmentB=PIN-STUB(")
                                .append(b.componentId()).append('.').append(b.pinId()).append(")\n");
                        joinCount++;
                    }
                }
            }

            if (joinCount == beforeNetJoins) {
                report.append("    DIFFERENT-NET JOIN could not be attributed to a pair "
                        + "of listed primitives; merged expected signal groups=")
                        .append(signals).append("\n");
                joinCount++;
            }
        }

        report.append("DIFFERENT_NET_JOIN_COUNT=").append(joinCount).append("\n");
        if (joinCount == 0) {
            report.append("DIFFERENT_NET_JOINS: none; each Connectivity.analyze net "
                    + "contains at most one expected netlist signal.\n");
        }
        report.append("--- END GEOMETRY DIAGNOSTIC ---");
        return report.toString();
    }

    private record GeometryJoin(Connectivity.Point point, String rule) {
    }

    private static String firstDifferentSignal(List<String> signals, String signal) {
        return signals.stream().filter(candidate -> !candidate.equals(signal))
                .findFirst().orElse("UNKNOWN-OTHER-NET");
    }

    private static String describeDiagnosticSegment(WireSegment segment) {
        int grid = PlacedComponent.GRID_SPACING;
        if (segment.isHorizontal()) {
            return "H trackIndex=" + (segment.y1() / grid) + " coords=("
                    + segment.x1() + "," + segment.y1() + ")->("
                    + segment.x2() + "," + segment.y2() + ")";
        }
        return "V laneIndex=" + (segment.x1() / grid) + " coords=("
                + segment.x1() + "," + segment.y1() + ")->("
                + segment.x2() + "," + segment.y2() + ")";
    }

    private static List<GeometryJoin> diagnosticSegmentJoins(
            WireSegment first, WireSegment second) {
        Map<Connectivity.Point, String> joins = new LinkedHashMap<>();
        List<Connectivity.Point> firstEndpoints = List.of(
                new Connectivity.Point(first.x1(), first.y1()),
                new Connectivity.Point(first.x2(), first.y2()));
        List<Connectivity.Point> secondEndpoints = List.of(
                new Connectivity.Point(second.x1(), second.y1()),
                new Connectivity.Point(second.x2(), second.y2()));
        for (Connectivity.Point point : firstEndpoints) {
            if (diagnosticContains(second, point)) {
                joins.put(point, diagnosticJoinRule(first, second, point));
            }
        }
        for (Connectivity.Point point : secondEndpoints) {
            if (diagnosticContains(first, point)) {
                joins.put(point, diagnosticJoinRule(first, second, point));
            }
        }
        return joins.entrySet().stream()
                .map(entry -> new GeometryJoin(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static String diagnosticJoinRule(
            WireSegment first, WireSegment second, Connectivity.Point point) {
        boolean firstInterior = diagnosticStrictlyContains(first, point);
        boolean secondInterior = diagnosticStrictlyContains(second, point);
        if (first.isHorizontal() == second.isHorizontal()) {
            return firstInterior || secondInterior
                    ? "ENDPOINT_ON_WIRE_INTERIOR" : "COLLINEAR_OVERLAP_OR_TOUCH";
        }
        return firstInterior || secondInterior ? "T_JUNCTION" : "ENDPOINT_TOUCH";
    }

    private static boolean diagnosticContains(
            WireSegment segment, Connectivity.Point point) {
        if (segment.isHorizontal()) {
            return point.y() == segment.y1()
                    && point.x() >= segment.x1() && point.x() <= segment.x2();
        }
        return point.x() == segment.x1()
                && point.y() >= segment.y1() && point.y() <= segment.y2();
    }

    private static boolean diagnosticStrictlyContains(
            WireSegment segment, Connectivity.Point point) {
        return diagnosticContains(segment, point)
                && !diagnosticIsEndpoint(segment, point);
    }

    private static boolean diagnosticIsEndpoint(
            WireSegment segment, Connectivity.Point point) {
        return (segment.x1() == point.x() && segment.y1() == point.y())
                || (segment.x2() == point.x() && segment.y2() == point.y());
    }

    private static void checkConnectivity(
            Netlist netlist,
            Schematic schematic,
            Map<String, String> inputIds,
            Map<String, String> constantIds,
            Map<String, Node> gateNodes,
            Map<String, String> outputIds) {
        Map<PinKey, String> expected = new LinkedHashMap<>();
        for (String input : netlist.inputs()) {
            expected.put(new PinKey(inputIds.get(input), "out"), input);
        }
        for (Map.Entry<String, String> constant : constantIds.entrySet()) {
            expected.put(new PinKey(constant.getValue(), "out"), constant.getKey());
        }
        for (Netlist.Gate gate : netlist.gates()) {
            Node node = gateNodes.get(gate.id());
            expected.put(new PinKey(node.componentId, "out"), gate.id());
            for (int index = 0; index < gate.inputs().size(); index++) {
                expected.put(new PinKey(node.componentId, "in" + index), gate.inputs().get(index));
            }
        }
        for (Netlist.Output output : netlist.outputs()) {
            expected.put(new PinKey(outputIds.get(output.name()), "in0"), output.source());
        }

        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        Map<PinKey, Integer> netByPin = new HashMap<>();
        Map<String, Integer> netBySignal = new HashMap<>();
        Set<String> involved = new TreeSet<>();

        for (int netIndex = 0; netIndex < topology.nets().size(); netIndex++) {
            Connectivity.Net net = topology.nets().get(netIndex);
            if (net.pins().isEmpty()) {
                throw new LayoutCheckException(
                        "wire geometry formed an orphan net", List.of("orphan wire"));
            }
            Set<String> expectedSignals = new TreeSet<>();
            for (Connectivity.PinPosition pin : net.pins()) {
                PinKey key = new PinKey(pin.componentId(), pin.pinId());
                String signal = expected.get(key);
                if (signal == null) {
                    throw new LayoutCheckException(
                            "unexpected component pin in generated topology: " + key,
                            List.of(key.componentId(), key.pinId()));
                }
                expectedSignals.add(signal);
                netByPin.put(key, netIndex);
                Integer previousNet = netBySignal.putIfAbsent(signal, netIndex);
                if (previousNet != null && previousNet != netIndex) {
                    involved.add(signal);
                }
            }
            if (expectedSignals.size() > 1) {
                involved.addAll(expectedSignals);
            }
        }

        if (!involved.isEmpty()) {
            Map<String, String> diagnosticSignals = new LinkedHashMap<>();
            expected.forEach((pin, signal) -> diagnosticSignals.put(
                    diagnosticPinKey(pin.componentId(), pin.pinId()), signal));
            throw new LayoutCheckException(
                    "different netlist signals were joined or one signal was split: "
                            + String.join(", ", involved)
                            + diagnosticDump(schematic, diagnosticSignals, topology),
                    involved);
        }

        for (Map.Entry<PinKey, String> entry : expected.entrySet()) {
            if (!netByPin.containsKey(entry.getKey())) {
                throw new LayoutCheckException(
                        "generated pin is missing from connectivity analysis: " + entry.getKey(),
                        List.of(entry.getValue()));
            }
        }
    }
}
