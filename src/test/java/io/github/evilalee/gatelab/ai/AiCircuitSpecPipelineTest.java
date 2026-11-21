package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.evilalee.gatelab.io.GateLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiCircuitSpecPipelineTest {
    private static String answer(JsonArray inputs, JsonArray outputs, JsonArray signals, JsonObject spec) {
        JsonObject root = new JsonObject();
        root.addProperty("summary", "pipeline test");
        root.add("inputs", inputs);
        root.add("outputs", outputs);
        if (signals != null) root.add("signals", signals);
        if (spec != null) root.add("spec", spec);
        return root.toString();
    }

    private static JsonArray names(String... values) {
        JsonArray result = new JsonArray();
        for (String value : values) result.add(value);
        return result;
    }

    private static JsonArray named(String name, String expression) {
        JsonObject object = new JsonObject();
        object.addProperty("name", name);
        object.addProperty("expression", expression);
        JsonArray result = new JsonArray();
        result.add(object);
        return result;
    }

    private static AiCircuitService service(String raw) {
        AiProvider provider = (prompt, conversation, schema) -> raw;
        return new AiCircuitService(provider, GateLibrary.load().all());
    }

    @Test
    void internalSignalsSupportForwardReferencesWithoutBecomingInputs() throws Exception {
        JsonArray signals = new JsonArray();
        signals.add(named("RESULT", "MIDDLE ^ A").get(0));
        signals.add(named("MIDDLE", "A & B").get(0));
        AiCircuitService.Result result = service(answer(
                names("A", "B"),
                named("F", "RESULT"),
                signals, null)).generate("Build a small circuit with an internal forward reference.");

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertEquals(List.of("A", "B"), result.truthTable().inputNames());
        assertEquals(List.of("F"), result.truthTable().outputNames());
        assertEquals(4, result.truthTable().rows().size());
    }

    @Test
    void misspelledExpressionVariableIsNamedAsAnUndeclaredInput() throws Exception {
        AiCircuitService.Result result = service(answer(
                names("A", "B"),
                named("F", "A & TYP0"),
                null, null)).generate("Build an AND circuit.");

        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(result.mismatchingRows().stream().anyMatch(
                line -> line.contains("TYP0") && line.contains("not declared")));
    }

    @Test
    void expectedRowsAreLimitedToSixtyFour() throws Exception {
        AiCircuitService.Result result = service(answer(
                names("A", "B"),
                named("F", "A & B"),
                null, null)).generate("Build an AND circuit.", false, false,
                "A=0 B=0 -> F=0\n".repeat(AiCircuitLimits.MAX_EXPECTED_ROWS + 1));

        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(result.mismatchingRows().stream().anyMatch(
                line -> line.contains("Expected rows exceed maximum of 64")));
    }

    @Test
    void specRuleCountIsLimitedToSixteen() throws Exception {
        JsonObject spec = new JsonObject();
        spec.add("buses", new JsonObject());
        JsonArray rules = new JsonArray();
        for (int index = 0; index < AiCircuitLimits.MAX_SPEC_RULES + 1; index++) {
            JsonObject rule = new JsonObject();
            rule.addProperty("out", "F");
            rule.addProperty("expr", "A & B");
            rules.add(rule);
        }
        spec.add("rules", rules);
        AiCircuitService.Result result = service(answer(
                names("A", "B"), named("F", "A & B"), null, spec))
                .generate("Build an AND circuit.");

        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(result.mismatchingRows().stream().anyMatch(
                line -> line.contains("spec.rules has 17 entries; maximum is 16")));
    }

    @Test
    void malformedSpecExpressionFailsWithItsPosition() throws Exception {
        JsonObject spec = new JsonObject();
        spec.add("buses", new JsonObject());
        JsonArray rules = new JsonArray();
        JsonObject rule = new JsonObject();
        rule.addProperty("out", "F");
        rule.addProperty("expr", "A +");
        rules.add(rule);
        spec.add("rules", rules);

        AiCircuitService.Result result = service(answer(
                names("A", "B"), named("F", "A & B"), null, spec))
                .generate("Build an AND circuit.");

        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(result.mismatchingRows().stream().anyMatch(
                line -> line.contains("Spec error at position") && line.contains("expected")));
    }
}
