package io.github.evilalee.gatelab.io;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Loads and validates the built-in component catalogue exactly once per process. */
public final class GateLibrary {
    public static final String RESOURCE_PATH = "/gates.json";
    private static final int GRID_SPACING = 10;

    private final List<GateDef> definitions;
    private final Map<String, GateDef> byId;

    private GateLibrary(List<GateDef> definitions) {
        this.definitions = List.copyOf(definitions);
        Map<String, GateDef> indexed = new HashMap<>();
        for (GateDef definition : definitions) {
            indexed.put(definition.id(), definition);
        }
        this.byId = Map.copyOf(indexed);
    }

    public static GateLibrary load() {
        return Holder.INSTANCE;
    }

    public List<GateDef> all() {
        return definitions;
    }

    public GateDef get(String id) {
        GateDef definition = byId.get(Objects.requireNonNull(id, "id"));
        if (definition == null) {
            throw new IllegalArgumentException("unknown component type: " + id);
        }
        return definition;
    }

    public URL svgUrl(GateDef definition) {
        Objects.requireNonNull(definition, "definition");
        URL url = GateLibrary.class.getResource("/" + definition.svgResource());
        if (url == null) {
            throw new IllegalStateException(
                    "missing SVG resource for " + definition.id() + ": " + definition.svgResource());
        }
        return url;
    }

    private static GateLibrary loadFromClasspath() {
        try (InputStream stream = GateLibrary.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                throw new IllegalStateException("missing classpath resource " + RESOURCE_PATH);
            }

            GateFile file = new Gson().fromJson(
                    new InputStreamReader(stream, StandardCharsets.UTF_8), GateFile.class);
            if (file == null || file.gates == null || file.gates.isEmpty()) {
                throw new IllegalStateException("gates.json must contain a non-empty gates array");
            }
            return new GateLibrary(parseAndValidate(file.gates));
        } catch (IOException | JsonParseException exception) {
            throw new IllegalStateException("could not load " + RESOURCE_PATH, exception);
        }
    }

    private static List<GateDef> parseAndValidate(List<GateEntry> entries) {
        List<GateDef> definitions = new ArrayList<>(entries.size());
        Set<String> ids = new HashSet<>();
        for (GateEntry entry : entries) {
            if (entry == null) {
                throw new IllegalStateException("gates.json contains a null gate entry");
            }
            String id = requireText(entry.id, "gate id");
            String svg = requireText(entry.svg, "SVG resource for " + id);
            if (!ids.add(id)) {
                throw new IllegalStateException("duplicate gate id: " + id);
            }
            if (!isSafeResourcePath(svg)) {
                throw new IllegalStateException("invalid SVG resource path for " + id + ": " + svg);
            }
            if (entry.width == null || entry.height == null) {
                throw new IllegalStateException("missing dimensions for " + id);
            }
            requireGridValue(entry.width, "width", id);
            requireGridValue(entry.height, "height", id);
            if (entry.width <= 0 || entry.height <= 0) {
                throw new IllegalStateException("non-positive dimensions for " + id);
            }
            if (entry.inputs == null) {
                throw new IllegalStateException("missing inputs array for " + id);
            }

            List<GateGeometry.Offset> inputs = new ArrayList<>(entry.inputs.size());
            for (int index = 0; index < entry.inputs.size(); index++) {
                inputs.add(parsePoint(entry.inputs.get(index), "input " + index, id));
            }
            GateGeometry.Offset output = entry.output == null
                    ? null
                    : parsePoint(entry.output, "output", id);

            GateDef definition;
            try {
                definition = new GateDef(id, svg, entry.width, entry.height, inputs, output);
                // This also enforces the single-input origin rule for output probes.
                definition.origin();
            } catch (IllegalArgumentException | IllegalStateException exception) {
                throw new IllegalStateException("invalid gate definition for " + id + ": "
                        + exception.getMessage(), exception);
            }

            validatePinsInsideBounds(definition);
            URL svgUrl = GateLibrary.class.getResource("/" + svg);
            if (svgUrl == null) {
                throw new IllegalStateException("missing SVG resource for " + id + ": " + svg);
            }
            definitions.add(definition);
        }
        return List.copyOf(definitions);
    }

    private static void validatePinsInsideBounds(GateDef definition) {
        for (int index = 0; index < definition.inputs().size(); index++) {
            validatePin(definition, definition.inputs().get(index), "input " + index);
        }
        if (definition.output() != null) {
            validatePin(definition, definition.output(), "output");
        }
    }

    private static void validatePin(GateDef definition, GateGeometry.Offset pin, String label) {
        if (pin.x() < 0 || pin.x() > definition.width()
                || pin.y() < 0 || pin.y() > definition.height()) {
            throw new IllegalStateException(
                    label + " pin lies outside bounds for " + definition.id() + ": " + pin);
        }
    }

    private static GateGeometry.Offset parsePoint(PointEntry point, String label, String id) {
        if (point == null || point.x == null || point.y == null) {
            throw new IllegalStateException("missing coordinates for " + label + " on " + id);
        }
        requireGridValue(point.x, label + " x", id);
        requireGridValue(point.y, label + " y", id);
        return new GateGeometry.Offset(point.x, point.y);
    }

    private static void requireGridValue(int value, String label, String id) {
        if (value % GRID_SPACING != 0) {
            throw new IllegalStateException(
                    label + " for " + id + " must be a multiple of " + GRID_SPACING + ": " + value);
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(label + " must not be blank");
        }
        return value.trim();
    }

    private static boolean isSafeResourcePath(String path) {
        return !path.startsWith("/") && !path.contains("..") && !path.isBlank();
    }

    private static final class Holder {
        private static final GateLibrary INSTANCE = loadFromClasspath();
    }

    private static final class GateFile {
        private List<GateEntry> gates;
    }

    private static final class GateEntry {
        private String id;
        private String svg;
        private Integer width;
        private Integer height;
        private List<PointEntry> inputs;
        private PointEntry output;
    }

    private static final class PointEntry {
        private Integer x;
        private Integer y;
    }
}
