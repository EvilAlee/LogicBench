package io.github.evilalee.gatelab.synth.generators;

import java.util.List;
import java.util.Map;

/** A deterministic, headless generator with an explicit finite parameter domain. */
public interface TemplateGenerator {
    String id();

    /** Parameter names mapped to their supported integer range or named choices. */
    Map<String, Parameter> parameters();

    /** Builds an answer accepted by the same expression/specification pipeline as AI answers. */
    GeneratedCircuit build(Map<String, String> params);

    record Parameter(Integer minimum, Integer maximum, List<String> choices) {
        public Parameter {
            choices = List.copyOf(choices == null ? List.of() : choices);
            boolean range = minimum != null || maximum != null;
            if (range && (minimum == null || maximum == null || minimum > maximum || !choices.isEmpty())) {
                throw new IllegalArgumentException("parameter must be either an integer range or choices");
            }
            if (!range && choices.isEmpty()) {
                throw new IllegalArgumentException("parameter choices must not be empty");
            }
        }

        public static Parameter integerRange(int minimum, int maximum) {
            return new Parameter(minimum, maximum, List.of());
        }

        public static Parameter choices(String... values) {
            return new Parameter(null, null, List.of(values));
        }

        public List<String> values() {
            if (minimum == null) return choices;
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            for (int value = minimum; value <= maximum; value++) values.add(Integer.toString(value));
            return List.copyOf(values);
        }

        public boolean accepts(String value) {
            if (value == null) return false;
            if (minimum == null) return choices.contains(value);
            try {
                int number = Integer.parseInt(value);
                return number >= minimum && number <= maximum
                        && Integer.toString(number).equals(value);
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
    }
}
