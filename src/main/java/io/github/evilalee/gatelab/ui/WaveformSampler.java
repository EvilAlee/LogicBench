package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SchematicCompiler.SimulationResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Builds one ordered display-name to signal-value snapshot from an editor state. */
final class WaveformSampler {
    private WaveformSampler() {
    }

    static Map<String, Value> sample(Schematic schematic, SimulationResult simulation) {
        Objects.requireNonNull(schematic, "schematic");
        Objects.requireNonNull(simulation, "simulation");

        Map<String, Value> sample = new LinkedHashMap<>();
        Map<String, Integer> componentNumbers = new LinkedHashMap<>();
        for (PlacedComponent component : schematic.components()) {
            String kind = component.typeId();
            if (!kind.equals("input") && !kind.equals("clock") && !kind.equals("output")) {
                continue;
            }

            int number = componentNumbers.merge(kind, 1, Integer::sum);
            String prefix = displayPrefix(kind);
            String requestedLabel = component.label().isBlank()
                    ? prefix + " " + number
                    : component.label().trim();
            String label = uniqueLabel(requestedLabel, sample);
            Value value = switch (kind) {
                case "input", "clock" ->
                        simulation.inputValues().getOrDefault(component.id(), Value.X);
                case "output" ->
                        simulation.outputValues().getOrDefault(component.id(), Value.X);
                default -> throw new IllegalStateException("unexpected signal type: " + kind);
            };
            sample.put(label, value);
        }
        return Collections.unmodifiableMap(sample);
    }

    private static String displayPrefix(String typeId) {
        return switch (typeId) {
            case "input" -> "Input";
            case "clock" -> "Clock";
            case "output" -> "Output";
            default -> throw new IllegalArgumentException("not a recordable signal: " + typeId);
        };
    }

    private static String uniqueLabel(String requested, Map<String, Value> sample) {
        if (!sample.containsKey(requested)) {
            return requested;
        }
        for (int suffix = 2; ; suffix++) {
            String candidate = requested + " (" + suffix + ")";
            if (!sample.containsKey(candidate)) {
                return candidate;
            }
        }
    }
}
