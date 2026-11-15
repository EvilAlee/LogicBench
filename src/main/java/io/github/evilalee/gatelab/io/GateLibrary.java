package io.github.evilalee.gatelab.io;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import io.github.evilalee.gatelab.model.ComponentAttributeRegistry;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Loads and validates the component catalogue and indexes its visual variants. */
public final class GateLibrary {
    public static final String RESOURCE_PATH = "/gates.json";
    private static final int GRID_SPACING = 10;

    private record VariantKey(String family, String size, int inputCount) {
    }

    private final List<GateDef> definitions;
    private final Map<String, GateDef> byId;
    private final Map<String, List<GateDef>> byFamily;
    private final List<String> familyOrder;
    private final Map<String, GateDef> defaultByFamily;

    private GateLibrary(List<GateDef> definitions) {
        this.definitions = List.copyOf(definitions);
        Map<String, GateDef> ids = new LinkedHashMap<>();
        Map<String, List<GateDef>> families = new LinkedHashMap<>();
        Map<VariantKey, GateDef> variants = new HashMap<>();
        for (GateDef definition : definitions) {
            if (ids.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalStateException("duplicate gate id: " + definition.id());
            }
            families.computeIfAbsent(definition.family(), ignored -> new ArrayList<>())
                    .add(definition);
            VariantKey key = new VariantKey(
                    definition.family(), definition.size(), definition.inputCount());
            if (variants.putIfAbsent(key, definition) != null) {
                throw new IllegalStateException("duplicate gate variant: " + key);
            }
        }

        Map<String, List<GateDef>> immutableFamilies = new LinkedHashMap<>();
        Map<String, GateDef> defaults = new LinkedHashMap<>();
        for (Map.Entry<String, List<GateDef>> entry : families.entrySet()) {
            List<GateDef> familyVariants = List.copyOf(entry.getValue());
            immutableFamilies.put(entry.getKey(), familyVariants);
            defaults.put(entry.getKey(), chooseDefault(entry.getKey(), familyVariants));
        }
        byId = Map.copyOf(ids);
        byFamily = java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(immutableFamilies));
        familyOrder = List.copyOf(immutableFamilies.keySet());
        defaultByFamily = Map.copyOf(defaults);
    }

    public static GateLibrary load() {
        return Holder.INSTANCE;
    }

    /** All entries, including alternate visual/pin variants. */
    public List<GateDef> all() {
        return definitions;
    }

    /** Component families in first-entry order, once each. */
    public List<String> families() {
        return familyOrder;
    }

    /** The catalogue variants available for a family. */
    public List<GateDef> variants(String family) {
        List<GateDef> result = byFamily.get(Objects.requireNonNull(family, "family"));
        if (result == null) {
            throw new IllegalArgumentException("unknown component type: " + family);
        }
        return result;
    }

    /** The default narrow variant, using two inputs for multi-input gate families. */
    public GateDef defaultDefinition(String family) {
        GateDef definition = defaultByFamily.get(Objects.requireNonNull(family, "family"));
        if (definition == null) {
            throw new IllegalArgumentException("unknown component type: " + family);
        }
        return definition;
    }

    /**
     * Resolves a requested attribute combination. A missing variant is an
     * ordinary rejected user edit, not a request to approximate another variant.
     */
    public GateDef resolve(String family, String size, int inputCount) {
        Objects.requireNonNull(size, "size");
        for (GateDef definition : variants(family)) {
            if (definition.size().equalsIgnoreCase(size)
                    && definition.inputCount() == inputCount) {
                return definition;
            }
        }
        throw new IllegalArgumentException(
                "Not available: " + family + " " + size + " with " + inputCount + " inputs");
    }

    /** Backwards-compatible lookup by definition id, or by its family name. */
    public GateDef get(String idOrFamily) {
        Objects.requireNonNull(idOrFamily, "idOrFamily");
        GateDef definition = byId.get(idOrFamily);
        return definition == null ? defaultDefinition(idOrFamily) : definition;
    }

    /** Option values are derived only from variants actually present in gates.json. */
    public List<String> attributeOptions(String family, String key) {
        ComponentAttributeRegistry.require(family, key);
        if (key.equals("facing")) {
            return List.of("EAST", "NORTH", "WEST", "SOUTH");
        }
        if (key.equals("size")) {
            return variants(family).stream()
                    .map(GateDef::size)
                    .distinct()
                    .sorted(Comparator.comparingInt(GateLibrary::sizeOrder))
                    .toList();
        }
        if (key.equals("inputs")) {
            return variants(family).stream()
                    .map(GateDef::inputCount)
                    .distinct()
                    .sorted()
                    .map(String::valueOf)
                    .toList();
        }
        throw new IllegalArgumentException("unsupported attribute: " + key);
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

    /** Parses a JSON catalogue (also used by isolated tests with extra variants). */
    public static GateLibrary fromJson(String json) {
        Objects.requireNonNull(json, "json");
        try {
            GateFile file = new Gson().fromJson(json, GateFile.class);
            if (file == null || file.gates == null || file.gates.isEmpty()) {
                throw new IllegalStateException("gates.json must contain a non-empty gates array");
            }
            return new GateLibrary(parseAndValidate(file.gates));
        } catch (JsonParseException exception) {
            throw new IllegalStateException("could not parse gates.json", exception);
        }
    }

    private static GateLibrary loadFromClasspath() {
        try (InputStream stream = GateLibrary.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                throw new IllegalStateException("missing classpath resource " + RESOURCE_PATH);
            }
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return fromJson(json);
        } catch (IOException exception) {
            throw new IllegalStateException("could not load " + RESOURCE_PATH, exception);
        }
    }

    private static List<GateDef> parseAndValidate(List<GateEntry> entries) {
        List<GateDef> definitions = new ArrayList<>(entries.size());
        Set<String> ids = new HashSet<>();
        Set<VariantKey> variants = new HashSet<>();
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
            String family = entry.family == null
                    ? id : requireText(entry.family, "family for " + id);
            String size = entry.size == null ? "narrow"
                    : requireText(entry.size, "size for " + id).toLowerCase(java.util.Locale.ROOT);
            int inputCount = entry.inputCount == null ? inputs.size() : entry.inputCount;
            if (inputCount != inputs.size()) {
                throw new IllegalStateException(
                        "inputCount for " + id + " must match the number of listed input pins");
            }

            GateDef definition;
            try {
                definition = new GateDef(
                        id, svg, entry.width, entry.height, inputs, output,
                        family, size, inputCount);
                definition.origin();
            } catch (IllegalArgumentException | IllegalStateException exception) {
                throw new IllegalStateException("invalid gate definition for " + id + ": "
                        + exception.getMessage(), exception);
            }

            VariantKey key = new VariantKey(family, size, inputCount);
            if (!variants.add(key)) {
                throw new IllegalStateException("duplicate gate variant: " + key);
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

    private static GateDef chooseDefault(String family, List<GateDef> variants) {
        int defaultInputs = ComponentAttributeRegistry.supports(family, "inputs")
                ? 2
                : variants.stream()
                        .filter(definition -> definition.size().equals("narrow"))
                        .mapToInt(GateDef::inputCount)
                        .min().orElse(0);
        return variants.stream()
                .filter(definition -> definition.size().equals("narrow")
                        && definition.inputCount() == defaultInputs)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "family " + family + " needs a narrow default with "
                                + defaultInputs + " inputs"));
    }

    private static int sizeOrder(String size) {
        return switch (size) {
            case "narrow" -> 0;
            case "normal" -> 1;
            case "wide" -> 2;
            default -> 3;
        };
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
        private String family;
        private String size;
        private Integer inputCount;
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
