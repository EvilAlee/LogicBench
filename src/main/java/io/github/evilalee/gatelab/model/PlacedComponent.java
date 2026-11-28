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
        Map<String, String> attributes,
        String label) {

    public static final int GRID_SPACING = 10;

    /** Backwards-compatible constructor for components without non-default attributes or labels. */
    public PlacedComponent(String id, String typeId, int x, int y, Facing facing) {
        this(id, typeId, x, y, facing, Map.of(), "");
    }

    /** Backwards-compatible constructor for components without labels. */
    public PlacedComponent(
            String id, String typeId, int x, int y, Facing facing,
            Map<String, String> attributes) {
        this(id, typeId, x, y, facing, attributes, "");
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
        label = Objects.requireNonNull(label, "label");
        if (label.isBlank()) {
            label = "";
        }
        // Labels are presentation metadata; gates may carry optional user names too.
    }

    /** True for non-pokeable sources that always drive a fixed logic level. */
    public boolean isFixedSource() {
        return typeId.equals("constant") || typeId.equals("vcc") || typeId.equals("gnd");
    }

    /** Logic value driven by a fixed source; ordinary inputs and clocks are not fixed. */
    public Value fixedSourceValue() {
        return switch (typeId) {
            case "vcc" -> Value.ONE;
            case "gnd" -> Value.ZERO;
            case "constant" -> switch (attributes.getOrDefault("value", "0")) {
                case "0" -> Value.ZERO;
                case "1" -> Value.ONE;
                default -> throw new IllegalArgumentException(
                        "constant source " + id + " must have Value 0 or 1");
            };
            default -> throw new IllegalStateException(
                    "component is not a fixed source: " + typeId);
        };
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
