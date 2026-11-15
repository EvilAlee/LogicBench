package io.github.evilalee.gatelab.model;

import java.util.List;
import java.util.Objects;

/** Immutable visual and pin geometry for one component type from gates.json. */
public record GateDef(
        String id,
        String svgResource,
        int width,
        int height,
        List<GateGeometry.Offset> inputs,
        GateGeometry.Offset output) {

    public GateDef {
        id = requireText(id, "id");
        svgResource = requireText(svgResource, "svgResource");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("gate dimensions must be positive");
        }
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
    }

    /**
     * The component origin is its output pin. Output probes have no output pin,
     * so their single input pin defines the origin instead.
     */
    public GateGeometry.Offset origin() {
        if (output != null) {
            return output;
        }
        if (inputs.size() != 1) {
            throw new IllegalStateException(
                    "component " + id + " needs exactly one input when it has no output");
        }
        return inputs.getFirst();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }
}
