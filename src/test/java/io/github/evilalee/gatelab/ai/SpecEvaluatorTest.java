package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpecEvaluatorTest {
    private static SpecEvaluator spec(
            List<String> inputs, List<String> outputPins, String expression) {
        JsonObject root = new JsonObject();
        JsonObject buses = new JsonObject();
        JsonArray yPins = new JsonArray();
        for (String pin : outputPins) yPins.add(pin);
        buses.add("Y", yPins);
        root.add("buses", buses);
        JsonArray rules = new JsonArray();
        JsonObject rule = new JsonObject();
        rule.addProperty("out", "Y");
        rule.addProperty("expr", expression);
        rules.add(rule);
        root.add("rules", rules);
        return SpecEvaluator.parse(root, inputs, outputPins);
    }

    private static SpecEvaluator integerSpec(String expression) {
        JsonObject root = new JsonObject();
        JsonObject buses = new JsonObject();
        JsonArray aPins = new JsonArray();
        JsonArray bPins = new JsonArray();
        JsonArray yPins = new JsonArray();
        List<String> inputs = new java.util.ArrayList<>();
        List<String> outputs = new java.util.ArrayList<>();
        for (int index = 63; index >= 0; index--) {
            aPins.add("A" + index);
            bPins.add("B" + index);
            yPins.add("Y" + index);
            inputs.add("A" + index);
            inputs.add("B" + index);
            outputs.add("Y" + index);
        }
        buses.add("A", aPins);
        buses.add("B", bPins);
        buses.add("Y", yPins);
        root.add("buses", buses);
        JsonArray rules = new JsonArray();
        JsonObject rule = new JsonObject();
        rule.addProperty("out", "Y");
        rule.addProperty("expr", expression);
        rules.add(rule);
        root.add("rules", rules);
        return SpecEvaluator.parse(root, inputs, outputs);
    }

    private static Map<String, Boolean> longInputs(long a, long b) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        for (int index = 63; index >= 0; index--) {
            values.put("A" + index, ((a >>> index) & 1L) != 0);
            values.put("B" + index, ((b >>> index) & 1L) != 0);
        }
        return values;
    }

    private static Map<String, Boolean> values(Object... pairs) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], (Boolean) pairs[index + 1]);
        }
        return result;
    }

    private static long oneBit(String expression, long a, long b) {
        SpecEvaluator evaluator = integerSpec(expression);
        return evaluator.evaluate(longInputs(a, b)).outputBusValues().get("Y");
    }

    @Test
    void supportsArithmeticBitwiseShiftComparisonLogicalNotAndConditionalOperators() {
        assertEquals(8, oneBit("A + B", 6, 2));
        assertEquals(4, oneBit("A - B", 6, 2));
        assertEquals(12, oneBit("A * B", 6, 2));
        assertEquals(3, oneBit("A / B", 6, 2));
        assertEquals(0, oneBit("A % B", 6, 2));
        assertEquals(24, oneBit("A << B", 6, 2));
        assertEquals(1, oneBit("A >> B", 6, 2));
        assertEquals(2, oneBit("A & B", 6, 2));
        assertEquals(6, oneBit("A | B", 6, 2));
        assertEquals(4, oneBit("A ^ B", 6, 2));
        assertEquals(0, oneBit("!A", 6, 2));
        assertEquals(1, oneBit("A == B", 2, 2));
        assertEquals(1, oneBit("A != B", 6, 2));
        assertEquals(0, oneBit("A < B", 2, 2));
        assertEquals(1, oneBit("A <= B", 2, 2));
        assertEquals(1, oneBit("A > B", 6, 2));
        assertEquals(1, oneBit("A >= B", 6, 2));
        assertEquals(2, oneBit("A ? B : 9", 6, 2));
        assertEquals(0, oneBit("(A + B) & 7", 6, 2));
        assertEquals(-8, oneBit("-A - B", 6, 2));
    }

    @Test
    void popcountAndParenthesesAreEvaluated() {
        assertEquals(3, oneBit("popcount(A)", 11, 0));
        assertEquals(5, oneBit("(A + B) * 2 - 1", 1, 2));
    }

    @Test
    void busPinsArePackedMsbFirstAndResultsAreMaskedToOutputWidth() {
        JsonObject root = new JsonObject();
        JsonObject buses = new JsonObject();
        JsonArray aPins = new JsonArray();
        aPins.add("A3"); aPins.add("A2"); aPins.add("A1"); aPins.add("A0");
        buses.add("A", aPins);
        JsonArray yPins = new JsonArray();
        yPins.add("Y1"); yPins.add("Y0");
        buses.add("Y", yPins);
        root.add("buses", buses);
        JsonArray rules = new JsonArray();
        JsonObject rule = new JsonObject();
        rule.addProperty("out", "Y");
        rule.addProperty("expr", "A");
        rules.add(rule);
        root.add("rules", rules);

        SpecEvaluator evaluator = SpecEvaluator.parse(root,
                List.of("A3", "A2", "A1", "A0"), List.of("Y1", "Y0"));
        SpecEvaluator.Evaluation result = evaluator.evaluate(
                values("A3", true, "A2", false, "A1", true, "A0", true));
        assertEquals(3, result.outputBusValues().get("Y"));
        assertEquals(true, result.outputPins().get("Y1"));
        assertEquals(true, result.outputPins().get("Y0"));
        assertEquals("A=11", evaluator.formatInputContext(
                values("A3", true, "A2", false, "A1", true, "A0", true)));
    }

    @Test
    void subtractionWrapsToOutputBusWidth() {
        JsonObject root = new JsonObject();
        JsonObject buses = new JsonObject();
        JsonArray pins = new JsonArray();
        pins.add("Y2"); pins.add("Y1"); pins.add("Y0");
        buses.add("Y", pins);
        root.add("buses", buses);
        JsonArray rules = new JsonArray();
        JsonObject rule = new JsonObject();
        rule.addProperty("out", "Y");
        rule.addProperty("expr", "0 - 1");
        rules.add(rule);
        root.add("rules", rules);
        SpecEvaluator evaluator = SpecEvaluator.parse(root, List.of(), List.of("Y2","Y1","Y0"));
        assertEquals(7, evaluator.evaluate(Map.of()).outputBusValues().get("Y"));
    }

    @Test
    void divisionByZeroAndMissingOutputCoverageAreSpecErrors() {
        SpecEvaluator division = spec(List.of("A"), List.of("Y"), "A / 0");
        SpecEvaluator.SpecException error = assertThrows(
                SpecEvaluator.SpecException.class,
                () -> division.evaluate(values("A", true)));
        assertTrue(error.getMessage().contains("division by zero"));
        assertTrue(error.position() >= 0);

        JsonObject root = new JsonObject();
        root.add("buses", new JsonObject());
        JsonArray rules = new JsonArray();
        JsonObject rule = new JsonObject();
        rule.addProperty("out", "Y");
        rule.addProperty("expr", "A");
        rules.add(rule);
        root.add("rules", rules);
        SpecEvaluator.SpecException missing = assertThrows(
                SpecEvaluator.SpecException.class,
                () -> SpecEvaluator.parse(root, List.of("A"), List.of("Y", "Z")));
        assertTrue(missing.getMessage().contains("Z"));
    }

    @Test
    void unknownNamesAndSyntaxErrorsCarryPositions() {
        SpecEvaluator.SpecException unknown = assertThrows(
                SpecEvaluator.SpecException.class,
                () -> spec(List.of("A"), List.of("Y"), "MISSING + 1"));
        assertTrue(unknown.position() >= 0);
        SpecEvaluator.SpecException syntax = assertThrows(
                SpecEvaluator.SpecException.class,
                () -> spec(List.of("A"), List.of("Y"), "A +"));
        assertTrue(syntax.position() >= 0);
    }
    @Test
    void rejectsAmbiguousBusNameAndExtraRuleProperties() {
        JsonObject ambiguous = new JsonObject();
        JsonObject buses = new JsonObject();
        JsonArray aPins = new JsonArray(); aPins.add("A1"); aPins.add("A0");
        JsonArray otherPins = new JsonArray(); otherPins.add("B");
        buses.add("A", aPins);
        buses.add("A1", otherPins);
        ambiguous.add("buses", buses);
        JsonArray rules = new JsonArray();
        JsonObject rule = new JsonObject();
        rule.addProperty("out", "Y"); rule.addProperty("expr", "A");
        rules.add(rule);
        ambiguous.add("rules", rules);
        SpecEvaluator.SpecException busError = assertThrows(
                SpecEvaluator.SpecException.class,
                () -> SpecEvaluator.parse(ambiguous,
                        List.of("A1", "A0", "B"), List.of("Y")));
        assertTrue(busError.getMessage().contains("bus name conflicts with a declared pin"));

        JsonObject extraRule = new JsonObject();
        extraRule.add("buses", new JsonObject());
        JsonArray extraRules = new JsonArray();
        JsonObject extra = new JsonObject();
        extra.addProperty("out", "Y");
        extra.addProperty("expr", "A");
        extra.addProperty("comment", "ignored data is not accepted");
        extraRules.add(extra);
        extraRule.add("rules", extraRules);
        SpecEvaluator.SpecException ruleError = assertThrows(
                SpecEvaluator.SpecException.class,
                () -> SpecEvaluator.parse(extraRule, List.of("A"), List.of("Y")));
        assertTrue(ruleError.getMessage().contains("exactly out and expr"));
    }

}
