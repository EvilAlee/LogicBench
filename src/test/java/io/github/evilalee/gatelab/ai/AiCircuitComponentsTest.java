package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.evilalee.gatelab.io.ComponentLibrary;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiCircuitComponentsTest {
    @TempDir Path directory;

    private static final class ScriptedProvider implements AiProvider {
        private final List<String> responses;
        private final List<List<Turn>> conversations = new ArrayList<>();
        private int calls;
        private JsonObject schema;
        private String prompt = "";

        private ScriptedProvider(String... responses) {
            this.responses = List.of(responses);
            if (this.responses.isEmpty()) throw new IllegalArgumentException("at least one response is required");
        }

        @Override
        public String complete(String systemPrompt, List<Turn> conversation, JsonObject schema) {
            conversations.add(List.copyOf(conversation));
            this.schema = schema.deepCopy();
            this.prompt = systemPrompt;
            String response = responses.get(Math.min(calls, responses.size() - 1));
            calls++;
            return response;
        }
    }

    private List<ComponentDefinition> definitions() {
        return ComponentLibrary.load().all();
    }

    private AiCircuitService service(AiProvider provider, AiCache cache,
                                     List<ComponentDefinition> components) {
        return new AiCircuitService(provider, GateLibrary.load().all(),
                components, cache, null);
    }

    private static String hybridTemplateFullAdderAnswer() {
        return """
                {
                  "kind": "expressions",
                  "summary": "4-bit ripple-carry adder built from full adder blocks",
                  "template": {
                    "generator": "ripple_adder",
                    "params": {"n": "4", "carryIn": "yes"}
                  }
                }
                """;
    }

    private static String fourBitFullAdderAnswer() {
        JsonObject root = new JsonObject();
        root.addProperty("kind", "expressions");
        root.addProperty("summary", "4-bit ripple adder using four full-adder boxes");
        JsonArray inputs = new JsonArray();
        for (int bit = 3; bit >= 0; bit--) inputs.add("A" + bit);
        for (int bit = 3; bit >= 0; bit--) inputs.add("B" + bit);
        root.add("inputs", inputs);

        JsonArray signals = new JsonArray();
        for (int bit = 0; bit < 4; bit++) {
            JsonObject signal = new JsonObject();
            signal.addProperty("name", "C" + bit);
            signal.addProperty("expression", bit == 0 ? "0" : "U" + (bit - 1) + ".COUT");
            signals.add(signal);
        }
        root.add("signals", signals);

        JsonArray instances = new JsonArray();
        for (int bit = 0; bit < 4; bit++) {
            JsonObject instance = new JsonObject();
            instance.addProperty("name", "U" + bit);
            instance.addProperty("component", "FA");
            JsonArray args = new JsonArray();
            args.add("A" + bit);
            args.add("B" + bit);
            args.add("C" + bit);
            instance.add("args", args);
            instances.add(instance);
        }
        root.add("instances", instances);

        JsonArray outputs = new JsonArray();
        for (int bit = 0; bit < 4; bit++) {
            JsonObject output = new JsonObject();
            output.addProperty("name", "S" + bit);
            output.addProperty("expression", "U" + bit + ".S");
            outputs.add(output);
        }
        JsonObject carry = new JsonObject();
        carry.addProperty("name", "COUT");
        carry.addProperty("expression", "U3.COUT");
        outputs.add(carry);
        root.add("outputs", outputs);

        JsonObject buses = new JsonObject();
        buses.add("A", pins("A3", "A2", "A1", "A0"));
        buses.add("B", pins("B3", "B2", "B1", "B0"));
        buses.add("S", pins("S3", "S2", "S1", "S0"));
        buses.add("COUT", pins("COUT"));
        JsonArray rules = new JsonArray();
        rules.add(rule("S", "A + B"));
        rules.add(rule("COUT", "(A + B) >> 4"));
        JsonObject spec = new JsonObject();
        spec.add("buses", buses);
        spec.add("rules", rules);
        root.add("spec", spec);
        return root.toString();
    }

    private static List<String> toStrings(JsonArray values) {
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            result.add(values.get(index).getAsString());
        }
        return result;
    }

    private static JsonArray pins(String... values) {
        JsonArray result = new JsonArray();
        for (String value : values) result.add(value);
        return result;
    }

    private static JsonObject rule(String output, String expression) {
        JsonObject result = new JsonObject();
        result.addProperty("out", output);
        result.addProperty("expression", expression);
        return result;
    }

    private static String oneFaWithSignalAnswer() {
        JsonObject root = new JsonObject();
        root.addProperty("kind", "expressions");
        root.addProperty("summary", "one FA box with a named carry-in alias");
        root.add("inputs", pins("A", "B", "CIN"));
        JsonObject signal = new JsonObject();
        signal.addProperty("name", "C0");
        signal.addProperty("expression", "CIN");
        root.add("signals", pins());
        root.getAsJsonArray("signals").add(signal);
        JsonObject instance = new JsonObject();
        instance.addProperty("name", "U0");
        instance.addProperty("component", "FA");
        instance.add("args", pins("A", "B", "C0"));
        root.add("instances", pins());
        root.getAsJsonArray("instances").add(instance);
        JsonArray outputs = new JsonArray();
        outputs.add(output("SUM", "U0.S"));
        outputs.add(output("CARRY", "U0.COUT"));
        root.add("outputs", outputs);
        return root.toString();
    }

    private static JsonObject output(String name, String expression) {
        JsonObject result = new JsonObject();
        result.addProperty("name", name);
        result.addProperty("expression", expression);
        return result;
    }

    private static String componentAnswer(String component, String... args) {
        JsonObject root = new JsonObject();
        root.addProperty("kind", "expressions");
        root.addProperty("summary", "component validation test");
        root.add("inputs", pins("A", "B", "C"));
        JsonObject instance = new JsonObject();
        instance.addProperty("name", "U0");
        instance.addProperty("component", component);
        instance.add("args", pins(args));
        root.add("instances", pins());
        root.getAsJsonArray("instances").add(instance);
        JsonObject output = new JsonObject();
        output.addProperty("name", "F");
        output.addProperty("expression", "U0.S");
        root.add("outputs", new JsonArray());
        root.getAsJsonArray("outputs").add(output);
        return root.toString();
    }

    @Test
    void fourFullAdderInstancesFlattenAndVerifyEveryInputRow() throws Exception {
        String description = "Create a four bit adder using the full adder library component.";
        ScriptedProvider provider = new ScriptedProvider(fourBitFullAdderAnswer());
        AiCircuitService service = service(provider, null, definitions());

        AiCircuitService.Result result = service.generate(
                description, false, false, "", true, false, true);

        assertEquals(fourBitFullAdderAnswer(), service.lastRawAnswer());
        assertEquals(AiCircuitService.Status.VERIFIED, result.status(), result.mismatchingRows().toString());
        assertEquals(1, provider.calls);
        assertEquals(256, result.truthTable().rows().size());
        assertEquals(4, result.schematic().componentInstances().size());
        assertEquals(Map.of("FA", 4), result.componentUseCounts());
        assertTrue(result.expressionSource().contains("C1 := U0.COUT"));
        assertTrue(result.expressionSource().contains("U1 := FA(A1, B1, C1)"));
        assertTrue(result.expressionSource().contains("C3 := U2.COUT"));
        assertTrue(result.expressionSource().contains("U3 := FA(A3, B3, C3)"));
        assertTrue(result.expressionSource().contains("S3 = U3.S"));
        assertTrue(result.mismatchingRows().isEmpty());
        assertTrue(provider.prompt.contains("Library component catalog"));
        assertTrue(provider.prompt.contains("primaryOutput: none"));
        assertTrue(provider.prompt.indexOf("U0 := FA(A0, B0, C0)")
                < provider.prompt.indexOf("Library component catalog"));
        assertTrue(provider.schema.getAsJsonObject("properties").has("instances"));
        assertFalse(provider.schema.getAsJsonObject("properties").has("template"));
        assertEquals(List.of("kind", "summary", "inputs", "outputs", "instances"),
                toStrings(provider.schema.getAsJsonArray("required")));
        assertEquals(List.of("expressions"),
                toStrings(provider.schema.getAsJsonObject("properties")
                        .getAsJsonObject("kind").getAsJsonArray("enum")));
    }

    @Test
    void explicitFullAdderRequestRejectsHybridTemplateAndRetriesWithBoxedInstances()
            throws Exception {
        String description = "Create a four bit adder using the full adder library component.";
        ScriptedProvider provider = new ScriptedProvider(
                hybridTemplateFullAdderAnswer(), fourBitFullAdderAnswer());

        AiCircuitService.Result result = service(provider, null, definitions()).generate(
                description, false, false, "", true, true, true);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status(),
                result.attemptLog().toString());
        assertEquals(2, provider.calls);
        assertEquals(4, result.schematic().componentInstances().size());
        assertEquals(Map.of("FA", 4), result.componentUseCounts());
        assertFalse(provider.schema.getAsJsonObject("properties").has("template"));
        assertTrue(provider.prompt.startsWith("HIGHEST PRIORITY — REQUIRED LIBRARY BOXES:"));
        assertTrue(provider.conversations.get(1).stream().anyMatch(turn ->
                turn.text().contains("This request explicitly requires boxed library component(s) [FA]")));
        assertTrue(result.attemptLog().stream().noneMatch(line ->
                line.contains("Matched deterministic") || line.contains("Template: ripple_adder")));
    }

    @Test
    void earlierSignalsCanFeedBoxInstancesAndTheirCarryPinsCanBeSelected() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(oneFaWithSignalAnswer());
        AiCircuitService.Result result = service(provider, null, definitions()).generate(
                "Build one full adder box.", false, false, "", true, false, true);

        assertEquals(AiCircuitService.Status.BUILT, result.status(), result.mismatchingRows().toString());
        assertEquals(Map.of("FA", 1), result.componentUseCounts());
        assertEquals(1, result.schematic().componentInstances().size());
        assertTrue(result.expressionSource().contains("C0 := CIN"));
        assertTrue(result.expressionSource().contains("U0 := FA(A, B, C0)"));
        assertTrue(result.expressionSource().contains("CARRY = U0.COUT"));
        assertFalse(result.attemptLog().stream().anyMatch(line ->
                line.contains("Signal 'C0' is unused")));
    }

    @Test
    void userDefinedCombinationalComponentCanBeCataloguedBuiltAndVerified() throws Exception {
        ComponentDefinition fa = definitions().stream()
                .filter(definition -> definition.name().equals("FA"))
                .findFirst().orElseThrow();
        ComponentDefinition userFa = new ComponentDefinition(
                "USER_FA", "Custom user full adder", "User",
                fa.ports(), fa.primaryOutput(), fa.internalCircuit());
        List<ComponentDefinition> userLibrary = new ArrayList<>(definitions());
        userLibrary.add(userFa);
        String answer = fourBitFullAdderAnswer()
                .replace("\"component\":\"FA\"", "\"component\":\"USER_FA\"");
        ScriptedProvider provider = new ScriptedProvider(answer);

        AiCircuitService.Result result = service(provider, null, userLibrary).generate(
                "Build a four bit adder using my custom full adder component.", false,
                false, "", true, false, true);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status(), result.mismatchingRows().toString());
        assertEquals(Map.of("USER_FA", 4), result.componentUseCounts());
        assertTrue(provider.prompt.contains("USER_FA | IN: A, B, CIN | OUT: S, COUT"));
    }

    @Test
    void unknownComponentReportsValidNames() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(componentAnswer("NOT_A_PART", "A", "B", "C"));
        AiCircuitService.Result result = service(provider, null, definitions()).generate(
                "Use an unknown black box.", false, false, "", true, false, true);

        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(result.mismatchingRows().stream().anyMatch(message ->
                message.contains("Unknown component 'NOT_A_PART'")
                        && message.contains("FA") && message.contains("HA")));
    }

    @Test
    void wrongArgumentCountNamesExpectedInputPins() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(componentAnswer("FA", "A", "B"));
        AiCircuitService.Result result = service(provider, null, definitions()).generate(
                "Use a full adder box.", false, false, "", true, false, true);

        assertTrue(result.mismatchingRows().stream().anyMatch(message ->
                message.contains("expected 3 ordered input pins [A, B, CIN]")));
    }

    @Test
    void naturalLanguageAccumulatorRequestGetsSequentialAvailabilityExplanation() throws Exception {
        ScriptedProvider provider = new ScriptedProvider("{}");
        AiCircuitService.Result result = service(provider, null, definitions()).generate(
                "Create a 4 bit accumulator, D-flipflop is available and full adder is available use those.",
                false, false, "", true, false, true);

        assertEquals(AiCircuitService.Status.UNAVAILABLE, result.status());
        assertTrue(result.summary().contains("Sequential behavior is not available yet"));
        assertFalse(result.summary().contains("response fields invalid"));
        assertEquals(0, provider.calls);
    }

    @Test
    void sequentialComponentCallExplainsThatItIsNotAvailableYet() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(componentAnswer("DFF", "A", "B", "C"));
        AiCircuitService.Result result = service(provider, null, definitions()).generate(
                "Build a circuit from the model response.", false, false, "", true, false, true);

        assertTrue(result.mismatchingRows().stream().anyMatch(message ->
                message.contains("DFF is sequential: not available yet")));
    }

    @Test
    void componentOutputPinAndMultiOutputScalarErrorsAreNamed() throws Exception {
        JsonObject unknownPin = JsonParser.parseString(componentAnswer("FA", "A", "B", "C"))
                .getAsJsonObject();
        unknownPin.getAsJsonArray("outputs").get(0).getAsJsonObject()
                .addProperty("expression", "U0.CARRY");
        AiCircuitService.Result pinResult = service(
                new ScriptedProvider(unknownPin.toString()), null, definitions())
                .generate("Use a full adder box.", false, false, "", true, false, true);
        assertTrue(pinResult.mismatchingRows().stream().anyMatch(message ->
                message.contains("Unknown output pin 'CARRY'")
                        || message.contains("valid pins: [S, COUT]")));

        JsonObject scalar = JsonParser.parseString(componentAnswer("FA", "A", "B", "C"))
                .getAsJsonObject();
        scalar.getAsJsonArray("outputs").get(0).getAsJsonObject()
                .addProperty("expression", "U0");
        AiCircuitService.Result scalarResult = service(
                new ScriptedProvider(scalar.toString()), null, definitions())
                .generate("Use a full adder box.", false, false, "", true, false, true);
        assertTrue(scalarResult.mismatchingRows().stream().anyMatch(message ->
                message.toLowerCase().contains("multiple outputs")));
    }

    @Test
    void componentCacheRoundTripsAndRevalidatesTheInstanceList() throws Exception {
        AiCache cache = new AiCache(directory);
        String description = "unique cached full-adder component synthesis example";
        ScriptedProvider firstProvider = new ScriptedProvider(fourBitFullAdderAnswer());
        AiCircuitService.Result initial = service(firstProvider, cache, definitions()).generate(
                description, false, false, "", false, false, true);
        assertEquals(AiCircuitService.Status.VERIFIED, initial.status());

        ScriptedProvider neverCall = new ScriptedProvider("{}");
        AiCircuitService.Result cached = service(neverCall, cache, definitions()).generate(
                description, false, false, "", false, false, true);

        assertTrue(cached.fromCache());
        assertEquals(0, neverCall.calls);
        assertEquals(Map.of("FA", 4), cached.componentUseCounts());
        assertEquals(4, cached.schematic().componentInstances().size());
    }

    @Test
    void cachedInstancesAreIgnoredWhenTheirComponentIsRemovedFromTheLibrary() throws Exception {
        AiCache cache = new AiCache(directory);
        String description = "unique cached user black-box component response";
        ScriptedProvider first = new ScriptedProvider(fourBitFullAdderAnswer());
        AiCircuitService.Result original = service(first, cache, definitions()).generate(
                description, false, false, "", false, false, true);
        assertEquals(AiCircuitService.Status.VERIFIED, original.status());

        ScriptedProvider live = new ScriptedProvider("""
                {"summary":"fallback gate circuit","inputs":["A","B"],
                 "outputs":[{"name":"F","expression":"A & B"}]}
                """);
        List<ComponentDefinition> reduced = new ArrayList<>(definitions());
        reduced.removeIf(definition -> definition.name().equals("FA"));
        AiCircuitService.Result afterRemoval = service(live, cache, reduced).generate(
                description, false, false, "", false, false, true);

        assertFalse(afterRemoval.fromCache());
        assertEquals(1, live.calls);
        assertTrue(afterRemoval.attemptLog().stream().anyMatch(line ->
                line.contains("Ignored invalid user cache entry")
                        && line.contains("FA")));
    }

    @Test
    void disablingLibraryComponentsOmitsCatalogAndRejectsInstances() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(componentAnswer("FA", "A", "B", "C"));
        AiCircuitService.Result result = service(provider, null, definitions()).generate(
                "Use one full adder box.", false, false, "", true, false, false);

        assertFalse(provider.schema.getAsJsonObject("properties").has("instances"));
        assertFalse(provider.prompt.contains("Library component catalog"));
        assertTrue(result.mismatchingRows().stream().anyMatch(message ->
                message.contains("allowed fields") && message.contains("instances")));
    }

    @Test
    void unmatchedTemplateFieldProducesOneHelpfulValidationFailurePerAttempt() throws Exception {
        String template = "{\"kind\":\"template\",\"template\":"
                + "{\"generator\":\"ripple_adder\",\"params\":{\"n\":\"4\",\"carryIn\":\"no\"}}}";
        ScriptedProvider provider = new ScriptedProvider(template);
        AiCircuitService.Result result = service(provider, null, definitions()).generate(
                "Design a combinational arithmetic datapath with two operands.", false, false, "",
                true, false, true);

        assertEquals(AiCircuitLimits.MAX_ATTEMPTS, provider.calls);
        assertTrue(result.mismatchingRows().stream().anyMatch(message ->
                message.contains("no supported template matched")
                        && message.contains("Allowed fields")
                        && message.contains("Fields sent")));
        assertFalse(result.mismatchingRows().stream().anyMatch(message ->
                message.contains("missing=[outputs, inputs]")));
    }

    @Test
    void catalogFilterUsesOnlyComponentNamesAndDescriptionsForLargeCatalogs() {
        List<ComponentDefinition> library = new ArrayList<>(definitions());
        ComponentDefinition fullAdder = library.stream()
                .filter(definition -> definition.name().equals("FA")).findFirst().orElseThrow();
        for (int index = 0; index < 7; index++) {
            library.add(new ComponentDefinition("MACRO" + index, "Generic utility macro " + index,
                    "User", fullAdder.ports(), fullAdder.primaryOutput(), fullAdder.internalCircuit()));
        }

        String catalog = AiComponentCatalog.promptBlock(library, "Build a full adder");

        assertTrue(catalog.contains("FA | IN: A, B, CIN | OUT: S, COUT"));
        assertFalse(catalog.contains("MACRO0"));
        assertTrue(catalog.contains("DFF — sequential: not available yet"));
    }

    @Test
    void componentAwarePromptExplainsCombinationalCallsAndSequentialAvailability() {
        String prompt = AiCircuitService.systemPrompt(definitions(),
                "Build a full adder", true);
        assertTrue(prompt.contains("U0 := FA(A0, B0, C0)"));
        assertTrue(prompt.contains("U0.COUT"));
        assertTrue(prompt.contains("C1 := U0.COUT"));
        assertTrue(prompt.contains("U1 := FA(A1, B1, C1)"));
        assertFalse(prompt.contains("C1 = U0.COUT"));
        assertTrue(prompt.contains("DFF"));
        assertTrue(prompt.contains("sequential components are not available yet"));
        assertEquals(AiCircuitService.systemPrompt(),
                AiCircuitService.systemPrompt(definitions(), "Build a full adder", false));
    }

    @Test
    void lastRawAnswerIsAvailableAndDebugLogRedactsAndBoundsIt() throws Exception {
        String secret = "private-token";
        JsonObject answer = JsonParser.parseString(fourBitFullAdderAnswer()).getAsJsonObject();
        answer.addProperty("summary", secret + " 4-bit full-adder test");
        String raw = answer.toString();
        AiProvider provider = new AiProvider() {
            @Override
            public String complete(String prompt, List<Turn> conversation, JsonObject schema) {
                return raw;
            }

            @Override
            public String redact(String value) {
                return value == null ? "" : value.replace(secret, "[REDACTED]");
            }
        };
        AiDebugLog log = new AiDebugLog(directory,
                text -> text.replace(secret, "[REDACTED]"));
        AiCircuitService service = new AiCircuitService(
                provider, GateLibrary.load().all(), definitions(), null, log);

        AiCircuitService.Result result = service.generate(
                "Build a four bit full-adder component circuit.", false, false, "",
                true, false, true);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertTrue(service.lastRawAnswer().contains("[REDACTED]"));
        assertFalse(service.lastRawAnswer().contains(secret));
        String logged = Files.readString(log.file());
        assertTrue(logged.contains("[REDACTED]"));
        assertFalse(logged.contains(secret));
        assertTrue(Files.size(log.file()) <= AiDebugLog.MAX_ENTRY_BYTES);
    }

    @Test
    void debugLogRotatesAtOneMegabyte() throws Exception {
        AiDebugLog log = new AiDebugLog(directory, text -> text);
        Files.write(log.file(), new byte[(int) AiDebugLog.MAX_FILE_BYTES - 1]);
        log.append("fake-model", "raw provider answer");
        assertTrue(Files.exists(directory.resolve("ai_debug.log.1")));
        assertEquals(AiDebugLog.MAX_FILE_BYTES - 1,
                Files.size(directory.resolve("ai_debug.log.1")));
        assertTrue(Files.size(log.file()) < AiDebugLog.MAX_ENTRY_BYTES);
    }
}
