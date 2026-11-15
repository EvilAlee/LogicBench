package io.github.evilalee.gatelab.model;

import java.util.Objects;

/** One component instance positioned by its fixed origin pin on the schematic. */
public record PlacedComponent(
        String id,
        String typeId,
        int x,
        int y,
        Facing facing) {

    public static final int GRID_SPACING = 10;

    public PlacedComponent {
        id = requireText(id, "id");
        typeId = requireText(typeId, "typeId");
        Objects.requireNonNull(facing, "facing");
        requireGridCoordinate(x, "x");
        requireGridCoordinate(y, "y");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }

    private static void requireGridCoordinate(int value, String name) {
        if (value % GRID_SPACING != 0) {
            throw new IllegalArgumentException(
                    name + " must be a multiple of " + GRID_SPACING + ": " + value);
        }
    }
}
