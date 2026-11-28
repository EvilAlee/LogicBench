package io.github.evilalee.gatelab.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Pin names and output-port conventions shared by the built-in display components. */
public final class DisplayComponent {
    public record SinkPin(String componentId, String pinId, String pinName, String portName) {
        public SinkPin {
            Objects.requireNonNull(componentId, "componentId");
            Objects.requireNonNull(pinId, "pinId");
            Objects.requireNonNull(pinName, "pinName");
            Objects.requireNonNull(portName, "portName");
        }
    }

    private DisplayComponent() {
    }

    public static boolean isDisplay(String typeId) {
        return typeId.equals("seg7") || typeId.equals("hexdigit");
    }

    public static List<String> inputNames(String typeId) {
        return switch (typeId) {
            case "seg7" -> List.of("a", "b", "c", "d", "e", "f", "g", "dp");
            case "hexdigit" -> List.of("D0", "D1", "D2", "D3", "dp");
            default -> throw new IllegalArgumentException("not a display component: " + typeId);
        };
    }

    public static List<String> outputSuffixes(String typeId) {
        return switch (typeId) {
            case "seg7" -> List.of("a", "b", "c", "d", "e", "f", "g", "dp");
            case "hexdigit" -> List.of("d0", "d1", "d2", "d3", "dp");
            default -> throw new IllegalArgumentException("not a display component: " + typeId);
        };
    }

    /** Output-port names for each input sink; unnamed displays get stable Seg1/Hex1 bases. */
    public static List<SinkPin> sinkPins(Schematic schematic) {
        Objects.requireNonNull(schematic, "schematic");
        List<PlacedComponent> displays = schematic.components().stream()
                .filter(component -> isDisplay(component.typeId()))
                .sorted(Comparator.comparing(PlacedComponent::typeId)
                        .thenComparing(PlacedComponent::id))
                .toList();
        int segNumber = 0;
        int hexNumber = 0;
        List<SinkPin> result = new ArrayList<>();
        for (PlacedComponent display : displays) {
            String fallback;
            if (display.typeId().equals("seg7")) {
                fallback = "Seg" + (++segNumber);
            } else {
                fallback = "Hex" + (++hexNumber);
            }
            String base = display.label().isBlank() ? fallback : display.label().trim();
            List<String> names = inputNames(display.typeId());
            List<String> suffixes = outputSuffixes(display.typeId());
            for (int index = 0; index < names.size(); index++) {
                result.add(new SinkPin(
                        display.id(), "in" + index, names.get(index),
                        base + "_" + suffixes.get(index)));
            }
        }
        return List.copyOf(result);
    }
}
