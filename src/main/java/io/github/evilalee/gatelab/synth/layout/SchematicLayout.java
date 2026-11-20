package io.github.evilalee.gatelab.synth.layout;

import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
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
            int targetInputIndex) {
    }

    private record ChannelSignal(int channel, String signal) {
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
            return build(netlist, 1, null, false);
        } catch (LayoutCheckException firstFailure) {
            try {
                return build(netlist, 2, null, false);
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

    /** Builds a feedback-aware layout while keeping the ordinary DAG path unchanged. */
    public LayoutResult layoutFeedback(FeedbackCircuitExtractor.Extraction extraction) {
        Objects.requireNonNull(extraction, "extraction");
        try {
            return build(extraction.dagNetlist(), 1, extraction, true);
        } catch (LayoutCheckException compactFailure) {
            // The final row sweep is opportunistic: retain it when the full
            // placed-schematic self-check accepts it, otherwise restore the
            // pre-sweep layout and retry at the same spacing before expanding.
            try {
                return build(extraction.dagNetlist(), 1, extraction, false);
            } catch (LayoutCheckException uncompressedFailure) {
                try {
                    return build(extraction.dagNetlist(), 2, extraction, false);
                } catch (LayoutCheckException retryFailure) {
                    Set<String> involved = new TreeSet<>(compactFailure.involvedNets);
                    involved.addAll(uncompressedFailure.involvedNets);
                    involved.addAll(retryFailure.involvedNets);
                    String names = involved.isEmpty() ? "unknown" : String.join(", ", involved);
                    throw new IllegalStateException(
                            "Feedback layout failed its connectivity self-check after "
                                    + "the compact and uncompressed candidates and one "
                                    + "doubled-spacing retry. Involved nets: " + names
                                    + ". Compact failure: " + compactFailure.getMessage()
                                    + "; uncompressed failure: " + uncompressedFailure.getMessage()
                                    + "; retry failure: " + retryFailure.getMessage(),
                            retryFailure);
                }
            }
        }
    }

    private LayoutResult build(
            Netlist netlist, int spacingFactor,
            FeedbackCircuitExtractor.Extraction feedback,
            boolean compactFeedback) {
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
                    constant, id, "input");
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
        } else {
            assignFeedbackInputColumns(inputColumnNodes, rowGap);
            orderFeedbackColumns(columns);
        }
        optimizeVerticalPositions(
                columns, outputNodes, routeSteps, rowGap, feedback != null, compactFeedback);

        int laneStart = TRUNK_START_MARGIN * spacingFactor;
        int lanePitch = TRUNK_PITCH * spacingFactor;
        int laneEnd = TRUNK_END_MARGIN * spacingFactor;
        int[] columnLeft = new int[columns.size()];
        int[] columnWidths = new int[columns.size()];
        for (int column = 0; column < columns.size(); column++) {
            for (Node node : columns.get(column)) {
                if (!node.isDummy()) {
                    columnWidths[column] = Math.max(
                            columnWidths[column], node.definition.width());
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
                schematic.add(new PlacedComponent(
                        node.componentId, node.definition.family(), node.x, node.y,
                        Facing.EAST, Map.of(), label));
            }
        }

        for (String constant : constantsUsed) {
            if (constant.equals("1")) {
                schematic.setInputValue(constantIds.get(constant), true);
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
                normalGapByChannel, columnLeft, columnWidths,
                feedbackTrackPitch, allNodes(columns));
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
        for (int pass = 0; pass < 4; pass++) {
            for (int column = 1; column < columns.size(); column++) {
                reorderFeedbackByBarycenter(columns, column, true);
            }
            for (int column = columns.size() - 2; column >= 0; column--) {
                reorderFeedbackByBarycenter(columns, column, false);
            }
        }
    }

    /**
     * Uses the average row rank of the connected drivers (or sinks in the
     * backward pass). The stable component id resolves equal barycentres.
     */
    private static void reorderFeedbackByBarycenter(
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
        layer.sort(Comparator
                .comparingDouble((Node node) -> barycenter(
                        usePrevious ? node.previous : node.next,
                        positions, currentPositions.get(node)))
                .thenComparing(node -> node.key));
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
            boolean compactFeedback) {
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
            if (length < bestLength) {
                bestLength = length;
                bestPositions = captureVerticalPositions(columns);
            }
        }

        if (bestPositions == null) {
            throw new IllegalStateException("no vertical layout candidate was produced");
        }
        restoreVerticalPositions(columns, bestPositions);
        if (feedbackPacking && compactFeedback) {
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
                    pinTotal += sourcePinY(step.source());
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
                            step.signal(), sourcePinY(step.source())));
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
                movingRows.add(new PinRow(step.signal(), sourcePinY(step.source())));
            } else {
                fixedRows.add(new PinRow(step.signal(), sourcePinY(step.source())));
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
            int previousPins = previous.isDummy() ? 1 : previous.definition.inputs().size() + 1;
            int currentPins = current.isDummy() ? 1 : current.definition.inputs().size() + 1;
            requiredGap = 10 + 10 * Math.max(previousPins, currentPins);
        }
        int required = nodeBottomOffset(previous) - nodeTopOffset(current) + requiredGap;
        return roundUpToGrid(required);
    }

    private static int nodeTopOffset(Node node) {
        return node.isDummy()
                ? -DUMMY_SLOT_HEIGHT / 2
                : GateGeometry.bounds(node.definition, Facing.EAST).y();
    }

    private static int nodeBottomOffset(Node node) {
        if (node.isDummy()) {
            return DUMMY_SLOT_HEIGHT / 2;
        }
        GateGeometry.Bounds bounds = GateGeometry.bounds(node.definition, Facing.EAST);
        return bounds.y() + bounds.height();
    }

    private static int sourcePinY(Node source) {
        if (source.isDummy()) {
            return source.y;
        }
        GateGeometry.Offset offset = GateGeometry.outputOffset(source.definition, Facing.EAST);
        return source.y + (offset == null ? 0 : offset.y());
    }

    private static int targetPinY(RouteStep step) {
        Node target = step.target();
        if (target.isDummy()) {
            return target.y;
        }
        int inputIndex = step.targetInputIndex();
        GateGeometry.Offset offset = GateGeometry.pinOffset(
                target.definition, target.definition.inputs().get(inputIndex), Facing.EAST);
        return target.y + offset.y();
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
            int sourceY = sourcePinY(step.source());
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
                GateGeometry.Bounds bounds = GateGeometry.bounds(
                        node.definition, Facing.EAST);
                minX = Math.min(minX, node.x + bounds.x());
                minY = Math.min(minY, node.y + bounds.y());
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
                    int sourceY = step.source().y;
                    int[] target = targetPoint(step, columnLeft);
                    minY = Math.min(minY, Math.min(sourceY, target[1]));
                    maxY = Math.max(maxY, Math.max(sourceY, target[1]));
                }

                List<Node> orderedSources = sources.stream()
                        .sorted(Comparator.comparing(node -> node.key)).toList();
                for (Node source : orderedSources) {
                    int sourceX = source.isDummy()
                            ? columnLeft[channel] : source.x;
                    addWire(wires, sourceX, source.y, trunkX, source.y);
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
        GateDef definition = target.definition;
        int inputIndex = step.targetInputIndex();
        if (inputIndex < 0 || inputIndex >= definition.inputs().size()) {
            throw new IllegalStateException(
                    "invalid input index " + inputIndex + " for " + target.componentId);
        }
        GateGeometry.Offset offset = GateGeometry.pinOffset(
                definition, definition.inputs().get(inputIndex), Facing.EAST);
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
    private record FeedbackRouteSpan(int startColumn, int endColumn, int y) {
        private boolean overlaps(int start, int end) {
            return start <= endColumn && startColumn <= end;
        }
    }

    private static LinkedHashSet<WireSegment> routeFeedbackEdges(
            FeedbackCircuitExtractor.Extraction extraction,
            Map<String, Node> gateNodes,
            Map<Integer, List<FeedbackCircuitExtractor.FeedbackEdge>> sourcesByChannel,
            Map<Integer, List<FeedbackCircuitExtractor.FeedbackEdge>> targetsByChannel,
            Map<Integer, Integer> normalGapByChannel,
            int[] columnLeft,
            int[] columnWidths,
            int trackPitch,
            List<Node> allNodes) {
        LinkedHashSet<WireSegment> wires = new LinkedHashSet<>();
        Map<String, Integer> columnsByGate = new LinkedHashMap<>();
        gateNodes.forEach((signal, node) -> columnsByGate.put(signal, node.column));
        Map<FeedbackCircuitExtractor.FeedbackEdge, Integer> laneByEdge =
                assignFeedbackLanes(extraction.feedbackEdges(), columnsByGate);
        List<FeedbackRouteSpan> assignedRouteSpans = new ArrayList<>();

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
            int sourceX = columnLeft[source.column] + columnWidths[source.column]
                    + normalSourceGap + (sourceTrackIndex + 1) * trackPitch;
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
            int laneY = chooseFeedbackLaneY(
                    startColumn, endColumn, sourceY, targetY,
                    aboveY, belowY, trackPitch, assignedRouteSpans);
            assignedRouteSpans.add(new FeedbackRouteSpan(startColumn, endColumn, laneY));

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

    private static int chooseFeedbackLaneY(
            int startColumn, int endColumn, int sourceY, int targetY,
            int aboveY, int belowY, int trackPitch,
            List<FeedbackRouteSpan> assigned) {
        int bestY = 0;
        long bestLength = Long.MAX_VALUE;
        boolean found = false;
        for (int offset = 0; offset <= assigned.size(); offset++) {
            int above = aboveY - offset * trackPitch;
            if (hasFreeFeedbackLane(startColumn, endColumn, above, assigned)) {
                long length = (long) Math.abs(sourceY - above) + Math.abs(targetY - above);
                if (length < bestLength) {
                    bestLength = length;
                    bestY = above;
                    found = true;
                }
            }
            int below = belowY + offset * trackPitch;
            if (hasFreeFeedbackLane(startColumn, endColumn, below, assigned)) {
                long length = (long) Math.abs(sourceY - below) + Math.abs(targetY - below);
                if (length < bestLength) {
                    bestLength = length;
                    bestY = below;
                    found = true;
                }
            }
        }
        if (!found) {
            throw new IllegalStateException("could not allocate a non-overlapping feedback lane");
        }
        return bestY;
    }

    private static boolean hasFreeFeedbackLane(
            int startColumn, int endColumn, int y, List<FeedbackRouteSpan> assigned) {
        for (FeedbackRouteSpan lane : assigned) {
            if (lane.y() == y && lane.overlaps(startColumn, endColumn)) {
                return false;
            }
        }
        return true;
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
        List<String> joinedNetDetails = new ArrayList<>();
        List<String> splitSignalDetails = new ArrayList<>();

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
                    splitSignalDetails.add(signal + " occurs in net " + previousNet
                            + " and net " + netIndex);
                }
            }
            if (expectedSignals.size() > 1) {
                involved.addAll(expectedSignals);
                String pins = net.pins().stream()
                        .map(pin -> pin.componentId() + "." + pin.pinId())
                        .sorted().collect(java.util.stream.Collectors.joining(","));
                joinedNetDetails.add("net " + netIndex + " signals=" + expectedSignals
                        + " pins=[" + pins + "]");
            }
        }

        if (!involved.isEmpty()) {
            throw new LayoutCheckException(
                    "different netlist signals were joined or one signal was split: "
                            + String.join(", ", involved)
                            + "; joined=" + joinedNetDetails
                            + "; split=" + splitSignalDetails,
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
