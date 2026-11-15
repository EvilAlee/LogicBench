package io.github.evilalee.gatelab.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Derives electrical nets from orthogonal wire geometry and placed pin positions.
 * This class is deliberately headless and has no dependency on the Swing canvas.
 */
public final class Connectivity {
    private Connectivity() {
    }

    public record Point(int x, int y) {
    }

    public record PinPosition(String componentId, String pinId, int x, int y) {
        public PinPosition {
            componentId = requireText(componentId, "componentId");
            pinId = requireText(pinId, "pinId");
            requireGrid(x, "x");
            requireGrid(y, "y");
        }

        private static String requireText(String value, String label) {
            Objects.requireNonNull(value, label);
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException(label + " must not be blank");
            }
            return trimmed;
        }

        private static void requireGrid(int value, String label) {
            if (value % PlacedComponent.GRID_SPACING != 0) {
                throw new IllegalArgumentException(
                        label + " must be a multiple of " + PlacedComponent.GRID_SPACING);
            }
        }
    }

    public record Net(Set<PinPosition> pins, Set<WireSegment> segments) {
        public Net {
            pins = Set.copyOf(Objects.requireNonNull(pins, "pins"));
            segments = Set.copyOf(Objects.requireNonNull(segments, "segments"));
        }
    }

    public record Result(List<Net> nets, Set<Point> junctions) {
        public Result {
            nets = List.copyOf(Objects.requireNonNull(nets, "nets"));
            junctions = Set.copyOf(Objects.requireNonNull(junctions, "junctions"));
        }
    }

    /**
     * Builds connected sets. A crossing has no node unless a pin or segment endpoint
     * is present there; a node on a segment interior joins that segment.
     */
    public static Result analyze(
            Collection<PinPosition> pinPositions,
            Collection<WireSegment> wireSegments) {
        Objects.requireNonNull(pinPositions, "pinPositions");
        Objects.requireNonNull(wireSegments, "wireSegments");

        List<PinPosition> pins = List.copyOf(pinPositions);
        LinkedHashSet<WireSegment> segments = new LinkedHashSet<>(wireSegments);
        LinkedHashMap<Point, Integer> nodeIndexes = new LinkedHashMap<>();
        for (PinPosition pin : pins) {
            Objects.requireNonNull(pin, "pinPosition");
            nodeIndexes.computeIfAbsent(new Point(pin.x(), pin.y()), ignored -> nodeIndexes.size());
        }
        for (WireSegment segment : segments) {
            Objects.requireNonNull(segment, "wireSegment");
            nodeIndexes.computeIfAbsent(new Point(segment.x1(), segment.y1()),
                    ignored -> nodeIndexes.size());
            nodeIndexes.computeIfAbsent(new Point(segment.x2(), segment.y2()),
                    ignored -> nodeIndexes.size());
        }

        DisjointSet joined = new DisjointSet(nodeIndexes.size());
        Map<Point, Integer> degrees = new LinkedHashMap<>();
        for (Map.Entry<Point, Integer> node : nodeIndexes.entrySet()) {
            Point point = node.getKey();
            int degree = 0;
            for (WireSegment segment : segments) {
                if (!contains(segment, point)) {
                    continue;
                }
                int first = nodeIndexes.get(new Point(segment.x1(), segment.y1()));
                int second = nodeIndexes.get(new Point(segment.x2(), segment.y2()));
                joined.union(node.getValue(), first);
                joined.union(node.getValue(), second);
                degree += isEndpoint(segment, point) ? 1 : 2;
            }
            if (degree >= 3) {
                degrees.put(point, degree);
            }
        }

        LinkedHashMap<Integer, NetBuilder> builders = new LinkedHashMap<>();
        for (Map.Entry<Point, Integer> node : nodeIndexes.entrySet()) {
            builders.computeIfAbsent(joined.find(node.getValue()), ignored -> new NetBuilder());
        }
        for (PinPosition pin : pins) {
            int index = nodeIndexes.get(new Point(pin.x(), pin.y()));
            builders.get(joined.find(index)).pins.add(pin);
        }
        for (WireSegment segment : segments) {
            int index = nodeIndexes.get(new Point(segment.x1(), segment.y1()));
            builders.get(joined.find(index)).segments.add(segment);
        }

        List<Net> nets = new ArrayList<>(builders.size());
        for (NetBuilder builder : builders.values()) {
            nets.add(new Net(builder.pins, builder.segments));
        }
        return new Result(nets, degrees.keySet());
    }

    private static boolean contains(WireSegment segment, Point point) {
        if (segment.isHorizontal()) {
            return point.y() == segment.y1()
                    && point.x() >= segment.x1() && point.x() <= segment.x2();
        }
        return point.x() == segment.x1()
                && point.y() >= segment.y1() && point.y() <= segment.y2();
    }

    private static boolean isEndpoint(WireSegment segment, Point point) {
        return (segment.x1() == point.x() && segment.y1() == point.y())
                || (segment.x2() == point.x() && segment.y2() == point.y());
    }

    private static final class NetBuilder {
        private final Set<PinPosition> pins = new LinkedHashSet<>();
        private final Set<WireSegment> segments = new LinkedHashSet<>();
    }

    private static final class DisjointSet {
        private final int[] parent;
        private final byte[] rank;

        private DisjointSet(int size) {
            parent = new int[size];
            rank = new byte[size];
            for (int index = 0; index < size; index++) {
                parent[index] = index;
            }
        }

        private int find(int value) {
            if (parent[value] != value) {
                parent[value] = find(parent[value]);
            }
            return parent[value];
        }

        private void union(int first, int second) {
            int firstRoot = find(first);
            int secondRoot = find(second);
            if (firstRoot == secondRoot) {
                return;
            }
            if (rank[firstRoot] < rank[secondRoot]) {
                parent[firstRoot] = secondRoot;
            } else if (rank[firstRoot] > rank[secondRoot]) {
                parent[secondRoot] = firstRoot;
            } else {
                parent[secondRoot] = firstRoot;
                rank[firstRoot]++;
            }
        }
    }
}
