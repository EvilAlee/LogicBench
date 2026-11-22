package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import io.github.evilalee.gatelab.synth.TruthTable;
import io.github.evilalee.gatelab.synth.TruthTableGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiCircuitServiceTest {
    @TempDir Path directory;

    private record Output(String name, String expression) { }

    private static final class ScriptedProvider implements AiProvider {
        private final List<String> responses;
        private final List<List<Turn>> conversations = new ArrayList<>();
        private int calls;

        private ScriptedProvider(String... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public String complete(String prompt, List<Turn> conversation, JsonObject schema) {
            conversations.add(List.copyOf(conversation));
            String response = responses.get(Math.min(calls, responses.size() - 1));
            calls++;
            return response;
        }
    }

    private static AiCircuitService service(ScriptedProvider provider) {
        return new AiCircuitService(provider, GateLibrary.load().all());
    }

    private AiCircuitService service(AiProvider provider, AiCache cache, List<Long> delays) {
        return new AiCircuitService(provider, GateLibrary.load().all(), cache, delays::add);
    }

    private static String answer(List<String> inputs, List<Output> outputs, String... bits) {
        JsonObject root = new JsonObject();
        root.addProperty("summary", "test circuit");
        JsonArray inputArray = new JsonArray();
        inputs.forEach(inputArray::add);
        root.add("inputs", inputArray);

        JsonArray outputArray = new JsonArray();
        for (Output output : outputs) {
            JsonObject item = new JsonObject();
            item.addProperty("name", output.name());
            item.addProperty("expression", output.expression());
            outputArray.add(item);
        }
        root.add("outputs", outputArray);

        if (bits.length == outputs.size()) {
            JsonArray table = new JsonArray();
            for (int index = 0; index < outputs.size(); index++) {
                JsonObject row = new JsonObject();
                row.addProperty("output", outputs.get(index).name());
                row.addProperty("bits", bits[index]);
                table.add(row);
            }
            root.add("truthTable", table);
        }
        return root.toString();
    }

    private static String andAnswerWithSpec(String specificationExpression) {
        JsonObject root = JsonParser.parseString(andAnswer("")).getAsJsonObject();
        JsonObject spec = new JsonObject();
        spec.add("buses", new JsonObject());
        JsonArray rules = new JsonArray();
        JsonObject rule = new JsonObject();
        rule.addProperty("out", "F");
        rule.addProperty("expression", specificationExpression);
        rules.add(rule);
        spec.add("rules", rules);
        root.add("spec", spec);
        return root.toString();
    }

    private static String templateResponse(String generator, Map<String, String> params) {
        JsonObject root = new JsonObject();
        root.addProperty("kind", "template");
        JsonObject template = new JsonObject();
        template.addProperty("generator", generator);
        JsonObject parameterObject = new JsonObject();
        params.forEach((name, value) -> parameterObject.addProperty(name, value));
        template.add("params", parameterObject);
        root.add("template", template);
        return root.toString();
    }

    private static String andAnswer(String bits) {
        return answer(List.of("A", "B"), List.of(new Output("F", "A & B")), bits);
    }

    private static String answerWithSignals(
            List<String> inputs, List<Output> outputs,
            Map<String, String> signals, JsonObject specification) {
        JsonObject root = JsonParser.parseString(answer(inputs, outputs)).getAsJsonObject();
        JsonArray signalArray = new JsonArray();
        signals.forEach((name, expression) -> {
            JsonObject item = new JsonObject();
            item.addProperty("name", name);
            item.addProperty("expression", expression);
            signalArray.add(item);
        });
        root.add("signals", signalArray);
        if (specification != null) root.add("spec", specification);
        return root.toString();
    }

    private static JsonObject rippleAdderSpec() {
        JsonObject spec = new JsonObject();
        JsonObject buses = new JsonObject();
        buses.add("A", pins("A3", "A2", "A1", "A0"));
        buses.add("B", pins("B3", "B2", "B1", "B0"));
        buses.add("S", pins("S3", "S2", "S1", "S0"));
        spec.add("buses", buses);
        JsonArray rules = new JsonArray();
        rules.add(specRule("S", "A + B + CIN"));
        rules.add(specRule("COUT", "(A + B + CIN) >> 4"));
        spec.add("rules", rules);
        return spec;
    }

    private static JsonObject multiplierSpec() {
        JsonObject spec = new JsonObject();
        JsonObject buses = new JsonObject();
        buses.add("A", pins("A3", "A2", "A1", "A0"));
        buses.add("B", pins("B3", "B2", "B1", "B0"));
        buses.add("Y", pins("Y7", "Y6", "Y5", "Y4", "Y3", "Y2", "Y1", "Y0"));
        spec.add("buses", buses);
        JsonArray rules = new JsonArray();
        rules.add(specRule("Y", "A * B"));
        spec.add("rules", rules);
        return spec;
    }

    private static JsonArray pins(String... names) {
        JsonArray result = new JsonArray();
        for (String name : names) result.add(name);
        return result;
    }

    private static JsonObject specRule(String output, String expression) {
        JsonObject rule = new JsonObject();
        rule.addProperty("out", output);
        rule.addProperty("expression", expression);
        return rule;
    }

    private static List<String> bitAdderInputs() {
        return List.of("A3", "A2", "A1", "A0", "B3", "B2", "B1", "B0", "CIN");
    }

    private static Map<String, String> fourBitRippleSignals() {
        Map<String, String> signals = new LinkedHashMap<>();
        for (int bit = 0; bit < 4; bit++) signals.put("G" + bit, "A" + bit + " & B" + bit);
        for (int bit = 0; bit < 4; bit++) signals.put("P" + bit, "A" + bit + " ^ B" + bit);
        signals.put("C1", "G0 | (P0 & CIN)");
        signals.put("C2", "G1 | (P1 & C1)");
        signals.put("C3", "G2 | (P2 & C2)");
        signals.put("C4", "G3 | (P3 & C3)");
        return signals;
    }

    private static List<Output> fourBitRippleOutputs() {
        return List.of(
                new Output("S0", "P0 ^ CIN"), new Output("S1", "P1 ^ C1"),
                new Output("S2", "P2 ^ C2"), new Output("S3", "P3 ^ C3"),
                new Output("COUT", "C4"));
    }

    private static String flatFourBitRippleAdder() {
        StringBuilder source = new StringBuilder();
        String carry = "CIN";
        for (int bit = 0; bit < 4; bit++) {
            if (!source.isEmpty()) source.append('\n');
            source.append("S").append(bit).append(" = A").append(bit)
                    .append(" ^ B").append(bit).append(" ^ (").append(carry).append(")");
            carry = "((A" + bit + " & B" + bit + ") | ((A" + bit + " ^ B" + bit
                    + ") & (" + carry + ")))";
        }
        source.append("\nCOUT = ").append(carry);
        return source.toString();
    }

    private static Map<String, String> fourByFourMultiplierSignals() {
        Map<String, String> signals = new LinkedHashMap<>();
        for (int multiplierBit = 0; multiplierBit < 4; multiplierBit++) {
            for (int multiplicandBit = 0; multiplicandBit < 4; multiplicandBit++) {
                signals.put("P" + multiplicandBit + multiplierBit,
                        "A" + multiplicandBit + " & B" + multiplierBit);
            }
        }
        List<List<String>> rows = List.of(
                List.of("P00", "P10", "P20", "P30", "0", "0", "0", "0"),
                List.of("0", "P01", "P11", "P21", "P31", "0", "0", "0"),
                List.of("0", "0", "P02", "P12", "P22", "P32", "0", "0"),
                List.of("0", "0", "0", "P03", "P13", "P23", "P33", "0"));
        List<String> sumBits = rows.getFirst();
        for (int stage = 1; stage <= 3; stage++) {
            List<String> addend = rows.get(stage);
            List<String> nextSum = new ArrayList<>(8);
            String carry = "0";
            for (int bit = 0; bit < 8; bit++) {
                String left = sumBits.get(bit);
                String right = addend.get(bit);
                String propagate = "PR" + stage + "_" + bit;
                String sum = "SS" + stage + "_" + bit;
                signals.put(propagate, left + " ^ " + right);
                if (bit == 0) {
                    String firstCarry = "CS" + stage + "_1";
                    signals.put(firstCarry, propagate + " & 0");
                    signals.put(sum, propagate);
                    carry = firstCarry;
                } else if (bit < 7) {
                    String generate = "GN" + stage + "_" + bit;
                    String nextCarry = "CS" + stage + "_" + (bit + 1);
                    signals.put(generate, left + " & " + right);
                    signals.put(nextCarry, generate + " | (" + propagate + " & " + carry + ")");
                    signals.put(sum, propagate + " ^ " + carry);
                    carry = nextCarry;
                } else {
                    signals.put(sum, propagate + " ^ " + carry);
                }
                nextSum.add(sum);
            }
            sumBits = List.copyOf(nextSum);
        }
        return signals;
    }

    private static List<Output> fourByFourMultiplierOutputs() {
        List<Output> outputs = new ArrayList<>();
        for (int bit = 0; bit < 8; bit++) outputs.add(new Output("Y" + bit, "SS3_" + bit));
        return List.copyOf(outputs);
    }

    private static Map<Map<String, Boolean>, Map<String, Value>> truthRowsByInputs(TruthTable table) {
        Map<Map<String, Boolean>, Map<String, Value>> result = new LinkedHashMap<>();
        for (TruthTable.Row row : table.rows()) {
            Map<String, Boolean> inputs = new LinkedHashMap<>();
            for (int index = 0; index < table.inputNames().size(); index++) {
                inputs.put(table.inputNames().get(index), row.inputValue(index));
            }
            Map<String, Value> outputs = new LinkedHashMap<>();
            for (int index = 0; index < table.outputNames().size(); index++) {
                outputs.put(table.outputNames().get(index), row.outputValues().get(index));
            }
            result.put(Map.copyOf(inputs), Map.copyOf(outputs));
        }
        return result;
    }

    @Test
    void recognizedTemplateIsVerifiedWithoutCallingTheProvider() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(andAnswer(""));
        AiCircuitService.Result result = service(provider).generate("4-bit adder with carry");

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(0, provider.calls);
        assertEquals(9, result.truthTable().inputNames().size());
        assertEquals(512, result.truthTable().rows().size());
        assertTrue(result.summary().startsWith("Template: ripple_adder("));
        assertTrue(result.summary().contains("verified against its integer spec"));
    }

    @Test
    void regenerateBypassesTemplatesAndCallsTheProvider() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(andAnswer(""));

        AiCircuitService.Result result = service(provider).generate(
                "4-bit adder", false, true, "", true);

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertEquals(1, provider.calls);
    }

    @Test
    void disablingTemplatesRoutesRecognizedPhrasesToTheProvider() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(andAnswer(""));

        AiCircuitService.Result result = service(provider).generate(
                "4-bit adder", false, true, "", false, false);

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertEquals(1, provider.calls);
    }

    @Test
    void recognizedOutOfRangeTemplateProducesANamedMessageWithoutClamping() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(andAnswer(""));

        AiCircuitService.Result result = service(provider).generate("5-bit adder");

        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(result.summary().contains("supported: 1 to 4 bits"));
        assertEquals(0, provider.calls);
    }

    @Test
    void schemaAndPromptDescribeBothAnswerKindsAndAllTemplates() {
        JsonObject schema = AiCircuitService.responseSchema();
        JsonObject properties = schema.getAsJsonObject("properties");
        JsonObject kind = properties.getAsJsonObject("kind");
        assertEquals("kind", schema.getAsJsonArray("required").get(0).getAsString());
        assertEquals(List.of("expressions", "template"),
                kind.getAsJsonArray("enum").asList().stream()
                        .map(element -> element.getAsString()).toList());
        assertTrue(properties.getAsJsonObject("template").getAsJsonArray("required")
                .asList().stream().map(element -> element.getAsString()).toList()
                .containsAll(List.of("generator", "params")));
        String prompt = AiCircuitService.systemPrompt();
        for (String generator : List.of("ripple_adder", "subtractor", "comparator",
                "array_multiplier", "mux", "decoder", "parity", "majority",
                "gray_to_binary", "binary_to_gray")) {
            assertTrue(prompt.contains(generator), generator);
        }
    }

    @Test
    void modelTemplateAnswerBuildsAndVerifiesThroughTheNormalPipeline() throws Exception {
        JsonObject modelTemplate = JsonParser.parseString(templateResponse(
                "ripple_adder", Map.of("n", "2", "carryIn", "no"))).getAsJsonObject();
        // The generator remains authoritative even if a model adds answer-like fields.
        modelTemplate.addProperty("summary", "untrusted model summary");
        JsonArray untrustedInputs = new JsonArray();
        untrustedInputs.add("MODEL_INPUT");
        modelTemplate.add("inputs", untrustedInputs);
        ScriptedProvider provider = new ScriptedProvider(modelTemplate.toString());
        String description = "Design a compact arithmetic datapath for two unsigned operands.";

        AiCircuitService.Result result = service(provider).generate(description);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(1, provider.calls);
        assertEquals(4, result.truthTable().inputNames().size());
        assertEquals(16, result.truthTable().rows().size());
        assertTrue(result.summary().startsWith("Template: ripple_adder("));
        assertEquals(0, result.mismatchingRows().size());
    }

    @Test
    void invalidTemplateRangeProducesNamedRetryFeedbackThenVerifies() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(
                templateResponse("ripple_adder", Map.of("n", "5", "carryIn", "no")),
                templateResponse("ripple_adder", Map.of("n", "2", "carryIn", "no")));

        AiCircuitService.Result result = service(provider).generate(
                "Design a compact arithmetic datapath for two unsigned operands.");

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(2, result.attemptCount());
        assertEquals(2, provider.calls);
        String retry = provider.conversations.get(1).stream()
                .map(Turn::text).reduce("", (left, right) -> left + "\n" + right);
        assertTrue(retry.contains("parameter 'n' for ripple_adder"), retry);
        assertTrue(retry.contains("got 5"), retry);
    }

    @Test
    void unknownTemplateGeneratorProducesNamedRetryFeedbackThenVerifies() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(
                templateResponse("mystery_circuit", Map.of("n", "2")),
                templateResponse("ripple_adder", Map.of("n", "2", "carryIn", "no")));

        AiCircuitService.Result result = service(provider).generate(
                "Design a compact arithmetic datapath for two unsigned operands.");

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(2, provider.calls);
        String retry = provider.conversations.get(1).stream()
                .map(Turn::text).reduce("", (left, right) -> left + "\n" + right);
        assertTrue(retry.contains("unknown generator 'mystery_circuit'"), retry);
    }

    @Test
    void unexpectedTemplateParameterProducesNamedRetryFeedbackThenVerifies() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(
                templateResponse("ripple_adder",
                        Map.of("n", "2", "carryIn", "no", "width", "2")),
                templateResponse("ripple_adder", Map.of("n", "2", "carryIn", "no")));

        AiCircuitService.Result result = service(provider).generate(
                "Design a compact arithmetic datapath for two unsigned operands.");

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(2, provider.calls);
        String retry = provider.conversations.get(1).stream()
                .map(Turn::text).reduce("", (left, right) -> left + "\n" + right);
        assertTrue(retry.contains("unexpected=[width]"), retry);
    }

    @Test
    void expressionsKindRemainsCompatibleWithFreeFormAnswers() throws Exception {
        JsonObject expressionAnswer = JsonParser.parseString(andAnswerWithSpec("A & B"))
                .getAsJsonObject();
        expressionAnswer.addProperty("kind", "expressions");
        ScriptedProvider provider = new ScriptedProvider(expressionAnswer.toString());

        AiCircuitService.Result result = service(provider).generate(
                "Build an unrelated two-input boolean function.");

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(1, provider.calls);
        assertEquals(4, result.truthTable().rows().size());
    }

    @Test
    void verifiedModelTemplateIsStoredAndRevalidatedFromCache() throws Exception {
        AiCache cache = new AiCache(directory);
        List<Long> delays = new ArrayList<>();
        ScriptedProvider provider = new ScriptedProvider(templateResponse(
                "ripple_adder", Map.of("n", "2", "carryIn", "no")));
        String description = "Design a cache-test arithmetic datapath for two operands.";

        AiCircuitService.Result live = service(provider, cache, delays).generate(description);
        assertEquals(AiCircuitService.Status.VERIFIED, live.status());
        assertEquals(1, provider.calls);
        assertEquals(1, cache.lookupCandidates(description).size());

        AiProvider neverCall = (prompt, conversation, schema) -> {
            fail("verified template should be revalidated from cache");
            return "";
        };
        AiCircuitService.Result cached = service(neverCall, cache, delays).generate(description);
        assertEquals(AiCircuitService.Status.VERIFIED, cached.status());
        assertTrue(cached.fromCache());
        assertTrue(cached.summary().startsWith("Template: ripple_adder("));
    }

    @Test
    void fourBitRippleAdderUsesOrderedGeneratePropagateAndCarrySignalsAndMatchesFlatCircuit()
            throws Exception {
        String raw = answerWithSignals(bitAdderInputs(), fourBitRippleOutputs(),
                fourBitRippleSignals(), rippleAdderSpec());
        ScriptedProvider provider = new ScriptedProvider(raw);

        AiCircuitService.Result result = service(provider).generate("Build a 4-bit ripple-carry adder.", false, true, "", false, false);
        TruthTable flat = TruthTableGenerator.generate(
                ExpressionCompiler.compile(flatFourBitRippleAdder(), false, true).netlist());

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(1, provider.calls);
        assertEquals(12, result.signals().size());
        assertEquals(truthRowsByInputs(flat), truthRowsByInputs(result.truthTable()));
        assertEquals(List.of("G0", "G1", "G2", "G3", "P0", "P1", "P2", "P3", "C1", "C2", "C3", "C4"),
                result.signals().stream().map(expression -> expression.name()).toList());
    }

    @Test
    void fourByFourMultiplierWithPartialProductSignalsPassesExecutableSpec() throws Exception {
        List<String> inputs = List.of("A3", "A2", "A1", "A0", "B3", "B2", "B1", "B0");
        String raw = answerWithSignals(inputs, fourByFourMultiplierOutputs(),
                fourByFourMultiplierSignals(), multiplierSpec());
        assertTrue(raw.contains("& 0"), "the multiplier fixture must contain a literal-zero term");
        ScriptedProvider provider = new ScriptedProvider(raw);

        AiCircuitService.Result result = service(provider).generate("Build a 4x4 multiplier.", false, true, "", false, false);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(1, provider.calls);
        assertEquals(103, result.signals().size());
        assertEquals(256, result.truthTable().rows().size());
        assertEquals(inputs.size(), result.netlist().inputs().size(),
                "literal constants must not count as declared primary inputs");
        assertTrue(result.netlist().inputs().containsAll(inputs),
                "all declared primary inputs must remain in the input list");
        assertFalse(result.layout().schematic().components().stream()
                .anyMatch(component -> component.typeId().equals("input")
                        && component.label().equals("0")),
                "literal zero must not become a pokeable Input pin");
        for (String constantId : result.layout().constantComponentIds().values()) {
            assertEquals("constant", result.layout().schematic().components().stream()
                    .filter(component -> component.id().equals(constantId))
                    .findFirst().orElseThrow().typeId());
        }
    }

    @Test
    void signalDependencyMustReferOnlyToInputsAndEarlierSignals() throws Exception {
        Map<String, String> forward = new LinkedHashMap<>();
        forward.put("C2", "C1 ^ A");
        forward.put("C1", "A & A");
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("C1", "A & A");
        ordered.put("C2", "C1 ^ A");
        ScriptedProvider provider = new ScriptedProvider(
                answerWithSignals(List.of("A"), List.of(new Output("F", "C2")), forward, null),
                answerWithSignals(List.of("A"), List.of(new Output("F", "C2")), ordered, null));

        AiCircuitService.Result result = service(provider).generate("Build an intermediate-signal chain.");

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertEquals(2, provider.calls);
        assertTrue(String.join("\\n", provider.conversations.get(1).stream()
                .map(Turn::text).toList()).contains("references signal 'C1' before it is defined"));
    }

    @Test
    void malformedSignalSyntaxKeepsPositionFeedbackAndRetries() throws Exception {
        Map<String, String> malformed = new LinkedHashMap<>();
        malformed.put("C1", "A & (B");
        Map<String, String> corrected = new LinkedHashMap<>();
        corrected.put("C1", "A & B");
        ScriptedProvider provider = new ScriptedProvider(
                answerWithSignals(List.of("A", "B"), List.of(new Output("F", "C1")), malformed, null),
                answerWithSignals(List.of("A", "B"), List.of(new Output("F", "C1")), corrected, null));

        AiCircuitService.Result result = service(provider).generate("Build a circuit with a named signal.");

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertEquals(2, provider.calls);
        assertTrue(String.join("\\n", provider.conversations.get(1).stream()
                .map(Turn::text).toList()).contains("Signal 'C1'"));
        assertTrue(String.join("\\n", provider.conversations.get(1).stream()
                .map(Turn::text).toList()).contains("position"));
    }

    @Test
    void unusedIntermediateSignalsProduceNonFatalWarnings() throws Exception {
        Map<String, String> signals = new LinkedHashMap<>();
        signals.put("USED", "A ^ B");
        signals.put("UNUSED", "A & B");
        ScriptedProvider provider = new ScriptedProvider(answerWithSignals(
                List.of("A", "B"), List.of(new Output("F", "USED")), signals, null));

        AiCircuitService.Result result = service(provider).generate("Build a circuit using a named signal.");

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertTrue(result.attemptLog().stream()
                .anyMatch(line -> line.contains("Warning: Signal 'UNUSED' is unused")));
    }

    @Test
    void noSignalsLeavesThePreviouslyCompiledNetlistUnchanged() throws Exception {
        AiCircuitService.Result result = service(new ScriptedProvider(andAnswer("")))
                .generate("Build a two-input AND gate with inputs A and B.");
        var expected = ExpressionCompiler.compile("F = A & B", false, true).netlist();

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertTrue(result.signals().isEmpty());
        assertEquals(expected, result.netlist());
    }

    @Test
    void signalCountAndIndividualExpressionLengthLimitsAreEnforced() throws Exception {
        Map<String, String> tooManySignals = new LinkedHashMap<>();
        for (int index = 0; index <= AiCircuitLimits.MAX_SIGNALS; index++) {
            tooManySignals.put("S" + index, "A");
        }
        ScriptedProvider signalsProvider = new ScriptedProvider(answerWithSignals(
                List.of("A"), List.of(new Output("F", "A")), tooManySignals, null));
        AiCircuitService.Result signalsResult = service(signalsProvider).generate("Build a circuit.");
        assertEquals(AiCircuitService.Status.UNVERIFIED, signalsResult.status());
        assertTrue(String.join("\\n", signalsResult.attemptLog()).contains(
                "signals has " + (AiCircuitLimits.MAX_SIGNALS + 1) + " entries; maximum is "
                        + AiCircuitLimits.MAX_SIGNALS));

        ScriptedProvider expressionProvider = new ScriptedProvider(answer(
                List.of("A"), List.of(new Output("F", "A".repeat(
                        AiCircuitLimits.MAX_EXPRESSION_LENGTH + 1)))));
        AiCircuitService.Result expressionResult = service(expressionProvider).generate("Build a circuit.");
        assertEquals(AiCircuitService.Status.UNVERIFIED, expressionResult.status());
        assertTrue(String.join("\\n", expressionResult.attemptLog()).contains(
                "split the formula into named signals"));
    }

    @Test
    void modelClaimedTruthTableIsIgnoredAndAnAnswerWithoutSpecIsBuilt() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(andAnswer("0000"));
        AiCircuitService.Result result = service(provider).generate(
                "Build a two-input AND gate with inputs A and B.");

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertEquals(1, result.attemptCount());
        assertEquals(1, provider.calls);
        assertNotNull(result.truthTable());
        assertTrue(result.attemptLog().stream()
                .anyMatch(line -> line.contains("no independent check")));
        assertFalse(AiCircuitService.responseSchema().getAsJsonObject("properties")
                .has("truthTable"));
    }

    @Test
    void legacyClaimedRowsDoNotCauseRetriesOrAFalseVerification() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(
                andAnswer("0000"), andAnswer("1111"), andAnswer("0001"));
        AiCircuitService.Result result = service(provider).generate(
                "Build a two-input AND gate with inputs A and B.");

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertEquals(1, result.attemptCount());
        assertEquals(1, provider.calls);
        assertTrue(result.mismatchingRows().isEmpty());
    }

    @Test
    void transient503ThenSuccessUsesOneVerificationAttemptAndRetriesSameRequest() throws Exception {
        int[] calls = {0};
        List<Long> delays = new ArrayList<>();
        AiProvider provider = (prompt, conversation, schema) -> {
            if (calls[0]++ == 0) throw new IOException("Gemini server error (HTTP 503).");
            return andAnswer("0001");
        };

        AiCircuitService.Result result = service(provider, null, delays).generate(
                "Build a two-input AND gate with inputs A and B.");

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertEquals(1, result.attemptCount());
        assertEquals(2, calls[0]);
        assertEquals(List.of(2_000L), delays);
    }

    @Test
    void repeated503FallsBackToCacheAndShowsUnavailableNotice() throws Exception {
        AiCache cache = new AiCache(directory);
        String description = "Build a two-input AND gate with inputs A and B.";
        int[] calls = {0};
        List<Long> delays = new ArrayList<>();
        AiProvider provider = (prompt, conversation, schema) -> {
            calls[0]++;
            cache.storeVerified(description, andAnswer("0001"));
            throw new IOException("Gemini server error (HTTP 503).");
        };

        AiCircuitService.Result result = service(provider, cache, delays).generate(description);

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertTrue(result.fromCache());
        assertEquals(0, result.attemptCount());
        assertEquals(3, calls[0]);
        assertEquals(List.of(2_000L, 6_000L), delays);
        assertTrue(result.attemptLog().stream().anyMatch(line ->
                line.startsWith("AI unavailable (HTTP 503, model default) - showing cached result")));
    }

    @Test
    void repeated503WithoutCacheReturnsUnavailableRatherThanUnverified() throws Exception {
        int[] calls = {0};
        List<Long> delays = new ArrayList<>();
        AiProvider provider = (prompt, conversation, schema) -> {
            calls[0]++;
            throw new IOException("Gemini server error (HTTP 503).");
        };

        AiCircuitService.Result result = service(provider, null, delays).generate(
                "Build a two-input AND gate with inputs A and B.");

        assertEquals(AiCircuitService.Status.UNAVAILABLE, result.status());
        assertEquals(0, result.attemptCount());
        assertNull(result.netlist());
        assertTrue(result.summary().startsWith("AI unavailable: "));
        assertTrue(result.summary().contains("HTTP 503"));
        assertEquals(3, calls[0]);
        assertEquals(List.of(2_000L, 6_000L), delays);
    }

    @Test
    void repeated429ReturnsTheRequestedRateLimitHint() throws Exception {
        int[] calls = {0};
        List<Long> delays = new ArrayList<>();
        AiProvider provider = (prompt, conversation, schema) -> {
            calls[0]++;
            throw new IOException("Gemini rate limit or quota reached (HTTP 429). "
                    + "Rate limit reached, try again in a minute or switch model in ai.json.");
        };

        AiCircuitService.Result result = service(provider, null, delays).generate(
                "Build a two-input AND gate with inputs A and B.");

        assertEquals(AiCircuitService.Status.UNAVAILABLE, result.status());
        assertTrue(result.summary().contains(
                "Rate limit reached, try again in a minute or switch model in ai.json"));
        assertEquals(3, calls[0]);
        assertEquals(List.of(2_000L, 6_000L), delays);
    }

    @Test
    void primary404IsSkippedAndFallbackModelCanAnswer() throws Exception {
        List<String> calls = new ArrayList<>();
        AiProvider provider = new AiProvider() {
            @Override
            public String complete(String prompt, List<Turn> conversation, JsonObject schema) {
                fail("model-aware overload should be used");
                return "";
            }

            @Override
            public List<String> models() {
                return List.of("missing-model", "working-model");
            }

            @Override
            public String complete(String model, String prompt,
                                   List<Turn> conversation, JsonObject schema) throws IOException {
                calls.add(model);
                if (model.equals("missing-model")) {
                    throw new ProviderFailure(ProviderFailure.Kind.MODEL_UNAVAILABLE,
                            "Gemini model not found (HTTP 404).", model, 404);
                }
                return andAnswer("0001");
            }
        };

        AiCircuitService.Result result = service(provider, null, new ArrayList<>()).generate(
                "Build a two-input AND gate with inputs A and B.");

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertEquals(1, result.attemptCount());
        assertEquals("working-model", result.answeredBy());
        assertEquals(List.of("missing-model", "working-model"), calls);
        assertEquals(List.of("missing-model: HTTP 404", "working-model: 200 OK"),
                result.modelAttempts());
    }

    @Test
    void a404ModelStaysSkippedAcrossAllVerificationAttempts() throws Exception {
        int[] missingCalls = {0};
        int[] workingCalls = {0};
        AiProvider provider = new AiProvider() {
            @Override
            public String complete(String prompt, List<Turn> conversation, JsonObject schema) {
                fail("model-aware overload should be used");
                return "";
            }

            @Override
            public List<String> models() {
                return List.of("retired-model", "working-model");
            }

            @Override
            public String complete(String model, String prompt,
                                   List<Turn> conversation, JsonObject schema) throws IOException {
                if (model.equals("retired-model")) {
                    missingCalls[0]++;
                    throw new ProviderFailure(ProviderFailure.Kind.MODEL_UNAVAILABLE,
                            "Gemini model not found (HTTP 404).", model, 404);
                }
                workingCalls[0]++;
                return andAnswerWithSpec(workingCalls[0] == 1 ? "0" : "A & B");
            }
        };

        AiCircuitService.Result result = service(provider, null, new ArrayList<>()).generate(
                "Build a two-input AND gate with inputs A and B.");

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(2, result.attemptCount());
        assertEquals(1, missingCalls[0]);
        assertEquals(2, workingCalls[0]);
        assertEquals("working-model", result.answeredBy());
        assertEquals(1, result.modelAttempts().stream()
                .filter(line -> line.startsWith("retired-model:")).count());
    }

    @Test
    void keyRejection401And403StopWithoutRetryingOrTryingFallbackModels() throws Exception {
        for (int status : List.of(401, 403)) {
            List<String> calls = new ArrayList<>();
            List<Long> delays = new ArrayList<>();
            AiProvider provider = new AiProvider() {
                @Override
                public String complete(String prompt, List<Turn> conversation, JsonObject schema) {
                    fail("model-aware overload should be used");
                    return "";
                }

                @Override
                public List<String> models() {
                    return List.of("primary-model", "fallback-model");
                }

                @Override
                public String complete(String model, String prompt,
                                       List<Turn> conversation, JsonObject schema) throws IOException {
                    calls.add(model);
                    throw new ProviderFailure(ProviderFailure.Kind.KEY_REJECTED,
                            "API key rejected (HTTP " + status + ").", model, status);
                }
            };

            AiCircuitService.Result result = service(provider, null, delays).generate("Make a circuit.");

            assertEquals(AiCircuitService.Status.UNAVAILABLE, result.status());
            assertTrue(result.summary().contains("API key rejected (HTTP " + status + ")"));
            assertEquals(List.of("primary-model"), calls);
            assertTrue(delays.isEmpty());
            assertEquals(0, result.attemptCount());
        }
    }

    @Test
    void cancelDuringRetryWaitPropagatesImmediately() {
        int[] calls = {0};
        List<Long> waits = new ArrayList<>();
        AiProvider provider = new AiProvider() {
            @Override
            public String complete(String prompt, List<Turn> conversation, JsonObject schema)
                    throws IOException {
                calls[0]++;
                throw new ProviderFailure(ProviderFailure.Kind.RETRYABLE,
                        "Gemini server error (HTTP 503).", "primary-model", 503);
            }

            @Override
            public List<String> models() {
                return List.of("primary-model");
            }
        };
        AiCircuitService.Sleeper cancellingSleeper = milliseconds -> {
            waits.add(milliseconds);
            throw new InterruptedException("cancelled");
        };
        AiCircuitService service = new AiCircuitService(
                provider, GateLibrary.load().all(), null, cancellingSleeper);

        assertThrows(InterruptedException.class, () -> service.generate("Make a circuit."));
        assertEquals(1, calls[0]);
        assertEquals(List.of(2_000L), waits);
    }

    @Test
    void transportFailureDuringVerificationRetryReturnsUnavailableNotUnverified() throws Exception {
        int[] calls = {0};
        List<Long> delays = new ArrayList<>();
        AiProvider provider = new AiProvider() {
            @Override
            public String complete(String prompt, List<Turn> conversation, JsonObject schema) {
                fail("model-aware overload should be used");
                return "";
            }

            @Override
            public List<String> models() {
                return List.of("working-model");
            }

            @Override
            public String complete(String model, String prompt,
                                   List<Turn> conversation, JsonObject schema) throws IOException {
                if (calls[0]++ == 0) return andAnswerWithSpec("0");
                throw new ProviderFailure(ProviderFailure.Kind.RETRYABLE,
                        "Gemini server error (HTTP 503).", model, 503);
            }
        };

        AiCircuitService.Result result = service(provider, null, delays).generate(
                "Build a two-input AND gate with inputs A and B.");

        assertEquals(AiCircuitService.Status.UNAVAILABLE, result.status());
        assertEquals(1, result.attemptCount());
        assertNull(result.netlist());
        assertEquals(4, calls[0]);
        assertEquals(List.of(2_000L, 6_000L), delays);
        assertTrue(result.summary().startsWith("AI unavailable:"));
    }

    @Test
    void parseErrorFeedbackIncludesTheParserPosition() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(
                answer(List.of("A", "B"), List.of(new Output("F", "A &")), "0001"),
                andAnswer("0001"));
        AiCircuitService.Result result = service(provider).generate(
                "Build a two-input AND gate with inputs A and B.");

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertTrue(provider.conversations.get(1).stream()
                .anyMatch(turn -> turn.text().contains("parse error at position 3")));
    }

    @Test
    void inputAndOutputSchemaLimitsAreRejected() throws Exception {
        List<String> elevenInputs = List.of(
                "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K");
        String tooManyInputs = answer(elevenInputs,
                List.of(new Output("Fout", "A&B&C&D&E&F&G&H&I&J&K")), "");
        ScriptedProvider inputProvider = new ScriptedProvider(tooManyInputs);
        AiCircuitService.Result inputResult = service(inputProvider).generate("Please build a circuit.");
        assertEquals(AiCircuitService.Status.UNVERIFIED, inputResult.status());
        assertTrue(inputResult.attemptLog().stream().anyMatch(
                line -> line.contains("maximum is " + AiCircuitLimits.MAX_INPUTS)));

        List<Output> seventeenOutputs = new ArrayList<>();
        String[] tables = new String[17];
        for (int index = 0; index < 17; index++) {
            seventeenOutputs.add(new Output("O" + index, "A"));
            tables[index] = "";
        }
        ScriptedProvider outputProvider = new ScriptedProvider(
                answer(List.of("A"), seventeenOutputs, tables));
        AiCircuitService.Result outputResult = service(outputProvider).generate("Please build a circuit.");
        assertEquals(AiCircuitService.Status.UNVERIFIED, outputResult.status());
        assertTrue(outputResult.attemptLog().stream().anyMatch(
                line -> line.contains("maximum is " + AiCircuitLimits.MAX_OUTPUTS)));
    }

    @Test
    void circuitsOverFourHundredGatesAreRejectedBeforeLayout() throws Exception {
        List<String> inputs = List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J");
        Map<String, String> signals = new LinkedHashMap<>();
        signals.put("S0", "A ^ B ^ C ^ D ^ E ^ F ^ G ^ H ^ I ^ J");
        for (int index = 1; index < 60; index++) {
            signals.put("S" + index,
                    "S" + (index - 1) + " ^ A ^ B ^ C ^ D ^ E ^ F ^ G ^ H");
        }
        String raw = answerWithSignals(inputs,
                List.of(new Output("Result", "S59")), signals, null);
        assertTrue(signals.values().stream()
                .allMatch(expression -> expression.length() <= AiCircuitLimits.MAX_EXPRESSION_LENGTH));
        AiCircuitService.Result result = service(new ScriptedProvider(raw))
                .generate("Build a large logic network.");
        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(result.attemptLog().stream().anyMatch(line -> line.contains(
                "maximum is " + AiCircuitLimits.MAX_GATES)));
        assertNull(result.layout());
    }

    @Test
    void totalExpressionTextOverThreeThousandIsRejected() throws Exception {
        List<Output> outputs = new ArrayList<>();
        String legalExpression = "A" + " OR A".repeat(55);
        assertTrue(legalExpression.length() <= AiCircuitLimits.MAX_EXPRESSION_LENGTH);
        for (int index = 0; index < 12; index++) {
            outputs.add(new Output("O" + index, legalExpression));
        }
        ScriptedProvider provider = new ScriptedProvider(answer(List.of("A"), outputs));
        AiCircuitService.Result result = service(provider).generate("Build a circuit from this description.");
        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(result.attemptLog().stream().anyMatch(line -> line.contains("total expression text exceeds "
                + AiCircuitLimits.MAX_EXPRESSION_TEXT + " characters")));
    }

    @Test
    void parsableExpressionBeyondCompilerInputLimitStillSkipsTheProvider() throws Exception {
        String description = "Result = A & B & C & D & E & F & G & H & I & J & K";
        ScriptedProvider provider = new ScriptedProvider(andAnswer("0001"));
        AiCircuitService.Result result = service(provider).generate(description);

        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertEquals(0, result.attemptCount());
        assertEquals(0, provider.calls);
        assertTrue(result.attemptLog().getFirst().contains("exceeds AI circuit limits"));
    }

    @Test
    void parsableExpressionWithoutIndependentRowsIsBuiltLocally() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(andAnswer("0001"));
        AiCircuitService.Result result = service(provider).generate("F = A & B");

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertEquals(0, result.attemptCount());
        assertEquals(0, provider.calls);
        assertTrue(result.attemptLog().stream().anyMatch(line -> line.contains("skipped AI")));
    }

    private static String twoBitAdderAnswer(List<String> inputOrder, boolean corruptSpec) {
        String raw = answer(inputOrder, List.of(
                new Output("S0", "A0 ^ B0"),
                new Output("S1", "A1 ^ B1 ^ (A0 & B0)"),
                new Output("C2", "A1 & B1 | A1 & A0 & B0 | B1 & A0 & B0")), "");
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        root.remove("truthTable");
        JsonObject spec = new JsonObject();
        JsonObject buses = new JsonObject();
        JsonArray a = new JsonArray(); a.add("A1"); a.add("A0");
        JsonArray b = new JsonArray(); b.add("B1"); b.add("B0");
        JsonArray sum = new JsonArray(); sum.add("S1"); sum.add("S0");
        buses.add("A", a); buses.add("B", b); buses.add("S", sum);
        spec.add("buses", buses);
        JsonArray rules = new JsonArray();
        JsonObject sumRule = new JsonObject();
        sumRule.addProperty("out", "S");
        sumRule.addProperty("expr", corruptSpec ? "A + B + 1" : "A + B");
        rules.add(sumRule);
        JsonObject carryRule = new JsonObject();
        carryRule.addProperty("out", "C2");
        carryRule.addProperty("expr", "(A + B) >> 2");
        rules.add(carryRule);
        spec.add("rules", rules);
        root.add("spec", spec);
        return root.toString();
    }

    @Test
    void twoBitAdderAcceptsDeclaredInputPermutationAndKeepsEngineOrder() throws Exception {
        List<String> declaredOrder = List.of("A1", "A0", "B1", "B0");
        ScriptedProvider provider = new ScriptedProvider(twoBitAdderAnswer(declaredOrder, false));

        AiCircuitService.Result result = service(provider).generate("Build a two-bit adder.", false, true, "", false, false);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(1, provider.calls);
        assertEquals(List.of("A0", "B0", "A1", "B1"), result.truthTable().inputNames());
        assertEquals(16, result.truthTable().rows().size());
    }

    @Test
    void firstAppearanceInputOrderStillVerifiesForTwoBitAdder() throws Exception {
        List<String> declaredOrder = List.of("A0", "B0", "A1", "B1");
        ScriptedProvider provider = new ScriptedProvider(twoBitAdderAnswer(declaredOrder, false));

        AiCircuitService.Result result = service(provider).generate("Build a two-bit adder.", false, true, "", false, false);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(List.of("A0", "B0", "A1", "B1"), result.truthTable().inputNames());
    }

    @Test
    void missingExtraAndDuplicateDeclaredInputsAreRejected() throws Exception {
        List<List<String>> invalidOrders = List.of(
                List.of("A0", "B0", "A1"),
                List.of("A0", "B0", "A1", "B1", "EXTRA"),
                List.of("A0", "B0", "A1", "B1", "B1"));
        for (List<String> inputs : invalidOrders) {
            String zeros = "0".repeat(1 << inputs.size());
            String raw = answer(inputs, List.of(
                    new Output("S0", "A0 ^ B0"),
                    new Output("S1", "A1 ^ B1 ^ (A0 & B0)"),
                    new Output("C2", "A1 & B1 | A1 & A0 & B0 | B1 & A0 & B0")),
                    zeros, zeros, zeros);
            AiCircuitService.Result result = service(new ScriptedProvider(raw))
                    .generate("Build a two-bit adder.", false, true, "", false, false);

            assertEquals(AiCircuitService.Status.UNVERIFIED, result.status(), inputs.toString());
            String log = String.join("\\n", result.attemptLog());
            if (inputs.size() == 3) {
                assertTrue(log.contains("B1"), log);
            } else if (inputs.size() == 5 && inputs.getLast().equals("EXTRA")) {
                assertTrue(log.contains("EXTRA"), log);
            } else {
                assertTrue(log.contains("duplicate input name"), log);
            }
        }
    }


    @Test
    void modelAnswerWithFeedbackCycleIsStillRejectedAsCombinationalAiOutput() throws Exception {
        String cyclic = answer(List.of("Qn", "Q"), List.of(
                new Output("Q", "NOT Qn"),
                new Output("Qn", "NOT Q")), "0000", "0000");
        ScriptedProvider provider = new ScriptedProvider(cyclic);

        AiCircuitService.Result result = service(provider).generate(
                "Build a cross-coupled latch with outputs Q and Qn.");

        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(provider.calls > 0);
        assertTrue(result.attemptLog().stream()
                .anyMatch(line -> line.contains("Expression compilation failed")
                        && line.toLowerCase(java.util.Locale.ROOT).contains("feedback")),
        assertNull(result.layout());
        assertNull(result.truthTable());
    }

    @Test
    void wrongExecutableSpecProducesDecimalBusFeedbackAndRetries() throws Exception {
        List<String> declaredOrder = List.of("A1", "A0", "B1", "B0");
        ScriptedProvider provider = new ScriptedProvider(
                twoBitAdderAnswer(declaredOrder, true),
                twoBitAdderAnswer(declaredOrder, false));

        AiCircuitService.Result result = service(provider).generate("Build a two-bit adder.", false, true, "", false, false);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(2, result.attemptCount());
        String retryConversation = String.join("\n", provider.conversations.get(1).stream()
                .map(Turn::text).toList());
        assertTrue(retryConversation.contains(
                "A=0 B=0: S spec 1, circuit 0. Either side may be wrong."));
        assertFalse(retryConversation.contains("truthTable"));
    }

    @Test
    void expectedRowsVerifyWhenCorrectAndFailWithNamedLineErrorsWhenWrong() throws Exception {
        ScriptedProvider passProvider = new ScriptedProvider(andAnswer("0000"));
        AiCircuitService.Result pass = service(passProvider).generate(
                "Build a two-input AND gate with inputs A and B.", false, false,
                "A=1 B=1 -> F=1\nA=0 B=0 -> F=0");
        assertEquals(AiCircuitService.Status.VERIFIED, pass.status());

        ScriptedProvider failProvider = new ScriptedProvider(andAnswer("0001"));
        AiCircuitService.Result fail = service(failProvider).generate(
                "Build a two-input AND gate with inputs A and B.", false, false,
                "A=1 B=1 -> F=0");
        assertEquals(AiCircuitService.Status.UNVERIFIED, fail.status());
        assertTrue(fail.mismatchingRows().stream().anyMatch(
                line -> line.contains("Expected row line 1") && line.contains("F expected 0, circuit 1")));
    }

    @Test
    void expectedRowParseErrorsNameTheLineAndUnknownSignal() throws Exception {
        AiCircuitService.Result result = service(new ScriptedProvider(andAnswer("0001"))).generate(
                "Build a two-input AND gate with inputs A and B.", false, false,
                "A=1 TYPO=0 -> F=1");
        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(result.mismatchingRows().stream().anyMatch(
                line -> line.contains("Expected row line 1") && line.contains("TYPO")));
    }


}
