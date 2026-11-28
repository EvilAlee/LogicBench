package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.WireSegment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Deterministic orthogonal A* router for schematic pin and net attachments.
 *
 * <p>The caller supplies the connected source/target net geometry. Those nets
 * are possible attachment locations, not hard obstacles. Unrelated nets remain
 * obstacles; a perpendicular crossing may pass through without joining.</p>
 */
public final class AutoWireRouter {
    private static final int GRID = PlacedComponent.GRID_SPACING;
    private static final int CLEARANCE_CELLS = 1;
    private static final int INITIAL_MARGIN_CELLS = 20;
    private static final int MAX_MARGIN_CELLS = 40;
    private static final int BEND_COST = 4;
    private static final int CROSSING_COST = 6;
    private static final int NEAR_BODY_COST = 1;

    private static final StepDirection[] NEIGHBOURS = {
            StepDirection.NORTH, StepDirection.EAST,
            StepDirection.SOUTH, StepDirection.WEST
    };

    public record Endpoint(Connectivity.Point position, Facing facing, boolean output) {
        public Endpoint {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(facing, "facing");
            if (position.x() % GRID != 0 || position.y() % GRID != 0) {
                throw new IllegalArgumentException("endpoint must lie on the schematic grid");
            }
        }
    }

    /** A clicked pin may attach anywhere on its existing net; a clicked wire fixes the point. */
    public record Attachment(
            Connectivity.Point position, Endpoint pinEndpoint, boolean fixedWirePoint) {
        public Attachment {
            Objects.requireNonNull(position, "position");
            if (position.x() % GRID != 0 || position.y() % GRID != 0) {
                throw new IllegalArgumentException("attachment must lie on the schematic grid");
            }
            if (pinEndpoint == null && !fixedWirePoint) {
                throw new IllegalArgumentException("a non-pin attachment must be a fixed wire point");
            }
            if (pinEndpoint != null && fixedWirePoint) {
                throw new IllegalArgumentException("a pin attachment cannot be a fixed wire point");
            }
            if (pinEndpoint != null && !pinEndpoint.position().equals(position)) {
                throw new IllegalArgumentException("pin and attachment positions must match");
            }
        }

        public static Attachment pin(Endpoint endpoint) {
            Objects.requireNonNull(endpoint, "endpoint");
            return new Attachment(endpoint.position(), endpoint, false);
        }

        public static Attachment wire(Connectivity.Point point) {
            return new Attachment(point, null, true);
        }
    }

    public record Request(
            Attachment source,
            Attachment target,
            List<GateGeometry.Bounds> componentBounds,
            List<Connectivity.Point> otherPins,
            List<WireSegment> existingWires,
            Set<Connectivity.Point> junctions,
            List<WireSegment> sourceNetWires,
            List<WireSegment> targetNetWires) {
        public Request {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            componentBounds = List.copyOf(
                    Objects.requireNonNull(componentBounds, "componentBounds"));
            otherPins = List.copyOf(Objects.requireNonNull(otherPins, "otherPins"));
            existingWires = List.copyOf(Objects.requireNonNull(existingWires, "existingWires"));
            junctions = Set.copyOf(Objects.requireNonNull(junctions, "junctions"));
            sourceNetWires = sortedWires(sourceNetWires);
            targetNetWires = sortedWires(targetNetWires);
        }

        /** Source-compatible constructor for ordinary pin-to-pin routing and existing tests. */
        public Request(
                Endpoint source,
                Endpoint target,
                List<GateGeometry.Bounds> componentBounds,
                List<Connectivity.Point> otherPins,
                List<WireSegment> existingWires,
                Set<Connectivity.Point> junctions) {
            this(Attachment.pin(source), Attachment.pin(target), componentBounds,
                    otherPins, existingWires, junctions, List.of(), List.of());
        }
    }

    public record Route(List<WireSegment> segments, boolean usedFallback) {
        public Route {
            segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        }
    }

    private enum StepDirection {
        NONE(0, 0),
        NORTH(0, -1),
        EAST(1, 0),
        SOUTH(0, 1),
        WEST(-1, 0);

        private final int dx;
        private final int dy;

        StepDirection(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }

        private static StepDirection of(Facing facing) {
            return switch (facing) {
                case EAST -> EAST;
                case SOUTH -> SOUTH;
                case WEST -> WEST;
                case NORTH -> NORTH;
            };
        }

        private boolean horizontal() {
            return dx != 0;
        }
    }

    private record Cell(int x, int y) {
        private Connectivity.Point worldPoint() {
            return new Connectivity.Point(x * GRID, y * GRID);
        }
    }

    /** sourceIndex keeps distinct starts independent and gives the pin first tie priority. */
    private record State(int x, int y, StepDirection incoming, int sourceIndex) {
        private Cell cell() {
            return new Cell(x, y);
        }
    }

    private record SearchNode(State state, int cost, int estimate, long order) {
    }

    private record Anchor(Cell cell, Endpoint pin, List<StepDirection> openDirections) {
    }

    private record SearchResult(List<Cell> path) {
    }

    private record Attempt(boolean relaxSourceFacing, boolean relaxTargetFacing) {
    }

    private AutoWireRouter() {
    }

    /**
     * Finds the cheapest route between all legal source/target attachment points.
     * Pin candidates are inserted first, so equal-cost paths prefer leaving the pin.
     */
    public static Route route(Request request) {
        Objects.requireNonNull(request, "request");
        if (request.source().position().equals(request.target().position())) {
            return new Route(List.of(), false);
        }

        List<Anchor> starts = anchors(
                request.source(), request.sourceNetWires(), true);
        List<Anchor> targets = anchors(
                request.target(), request.targetNetWires(), false);
        for (Attempt attempt : attempts()) {
            for (int margin : new int[]{INITIAL_MARGIN_CELLS, MAX_MARGIN_CELLS}) {
                List<Cell> path = search(request, starts, targets, margin, attempt);
                if (path != null && path.size() >= 2) {
                    return new Route(toSegments(path), false);
                }
            }
        }
        return new Route(directFallback(
                request.source().position(), request.target().position()), true);
    }

    /** The deliberately simple two-bend path used only when A* cannot find a clean route. */
    public static List<WireSegment> directFallback(
            Connectivity.Point source, Connectivity.Point target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (source.equals(target)) {
            return List.of();
        }
        List<Connectivity.Point> points = new ArrayList<>(4);
        points.add(source);
        int dx = target.x() - source.x();
        int dy = target.y() - source.y();
        if (dy == 0) {
            points.add(target);
        } else if (dx == 0) {
            points.add(target);
        } else if (Math.abs(dx) >= 2 * GRID) {
            int middleX = snapToGrid(source.x() + dx / 2);
            points.add(new Connectivity.Point(middleX, source.y()));
            points.add(new Connectivity.Point(middleX, target.y()));
            points.add(target);
        } else {
            int middleY = snapToGrid(source.y() + dy / 2);
            points.add(new Connectivity.Point(source.x(), middleY));
            points.add(new Connectivity.Point(target.x(), middleY));
            points.add(target);
        }
        return pointsToSegments(points);
    }

    private static List<Attempt> attempts() {
        return List.of(
                new Attempt(false, false),
                new Attempt(false, true),
                new Attempt(true, false),
                new Attempt(true, true));
    }

    private static List<Anchor> anchors(
            Attachment attachment, List<WireSegment> netWires, boolean source) {
        LinkedHashMap<Cell, Anchor> result = new LinkedHashMap<>();
        Endpoint pin = attachment.pinEndpoint();
        Cell direct = cell(attachment.position());
        result.put(direct, new Anchor(direct, pin,
                openDirections(attachment.position(), netWires, source)));
        if (attachment.fixedWirePoint()) {
            return List.copyOf(result.values());
        }

        for (WireSegment wire : netWires) {
            if (wire.isHorizontal()) {
                for (int x = wire.x1(); x <= wire.x2(); x += GRID) {
                    addAnchor(result, new Connectivity.Point(x, wire.y1()), netWires, source);
                }
            } else {
                for (int y = wire.y1(); y <= wire.y2(); y += GRID) {
                    addAnchor(result, new Connectivity.Point(wire.x1(), y), netWires, source);
                }
            }
        }
        return List.copyOf(result.values());
    }

    private static void addAnchor(
            Map<Cell, Anchor> anchors,
            Connectivity.Point point,
            List<WireSegment> netWires,
            boolean source) {
        Cell cell = cell(point);
        anchors.putIfAbsent(cell, new Anchor(
                cell, null, openDirections(point, netWires, source)));
    }

    /**
     * Directions that do not continue along an existing segment of this net.
     * For a source, directions describe the next cell; for a target, the previous cell.
     */
    private static List<StepDirection> openDirections(
            Connectivity.Point point, List<WireSegment> netWires, boolean source) {
        List<StepDirection> result = new ArrayList<>(4);
        for (StepDirection direction : NEIGHBOURS) {
            int sign = source ? 1 : -1;
            Connectivity.Point neighbour = new Connectivity.Point(
                    point.x() + sign * direction.dx * GRID,
                    point.y() + sign * direction.dy * GRID);
            if (!liesOnAnyWire(netWires, neighbour)) {
                result.add(direction);
            }
        }
        return List.copyOf(result);
    }

    private static List<Cell> search(
            Request request,
            List<Anchor> starts,
            List<Anchor> targets,
            int marginCells,
            Attempt attempt) {
        if (starts.isEmpty() || targets.isEmpty()) {
            return null;
        }

        Set<Cell> targetCells = new HashSet<>();
        Map<Cell, List<Anchor>> targetsAt = new HashMap<>();
        for (Anchor target : targets) {
            targetCells.add(target.cell());
            targetsAt.computeIfAbsent(target.cell(), ignored -> new ArrayList<>()).add(target);
        }

        Set<WireSegment> ownNetWires = new LinkedHashSet<>(request.sourceNetWires());
        ownNetWires.addAll(request.targetNetWires());
        List<WireSegment> unrelatedWires = request.existingWires().stream()
                .filter(wire -> !ownNetWires.contains(wire))
                .toList();

        Set<Cell> wireEndpoints = new HashSet<>();
        for (WireSegment wire : unrelatedWires) {
            wireEndpoints.add(cell(new Connectivity.Point(wire.x1(), wire.y1())));
            wireEndpoints.add(cell(new Connectivity.Point(wire.x2(), wire.y2())));
        }
        Set<Cell> unrelatedJunctions = new HashSet<>();
        for (Connectivity.Point junction : request.junctions()) {
            if (!liesOnAny(ownNetWires, junction)) {
                unrelatedJunctions.add(cell(junction));
            }
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Anchor anchor : starts) {
            minX = Math.min(minX, anchor.cell().x());
            minY = Math.min(minY, anchor.cell().y());
            maxX = Math.max(maxX, anchor.cell().x());
            maxY = Math.max(maxY, anchor.cell().y());
        }
        for (Anchor anchor : targets) {
            minX = Math.min(minX, anchor.cell().x());
            minY = Math.min(minY, anchor.cell().y());
            maxX = Math.max(maxX, anchor.cell().x());
            maxY = Math.max(maxY, anchor.cell().y());
        }
        minX -= marginCells;
        minY -= marginCells;
        maxX += marginCells;
        maxY += marginCells;

        Comparator<SearchNode> order = Comparator
                .comparingInt(SearchNode::estimate)
                .thenComparingInt(SearchNode::cost)
                .thenComparingInt(node -> node.state().sourceIndex())
                .thenComparingLong(SearchNode::order);
        PriorityQueue<SearchNode> open = new PriorityQueue<>(order);
        Map<State, Integer> bestCost = new HashMap<>();
        Map<State, State> previous = new HashMap<>();
        long sequence = 0;

        for (int index = 0; index < starts.size(); index++) {
            Anchor start = starts.get(index);
            if (allowedStartDirections(start, attempt).isEmpty()) {
                continue;
            }
            State initial = new State(
                    start.cell().x(), start.cell().y(), StepDirection.NONE, index);
            open.add(new SearchNode(initial, 0, heuristic(start.cell(), targets), sequence++));
            bestCost.put(initial, 0);
        }

        while (!open.isEmpty()) {
            SearchNode node = open.remove();
            State current = node.state();
            if (node.cost() != bestCost.getOrDefault(current, Integer.MAX_VALUE)) {
                continue;
            }

            List<Anchor> matchingTargets = targetsAt.get(current.cell());
            if (matchingTargets != null
                    && allowedTargetDirections(matchingTargets, attempt)
                            .contains(current.incoming())) {
                return reconstruct(current, previous);
            }

            Cell from = current.cell();
            Anchor root = starts.get(current.sourceIndex());
            for (StepDirection direction : NEIGHBOURS) {
                if (current.incoming() == StepDirection.NONE) {
                    if (!allowedStartDirections(root, attempt).contains(direction)) {
                        continue;
                    }
                }

                Cell next = new Cell(from.x() + direction.dx, from.y() + direction.dy);
                if (next.x() < minX || next.x() > maxX
                        || next.y() < minY || next.y() > maxY) {
                    continue;
                }
                if (!targetCells.contains(next)
                        && isBlocked(request, next, wireEndpoints, unrelatedJunctions)) {
                    continue;
                }
                if (!targetCells.contains(next)
                        && request.otherPins().contains(next.worldPoint())) {
                    continue;
                }

                int currentWireOrientation = wireOrientationAt(
                        request.existingWires(), from);
                if (current.incoming() != StepDirection.NONE
                        && currentWireOrientation != 0
                        && (current.incoming() != direction
                                || !perpendicularToWire(direction, currentWireOrientation))) {
                    continue;
                }

                int unrelatedOrientation = wireOrientationAt(unrelatedWires, next);
                int ownOrientation = wireOrientationAt(
                        new ArrayList<>(ownNetWires), next);
                if (unrelatedOrientation != 0) {
                    // Even a clicked attachment may not end on or merge an unrelated net.
                    if (targetCells.contains(next)
                            || !perpendicularToWire(direction, unrelatedOrientation)) {
                        continue;
                    }
                }
                if (ownOrientation != 0 && !targetCells.contains(next)
                        && !perpendicularToWire(direction, ownOrientation)) {
                    continue;
                }

                int stepCost = 1;
                if (current.incoming() != StepDirection.NONE
                        && current.incoming() != direction) {
                    stepCost += BEND_COST;
                }
                if (unrelatedOrientation != 0
                        && wireOrientationAt(unrelatedWires, from) == 0) {
                    stepCost += CROSSING_COST;
                }
                if (!targetCells.contains(next)) {
                    stepCost += proximityPenalty(request.componentBounds(), next);
                }

                int candidateCost = node.cost() + stepCost;
                State state = new State(
                        next.x(), next.y(), direction, current.sourceIndex());
                if (candidateCost >= bestCost.getOrDefault(state, Integer.MAX_VALUE)) {
                    continue;
                }
                bestCost.put(state, candidateCost);
                previous.put(state, current);
                open.add(new SearchNode(
                        state,
                        candidateCost,
                        candidateCost + heuristic(next, targets),
                        sequence++));
            }
        }
        return null;
    }

    private static List<StepDirection> allowedStartDirections(
            Anchor anchor, Attempt attempt) {
        if (anchor.pin() == null || attempt.relaxSourceFacing()) {
            return anchor.openDirections();
        }
        StepDirection preferred = departure(anchor.pin());
        return anchor.openDirections().contains(preferred) ? List.of(preferred) : List.of();
    }

    private static List<StepDirection> allowedTargetDirections(
            List<Anchor> anchors, Attempt attempt) {
        List<StepDirection> result = new ArrayList<>();
        for (Anchor anchor : anchors) {
            List<StepDirection> allowed;
            if (anchor.pin() == null || attempt.relaxTargetFacing()) {
                allowed = anchor.openDirections();
            } else {
                StepDirection preferred = arrival(anchor.pin());
                allowed = anchor.openDirections().contains(preferred)
                        ? List.of(preferred) : List.of();
            }
            result.addAll(allowed);
        }
        return result;
    }

    private static StepDirection departure(Endpoint endpoint) {
        return StepDirection.of(endpoint.facing());
    }

    private static StepDirection arrival(Endpoint endpoint) {
        return StepDirection.of(endpoint.facing());
    }

    private static boolean isBlocked(
            Request request,
            Cell cell,
            Set<Cell> wireEndpoints,
            Set<Cell> junctions) {
        if (wireEndpoints.contains(cell) || junctions.contains(cell)) {
            return true;
        }
        for (GateGeometry.Bounds bounds : request.componentBounds()) {
            int left = Math.floorDiv(bounds.x(), GRID);
            int top = Math.floorDiv(bounds.y(), GRID);
            int right = Math.floorDiv(bounds.x() + bounds.width(), GRID);
            int bottom = Math.floorDiv(bounds.y() + bounds.height(), GRID);
            boolean insideBody = cell.x() >= left && cell.x() <= right
                    && cell.y() >= top && cell.y() <= bottom;
            if (insideBody) {
                return true;
            }
            boolean insideClearance = cell.x() >= left - CLEARANCE_CELLS
                    && cell.x() <= right + CLEARANCE_CELLS
                    && cell.y() >= top - CLEARANCE_CELLS
                    && cell.y() <= bottom + CLEARANCE_CELLS;
            if (insideClearance && !inEndpointTunnel(request, cell)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inEndpointTunnel(Request request, Cell cell) {
        Endpoint source = request.source().pinEndpoint();
        if (source != null) {
            Cell sourceCell = cell(source.position());
            StepDirection direction = departure(source);
            for (int step = 0; step <= CLEARANCE_CELLS + 1; step++) {
                if (cell.equals(new Cell(
                        sourceCell.x() + direction.dx * step,
                        sourceCell.y() + direction.dy * step))) {
                    return true;
                }
            }
        }

        Endpoint target = request.target().pinEndpoint();
        if (target != null) {
            Cell targetCell = cell(target.position());
            StepDirection direction = arrival(target);
            for (int step = 0; step <= CLEARANCE_CELLS + 1; step++) {
                if (cell.equals(new Cell(
                        targetCell.x() - direction.dx * step,
                        targetCell.y() - direction.dy * step))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int wireOrientationAt(List<WireSegment> wires, Cell point) {
        int orientation = 0;
        int worldX = point.x() * GRID;
        int worldY = point.y() * GRID;
        for (WireSegment wire : wires) {
            boolean contains = wire.isHorizontal()
                    ? worldY == wire.y1() && worldX >= wire.x1() && worldX <= wire.x2()
                    : worldX == wire.x1() && worldY >= wire.y1() && worldY <= wire.y2();
            if (contains) {
                orientation |= wire.isHorizontal() ? 1 : 2;
                if (orientation == 3) {
                    return orientation;
                }
            }
        }
        return orientation;
    }

    private static boolean perpendicularToWire(StepDirection direction, int orientation) {
        if (orientation == 1) {
            return !direction.horizontal();
        }
        if (orientation == 2) {
            return direction.horizontal();
        }
        return false;
    }

    private static boolean liesOnAny(
            List<WireSegment> wires, Connectivity.Point point) {
        for (WireSegment wire : wires) {
            if (wire.isHorizontal()) {
                if (point.y() == wire.y1()
                        && point.x() >= wire.x1() && point.x() <= wire.x2()) {
                    return true;
                }
            } else if (point.x() == wire.x1()
                    && point.y() >= wire.y1() && point.y() <= wire.y2()) {
                return true;
            }
        }
        return false;
    }

    private static boolean liesOnAny(
            Set<WireSegment> wires, Connectivity.Point point) {
        return liesOnAny(new ArrayList<>(wires), point);
    }

    private static int proximityPenalty(List<GateGeometry.Bounds> bounds, Cell cell) {
        int penalty = 0;
        for (GateGeometry.Bounds rectangle : bounds) {
            int left = Math.floorDiv(rectangle.x(), GRID);
            int top = Math.floorDiv(rectangle.y(), GRID);
            int right = Math.floorDiv(rectangle.x() + rectangle.width(), GRID);
            int bottom = Math.floorDiv(rectangle.y() + rectangle.height(), GRID);
            int dx = Math.max(Math.max(left - cell.x(), 0), cell.x() - right);
            int dy = Math.max(Math.max(top - cell.y(), 0), cell.y() - bottom);
            int chebyshev = Math.max(dx, dy);
            if (chebyshev > CLEARANCE_CELLS && chebyshev <= CLEARANCE_CELLS + 1) {
                penalty += NEAR_BODY_COST;
            }
        }
        return penalty;
    }

    private static List<Cell> reconstruct(State end, Map<State, State> previous) {
        List<Cell> reversed = new ArrayList<>();
        State current = end;
        reversed.add(current.cell());
        while (previous.containsKey(current)) {
            current = previous.get(current);
            reversed.add(current.cell());
        }
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private static List<WireSegment> toSegments(List<Cell> path) {
        List<Connectivity.Point> points = new ArrayList<>();
        points.add(path.getFirst().worldPoint());
        StepDirection previousDirection = null;
        for (int index = 1; index < path.size(); index++) {
            Cell previous = path.get(index - 1);
            Cell current = path.get(index);
            StepDirection direction = current.x() > previous.x() ? StepDirection.EAST
                    : current.x() < previous.x() ? StepDirection.WEST
                    : current.y() > previous.y() ? StepDirection.SOUTH : StepDirection.NORTH;
            if (previousDirection != null && direction != previousDirection) {
                points.add(previous.worldPoint());
            }
            previousDirection = direction;
        }
        points.add(path.getLast().worldPoint());
        return pointsToSegments(points);
    }

    private static List<WireSegment> pointsToSegments(List<Connectivity.Point> points) {
        List<WireSegment> segments = new ArrayList<>();
        for (int index = 1; index < points.size(); index++) {
            Connectivity.Point first = points.get(index - 1);
            Connectivity.Point second = points.get(index);
            if (!first.equals(second)) {
                segments.add(new WireSegment(
                        first.x(), first.y(), second.x(), second.y()));
            }
        }
        return List.copyOf(segments);
    }

    private static int heuristic(Cell first, List<Anchor> targets) {
        int closest = Integer.MAX_VALUE;
        for (Anchor target : targets) {
            closest = Math.min(closest, heuristic(first, target.cell()));
        }
        return closest == Integer.MAX_VALUE ? 0 : closest;
    }

    private static int heuristic(Cell first, Cell second) {
        return Math.abs(first.x() - second.x()) + Math.abs(first.y() - second.y());
    }

    private static Cell cell(Connectivity.Point point) {
        return new Cell(point.x() / GRID, point.y() / GRID);
    }

    private static int snapToGrid(int value) {
        return (int) (Math.floor((double) value / GRID + 0.5) * GRID);
    }

    private static List<WireSegment> sortedWires(List<WireSegment> wires) {
        Objects.requireNonNull(wires, "wires");
        return wires.stream().sorted(Comparator
                .comparingInt(WireSegment::y1)
                .thenComparingInt(WireSegment::x1)
                .thenComparingInt(WireSegment::y2)
                .thenComparingInt(WireSegment::x2)).toList();
    }
}
