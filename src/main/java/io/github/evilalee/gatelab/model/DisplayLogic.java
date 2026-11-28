package io.github.evilalee.gatelab.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure three-valued input-to-segment decoding for Seg7 and HexDigit. */
public final class DisplayLogic {
    public enum ActiveLevel {
        HIGH, LOW
    }

    public enum SegmentState {
        LIT, DIM, UNKNOWN
    }

    /** connected=false distinguishes a bare schematic pin from a driven X net. */
    public record Input(Value value, boolean connected) {
        public Input {
            Objects.requireNonNull(value, "value");
        }
    }

    public record State(
            Map<String, SegmentState> segments,
            Integer hexDigit,
            boolean unknownHex) {
        public State {
            segments = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(segments, "segments")));
        }

        public String tooltip(boolean hexadecimal) {
            if (hexadecimal) {
                return unknownHex ? "Hex digit: ?" : "Hex digit: " + HEX_DIGITS[hexDigit];
            }
            List<String> lit = segments.entrySet().stream()
                    .filter(entry -> entry.getValue() == SegmentState.LIT
                            && !entry.getKey().equals("dp"))
                    .map(Map.Entry::getKey).toList();
            List<String> unknown = segments.entrySet().stream()
                    .filter(entry -> entry.getValue() == SegmentState.UNKNOWN
                            && !entry.getKey().equals("dp"))
                    .map(Map.Entry::getKey).toList();
            if (unknown.isEmpty()) {
                for (int digit = 0; digit <= 9; digit++) {
                    Set<String> glyph = glyphSegments(digit);
                    if (new LinkedHashSet<>(lit).equals(glyph)) {
                        return "Digit " + digit;
                    }
                }
            }
            String text = lit.isEmpty() ? "none" : String.join(", ", lit);
            return unknown.isEmpty()
                    ? "Segments lit: " + text
                    : "Segments lit: " + text + "; unknown: " + String.join(", ", unknown);
        }
    }

    private static final List<String> SEGMENT_NAMES = List.of("a", "b", "c", "d", "e", "f", "g");
    private static final String[] HEX_DIGITS = {
            "0", "1", "2", "3", "4", "5", "6", "7",
            "8", "9", "A", "b", "C", "d", "E", "F"
    };
    private static final List<Set<String>> HEX_GLYPHS = List.of(
            Set.of("a", "b", "c", "d", "e", "f"),
            Set.of("b", "c"),
            Set.of("a", "b", "d", "e", "g"),
            Set.of("a", "b", "c", "d", "g"),
            Set.of("b", "c", "f", "g"),
            Set.of("a", "c", "d", "f", "g"),
            Set.of("a", "c", "d", "e", "f", "g"),
            Set.of("a", "b", "c"),
            Set.of("a", "b", "c", "d", "e", "f", "g"),
            Set.of("a", "b", "c", "d", "f", "g"),
            Set.of("a", "b", "c", "e", "f", "g"),
            Set.of("c", "d", "e", "f", "g"),
            Set.of("a", "d", "e", "f"),
            Set.of("b", "c", "d", "e", "g"),
            Set.of("a", "d", "e", "f", "g"),
            Set.of("a", "e", "f", "g"));

    private DisplayLogic() {
    }

    public static State seg7(List<Input> inputs, ActiveLevel activeLevel) {
        requireSize(inputs, 8, "Seg7");
        Objects.requireNonNull(activeLevel, "activeLevel");
        Map<String, SegmentState> states = new LinkedHashMap<>();
        for (int index = 0; index < 7; index++) {
            states.put(SEGMENT_NAMES.get(index), stateFor(inputs.get(index), activeLevel));
        }
        states.put("dp", stateFor(inputs.get(7), activeLevel));
        return new State(states, null, false);
    }

    /** D0 is the least-significant bit; Active level also controls data polarity. */
    public static State hexDigit(List<Input> inputs, ActiveLevel activeLevel) {
        requireSize(inputs, 5, "HexDigit");
        Objects.requireNonNull(activeLevel, "activeLevel");
        boolean unknown = false;
        int digit = 0;
        for (int bit = 0; bit < 4; bit++) {
            Input input = inputs.get(bit);
            if (!input.connected() || input.value() == Value.X) {
                unknown = true;
                continue;
            }
            if (isActive(input.value(), activeLevel)) {
                digit |= 1 << bit;
            }
        }
        Map<String, SegmentState> states = new LinkedHashMap<>();
        Set<String> glyph = unknown ? Set.of() : glyphSegments(digit);
        for (String segment : SEGMENT_NAMES) {
            states.put(segment, glyph.contains(segment)
                    ? SegmentState.LIT : SegmentState.DIM);
        }
        states.put("dp", stateFor(inputs.get(4), activeLevel));
        return new State(states, unknown ? null : digit, unknown);
    }

    public static Set<String> glyphSegments(int digit) {
        if (digit < 0 || digit > 15) {
            throw new IllegalArgumentException("hex digit must be between 0 and 15");
        }
        return HEX_GLYPHS.get(digit);
    }

    public static String hexCharacter(int digit) {
        if (digit < 0 || digit > 15) {
            throw new IllegalArgumentException("hex digit must be between 0 and 15");
        }
        return HEX_DIGITS[digit];
    }

    public static SegmentState stateFor(Input input, ActiveLevel activeLevel) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(activeLevel, "activeLevel");
        if (!input.connected()) {
            return SegmentState.DIM;
        }
        if (input.value() == Value.X) {
            return SegmentState.UNKNOWN;
        }
        return isActive(input.value(), activeLevel) ? SegmentState.LIT : SegmentState.DIM;
    }

    private static boolean isActive(Value value, ActiveLevel activeLevel) {
        return activeLevel == ActiveLevel.HIGH ? value == Value.ONE : value == Value.ZERO;
    }

    private static void requireSize(List<Input> inputs, int expected, String display) {
        Objects.requireNonNull(inputs, "inputs");
        if (inputs.size() != expected) {
            throw new IllegalArgumentException(
                    display + " requires " + expected + " inputs, got " + inputs.size());
        }
        inputs.forEach(input -> Objects.requireNonNull(input, "input"));
    }
}
