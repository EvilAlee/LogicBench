package io.github.evilalee.gatelab.synth.generators;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Accepted and rejected deterministic phrase examples, including word-number spellings. */
class TemplateMatcherTest {
    private record Case(String phrase, String generator, Map<String, String> parameters) { }

    @Test
    void acceptsTheDocumentedCommonCircuitPhrases() {
        List<Case> cases = List.of(
                new Case("4 bit adder", "ripple_adder",
                        Map.of("n", "4", "carryIn", "no")),
                new Case("4-bit adder with carry", "ripple_adder",
                        Map.of("n", "4", "carryIn", "yes")),
                new Case("four bit adder", "ripple_adder",
                        Map.of("n", "4", "carryIn", "no")),
                new Case("Build me a four-bit ripple-carry adder with carry",
                        "ripple_adder", Map.of("n", "4", "carryIn", "yes")),
                new Case("4x4 multiplier", "array_multiplier",
                        Map.of("n", "4", "m", "4")),
                new Case("multiplier 4 by 4", "array_multiplier",
                        Map.of("n", "4", "m", "4")),
                new Case("4 to 1 mux", "mux", Map.of("k", "2")),
                new Case("4:1 multiplexer", "mux", Map.of("k", "2")),
                new Case("four-to-one mux", "mux", Map.of("k", "2")),
                new Case("three-to-eight decoder with enable", "decoder",
                        Map.of("n", "3", "enable", "yes")),
                new Case("3 to 8 decoder", "decoder",
                        Map.of("n", "3", "enable", "no")),
                new Case("4 bit comparator", "comparator", Map.of("n", "4")),
                new Case("4-bit unsigned subtractor", "subtractor", Map.of("n", "4")),
                new Case("parity of 6 bits", "parity", Map.of("n", "6", "mode", "even")),
                new Case("odd parity of six bits", "parity", Map.of("n", "6", "mode", "odd")),
                new Case("3 input majority", "majority", Map.of("n", "3")),
                new Case("majority of five inputs", "majority", Map.of("n", "5")),
                new Case("4 bit gray code to binary", "gray_to_binary", Map.of("n", "4")),
                new Case("four-bit binary to gray code", "binary_to_gray", Map.of("n", "4"))
        );

        for (Case expected : cases) {
            TemplateMatcher.Matched matched = assertInstanceOf(
                    TemplateMatcher.Matched.class, TemplateMatcher.match(expected.phrase()),
                    expected.phrase());
            assertEquals(expected.generator(), matched.circuit().generator(), expected.phrase());
            assertEquals(expected.parameters(), matched.circuit().params(), expected.phrase());
        }
    }

    @Test
    void rejectsPhrasesWithAdditionalFunctionalRequirements() {
        List<String> phrases = List.of(
                "adder with an alarm light",
                "Build a 4-bit adder with an alarm light",
                "Build a 3-bit adder and a decoder",
                "traffic light",
                "Build a traffic light controller",
                "Create a traffic-light controller with pedestrian crossing"
        );
        for (String phrase : phrases) {
            assertInstanceOf(TemplateMatcher.NoMatch.class, TemplateMatcher.match(phrase), phrase);
        }
    }

    @Test
    void reportsOutOfRangeRequestsInsteadOfClamping() {
        TemplateMatcher.Unsupported adder = assertInstanceOf(
                TemplateMatcher.Unsupported.class, TemplateMatcher.match("5-bit adder"));
        assertTrue(adder.message().contains("supported: 1 to 4 bits"));

        TemplateMatcher.Unsupported subtractor = assertInstanceOf(
                TemplateMatcher.Unsupported.class, TemplateMatcher.match("5-bit subtractor"));
        assertTrue(subtractor.message().contains("supported: 1 to 4 bits"));

        TemplateMatcher.Unsupported parity = assertInstanceOf(
                TemplateMatcher.Unsupported.class, TemplateMatcher.match("parity of 9 bits"));
        assertTrue(parity.message().contains("supported: 2 to 8 bits"));

        TemplateMatcher.Unsupported mux = assertInstanceOf(
                TemplateMatcher.Unsupported.class, TemplateMatcher.match("8 to 1 mux"));
        assertTrue(mux.message().contains("11 single-bit inputs"));
    }

    @Test
    void rejectsInvalidMuxAndDecoderRatiosExplicitly() {
        TemplateMatcher.Unsupported mux = assertInstanceOf(
                TemplateMatcher.Unsupported.class, TemplateMatcher.match("6:1 multiplexer"));
        assertTrue(mux.message().contains("2, 4, or 8"));

        TemplateMatcher.Unsupported decoder = assertInstanceOf(
                TemplateMatcher.Unsupported.class, TemplateMatcher.match("3 to 4 decoder"));
        assertTrue(decoder.message().contains("2^n"));
    }
}
