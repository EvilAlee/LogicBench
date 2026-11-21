package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.evilalee.gatelab.io.GateLibrary;
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

        JsonArray table = new JsonArray();
        for (int index = 0; index < outputs.size(); index++) {
            JsonObject row = new JsonObject();
            row.addProperty("output", outputs.get(index).name());
            row.addProperty("bits", bits[index]);
            table.add(row);
        }
        root.add("truthTable", table);
        return root.toString();
    }

    private static String andAnswer(String bits) {
        return answer(List.of("A", "B"), List.of(new Output("F", "A & B")), bits);
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
                return andAnswer(workingCalls[0] == 1 ? "0000" : "0001");
            }
        };

        AiCircuitService.Result result = service(provider, null, new ArrayList<>()).generate(
                "Build a two-input AND gate with inputs A and B.");

        assertEquals(AiCircuitService.Status.BUILT, result.status());
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
                if (calls[0]++ == 0) return andAnswer("0000");
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
        StringBuilder expression = new StringBuilder("A");
        String[] variables = {"B", "C", "D", "E", "F", "G", "H", "I", "J", "A"};
        for (int index = 0; index < 401; index++) {
            expression.append(" ^ ").append(variables[index % variables.length]);
        }
        assertTrue(expression.length() <= AiCircuitLimits.MAX_EXPRESSION_TEXT);
        List<String> inputs = List.of("A","B","C","D","E","F","G","H","I","J");
        String raw = answer(inputs, List.of(new Output("Result", expression.toString())), "");
        AiCircuitService.Result result = service(new ScriptedProvider(raw))
                .generate("Build a large logic network.");
        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(result.attemptLog().stream().anyMatch(line -> line.contains(
                "maximum is " + AiCircuitLimits.MAX_GATES)));
        assertNull(result.layout());
    }

    @Test
    void totalExpressionTextOverThreeThousandIsRejected() throws Exception {
        String oversized = "A".repeat(AiCircuitLimits.MAX_EXPRESSION_TEXT + 1);
        ScriptedProvider provider = new ScriptedProvider(
                answer(List.of("A"), List.of(new Output("F", oversized)), ""));
        AiCircuitService.Result result = service(provider).generate("Build a circuit from this description.");
        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(result.attemptLog().stream().anyMatch(line -> line.contains("total expression text exceeds "
                + AiCircuitLimits.MAX_EXPRESSION_TEXT + " characters")));
    }

    @Test
    void parsableExpressionBeyondCompilerInputLimitStillSkipsTheProvider() throws Exception {
        String description = "Result = A & B & C & D & E & F & G & H & I & J & K & L & M";
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
        assertTrue(result.attemptLog().getFirst().contains("skipped AI"));
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

        AiCircuitService.Result result = service(provider).generate("Build a two-bit adder.");

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(1, provider.calls);
        assertEquals(List.of("A0", "B0", "A1", "B1"), result.truthTable().inputNames());
        assertEquals(16, result.truthTable().rows().size());
    }

    @Test
    void firstAppearanceInputOrderStillVerifiesForTwoBitAdder() throws Exception {
        List<String> declaredOrder = List.of("A0", "B0", "A1", "B1");
        ScriptedProvider provider = new ScriptedProvider(twoBitAdderAnswer(declaredOrder, false));

        AiCircuitService.Result result = service(provider).generate("Build a two-bit adder.");

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
                    .generate("Build a two-bit adder.");

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
                        && line.contains("raw feedback is disabled")));
        assertNull(result.layout());
        assertNull(result.truthTable());
    }

    @Test
    void wrongExecutableSpecProducesDecimalBusFeedbackAndRetries() throws Exception {
        List<String> declaredOrder = List.of("A1", "A0", "B1", "B0");
        ScriptedProvider provider = new ScriptedProvider(
                twoBitAdderAnswer(declaredOrder, true),
                twoBitAdderAnswer(declaredOrder, false));

        AiCircuitService.Result result = service(provider).generate("Build a two-bit adder.");

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(2, result.attemptCount());
        String retryConversation = String.join("\n", provider.conversations.get(1).stream()
                .map(Turn::text).toList());
        assertTrue(retryConversation.contains(
                "A=0 B=1: S spec 3, circuit 1. Either side may be wrong."));
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

    @Test
    void modelAnswerWithFeedbackCycleIsStillRejectedAsCombinationalAiOutput() throws Exception {
        String cyclic = answer(List.of("Qn", "Q"), List.of(
                new Output("Q", "NOT Qn"),
                new Output("Qn", "NOT Q")), "");
        ScriptedProvider provider = new ScriptedProvider(cyclic);

        AiCircuitService.Result result = service(provider).generate(
                "Build a cross-coupled latch with outputs Q and Qn.");

        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(provider.calls > 0);
        assertTrue(result.attemptLog().stream()
                .anyMatch(line -> line.contains("Expression compilation failed")
                        && line.contains("raw feedback is disabled")));
        assertNull(result.layout());
        assertNull(result.truthTable());
    }


}
