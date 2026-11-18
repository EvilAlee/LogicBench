package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.evilalee.gatelab.io.GateLibrary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiCircuitServiceTest {
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
