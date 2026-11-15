package io.github.evilalee.gatelab.model;

import java.util.Map;
import java.util.Objects;

/** One component instance positioned by its fixed origin pin on the schematic. */
public record PlacedComponent(
        String id,
        String typeId,
        int x,
        int y,
        Facing facing,
        Map<String, String> attributes) {

    public static final int GRID_SPACING = 10;

    /** Backwards-compatible constructor for components without non-default attributes. */
    public PlacedComponent(String id, String typeId, int x, int y, Facing facing) {
        this(id, typeId, x, y, facing, Map.of());
    }

    public PlacedComponent {
        id = requireText(id, "id");
        typeId = requireText(typeId, "typeId");
        Objects.requireNonNull(facing, "facing");
        requireGridCoordinate(x, "x");
        requireGridCoordinate(y, "y");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            requireText(entry.getKey(), "attribute key");
            requireText(entry.getValue(), "attribute value");
        }
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
