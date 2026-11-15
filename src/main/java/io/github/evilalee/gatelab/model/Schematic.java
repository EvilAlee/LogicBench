package io.github.evilalee.gatelab.model;

import java.util.ArrayDeque;
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

/**
 * Editable placed-component layer. This is deliberately independent of the
 * engine Circuit: M6 will translate it into an engine graph.
 */
public final class Schematic {
    private final Map<String, GateDef> definitionsById;
    private final List<PlacedComponent> components = new ArrayList<>();
    private final List<WireSegment> wires = new ArrayList<>();
    private final Map<String, Boolean> inputValues = new HashMap<>();
    private final List<Runnable> changeListeners = new ArrayList<>();
    private long modificationCount;
    private long nextComponentNumber = 1;

    public Schematic(Collection<GateDef> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        Map<String, GateDef> indexed = new HashMap<>();
        for (GateDef definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            if (indexed.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalArgumentException("duplicate component type: " + definition.id());
            }
        }
        if (indexed.isEmpty()) {
            throw new IllegalArgumentException("at least one component definition is required");
        }
        definitionsById = Map.copyOf(indexed);
    }

    /**
     * Connects a moved pin to its original net. The source can be any point on
     * that net, not just the old pin coordinate: a crowded junction can have
     * all four adjacent grid points occupied by wires that are already safe.
     */
    private void addConnectingPath(PinMove pinMove) {
        Connectivity.PinPosition newPin = pinMove.newPin();
        Connectivity.Point destination = new Connectivity.Point(newPin.x(), newPin.y());
        if (hasAttachmentAt(destination, newPin)) {
            // The destination already joins existing geometry; another link could short nets.
            return;
        }
        List<Connectivity.Point> route = findSafeOrthogonalRoute(
                pinMove.attachmentPoints(),
                new Connectivity.Point(pinMove.oldPin().x(), pinMove.oldPin().y()),
                destination);
        for (WireSegment segment : compressRoute(route)) {
            addWireIfAbsent(segment.x1(), segment.y1(), segment.x2(), segment.y2());
        }
    }

    private boolean hasAttachmentAt(
            Connectivity.Point point, Connectivity.PinPosition movingPin) {
        if (wires.stream().anyMatch(wire -> contains(wire, point))) {
            return true;
        }
        return pinPositions().stream().anyMatch(pin ->
                pin.x() == point.x() && pin.y() == point.y()
                        && !(pin.componentId().equals(movingPin.componentId())
                        && pin.pinId().equals(movingPin.pinId())));
    }

    private List<Connectivity.Point> findSafeOrthogonalRoute(
            List<Connectivity.Point> sourcePoints,
            Connectivity.Point originalStart,
            Connectivity.Point destination) {
        Set<Connectivity.Point> sourceNetPoints = new HashSet<>(sourcePoints);
        Set<Connectivity.Point> blocked = new HashSet<>();
        for (WireSegment wire : wires) {
            addWireGridPoints(wire, blocked);
        }
        for (Connectivity.PinPosition pin : pinPositions()) {
            blocked.add(new Connectivity.Point(pin.x(), pin.y()));
        }

        // Existing source-net geometry is safe to branch from; all other nets stay blocked.
        blocked.removeAll(sourceNetPoints);
        blocked.remove(destination);

        long minX = Math.min(originalStart.x(), destination.x());
        long maxX = Math.max(originalStart.x(), destination.x());
        long minY = Math.min(originalStart.y(), destination.y());
        long maxY = Math.max(originalStart.y(), destination.y());
        for (Connectivity.Point point : sourceNetPoints) {
            minX = Math.min(minX, point.x());
            maxX = Math.max(maxX, point.x());
            minY = Math.min(minY, point.y());
            maxY = Math.max(maxY, point.y());
        }
        for (Connectivity.Point point : blocked) {
            minX = Math.min(minX, point.x());
            maxX = Math.max(maxX, point.x());
            minY = Math.min(minY, point.y());
            maxY = Math.max(maxY, point.y());
        }

        int spacing = PlacedComponent.GRID_SPACING;
        minX -= spacing;
        maxX += spacing;
        minY -= spacing;
        maxY += spacing;

        int[][] directions = preferredRouteDirections(originalStart, destination);
        ArrayDeque<Connectivity.Point> pending = new ArrayDeque<>();
        Map<Connectivity.Point, Connectivity.Point> previous = new HashMap<>();
        // Seed the search from the original net's nearest points. This lets a route
        // leave a crowded junction through another open branch without crossing a net.
        for (Connectivity.Point source : sourcePoints) {
            if (!blocked.contains(source) && !previous.containsKey(source)) {
                previous.put(source, null);
                pending.addLast(source);
            }
        }

        while (!pending.isEmpty() && !previous.containsKey(destination)) {
            Connectivity.Point current = pending.removeFirst();
            for (int[] direction : directions) {
                long nextX = (long) current.x() + (long) direction[0] * spacing;
                long nextY = (long) current.y() + (long) direction[1] * spacing;
                if (nextX < minX || nextX > maxX || nextY < minY || nextY > maxY
                        || nextX < Integer.MIN_VALUE || nextX > Integer.MAX_VALUE
                        || nextY < Integer.MIN_VALUE || nextY > Integer.MAX_VALUE) {
                    continue;
                }

                Connectivity.Point next = new Connectivity.Point((int) nextX, (int) nextY);
                if (blocked.contains(next) || previous.containsKey(next)) {
                    continue;
                }
                previous.put(next, current);
                pending.addLast(next);
            }
        }

        if (!previous.containsKey(destination)) {
            // Never create an accidental short just to force a visual connection.
            return List.of();
        }

        List<Connectivity.Point> reversed = new ArrayList<>();
        for (Connectivity.Point point = destination; point != null; point = previous.get(point)) {
            reversed.add(point);
        }
        List<Connectivity.Point> route = new ArrayList<>(reversed.size());
        for (int index = reversed.size() - 1; index >= 0; index--) {
            route.add(reversed.get(index));
        }
        return List.copyOf(route);
    }

    private static void addWireGridPoints(
            WireSegment wire, Set<Connectivity.Point> blocked) {
        int spacing = PlacedComponent.GRID_SPACING;
        if (wire.isHorizontal()) {
            for (long x = wire.x1(); x <= wire.x2(); x += spacing) {
                blocked.add(new Connectivity.Point((int) x, wire.y1()));
            }
        } else {
            for (long y = wire.y1(); y <= wire.y2(); y += spacing) {
                blocked.add(new Connectivity.Point(wire.x1(), (int) y));
            }
        }
    }

    private static int[][] preferredRouteDirections(
            Connectivity.Point start, Connectivity.Point destination) {
        int towardX = Integer.compare(destination.x(), start.x());
        int towardY = Integer.compare(destination.y(), start.y());
        int preferredX = towardX == 0 ? 1 : towardX;
        int preferredY = towardY == 0 ? 1 : towardY;
        long deltaX = Math.abs((long) destination.x() - start.x());
        long deltaY = Math.abs((long) destination.y() - start.y());

        List<int[]> directions = new ArrayList<>(4);
        if (deltaX >= deltaY) {
            addDirection(directions, preferredX, 0);
            addDirection(directions, 0, preferredY);
            addDirection(directions, -preferredX, 0);
            addDirection(directions, 0, -preferredY);
        } else {
            addDirection(directions, 0, preferredY);
            addDirection(directions, preferredX, 0);
            addDirection(directions, 0, -preferredY);
            addDirection(directions, -preferredX, 0);
        }
        return directions.toArray(int[][]::new);
    }

    private static void addDirection(List<int[]> directions, int deltaX, int deltaY) {
        directions.add(new int[]{deltaX, deltaY});
    }

    private static List<WireSegment> compressRoute(List<Connectivity.Point> route) {
        if (route.size() < 2) {
            return List.of();
        }

        List<WireSegment> segments = new ArrayList<>();
        Connectivity.Point segmentStart = route.getFirst();
        Connectivity.Point previous = route.get(1);
        int directionX = Integer.compare(previous.x(), segmentStart.x());
        int directionY = Integer.compare(previous.y(), segmentStart.y());

        for (int index = 2; index < route.size(); index++) {
            Connectivity.Point current = route.get(index);
            int nextDirectionX = Integer.compare(current.x(), previous.x());
            int nextDirectionY = Integer.compare(current.y(), previous.y());
            if (nextDirectionX != directionX || nextDirectionY != directionY) {
                segments.add(new WireSegment(
                        segmentStart.x(), segmentStart.y(), previous.x(), previous.y()));
                segmentStart = previous;
                directionX = nextDirectionX;
                directionY = nextDirectionY;
            }
            previous = current;
        }

        segments.add(new WireSegment(
                segmentStart.x(), segmentStart.y(), previous.x(), previous.y()));
        return List.copyOf(segments);
    }

    private boolean addWireIfAbsent(int x1, int y1, int x2, int y2) {
        if (x1 == x2 && y1 == y2) {
            return false;
        }
        WireSegment wire = new WireSegment(x1, y1, x2, y2);
        if (wires.contains(wire)) {
            return false;
        }
        wires.add(wire);
        return true;
    }

    private static WireSegment translateWire(WireSegment wire, int deltaX, int deltaY) {
        return new WireSegment(
                wire.x1() + deltaX, wire.y1() + deltaY,
                wire.x2() + deltaX, wire.y2() + deltaY);
    }

    private static void requireGridDelta(int deltaX, int deltaY) {
        if (deltaX % PlacedComponent.GRID_SPACING != 0
                || deltaY % PlacedComponent.GRID_SPACING != 0) {
            throw new IllegalArgumentException("component and wire movement must stay on the schematic grid");
        }
    }

    private record PinMove(
            Connectivity.PinPosition oldPin,
            Connectivity.PinPosition newPin,
            List<Connectivity.Point> attachmentPoints) {
        private PinMove {
            attachmentPoints = List.copyOf(attachmentPoints);
        }
    }

    private PlacedComponent requireInput(String id) {
        Objects.requireNonNull(id, "id");
        PlacedComponent component = components.get(indexOf(id));
        if (!"input".equals(component.typeId())) {
            throw new IllegalArgumentException("component is not an input: " + id);
        }
        return component;
    }

    private void notifyChanged() {
        modificationCount++;
        for (Runnable listener : List.copyOf(changeListeners)) {
            listener.run();
        }
    }

    private GateDef requireKnownType(String typeId) {
        Objects.requireNonNull(typeId, "typeId");
        GateDef definition = definitionsById.get(typeId);
        if (definition == null) {
            throw new IllegalArgumentException("unknown component type: " + typeId);
        }
        return definition;
    }

    private int indexOf(String id) {
        Objects.requireNonNull(id, "id");
        for (int index = 0; index < components.size(); index++) {
            if (components.get(index).id().equals(id)) {
                return index;
            }
        }
        throw new IllegalArgumentException("unknown component id: " + id);
    }

    private boolean containsId(String id) {
        return components.stream().anyMatch(component -> component.id().equals(id));
    }
}
