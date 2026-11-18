package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
    void wrongFirstTruthTableIsCorrectedOnSecondAttemptWithConcreteFeedback() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(andAnswer("0000"), andAnswer("0001"));
        AiCircuitService.Result result = service(provider).generate(
                "Build a two-input AND gate with inputs A and B.");

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(2, result.attemptCount());
        assertEquals(2, provider.calls);
        assertTrue(provider.conversations.get(1).stream()
                .anyMatch(turn -> turn.text().contains("inputs 11")
                        && turn.text().contains("you claimed 0, circuit gives 1")));
        assertTrue(provider.conversations.get(1).stream()
                .anyMatch(turn -> turn.text().contains(andAnswer("0000"))));
        assertTrue(result.attemptLog().stream().anyMatch(line -> line.contains("Verified 4/4 rows")));
    }

    @Test
    void threeFailuresReturnTheAttemptWithFewestMismatchingRows() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(
                andAnswer("0000"), andAnswer("1111"), andAnswer("0000"));
        AiCircuitService.Result result = service(provider).generate(
                "Build a two-input AND gate with inputs A and B.");

        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertEquals(3, result.attemptCount());
        assertEquals(3, provider.calls);
        assertEquals(1, result.mismatchingRows().size());
        assertTrue(result.mismatchingRows().getFirst().contains("inputs 11"));
        assertEquals("F", result.expressions().getFirst().name());
        assertNotNull(result.netlist());
        assertNotNull(result.truthTable());
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

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
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

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertTrue(result.fromCache());
        assertEquals(0, result.attemptCount());
        assertEquals(3, calls[0]);
        assertEquals(List.of(2_000L, 6_000L), delays);
        assertTrue(result.attemptLog().contains("AI unavailable, using cached answer"));
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

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(1, result.attemptCount());
        assertEquals("working-model", result.answeredBy());
        assertEquals(List.of("missing-model", "working-model"), calls);
        assertEquals(List.of("missing-model: HTTP 404", "working-model: 200 OK"),
                result.modelAttempts());
    }

    @Test
    void keyRejectionStopsWithoutRetryingOrTryingFallbackModels() throws Exception {
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
                        "API key rejected (HTTP 401).", model, 401);
            }
        };

        AiCircuitService.Result result = service(provider, null, delays).generate("Make a circuit.");

        assertEquals(AiCircuitService.Status.UNAVAILABLE, result.status());
        assertTrue(result.summary().contains("API key rejected (HTTP 401)"));
        assertEquals(List.of("primary-model"), calls);
        assertTrue(delays.isEmpty());
        assertEquals(0, result.attemptCount());
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
    void parseErrorFeedbackIncludesTheParserPosition() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(
                answer(List.of("A", "B"), List.of(new Output("F", "A &")), "0001"),
                andAnswer("0001"));
        AiCircuitService.Result result = service(provider).generate(
                "Build a two-input AND gate with inputs A and B.");

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertTrue(provider.conversations.get(1).stream()
                .anyMatch(turn -> turn.text().contains("parse error at position 3")));
    }

    @Test
    void inputAndOutputSchemaLimitsAreRejected() throws Exception {
        List<String> sevenInputs = List.of("A", "B", "C", "D", "E", "F", "G");
        String tooManyInputs = answer(sevenInputs,
                List.of(new Output("Fout", "A&B&C&D&E&F&G")), "0".repeat(128));
        ScriptedProvider inputProvider = new ScriptedProvider(tooManyInputs);
        AiCircuitService.Result inputResult = service(inputProvider).generate("Please build a circuit.");
        assertEquals(AiCircuitService.Status.UNVERIFIED, inputResult.status());
        assertTrue(inputResult.attemptLog().stream().anyMatch(line -> line.contains("maximum is 6")));

        List<Output> nineOutputs = new ArrayList<>();
        String[] tables = new String[9];
        for (int index = 0; index < 9; index++) {
            nineOutputs.add(new Output("O" + index, "A"));
            tables[index] = "01";
        }
        ScriptedProvider outputProvider = new ScriptedProvider(
                answer(List.of("A"), nineOutputs, tables));
        AiCircuitService.Result outputResult = service(outputProvider).generate("Please build a circuit.");
        assertEquals(AiCircuitService.Status.UNVERIFIED, outputResult.status());
        assertTrue(outputResult.attemptLog().stream().anyMatch(line -> line.contains("maximum is 8")));
    }

    @Test
    void circuitsOverTwoHundredGatesAreRejectedBeforeLayout() throws Exception {
        StringBuilder expression = new StringBuilder("A");
        char[] variables = {'B', 'C', 'D', 'E', 'F', 'A'};
        char[] operators = {'^'};
        for (int index = 0; index < 249; index++) {
            expression.append(' ').append(operators[index % operators.length])
                    .append(' ').append(variables[index % variables.length]);
        }
        assertTrue(expression.length() <= AiCircuitService.MAX_EXPRESSION_LENGTH);
        List<String> inputs = List.of("A", "B", "C", "D", "E", "F");
        String raw = answer(inputs, List.of(new Output("Result", expression.toString())),
                "0".repeat(64));
        ScriptedProvider provider = new ScriptedProvider(raw);
        AiCircuitService.Result result = service(provider).generate("Build a large logic network.");
        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(result.attemptLog().stream().anyMatch(line -> line.contains("maximum is 200")));
        assertNull(result.layout());
    }

    @Test
    void expressionLengthOverOneThousandIsRejectedByTheSchemaValidator() throws Exception {
        String oversized = "A".repeat(AiCircuitService.MAX_EXPRESSION_LENGTH + 1);
        ScriptedProvider provider = new ScriptedProvider(
                answer(List.of("A"), List.of(new Output("F", oversized)), "01"));
        AiCircuitService.Result result = service(provider).generate("Build a circuit from this description.");
        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertTrue(result.attemptLog().stream().anyMatch(line -> line.contains("exceeds 1000 characters")));
    }

    @Test
    void parsableExpressionBeyondCompilerInputLimitStillSkipsTheProvider() throws Exception {
        String description = "F = A & B & C & D & E & F & G & H & I & J & K & L & M";
        ScriptedProvider provider = new ScriptedProvider(andAnswer("0001"));
        AiCircuitService.Result result = service(provider).generate(description);

        assertEquals(AiCircuitService.Status.UNVERIFIED, result.status());
        assertEquals(0, result.attemptCount());
        assertEquals(0, provider.calls);
        assertTrue(result.attemptLog().getFirst().contains("parses as expression text"));
    }

    @Test
    void parsableExpressionDescriptionSkipsTheProviderEntirely() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(andAnswer("0001"));
        AiCircuitService.Result result = service(provider).generate("F = A & B");

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertEquals(0, result.attemptCount());
        assertEquals(0, provider.calls);
        assertTrue(result.attemptLog().getFirst().contains("skipped AI"));
    }
}
