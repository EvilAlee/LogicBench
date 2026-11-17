package io.github.evilalee.gatelab.synth.layout;

import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.WireSegment;
import io.github.evilalee.gatelab.synth.Netlist;

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
 * the number of distinct nets crossing it; each gets a private vertical trunk.
 * Vertically, each layer occupies its own band. This deliberate separation
 * keeps a horizontal branch in one layer from terminating on another net's
 * trunk endpoint in the adjacent layer. Real component height comes from
 * GateDef/GateGeometry; dummy nodes reserve a 20-unit slot. The standard
 * gaps are 40 units between rows, 60 units between layer bands, and 20 units
 * at each side of every trunk lane. All spacing dimensions are doubled on the single
 * retry after a failed connectivity self-check.
 */
public final class SchematicLayout {
    private static final int ROW_GAP = 40;
    private static final int LAYER_BAND_GAP = 60;
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
            int dummyNodeCount) {
        public LayoutResult {
            Objects.requireNonNull(schematic, "schematic");
            inputComponentIds = immutableOrderedMap(inputComponentIds);
            outputComponentIds = immutableOrderedMap(outputComponentIds);
            constantComponentIds = immutableOrderedMap(constantComponentIds);
            if (dummyNodeCount < 0) {
                throw new IllegalArgumentException("dummyNodeCount must not be negative");
            }
        }

        private static Map<String, String> immutableOrderedMap(Map<String, String> values) {
            return java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(values, "values")));
        }
    }

    private enum NodeKind {
        INPUT,
        CONSTANT,
        GATE,
        OUTPUT,
        DUMMY
    }

    private static final class Node {
        private final String key;
        private final NodeKind kind;
        private final int column;
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
            return build(netlist, 1);
        } catch (LayoutCheckException firstFailure) {
            try {
                return build(netlist, 2);
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

    private LayoutResult build(Netlist netlist, int spacingFactor) {
        Map<String, Integer> depthBySignal = new LinkedHashMap<>();
        netlist.inputs().forEach(name -> depthBySignal.put(name, 0));

        LinkedHashSet<String> constantsUsed = new LinkedHashSet<>();
        for (Netlist.Output output : netlist.outputs()) {
            if (output.source().equals("0") || output.source().equals("1")) {
                constantsUsed.add(output.source());
                depthBySignal.putIfAbsent(output.source(), 0);
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
        Map<String, String> outputIds = new LinkedHashMap<>();
        Map<String, String> constantIds = new LinkedHashMap<>();
        Map<String, Node> signalNodes = new LinkedHashMap<>();
        Map<String, Node> gateNodes = new LinkedHashMap<>();
        Map<String, Node> outputNodes = new LinkedHashMap<>();

        for (String input : netlist.inputs()) {
            String id = "expr-input-" + input;
            Node node = componentNode(
                    "input:" + input, NodeKind.INPUT, 0, input, id, "input");
            columns.get(0).add(node);
            signalNodes.put(input, node);
            inputIds.put(input, id);
        }

        for (String constant : constantsUsed) {
            String id = "expr-constant-" + constant;
            Node node = componentNode(
                    "constant:" + constant, NodeKind.CONSTANT, 0,
                    constant, id, "input");
            columns.get(0).add(node);
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

        orderColumns(columns);
        int rowGap = ROW_GAP * spacingFactor;
        int bandGap = LAYER_BAND_GAP * spacingFactor;
        assignVerticalBands(columns, rowGap, bandGap);

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
        for (int channel = 0; channel < columns.size() - 1; channel++) {
            int count = signalsByChannel.getOrDefault(channel, Set.of()).size();
            int channelGap = laneStart + count * lanePitch + laneEnd;
            columnLeft[channel + 1] = columnLeft[channel]
                    + columnWidths[channel] + channelGap;
        }

        for (Node node : allNodes(columns)) {
            if (node.isDummy()) {
                node.x = columnLeft[node.column];
            } else {
                node.x = columnLeft[node.column] + node.definition.origin().x();
                PlacedComponent placed = new PlacedComponent(
                        node.componentId,
                        node.definition.family(),
                        node.x,
                        node.y,
                        Facing.EAST,
                        Map.of(),
                        node.kind == NodeKind.INPUT || node.kind == NodeKind.CONSTANT
                                || node.kind == NodeKind.OUTPUT
                                ? node.kind == NodeKind.CONSTANT ? node.signal
                                        : node.kind == NodeKind.INPUT ? node.signal
                                        : outputLabelFor(node, netlist)
                                : "");
                schematic.add(placed);
            }
        }

        for (String constant : constantsUsed) {
            if (constant.equals("1")) {
                schematic.setInputValue(constantIds.get(constant), true);
            }
        }

        LinkedHashSet<WireSegment> wires = route(
                routeSteps, columnLeft, columnWidths, signalsByChannel,
                laneStart, lanePitch);
        for (WireSegment wire : wires) {
            schematic.addWire(wire);
        }

        checkNoComponentOverlaps(schematic);
        checkConnectivity(netlist, schematic, inputIds, constantIds,
                gateNodes, outputIds);

        return new LayoutResult(
                schematic, inputIds, outputIds, constantIds, dummyCount);
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

    private static void assignVerticalBands(
            List<List<Node>> columns, int rowGap, int bandGap) {
        int nextBandTop = 0;
        for (List<Node> column : columns) {
            int columnHeight = 0;
            for (Node node : column) {
                columnHeight += Math.max(node.slotHeight, DUMMY_SLOT_HEIGHT);
            }
            if (!column.isEmpty()) {
                columnHeight += rowGap * (column.size() - 1);
            }

            int cursor = nextBandTop;
            for (Node node : column) {
                int slotHeight = Math.max(node.slotHeight, DUMMY_SLOT_HEIGHT);
                node.y = cursor + slotHeight / 2;
                cursor += slotHeight + rowGap;
            }
            nextBandTop += columnHeight + bandGap;
        }
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
            throw new LayoutCheckException(
                    "different netlist signals were joined or one signal was split: "
                            + String.join(", ", involved),
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
