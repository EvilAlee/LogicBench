package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.evilalee.gatelab.io.ComponentLibrary;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiSequentialCircuitServiceTest {
    @TempDir Path directory;

    private static final String COUNTER_REQUEST = "Create a 2-bit counter with reset.";
    private static final String ACCUMULATOR_REQUEST =
            "Create a 4 bit accumulator using four full adders and four D flip-flops.";

    private static final class ScriptedProvider implements AiProvider {
        private final List<String> responses;
        private final List<List<Turn>> conversations = new ArrayList<>();
        private final List<JsonObject> schemas = new ArrayList<>();
        private final List<String> prompts = new ArrayList<>();
        private int calls;

        private ScriptedProvider(String... responses) {
            this.responses = List.of(responses);
            if (this.responses.isEmpty()) throw new IllegalArgumentException("at least one response is required");
        }

        @Override
        public String complete(String systemPrompt, List<Turn> conversation, JsonObject schema) {
            calls++;
            prompts.add(systemPrompt);
            conversations.add(List.copyOf(conversation));
            schemas.add(schema.deepCopy());
            return responses.get(Math.min(calls - 1, responses.size() - 1));
        }
    }

    private List<ComponentDefinition> definitions() throws IOException {
        return ComponentLibrary.load(GateLibrary.load().all(), directory).all();
    }

    private AiCircuitService service(AiProvider provider, AiCache cache) throws IOException {
        return new AiCircuitService(provider, GateLibrary.load().all(), definitions(), cache, null);
    }

    @Test
    void correctTwoBitCounterVerifiesAndPlacesOneNamedClock() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(counterAnswer(false));
        AiCircuitService service = service(provider, null);

        AiCircuitService.Result result = service.generate(
                COUNTER_REQUEST, false, true, "", false, false, true);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status(), result.mismatchingRows().toString());
        assertEquals("VERIFIED: matches the sequence in the answer", result.summary());
        assertNotNull(result.sequenceData());
        assertEquals("CLK", result.sequenceData().clockName());
        assertEquals(5, result.sequenceData().steps().size());
        assertEquals(Value.ONE, result.sequenceData().steps().get(2).observed().get("Q0"));
        assertEquals(Value.ONE, result.sequenceData().steps().get(2).observed().get("Q1"));
        List<PlacedComponent> clocks = result.schematic().components().stream()
                .filter(component -> component.typeId().equals("clock")).toList();
        assertEquals(1, clocks.size());
        assertEquals("CLK", clocks.getFirst().label());

        JsonObject schema = provider.schemas.getFirst();
        assertEquals(List.of("kind", "summary", "clock", "inputs", "outputs",
                        "signals", "instances", "sequence"),
                strings(schema.getAsJsonArray("required")));
        assertFalse(schema.getAsJsonObject("properties").has("template"));
        assertFalse(schema.getAsJsonObject("properties").has("spec"));
        assertTrue(provider.prompts.getFirst().contains("Worked example 1"));
        assertTrue(provider.prompts.getFirst().contains("Worked example 2"));
        assertTrue(provider.prompts.getFirst().contains("TFF"));
        assertTrue(provider.prompts.getFirst().contains("D = Q XOR T"));
    }

    @Test
    void wrongExpectedValueProducesNamedFeedbackAndRetries() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(counterAnswer(true), counterAnswer(false));
        AiCircuitService service = service(provider, null);

        AiCircuitService.Result result = service.generate(
                COUNTER_REQUEST, false, true, "", false, false, true);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(2, provider.calls);
        String retry = provider.conversations.get(1).getLast().text();
        assertTrue(retry.contains("Step 3"), retry);
        assertTrue(retry.contains("Q1 claimed 0, circuit gives 1"), retry);
    }

    @Test
    void fourFaAndFourDffAccumulatorVerifiesExpectedRegisterValues() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(accumulatorAnswer());
        AiCircuitService service = service(provider, null);

        AiCircuitService.Result result = service.generate(
                ACCUMULATOR_REQUEST, false, true, "", false, false, true);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status(),
                result.mismatchingRows().toString());
        assertEquals(Map.of("FA", 4, "DFF", 4), result.componentUseCounts());
        assertEquals(8, result.schematic().componentInstances().size());
        assertEquals(3, result.sequenceData().steps().size());
        assertEquals(Value.ONE, result.sequenceData().steps().get(1).observed().get("Q0"));
        assertEquals(Value.ONE, result.sequenceData().steps().get(1).observed().get("Q1"));
        assertEquals(Value.ONE, result.sequenceData().steps().get(2).observed().get("Q3"));
        assertEquals(Value.ZERO, result.sequenceData().steps().get(2).observed().get("Q2"));
        assertEquals(Value.ZERO, result.sequenceData().steps().get(2).observed().get("Q1"));
        assertEquals(Value.ZERO, result.sequenceData().steps().get(2).observed().get("Q0"));
    }

    @Test
    void rawCombinationalFeedbackCycleIsRejectedByName() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(rawCycleAnswer());
        AiCircuitService service = service(provider, null);

        AiCircuitService.Result result = service.generate(
                COUNTER_REQUEST, false, true, "", false, false, true);

        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(String.join("\n", result.mismatchingRows())
                .contains("Combinational feedback cycle does not pass through DFF or DLATCH"));
    }

    @Test
    void tffIsRejectedWithDffToggleGuidance() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(tffAnswer());
        AiCircuitService service = service(provider, null);

        AiCircuitService.Result result = service.generate(
                "Create a T flip-flop based toggle register with reset.",
                false, true, "", false, false, true);

        String feedback = String.join("\n", result.mismatchingRows());
        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(feedback.contains("TFF cannot be reset from the all-X start"), feedback);
        assertTrue(feedback.contains("D = Q XOR T"), feedback);
    }

    @Test
    void missingResetPathLeavesStateXAndReturnsResetFeedback() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(missingResetAnswer());
        AiCircuitService service = service(provider, null);

        AiCircuitService.Result result = service.generate(
                "Create a resettable one bit register using a DFF.",
                false, true, "", false, false, true);

        String feedback = String.join("\n", result.mismatchingRows());
        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(feedback.contains("must reach the D path"), feedback);
        assertTrue(feedback.contains("circuit gives X"), feedback);
        assertEquals(Value.X, result.sequenceData().steps().getFirst().observed().get("Q"));
    }

    @Test
    void twoDifferentClockNamesAreRejected() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(twoClockAnswer());
        AiCircuitService service = service(provider, null);

        AiCircuitService.Result result = service.generate(
                COUNTER_REQUEST, false, true, "", false, false, true);

        String feedback = String.join("\n", result.mismatchingRows());
        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(feedback.contains("single clock 'CLK'"), feedback);
        assertTrue(feedback.contains("CLK2"), feedback);
    }

    @Test
    void pr56HybridAnswerIsRejectedWithRequiredSequentialFieldNames() throws Exception {
        ScriptedProvider provider = new ScriptedProvider("""
                {
                  "kind":"expressions",
                  "summary":"hybrid old answer",
                  "inputs":["RESET_N"],
                  "outputs":[{"name":"Q0","expression":"U0.Q"}],
                  "instances":[{"name":"U0","component":"DFF","args":["RESET_N","CLK"]}],
                  "template":{"generator":"ripple_adder","params":{"n":"2"}}
                }
                """);
        AiCircuitService service = service(provider, null);

        AiCircuitService.Result result = service.generate(
                COUNTER_REQUEST, false, true, "", false, false, true);

        String feedback = String.join("\n", result.mismatchingRows());
        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(feedback.contains("clock"), feedback);
        assertTrue(feedback.contains("signals"), feedback);
        assertTrue(feedback.contains("sequence"), feedback);
        assertTrue(feedback.contains("template"), feedback);
    }

    @Test
    void verifiedSequentialAnswerReplaysFromNormalCacheWithoutProviderCall() throws Exception {
        AiCache cache = new AiCache(directory);
        ScriptedProvider provider = new ScriptedProvider(counterAnswer(false));
        AiCircuitService live = service(provider, cache);
        AiCircuitService.Result first = live.generate(
                COUNTER_REQUEST, false, true, "", false, false, true);
        assertEquals(AiCircuitService.Status.VERIFIED, first.status());
        assertEquals(1, provider.calls);

        AiCircuitService offline = service(provider, cache);
        AiCircuitService.Result replay = offline.generate(
                COUNTER_REQUEST, true, true, "", false, false, true);

        assertEquals(AiCircuitService.Status.VERIFIED, replay.status());
        assertTrue(replay.fromCache());
        assertNotNull(replay.sequenceData());
        assertEquals(1, provider.calls);
        assertTrue(String.join("\n", replay.attemptLog()).contains("FROM CACHE"));
    }

    @Test
    void combinationalSchemaAndPromptKeepTheirExistingAnswerKinds() {
        JsonObject schema = AiCircuitService.responseSchema();
        assertEquals(List.of("expressions", "template"),
                strings(schema.getAsJsonObject("properties")
                        .getAsJsonObject("kind").getAsJsonArray("enum")));
        assertTrue(AiCircuitService.systemPrompt().contains("Template catalogue"));
        assertFalse(strings(schema.getAsJsonObject("properties")
                .getAsJsonObject("kind").getAsJsonArray("enum")).contains("sequential"));
    }

    private static String counterAnswer(boolean wrongExpectation) {
        Map<String, String> signals = linked(
                "FB0", "U0.Q",
                "FB1", "U1.Q");
        List<Instance> instances = List.of(
                new Instance("U0", "DFF", List.of("RESET_N & NOT(FB0)", "CLK")),
                new Instance("U1", "DFF", List.of("RESET_N & (FB1 ^ FB0)", "CLK")));
        Map<String, String> outputs = linked("Q0", "U0.Q", "Q1", "U1.Q");
        List<Step> steps = new ArrayList<>();
        steps.add(step(linked("RESET_N", 0), 1, linked("Q0", 0, "Q1", 0)));
        steps.add(step(linked("RESET_N", 1), 1, linked("Q0", 1, "Q1", 0)));
        steps.add(step(linked("RESET_N", 1), 1,
                linked("Q0", 0, "Q1", wrongExpectation ? 0 : 1)));
        steps.add(step(linked("RESET_N", 1), 1, linked("Q0", 1, "Q1", 1)));
        steps.add(step(linked("RESET_N", 1), 1, linked("Q0", 0, "Q1", 0)));
        return answer("2-bit modulo-four counter", "CLK", List.of("RESET_N"),
                signals, instances, outputs, steps);
    }

    private static String accumulatorAnswer() {
        Map<String, String> signals = linked(
                "Q0_FB", "U4.Q",
                "Q1_FB", "U5.Q",
                "Q2_FB", "U6.Q",
                "Q3_FB", "U7.Q");
        List<Instance> instances = List.of(
                new Instance("U0", "FA", List.of("Q0_FB", "IN0", "0")),
                new Instance("U1", "FA", List.of("Q1_FB", "IN1", "U0.COUT")),
                new Instance("U2", "FA", List.of("Q2_FB", "IN2", "U1.COUT")),
                new Instance("U3", "FA", List.of("Q3_FB", "IN3", "U2.COUT")),
                new Instance("U4", "DFF", List.of("RESET_N & U0.S", "CLK")),
                new Instance("U5", "DFF", List.of("RESET_N & U1.S", "CLK")),
                new Instance("U6", "DFF", List.of("RESET_N & U2.S", "CLK")),
                new Instance("U7", "DFF", List.of("RESET_N & U3.S", "CLK")));
        Map<String, String> outputs = linked(
                "Q0", "U4.Q", "Q1", "U5.Q", "Q2", "U6.Q", "Q3", "U7.Q");
        List<Step> steps = List.of(
                step(linked("RESET_N", 0, "IN3", 0, "IN2", 0, "IN1", 0, "IN0", 0),
                        1, linked("Q3", 0, "Q2", 0, "Q1", 0, "Q0", 0)),
                step(linked("RESET_N", 1, "IN3", 0, "IN2", 0, "IN1", 1, "IN0", 1),
                        1, linked("Q3", 0, "Q2", 0, "Q1", 1, "Q0", 1)),
                step(linked("RESET_N", 1, "IN3", 0, "IN2", 1, "IN1", 0, "IN0", 1),
                        1, linked("Q3", 1, "Q2", 0, "Q1", 0, "Q0", 0)));
        return answer("4-bit accumulator", "CLK",
                List.of("RESET_N", "IN3", "IN2", "IN1", "IN0"),
                signals, instances, outputs, steps);
    }

    private static String rawCycleAnswer() {
        Map<String, String> signals = linked(
                "FB0", "U0.Q",
                "FB1", "U1.Q",
                "BAD0", "BAD1",
                "BAD1", "BAD0");
        List<Instance> instances = List.of(
                new Instance("U0", "DFF", List.of("RESET_N & NOT(FB0)", "CLK")),
                new Instance("U1", "DFF", List.of("RESET_N & (FB1 ^ FB0)", "CLK")));
        return answer("raw cycle", "CLK", List.of("RESET_N"), signals, instances,
                linked("Q0", "U0.Q", "Q1", "U1.Q"),
                List.of(step(linked("RESET_N", 0), 1, linked("Q0", 0, "Q1", 0))));
    }

    private static String tffAnswer() {
        return answer("toggle register", "CLK", List.of("RESET_N", "T"),
                linked("FB", "U0.Q"),
                List.of(new Instance("U0", "TFF", List.of("T", "CLK"))),
                linked("Q", "U0.Q"),
                List.of(step(linked("RESET_N", 0, "T", 0), 1, linked("Q", 0))));
    }

    private static String missingResetAnswer() {
        return answer("reset path missing", "CLK", List.of("RESET_N"),
                linked("FB", "U0.Q"),
                List.of(new Instance("U0", "DFF", List.of("NOT(FB)", "CLK"))),
                linked("Q", "U0.Q", "READY", "RESET_N"),
                List.of(step(linked("RESET_N", 0), 1, linked("Q", 0, "READY", 0))));
    }

    private static String twoClockAnswer() {
        return answer("two clocks", "CLK", List.of("RESET_N"),
                linked("FB0", "U0.Q", "FB1", "U1.Q"),
                List.of(
                        new Instance("U0", "DFF", List.of("RESET_N & NOT(FB0)", "CLK")),
                        new Instance("U1", "DFF", List.of("RESET_N & (FB1 ^ FB0)", "CLK2"))),
                linked("Q0", "U0.Q", "Q1", "U1.Q"),
                List.of(step(linked("RESET_N", 0), 1, linked("Q0", 0, "Q1", 0))));
    }

    private static String answer(
            String summary, String clock, List<String> inputs,
            Map<String, String> signals, List<Instance> instances,
            Map<String, String> outputs, List<Step> sequence) {
        JsonObject root = new JsonObject();
        root.addProperty("kind", "sequential");
        root.addProperty("summary", summary);
        root.addProperty("clock", clock);
        JsonArray inputArray = new JsonArray();
        inputs.forEach(inputArray::add);
        root.add("inputs", inputArray);
        root.add("signals", named(signals));
        JsonArray instanceArray = new JsonArray();
        for (Instance instance : instances) {
            JsonObject item = new JsonObject();
            item.addProperty("name", instance.name());
            item.addProperty("component", instance.component());
            JsonArray args = new JsonArray();
            instance.args().forEach(args::add);
            item.add("args", args);
            instanceArray.add(item);
        }
        root.add("instances", instanceArray);
        root.add("outputs", named(outputs));
        JsonArray steps = new JsonArray();
        for (Step step : sequence) {
            JsonObject item = new JsonObject();
            item.add("set", assignments(step.set()));
            item.addProperty("cycles", step.cycles());
            item.add("expect", assignments(step.expect()));
            steps.add(item);
        }
        root.add("sequence", steps);
        return root.toString();
    }

    private static JsonArray named(Map<String, String> definitions) {
        JsonArray result = new JsonArray();
        definitions.forEach((name, expression) -> {
            JsonObject item = new JsonObject();
            item.addProperty("name", name);
            item.addProperty("expression", expression);
            result.add(item);
        });
        return result;
    }

    private static JsonObject assignments(Map<String, Integer> values) {
        JsonObject result = new JsonObject();
        values.forEach(result::addProperty);
        return result;
    }

    private static Step step(
            Map<String, Integer> set, int cycles, Map<String, Integer> expect) {
        return new Step(set, cycles, expect);
    }

    private static Map<String, String> linked(String... keyValues) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            result.put(keyValues[index], keyValues[index + 1]);
        }
        return result;
    }

    private static Map<String, Integer> linked(Object... keyValues) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            result.put((String) keyValues[index], (Integer) keyValues[index + 1]);
        }
        return result;
    }

    private static List<String> strings(JsonArray values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.getAsString()));
        return result;
    }

    private record Instance(String name, String component, List<String> args) { }
    private record Step(Map<String, Integer> set, int cycles, Map<String, Integer> expect) { }
}
