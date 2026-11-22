package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonObject;
import io.github.evilalee.gatelab.io.ComponentLibrary;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.synth.ComponentExpressionCompiler;
import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import io.github.evilalee.gatelab.synth.layout.SchematicLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiExplainServiceTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();

    @TempDir Path directory;

    private static final class RecordingProvider implements AiProvider {
        private final List<Object> responses;
        private final List<String> systemPrompts = new ArrayList<>();
        private final List<List<Turn>> conversations = new ArrayList<>();
        private final List<JsonObject> schemas = new ArrayList<>();
        private int calls;

        private RecordingProvider(Object... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public String complete(String prompt, List<Turn> conversation, JsonObject schema)
                throws IOException {
            systemPrompts.add(prompt);
            conversations.add(List.copyOf(conversation));
            schemas.add(schema.deepCopy());
            Object response = responses.get(Math.min(calls, responses.size() - 1));
            calls++;
            if (response instanceof IOException failure) throw failure;
            return (String) response;
        }
    }

    private static String explanation(String summary, String... steps) {
        JsonObject json = new JsonObject();
        json.addProperty("summary", summary);
        com.google.gson.JsonArray values = new com.google.gson.JsonArray();
        for (String step : steps) values.add(step);
        json.add("steps", values);
        return json.toString();
    }

    private Schematic fullAdderBox() throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, directory.resolve("components"));
        return ComponentExpressionCompiler.compile("""
                U0 := FA(A, B, CIN)
                S = U0.S
                COUT = U0.COUT
                """, GATES, library.all()).schematic();
    }

    @Test
    void requestContainsExportAndFactsAndPreviewMatchesExactProviderRequest() throws Exception {
        Schematic schematic = fullAdderBox();
        AiExplainService.PreparedRequest prepared =
                AiExplainService.prepare(schematic, "Explain the carry path.");
        assertTrue(prepared.circuitText().contains("U0 := FA(A, B, CIN)"), prepared.circuitText());
        assertTrue(prepared.requestText().contains(prepared.circuitText()));
        assertTrue(prepared.requestText().contains("Gate count:"));
        assertTrue(prepared.requestText().contains("Instance count: 1"));
        assertTrue(prepared.requestText().contains("Inputs: [A, B, CIN]"));
        assertEquals(java.util.Set.of("S", "COUT"),
                java.util.Set.copyOf(prepared.facts().outputNames()));
        assertTrue(prepared.requestText().contains("Truth table: all 8 rows"));

        RecordingProvider provider = new RecordingProvider(explanation(
                "A full adder combines three input bits.", "The sum is the parity of A, B, and CIN.",
                "COUT is high when at least two inputs are high."));
        AiCircuitService service = new AiCircuitService(provider, GATES);
        AiCircuitService.ExplanationResult result =
                service.explain(prepared.systemPrompt(), prepared.requestText());

        assertEquals(AiCircuitService.ExplanationStatus.EXPLAINED, result.status());
        assertEquals(1, provider.calls);
        assertEquals(prepared.systemPrompt(), provider.systemPrompts.getFirst());
        assertEquals(prepared.requestText(), provider.conversations.getFirst().getFirst().text());
        assertEquals("SYSTEM PROMPT\n" + provider.systemPrompts.getFirst()
                        + "\n\nUSER MESSAGE\n" + provider.conversations.getFirst().getFirst().text(),
                prepared.previewText());
        assertEquals(3, result.steps().size());
    }

    @Test
    void boxedCircuitUsesItsComponentCallText() throws Exception {
        AiExplainService.PreparedRequest prepared =
                AiExplainService.prepare(fullAdderBox(), AiExplainService.DEFAULT_QUESTION);
        assertTrue(prepared.circuitText().contains("U0 := FA(A, B, CIN)"));
        assertTrue(prepared.circuitText().contains("U0.S"));
        assertTrue(prepared.circuitText().contains("U0.COUT"));
    }

    @Test
    void sequentialBoxReportsFeedbackAndOmitsTruthTable() throws Exception {
        ComponentLibrary library = ComponentLibrary.load(GATES, directory.resolve("sequential"));
        Schematic schematic = ComponentExpressionCompiler.compile("""
                U0 := DFF(D, CLK)
                Q = U0.Q
                """, GATES, library.all()).schematic();

        AiExplainService.EngineFacts facts =
                AiExplainService.prepare(schematic, "Explain the state element.").facts();
        assertFalse(facts.containsFeedback());
        assertTrue(facts.sequential());
        assertTrue(facts.truthRows().isEmpty());
        assertTrue(facts.format().contains("contains feedback; truth table not shown"));
        assertFalse(facts.format().contains("Truth table: all"));
    }

    @Test
    void latchBoxIsMarkedSequentialEvenWithoutAnExternalClockPort() throws Exception {
        ComponentLibrary library = ComponentLibrary.load(GATES, directory.resolve("latch"));
        Schematic schematic = ComponentExpressionCompiler.compile("""
                U0 := DLATCH(D, EN)
                Q = U0.Q
                """, GATES, library.all()).schematic();

        AiExplainService.EngineFacts facts =
                AiExplainService.prepare(schematic, "Explain the latch.").facts();
        assertFalse(facts.containsFeedback());
        assertTrue(facts.sequential());
        assertTrue(facts.truthRows().isEmpty());
        assertTrue(facts.format().contains("contains feedback; truth table not shown"));
    }

    @Test
    void circuitsWithMoreThanSixInputsUseExactlySixtyFourSpreadRows() {
        String expression = "Y = A | B | C | D | E | F | G | H";
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile(expression, false);
        Schematic schematic = new SchematicLayout(GATES).layout(compilation.netlist()).schematic();

        AiExplainService.EngineFacts facts =
                AiExplainService.prepare(schematic, "Describe this circuit.").facts();
        assertTrue(facts.sampled());
        assertEquals(256, facts.totalRows().intValueExact());
        assertEquals(64, facts.truthRows().size());
        assertEquals("00000000", facts.truthRows().getFirst().inputs());
        assertEquals("11111111", facts.truthRows().getLast().inputs());
    }

    @Test
    void malformedResponseRetriesAndThenParses() throws Exception {
        RecordingProvider provider = new RecordingProvider(
                "{not json", explanation("Parsed after retry.", "Step one."));
        AiCircuitService service = new AiCircuitService(provider, GATES);
        AiCircuitService.ExplanationResult result =
                service.explain(AiExplainService.SYSTEM_PROMPT, "Question with exported circuit and facts.");

        assertEquals(AiCircuitService.ExplanationStatus.EXPLAINED, result.status());
        assertEquals(2, result.attemptCount());
        assertEquals(2, provider.calls);
        assertTrue(provider.conversations.get(1).stream()
                .anyMatch(turn -> turn.text().contains("previous explanation response was invalid")));
    }

    @Test
    void malformedResponsesStopAtExistingAttemptLimit() throws Exception {
        RecordingProvider provider = new RecordingProvider("{", "{", "{", explanation("unused"));
        AiCircuitService service = new AiCircuitService(provider, GATES);
        AiCircuitService.ExplanationResult result =
                service.explain(AiExplainService.SYSTEM_PROMPT, "Read-only request.");

        assertEquals(AiCircuitService.ExplanationStatus.INVALID_RESPONSE, result.status());
        assertEquals(AiCircuitLimits.MAX_ATTEMPTS, provider.calls);
        assertEquals(AiCircuitLimits.MAX_ATTEMPTS, result.attemptCount());
    }

    @Test
    void explainNeverReadsOrWritesCacheAndDoesNotMutateSchematic() throws Exception {
        Schematic schematic = fullAdderBox();
        long revision = schematic.modificationCount();
        AiExplainService.PreparedRequest prepared =
                AiExplainService.prepare(schematic, "What does this do?");
        RecordingProvider provider = new RecordingProvider(
                explanation("Read-only explanation.", "It has two outputs."));
        AiCache cache = new AiCache(directory.resolve("cache"));
        AiCircuitService service = new AiCircuitService(
                provider, GATES, cache, ignored -> { });

        AiCircuitService.ExplanationResult result =
                service.explain(prepared.systemPrompt(), prepared.requestText());

        assertEquals(AiCircuitService.ExplanationStatus.EXPLAINED, result.status());
        assertFalse(Files.exists(cache.file()));
        assertTrue(cache.lookupCandidates("What does this do?").isEmpty());
        assertEquals(revision, schematic.modificationCount());
    }

    @Test
    void exporterLimitFailsBeforeProviderCanBeCalled() {
        Schematic schematic = new Schematic(GATES);
        GateDef and = GATES.stream().filter(gate -> gate.family().equals("and")
                && gate.size().equals("narrow") && gate.inputCount() == 2).findFirst().orElseThrow();
        for (int index = 0; index < 401; index++) {
            schematic.add(new PlacedComponent("G" + index, and.family(),
                    (index % 20) * 100, (index / 20) * 100, Facing.EAST));
        }
        RecordingProvider provider = new RecordingProvider(explanation("not sent", "never"));
        assertThrows(IllegalArgumentException.class,
                () -> AiExplainService.prepare(schematic, "Explain this."));
        assertEquals(0, provider.calls);
    }

    @Test
    void transportFailureUsesUnavailableStatusWithoutCacheFallback() throws Exception {
        RecordingProvider provider = new RecordingProvider(new IOException("connection failed"));
        AiCircuitService service = new AiCircuitService(
                provider, GATES, null, ignored -> { });

        AiCircuitService.ExplanationResult result =
                service.explain(AiExplainService.SYSTEM_PROMPT, "Explain only from provided facts.");

        assertEquals(AiCircuitService.ExplanationStatus.UNAVAILABLE, result.status());
        assertTrue(result.summary().startsWith("UNAVAILABLE:"));
        assertEquals(3, provider.calls); // primary plus the existing two retry rounds
    }
}
