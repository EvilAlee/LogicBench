package io.github.evilalee.gatelab.model;

/**
 * A normalized, non-zero, axis-aligned wire segment on the schematic grid.
 * It is geometric editor data, distinct from the engine's pin-to-pin Wire.
 */
public record WireSegment(int x1, int y1, int x2, int y2) {
    public WireSegment {
        if (x1 == x2 && y1 == y2) {
            throw new IllegalArgumentException("a wire segment must have non-zero length");
        }
        if (x1 != x2 && y1 != y2) {
            throw new IllegalArgumentException("wire segments must be axis-aligned");
        }
        requireGrid(x1, "x1");
        requireGrid(y1, "y1");
        requireGrid(x2, "x2");
        requireGrid(y2, "y2");

        if (y1 == y2 && x1 > x2) {
            int swap = x1;
            x1 = x2;
            x2 = swap;
        } else if (x1 == x2 && y1 > y2) {
            int swap = y1;
            y1 = y2;
            y2 = swap;
        }
    }

    public boolean isHorizontal() {
        return y1 == y2;
    }

    private static void requireGrid(int value, String coordinate) {
        if (value % PlacedComponent.GRID_SPACING != 0) {
            throw new IllegalArgumentException(
                    coordinate + " must be a multiple of "
                            + PlacedComponent.GRID_SPACING + ": " + value);
        }
    }
}
