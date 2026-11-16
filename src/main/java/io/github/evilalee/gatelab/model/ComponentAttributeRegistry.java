package io.github.evilalee.gatelab.model;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The single catalogue of editable attributes per component family.
 * Values are stored canonically; presentation labels belong to the UI.
 */
public final class ComponentAttributeRegistry {
    public record AttributeSpec(String key, String label, String defaultValue) {
        public AttributeSpec {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(defaultValue, "defaultValue");
        }
    }

    private static final AttributeSpec FACING = new AttributeSpec("facing", "Facing", "EAST");
    private static final AttributeSpec SIZE = new AttributeSpec("size", "Size", "narrow");
    private static final AttributeSpec INPUTS = new AttributeSpec("inputs", "Number of Inputs", "2");

    private static final Map<String, List<AttributeSpec>> BY_FAMILY = Map.ofEntries(
            Map.entry("and", List.of(FACING, SIZE, INPUTS)),
            Map.entry("or", List.of(FACING, SIZE, INPUTS)),
            Map.entry("nand", List.of(FACING, SIZE, INPUTS)),
            Map.entry("nor", List.of(FACING, SIZE, INPUTS)),
            Map.entry("xor", List.of(FACING, SIZE, INPUTS)),
            Map.entry("xnor", List.of(FACING, SIZE, INPUTS)),
            Map.entry("not", List.of(FACING, SIZE)),
            Map.entry("buffer", List.of(FACING, SIZE)),
            Map.entry("input", List.of(FACING)),
            Map.entry("output", List.of(FACING)));

    private ComponentAttributeRegistry() {
    }

    public static List<AttributeSpec> forFamily(String family) {
        List<AttributeSpec> specs = BY_FAMILY.get(Objects.requireNonNull(family, "family"));
        if (specs == null) {
            throw new IllegalArgumentException("no attribute definitions for component type: " + family);
        }
        return specs;
    }

    public static boolean supports(String family, String key) {
        List<AttributeSpec> specs = BY_FAMILY.get(family);
        return specs != null && specs.stream().anyMatch(spec -> spec.key().equals(key));
    }

    public static AttributeSpec require(String family, String key) {
        return forFamily(family).stream()
                .filter(spec -> spec.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown attribute '" + key + "' for component type " + family));
    }

    /** Facing is top-level in the file format; only size/inputs are stored in attrs. */
    public static List<AttributeSpec> storedAttributesFor(String family) {
        return forFamily(family).stream()
                .filter(spec -> !spec.key().equals("facing"))
                .toList();
    }

    public static String normalizeValue(String family, String key, String value) {
        require(family, key);
        Objects.requireNonNull(value, "value");
        String trimmed = value.trim();
        if (key.equals("facing")) {
            try {
                return Facing.valueOf(trimmed.toUpperCase(Locale.ROOT)).name();
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("unknown Facing value: " + value, exception);
            }
        }
        if (key.equals("size")) {
            String normalized = trimmed.toLowerCase(Locale.ROOT);
            if (!List.of("narrow", "normal", "wide").contains(normalized)) {
                throw new IllegalArgumentException("unknown Size value: " + value);
            }
            return normalized;
        }
        if (key.equals("inputs")) {
            if (!List.of("2", "3", "4").contains(trimmed)) {
                throw new IllegalArgumentException("unknown Number of Inputs value: " + value);
            }
            return trimmed;
        }
        throw new IllegalArgumentException("unsupported attribute: " + key);
    }

    public static String displayValue(String key, String value) {
        return switch (key) {
            case "facing" -> value.substring(0, 1).toUpperCase(Locale.ROOT)
                    + value.substring(1).toLowerCase(Locale.ROOT);
            case "size" -> value.substring(0, 1).toUpperCase(Locale.ROOT)
                    + value.substring(1).toLowerCase(Locale.ROOT);
            case "inputs" -> value;
            default -> value;
        };
    }
}
