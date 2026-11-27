package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.sim.BitParallelEvaluator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCircuit16InputTest {
    @TempDir
    Path directory;

    private static final class FixedProvider implements AiProvider {
        private final String answer;
        private int calls;

        FixedProvider(String answer) {
            this.answer = answer;
        }

        @Override
        public String complete(String systemPrompt, List<Turn> conversation, JsonObject schema) {
            calls++;
            return answer;
        }
    }

    @Test
    @Tag("slow")
    void sixteenInputParityVerifiesAll65536RowsAndChecksTheEngineSample() throws Exception {
        String answer = parityAnswer(false);
        FixedProvider provider = new FixedProvider(answer);
        AiCircuitService service = service(provider);

        AiCircuitService.Result result = service.generate(
                "Create a sixteen-input parity circuit with output PARITY",
                false, false, expectedParityRows(), false, false, false);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(65_536, result.truthTable().totalRowCount());
        assertTrue(result.attemptLog().stream().anyMatch(line ->
                line.contains("all 65,536 rows against the executable spec")));
        assertTrue(result.attemptLog().stream().anyMatch(line ->
                line.contains("3 user-supplied expected rows")));
        assertTrue(result.attemptLog().stream().anyMatch(line ->
                line.contains("real-engine cross-check on 546 rows")));
    }

    @Test
    @Tag("slow")
    void sixteenInputMajorityOfBitsVerifiesEveryInputAssignment() throws Exception {
        FixedProvider provider = new FixedProvider(majorityAnswer());
        AiCircuitService.Result result = service(provider).generate(
                "Create a sixteen-input majority-of-bits circuit with output Y",
                false, false, "", false, false, false);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(65_536, result.truthTable().totalRowCount());
        assertTrue(result.attemptLog().stream().anyMatch(line ->
                line.contains("all 65,536 rows against the executable spec")));
    }

    @Test
    @Tag("slow")
    void wrongSixteenInputSpecificationFailsWithAnExplicitRow() throws Exception {
        FixedProvider provider = new FixedProvider(parityAnswer(true));
        AiCircuitService.Result result = service(provider).generate(
                "Create a custom sixteen-variable parity network with output PARITY",
                false, false, "", false, false, false);

        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(result.mismatchingRows().stream().anyMatch(line ->
                line.contains("Spec mismatch at row 65535")
                        && line.contains("A=65535")));
        assertTrue(result.truthTable().rows().stream().anyMatch(row -> row.index() == 65_535),
                "the truth-table model must retain a failing row beyond its first 256 rows");
    }

    @Test
    void eightToOneMuxTemplateResponseIsAccepted() throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("kind", "template");
        JsonObject template = new JsonObject();
        template.addProperty("generator", "mux");
        JsonObject params = new JsonObject();
        params.addProperty("k", "3");
        template.add("params", params);
        root.add("template", template);

        AiCircuitService.Result result = service(new FixedProvider(root.toString())).generate(
                "Build an eight-channel data selector with three select bits",
                false, false, "", true, true, false);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(11, result.truthTable().inputNames().size());
        assertEquals(2_048, result.truthTable().totalRowCount());
    }

    @Test
    @Tag("slow")
    void sevenBitAdderTemplateWithCarryInIsAcceptedAtFifteenInputs() throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("kind", "template");
        JsonObject template = new JsonObject();
        template.addProperty("generator", "ripple_adder");
        JsonObject params = new JsonObject();
        params.addProperty("n", "7");
        params.addProperty("carryIn", "yes");
        template.add("params", params);
        root.add("template", template);

        AiCircuitService.Result result = service(new FixedProvider(root.toString())).generate(
                "Add two seven-bit values with carry in and carry out",
                false, false, "", true, true, false);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(15, result.truthTable().inputNames().size());
        assertEquals(32_768, result.truthTable().totalRowCount());
    }

    @Test
    void seventeenthInputIsRejectedWithTheNewLimit() throws Exception {
        List<String> inputs = new ArrayList<>();
        for (int index = 0; index < 17; index++) inputs.add("A" + index);
        FixedProvider provider = new FixedProvider(expressionAnswer(
                inputs, List.of("F"), List.of("A0"), null));
        AiCircuitService.Result result = service(provider).generate(
                "Build a custom seventeen-input combinational function",
                false, false, "", true, false, false);

        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(result.mismatchingRows().stream().anyMatch(line ->
                line.contains("17 entries") && line.contains("maximum is 16")));
    }

    @Test
    @Tag("slow")
    void engineCrossCheckRejectsADeliberatelyCorruptedParallelEvaluator() throws Exception {
        FixedProvider provider = new FixedProvider(parityAnswer(false));
        AiCircuitService service = new AiCircuitService(
                provider, GateLibrary.load().all(), List.of(), null,
                ignored -> { }, null,
                (compiled, inputs, outputs) -> BitParallelEvaluator.evaluate(
                        compiled, inputs, outputs)
                        .withOutputBitFlippedForTesting("PARITY", 0));

        AiCircuitService.Result result = service.generate(
                "Build a custom sixteen-input parity checker",
                false, false, "", false, false, false);

        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(result.mismatchingRows().stream().anyMatch(line ->
                line.contains("disagreement at sample row 0")));
    }

    @Test
    @Tag("slow")
    void verifiedSixteenInputAnswerCanBeReplayedFromCache() throws Exception {
        String description = "Build a custom sixteen-input odd parity network";
        AiCache cache = new AiCache(directory);
        AiCircuitService firstService = new AiCircuitService(
                new FixedProvider(parityAnswer(false)), GateLibrary.load().all(),
                cache, ignored -> { });
        AiCircuitService.Result generated = firstService.generate(
                description, false, false, "", false, false, false);
        assertEquals(AiCircuitService.Status.VERIFIED, generated.status());

        FixedProvider offline = new FixedProvider("{}");
        AiCircuitService secondService = new AiCircuitService(
                offline, GateLibrary.load().all(), cache, ignored -> { });
        AiCircuitService.Result replay = secondService.generate(
                description, true, false, "", false, false, false);

        assertTrue(replay.fromCache());
        assertEquals(AiCircuitService.Status.VERIFIED, replay.status());
        assertEquals(65_536, replay.truthTable().totalRowCount());
        assertEquals(0, offline.calls);
        assertTrue(replay.summary().contains("all 65,536 rows against the executable spec"));
        assertFalse(replay.truthTable().rows().isEmpty());
    }

    private static AiCircuitService service(AiProvider provider) {
        return new AiCircuitService(provider, GateLibrary.load().all(), null, ignored -> { });
    }

    private static String parityAnswer(boolean wrongSpec) {
        List<String> inputs = numberedInputs(16);
        List<String> signals = new ArrayList<>();
        signals.add("P1");
        List<String> expressions = new ArrayList<>();
        expressions.add("A0 ^ A1");
        String previous = "P1";
        for (int bit = 2; bit < 16; bit++) {
            String next = "P" + bit;
            signals.add(next);
            expressions.add(previous + " ^ A" + bit);
            previous = next;
        }
        String specExpression = wrongSpec
                ? "(popcount(A) % 2) ^ (A == 65535 ? 1 : 0)"
                : "popcount(A) % 2";
        JsonObject spec = spec("A", reversed(inputs), "PARITY", specExpression);
        return expressionAnswer(inputs, List.of("PARITY"), List.of(previous), spec,
                signals, expressions);
    }

    private static String expectedParityRows() {
        List<String> zero = new ArrayList<>();
        List<String> one = new ArrayList<>();
        List<String> allOne = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            String pin = "A" + index;
            zero.add(pin + "=0");
            one.add(pin + "=" + (index == 0 ? "1" : "0"));
            allOne.add(pin + "=1");
        }
        return String.join(" ", zero) + " -> PARITY=0\n"
                + String.join(" ", one) + " -> PARITY=1\n"
                + String.join(" ", allOne) + " -> PARITY=0";
    }

    private static String majorityAnswer() {
        List<String> inputs = numberedInputs(16);
        List<String> signals = new ArrayList<>();
        List<String> expressions = new ArrayList<>();
        Map<String, String> previous = new java.util.LinkedHashMap<>();
        previous.put("1", "A0");
        signals.add("T0_1");
        expressions.add("A0");
        for (int input = 1; input < 16; input++) {
            Map<String, String> current = new java.util.LinkedHashMap<>();
            int max = Math.min(input + 1, 9);
            for (int count = 1; count <= max; count++) {
                String name = "T" + input + "_" + count;
                String expression;
                if (count == 1) {
                    expression = previous.get("1") + " | A" + input;
                } else if (count == input + 1) {
                    expression = previous.get(Integer.toString(count - 1))
                            + " & A" + input;
                } else {
                    expression = previous.get(Integer.toString(count))
                            + " | (A" + input + " & "
                            + previous.get(Integer.toString(count - 1)) + ")";
                }
                current.put(Integer.toString(count), name);
                signals.add(name);
                expressions.add(expression);
            }
            previous = current;
        }
        String finalSignal = previous.get("9");
        JsonObject spec = spec("A", reversed(inputs), "Y",
                "(popcount(A) >= 9) ? 1 : 0");
        return expressionAnswer(inputs, List.of("Y"), List.of(finalSignal), spec,
                signals, expressions);
    }

    private static String expressionAnswer(
            List<String> inputs, List<String> outputs, List<String> outputExpressions,
            JsonObject spec) {
        return expressionAnswer(inputs, outputs, outputExpressions, spec, List.of(), List.of());
    }

    private static String expressionAnswer(
            List<String> inputs, List<String> outputs, List<String> outputExpressions,
            JsonObject spec, List<String> signalNames, List<String> signalExpressions) {
        JsonObject root = new JsonObject();
        root.addProperty("kind", "expressions");
        root.addProperty("summary", "Sixteen-input combinational verification fixture");
        JsonArray in = new JsonArray();
        inputs.forEach(in::add);
        root.add("inputs", in);
        JsonArray out = new JsonArray();
        for (int index = 0; index < outputs.size(); index++) {
            JsonObject pin = new JsonObject();
            pin.addProperty("name", outputs.get(index));
            pin.addProperty("expression", outputExpressions.get(index));
            out.add(pin);
        }
        root.add("outputs", out);
        JsonArray signals = new JsonArray();
        for (int index = 0; index < signalNames.size(); index++) {
            JsonObject signal = new JsonObject();
            signal.addProperty("name", signalNames.get(index));
            signal.addProperty("expression", signalExpressions.get(index));
            signals.add(signal);
        }
        root.add("signals", signals);
        if (spec != null) root.add("spec", spec);
        return root.toString();
    }

    private static JsonObject spec(
            String inputBusName, List<String> inputPins,
            String outputBusName, String expression) {
        JsonObject buses = new JsonObject();
        JsonArray inputs = new JsonArray();
        inputPins.forEach(inputs::add);
        buses.add(inputBusName, inputs);
        JsonObject body = new JsonObject();
        body.add("buses", buses);
        JsonArray rules = new JsonArray();
        JsonObject rule = new JsonObject();
        rule.addProperty("out", outputBusName);
        rule.addProperty("expression", expression);
        rules.add(rule);
        body.add("rules", rules);
        return body;
    }

    private static List<String> numberedInputs(int count) {
        List<String> inputs = new ArrayList<>();
        for (int index = 0; index < count; index++) inputs.add("A" + index);
        return List.copyOf(inputs);
    }

    private static List<String> reversed(List<String> values) {
        List<String> reversed = new ArrayList<>(values);
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }
}
