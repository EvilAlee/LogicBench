package io.github.evilalee.gatelab.model;

import java.util.List;

/** Integer-only gate geometry. Coordinates use a Y-down world coordinate system. */
public final class GateGeometry {
    private GateGeometry() {
    }

    public record Offset(int x, int y) {
    }

    /** Axis-aligned bounds after rotation, expressed relative to the component origin. */
    public record Bounds(int x, int y, int width, int height) {
    }

    public static Offset rotate(Offset offset, Facing facing) {
        return switch (facing) {
            case EAST -> offset;
            case SOUTH -> new Offset(-offset.y(), offset.x());
            case WEST -> new Offset(-offset.x(), -offset.y());
            case NORTH -> new Offset(offset.y(), -offset.x());
        };
    }

    /** Returns one absolute pin coordinate relative to the component origin and rotated. */
    public static Offset pinOffset(GateDef definition, Offset pin, Facing facing) {
        Offset origin = definition.origin();
        return rotate(new Offset(pin.x() - origin.x(), pin.y() - origin.y()), facing);
    }

    /** The input-pin offsets after the component is rotated about its origin. */
    public static List<Offset> inputOffsets(GateDef definition, Facing facing) {
        return definition.inputs().stream()
                .map(pin -> pinOffset(definition, pin, facing))
                .toList();
    }

    /** The output offset, or null for an output probe whose origin is its input pin. */
    public static Offset outputOffset(GateDef definition, Facing facing) {
        return definition.output() == null
                ? null
                : pinOffset(definition, definition.output(), facing);
    }

    /**
     * Rotates all four corners of the unrotated component rectangle, then returns
     * its axis-aligned envelope relative to the fixed origin pin.
     */
    public static Bounds bounds(GateDef definition, Facing facing) {
        Offset origin = definition.origin();
        List<Offset> corners = List.of(
                new Offset(-origin.x(), -origin.y()),
                new Offset(definition.width() - origin.x(), -origin.y()),
                new Offset(-origin.x(), definition.height() - origin.y()),
                new Offset(definition.width() - origin.x(), definition.height() - origin.y()));

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Offset corner : corners) {
            Offset rotated = rotate(corner, facing);
            minX = Math.min(minX, rotated.x());
            minY = Math.min(minY, rotated.y());
            maxX = Math.max(maxX, rotated.x());
            maxY = Math.max(maxY, rotated.y());
        }
        return new Bounds(minX, minY, maxX - minX, maxY - minY);
    }
}
