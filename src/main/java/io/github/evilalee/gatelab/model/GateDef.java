package io.github.evilalee.gatelab.model;

import java.util.List;
import java.util.Objects;

/** Immutable visual and pin geometry for one component variant from gates.json. */
public record GateDef(
        String id,
        String svgResource,
        int width,
        int height,
        List<GateGeometry.Offset> inputs,
        GateGeometry.Offset output,
        String family,
        String size,
        int inputCount) {

    /** Backwards-compatible constructor for a single narrow variant. */
    public GateDef(
            String id,
            String svgResource,
            int width,
            int height,
            List<GateGeometry.Offset> inputs,
            GateGeometry.Offset output) {
        this(id, svgResource, width, height, inputs, output, id, "narrow", inputs.size());
    }

    public GateDef {
        id = requireText(id, "id");
        svgResource = requireText(svgResource, "svgResource");
        family = requireText(family, "family");
        size = requireText(size, "size").toLowerCase(java.util.Locale.ROOT);
        if (!List.of("narrow", "normal", "wide").contains(size)) {
            throw new IllegalArgumentException("unknown gate size: " + size);
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("gate dimensions must be positive");
        }
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        if (inputCount < 0 || inputCount != inputs.size()) {
            throw new IllegalArgumentException(
                    "inputCount must match the number of input pins for " + id);
        }
    }

    /**
     * The component origin is its output pin. Output probes have no output pin,
     * so their single input pin defines the origin instead.
     */
    public GateGeometry.Offset origin() {
        if (output != null) {
            return output;
        }
        if (inputs.isEmpty()) {
            throw new IllegalStateException(
                    "component " + id + " needs at least one input when it has no output");
        }
        // Multi-input sinks (such as displays) use their first input as the fixed origin.
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
