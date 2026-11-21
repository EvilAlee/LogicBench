package io.github.evilalee.gatelab.synth.generators;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict, deterministic phrase recognition for common regular circuits.
 *
 * <p>Patterns are anchored to the complete normalized request. Extra requirements
 * therefore fall through to the normal AI path instead of being silently discarded.
 */
public final class TemplateMatcher {
    public sealed interface MatchResult permits NoMatch, Matched, Unsupported {
    }

    public record NoMatch() implements MatchResult {
    }

    public record Matched(GeneratedCircuit circuit) implements MatchResult {
        public Matched {
            Objects.requireNonNull(circuit, "circuit");
        }
    }

    public record Unsupported(String message) implements MatchResult {
        public Unsupported {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("unsupported message must not be blank");
            }
        }
    }

    private static final MatchResult NO_MATCH = new NoMatch();
    private static final String NUMBER =
            "(?:[0-9]+|one|two|three|four|five|six|seven|eight)";

    private static final Pattern ADDER = Pattern.compile(
            "^(" + NUMBER + ")\\s*-?\\s*bit\\s+"
                    + "(?:(?:ripple(?:[- ]carry)?|carry)\\s+)?adder"
                    + "(?:\\s+with\\s+carry(?:[- ]?in)?)?$");
    private static final Pattern MULTIPLIER = Pattern.compile(
            "^(" + NUMBER + ")\\s*(?:x|by)\\s*(" + NUMBER + ")"
                    + "(?:\\s*bit)?\\s+multiplier$");
    private static final Pattern MULTIPLIER_REVERSED = Pattern.compile(
            "^multiplier\\s+(" + NUMBER + ")\\s+by\\s+(" + NUMBER + ")$");
    private static final Pattern MUX = Pattern.compile(
            "^(" + NUMBER + ")(?:\\s*to\\s*|\\s*:\\s*|\\s*-to-)(?:1|one)\\s+(?:mux|multiplexer)$");
    private static final Pattern DECODER = Pattern.compile(
            "^(" + NUMBER + ")\\s*(?:to|-to-)\\s*(" + NUMBER + ")\\s+decoder"
                    + "(?:\\s+(?:with\\s+enable|enabled))?$");
    private static final Pattern DECODER_REVERSED = Pattern.compile(
            "^decoder\\s+(" + NUMBER + ")\\s*(?:to|-to-)\\s*(" + NUMBER + ")"
                    + "(?:\\s+(?:with\\s+enable|enabled))?$");
    private static final Pattern SUBTRACTOR = Pattern.compile(
            "^(" + NUMBER + ")\\s*-?\\s*bit\\s+(?:unsigned\\s+)?subtractor$");
    private static final Pattern COMPARATOR = Pattern.compile(
            "^(" + NUMBER + ")\\s*-?\\s*bit\\s+(?:magnitude\\s+)?comparator$");
    private static final Pattern PARITY = Pattern.compile(
            "^parity\\s+of\\s+(" + NUMBER + ")\\s+bits(?:\\s+(even|odd))?$");
    private static final Pattern PARITY_PREFIX = Pattern.compile(
            "^(even|odd)\\s+parity\\s+of\\s+(" + NUMBER + ")\\s+bits$");
    private static final Pattern MAJORITY = Pattern.compile(
            "^(" + NUMBER + ")\\s*-?\\s*input\\s+majority$");
    private static final Pattern MAJORITY_REVERSED = Pattern.compile(
            "^majority\\s+of\\s+(" + NUMBER + ")\\s+inputs$");
    private static final Pattern GRAY_TO_BINARY = Pattern.compile(
            "^(" + NUMBER + ")\\s*-?\\s*bit\\s+gray(?:\\s+code)?\\s+to\\s+binary$");
    private static final Pattern BINARY_TO_GRAY = Pattern.compile(
            "^(" + NUMBER + ")\\s*-?\\s*bit\\s+binary\\s+to\\s+gray(?:\\s+code)?$");

    private TemplateMatcher() {
    }

    public static MatchResult match(String description) {
        if (description == null || description.isBlank()) return NO_MATCH;
        String phrase = normalize(description);

        Matcher matcher = ADDER.matcher(phrase);
        if (matcher.matches()) {
            int width = number(matcher.group(1));
            if (!inRange(width, 1, 4)) {
                return range("ripple_adder", "bits", 1, 4, width);
            }
            boolean carryIn = phrase.endsWith("with carry")
                    || phrase.endsWith("with carry in")
                    || phrase.endsWith("with carry-in");
            return built("ripple_adder",
                    Map.of("n", Integer.toString(width), "carryIn", carryIn ? "yes" : "no"));
        }

        matcher = MULTIPLIER.matcher(phrase);
        if (!matcher.matches()) matcher = MULTIPLIER_REVERSED.matcher(phrase);
        if (matcher.matches()) {
            int n = number(matcher.group(1));
            int m = number(matcher.group(2));
            if (!inRange(n, 1, 4) || !inRange(m, 1, 4)) {
                return new Unsupported("Array multiplier supports 1 to 4 bits for each input bus.");
            }
            return built("array_multiplier",
                    Map.of("n", Integer.toString(n), "m", Integer.toString(m)));
        }

        matcher = MUX.matcher(phrase);
        if (matcher.matches()) {
            int dataInputs = number(matcher.group(1));
            if (dataInputs < 2 || (dataInputs & (dataInputs - 1)) != 0) {
                return new Unsupported("Mux input count must be 2, 4, or 8.");
            }
            int k = Integer.numberOfTrailingZeros(dataInputs);
            if (k > 2) {
                return new Unsupported("An 8:1 mux needs 11 single-bit inputs; supported under the 10-input limit: 2:1 and 4:1.");
            }
            return built("mux", Map.of("k", Integer.toString(k)));
        }

        matcher = DECODER.matcher(phrase);
        if (!matcher.matches()) matcher = DECODER_REVERSED.matcher(phrase);
        if (matcher.matches()) {
            int inputs = number(matcher.group(1));
            int outputs = number(matcher.group(2));
            if (!inRange(inputs, 1, 3)) {
                return new Unsupported("Decoder templates support 1 to 3 input bits.");
            }
            if (outputs != (1 << inputs)) {
                return new Unsupported("Decoder output count must be 2^n: supported pairs are 1-to-2, 2-to-4, and 3-to-8.");
            }
            boolean enabled = phrase.endsWith("with enable") || phrase.endsWith("enabled");
            return built("decoder", Map.of(
                    "n", Integer.toString(inputs), "enable", enabled ? "yes" : "no"));
        }

        matcher = SUBTRACTOR.matcher(phrase);
        if (matcher.matches()) {
            int width = number(matcher.group(1));
            if (!inRange(width, 1, 4)) {
                return range("subtractor", "bits", 1, 4, width);
            }
            return built("subtractor", Map.of("n", Integer.toString(width)));
        }

        matcher = COMPARATOR.matcher(phrase);
        if (matcher.matches()) {
            int width = number(matcher.group(1));
            if (!inRange(width, 1, 4)) {
                return range("comparator", "bits", 1, 4, width);
            }
            return built("comparator", Map.of("n", Integer.toString(width)));
        }

        matcher = PARITY.matcher(phrase);
        if (matcher.matches()) {
            int width = number(matcher.group(1));
            String mode = matcher.group(2) == null ? "even" : matcher.group(2);
            if (!inRange(width, 2, 8)) {
                return range("parity", "bits", 2, 8, width);
            }
            return built("parity", Map.of("n", Integer.toString(width), "mode", mode));
        }
        matcher = PARITY_PREFIX.matcher(phrase);
        if (matcher.matches()) {
            int width = number(matcher.group(2));
            if (!inRange(width, 2, 8)) {
                return range("parity", "bits", 2, 8, width);
            }
            return built("parity", Map.of("n", Integer.toString(width), "mode", matcher.group(1)));
        }

        matcher = MAJORITY.matcher(phrase);
        if (!matcher.matches()) matcher = MAJORITY_REVERSED.matcher(phrase);
        if (matcher.matches()) {
            int width = number(matcher.group(1));
            if (width != 3 && width != 5) {
                return new Unsupported("Majority templates support exactly 3 or 5 inputs.");
            }
            return built("majority", Map.of("n", Integer.toString(width)));
        }

        matcher = GRAY_TO_BINARY.matcher(phrase);
        if (matcher.matches()) {
            int width = number(matcher.group(1));
            if (!inRange(width, 2, 4)) {
                return range("gray_to_binary", "bits", 2, 4, width);
            }
            return built("gray_to_binary", Map.of("n", Integer.toString(width)));
        }

        matcher = BINARY_TO_GRAY.matcher(phrase);
        if (matcher.matches()) {
            int width = number(matcher.group(1));
            if (!inRange(width, 2, 4)) {
                return range("binary_to_gray", "bits", 2, 4, width);
            }
            return built("binary_to_gray", Map.of("n", Integer.toString(width)));
        }

        return NO_MATCH;
    }

    private static MatchResult built(String id, Map<String, String> parameters) {
        try {
            return new Matched(StandardCircuitGenerators.require(id).build(parameters));
        } catch (IllegalArgumentException exception) {
            return new Unsupported(exception.getMessage());
        }
    }

    private static Unsupported range(String id, String unit, int minimum, int maximum, int actual) {
        return new Unsupported(id + " request uses " + actual + " " + unit
                + "; supported: " + minimum + " to " + maximum + " " + unit + ".");
    }

    private static boolean inRange(int value, int minimum, int maximum) {
        return value >= minimum && value <= maximum;
    }

    private static int number(String token) {
        return switch (token) {
            case "one" -> 1;
            case "two" -> 2;
            case "three" -> 3;
            case "four" -> 4;
            case "five" -> 5;
            case "six" -> 6;
            case "seven" -> 7;
            case "eight" -> 8;
            default -> {
                try {
                    yield Integer.parseInt(token);
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("invalid template dimension: " + token, exception);
                }
            }
        };
    }

    private static String normalize(String description) {
        String result = description.toLowerCase(Locale.ROOT).trim()
                .replace('×', 'x')
                .replace('\u2010', '-')
                .replace('\u2011', '-')
                .replace('\u2012', '-')
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .replaceAll("[.!?]+$", "")
                .replaceAll("\\s+", " ");
        result = result.replaceFirst("^please\\s+", "");
        result = result.replaceFirst("^can you\\s+", "");
        for (int pass = 0; pass < 4; pass++) {
            String previous = result;
            result = result.replaceFirst("^(?:build|make|create|design|generate|synthesize)\\s+", "");
            result = result.replaceFirst("^(?:me|a|an|the)\\s+", "");
            if (result.equals(previous)) break;
        }
        return result;
    }
}
