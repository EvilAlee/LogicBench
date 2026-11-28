package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.WireSegment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Deterministic orthogonal A* router for schematic pin-to-pin links.
 *
 * <p>This class has no Swing dependency. The canvas supplies electrical pins,
 * component bounds, existing wires, and the existing connectivity junctions.</p>
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

    public record Request(
            Endpoint source,
            Endpoint target,
            List<GateGeometry.Bounds> componentBounds,
            List<Connectivity.Point> otherPins,
            List<WireSegment> existingWires,
            Set<Connectivity.Point> junctions) {
        public Request {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            componentBounds = List.copyOf(Objects.requireNonNull(componentBounds, "componentBounds"));
            otherPins = List.copyOf(Objects.requireNonNull(otherPins, "otherPins"));
            existingWires = List.copyOf(Objects.requireNonNull(existingWires, "existingWires"));
            junctions = Set.copyOf(Objects.requireNonNull(junctions, "junctions"));
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

        private StepDirection opposite() {
            return switch (this) {
                case NORTH -> SOUTH;
                case EAST -> WEST;
                case SOUTH -> NORTH;
                case WEST -> EAST;
                case NONE -> NONE;
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

    private record State(int x, int y, StepDirection incoming) {
        private Cell cell() {
            return new Cell(x, y);
        }
    }

    private record SearchNode(State state, int cost, int estimate, long order) {
    }

    private record Attempt(StepDirection first, StepDirection last) {
    }

    private AutoWireRouter() {
    }

    /** Finds a clean A* path, growing the search box once, then uses a two-bend fallback. */
    public static Route route(Request request) {
        Objects.requireNonNull(request, "request");
        Cell start = cell(request.source().position());
        Cell end = cell(request.target().position());
        if (start.equals(end)) {
            return new Route(List.of(), false);
        }

        for (Attempt attempt : attempts(request)) {
            for (int margin : new int[]{INITIAL_MARGIN_CELLS, MAX_MARGIN_CELLS}) {
                List<Cell> path = search(request, start, end, margin,
                        attempt.first(), attempt.last());
                if (path != null && path.size() >= 2) {
                    return new Route(toSegments(path), false);
                }
            }
        }
        return new Route(directFallback(request.source().position(), request.target().position()), true);
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

    private static List<Attempt> attempts(Request request) {
        StepDirection first = departure(request.source());
        StepDirection last = arrival(request.target());
        List<Attempt> attempts = new ArrayList<>(4);
        if (first != null && last != null) {
            attempts.add(new Attempt(first, last));
        }
        if (first != null) {
            attempts.add(new Attempt(first, null));
        }
        if (last != null) {
            attempts.add(new Attempt(null, last));
        }
        attempts.add(new Attempt(null, null));
        return List.copyOf(attempts);
    }

    private static StepDirection departure(Endpoint endpoint) {
        StepDirection facing = StepDirection.of(endpoint.facing());
        return endpoint.output() ? facing : facing.opposite();
    }

    private static StepDirection arrival(Endpoint endpoint) {
        return StepDirection.of(endpoint.facing());
    }

    private static List<Cell> search(
            Request request,
            Cell start,
            Cell end,
            int marginCells,
            StepDirection requiredFirst,
            StepDirection requiredLast) {
        int minX = Math.min(start.x(), end.x()) - marginCells;
        int maxX = Math.max(start.x(), end.x()) + marginCells;
        int minY = Math.min(start.y(), end.y()) - marginCells;
        int maxY = Math.max(start.y(), end.y()) + marginCells;

        Set<Cell> otherPins = new HashSet<>();
        for (Connectivity.Point pin : request.otherPins()) {
            otherPins.add(cell(pin));
        }
        Set<Cell> wireEndpoints = new HashSet<>();
        for (WireSegment wire : request.existingWires()) {
            wireEndpoints.add(cell(new Connectivity.Point(wire.x1(), wire.y1())));
            wireEndpoints.add(cell(new Connectivity.Point(wire.x2(), wire.y2())));
        }
        Set<Cell> junctions = new HashSet<>();
        for (Connectivity.Point junction : request.junctions()) {
            junctions.add(cell(junction));
        }

        Comparator<SearchNode> order = Comparator
                .comparingInt(SearchNode::estimate)
                .thenComparingInt(SearchNode::cost)
                .thenComparingLong(SearchNode::order);
        PriorityQueue<SearchNode> open = new PriorityQueue<>(order);
        Map<State, Integer> bestCost = new HashMap<>();
        Map<State, State> previous = new HashMap<>();
        State initial = new State(start.x(), start.y(), StepDirection.NONE);
        long sequence = 0;
        open.add(new SearchNode(initial, 0, heuristic(start, end), sequence++));
        bestCost.put(initial, 0);

        while (!open.isEmpty()) {
            SearchNode node = open.remove();
            State current = node.state();
            if (node.cost() != bestCost.getOrDefault(current, Integer.MAX_VALUE)) {
                continue;
            }
            if (current.x() == end.x() && current.y() == end.y()
                    && (requiredLast == null || current.incoming() == requiredLast)) {
                return reconstruct(current, previous);
            }

            Cell from = current.cell();
            for (StepDirection direction : NEIGHBOURS) {
                if (from.equals(start) && requiredFirst != null && direction != requiredFirst) {
                    continue;
                }
                Cell next = new Cell(from.x() + direction.dx, from.y() + direction.dy);
                if (next.x() < minX || next.x() > maxX
                        || next.y() < minY || next.y() > maxY) {
                    continue;
                }
                if (!next.equals(end) && isBlocked(
                        request, next, otherPins, start, end)) {
                    continue;
                }
                if (otherPins.contains(next) && !next.equals(start) && !next.equals(end)) {
                    continue;
                }

                int currentWireOrientation = wireOrientationAt(request, from);
                if (currentWireOrientation != 0 && !from.equals(start) && !from.equals(end)) {
                    if (current.incoming() != direction
                            || !perpendicularToWire(direction, currentWireOrientation)) {
                        continue;
                    }
                } else if (currentWireOrientation != 0 && from.equals(start)
                        && !perpendicularToWire(direction, currentWireOrientation)) {
                    continue;
                }

                int nextWireOrientation = wireOrientationAt(request, next);
                boolean nextIsEndpoint = wireEndpoints.contains(next) || junctions.contains(next);
                if (nextIsEndpoint || junctions.contains(next)) {
                    continue;
                }
                if (nextWireOrientation != 0) {
                    if (!perpendicularToWire(direction, nextWireOrientation)) {
                        continue;
                    }
                    if (!next.equals(end) && request.source().position().equals(next.worldPoint())) {
                        continue;
                    }
                }
                if (next.equals(end) && requiredLast != null && direction != requiredLast) {
                    continue;
                }

                int stepCost = 1;
                if (current.incoming() != StepDirection.NONE && current.incoming() != direction) {
                    stepCost += BEND_COST;
                }
                if (nextWireOrientation != 0 && !next.equals(end)) {
                    stepCost += CROSSING_COST;
                }
                if (!next.equals(end)) {
                    stepCost += proximityPenalty(request.componentBounds(), next);
                }
                int candidateCost = node.cost() + stepCost;
                State state = new State(next.x(), next.y(), direction);
                if (candidateCost >= bestCost.getOrDefault(state, Integer.MAX_VALUE)) {
                    continue;
                }
                bestCost.put(state, candidateCost);
                previous.put(state, current);
                open.add(new SearchNode(
                        state, candidateCost,
                        candidateCost + heuristic(next, end), sequence++));
            }
        }
        return null;
    }

    private static boolean isBlocked(
            Request request, Cell cell, Set<Cell> otherPins, Cell start, Cell end) {
        if (otherPins.contains(cell) && !cell.equals(start) && !cell.equals(end)) {
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
        Cell source = cell(request.source().position());
        StepDirection sourceDirection = departure(request.source());
        for (int step = 0; step <= CLEARANCE_CELLS + 1; step++) {
            if (cell.equals(new Cell(
                    source.x() + sourceDirection.dx * step,
                    source.y() + sourceDirection.dy * step))) {
                return true;
            }
        }

        Cell target = cell(request.target().position());
        StepDirection targetDirection = arrival(request.target());
        for (int step = 0; step <= CLEARANCE_CELLS + 1; step++) {
            if (cell.equals(new Cell(
                    target.x() - targetDirection.dx * step,
                    target.y() - targetDirection.dy * step))) {
                return true;
            }
        }
        return false;
    }

    private static int wireOrientationAt(Request request, Cell point) {
        int orientation = 0;
        int worldX = point.x() * GRID;
        int worldY = point.y() * GRID;
        for (WireSegment wire : request.existingWires()) {
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

    private static int heuristic(Cell first, Cell second) {
        return Math.abs(first.x() - second.x()) + Math.abs(first.y() - second.y());
    }

    private static Cell cell(Connectivity.Point point) {
        return new Cell(point.x() / GRID, point.y() / GRID);
    }

    private static int snapToGrid(int value) {
        return (int) (Math.floor((double) value / GRID + 0.5) * GRID);
    }
}
