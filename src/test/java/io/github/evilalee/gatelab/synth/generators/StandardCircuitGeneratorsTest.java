package io.github.evilalee.gatelab.synth.generators;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.evilalee.gatelab.ai.AiCircuitLimits;
import io.github.evilalee.gatelab.ai.SpecEvaluator;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import io.github.evilalee.gatelab.synth.TruthTable;
import io.github.evilalee.gatelab.synth.TruthTableGenerator;
import io.github.evilalee.gatelab.synth.layout.SchematicExpressionVerifier;
import io.github.evilalee.gatelab.synth.layout.SchematicLayout;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exhaustive engine/spec checks for every supported deterministic template parameter set. */
class StandardCircuitGeneratorsTest {
    @Test
    void everySupportedParameterCombinationMatchesItsIntegerSpecOnEveryEngineRow() {
        int combinations = 0;
        for (TemplateGenerator generator : StandardCircuitGenerators.all()) {
            for (Map<String, String> params : parameterCombinations(generator)) {
                combinations++;
                GeneratedCircuit answer = generator.build(params);
                assertNotNull(answer.toJson().get("spec"));
                assertEquals(generator.id(), answer.generator());

                ExpressionCompiler.Compilation plain =
                        ExpressionCompiler.compile(answer.expressionSource(), false, false);
                assertEquals(answer.inputs().size(), plain.netlist().inputs().size(),
                        context(generator, params));
                assertTrue(plain.netlist().inputs().size() <= AiCircuitLimits.MAX_INPUTS,
                        context(generator, params));
                assertTrue(plain.netlist().outputs().size() <= AiCircuitLimits.MAX_OUTPUTS,
                        context(generator, params));
                assertTrue(plain.netlist().gates().size() <= AiCircuitLimits.MAX_GATES,
                        context(generator, params));
                assertTrue(answer.signals().size() <= AiCircuitLimits.MAX_SIGNALS,
                        context(generator, params));
                assertTrue(answer.outputs().stream().allMatch(e ->
                        e.expression().length() <= AiCircuitLimits.MAX_EXPRESSION_LENGTH));
                assertTrue(answer.signals().stream().allMatch(e ->
                        e.expression().length() <= AiCircuitLimits.MAX_EXPRESSION_LENGTH));

                TruthTable table = TruthTableGenerator.generate(plain.netlist());
                assertEquals(1 << answer.inputs().size(), table.rows().size(),
                        context(generator, params));
                SpecEvaluator spec = SpecEvaluator.parse(
                        evaluatorSpec(answer.spec()), table.inputNames(), table.outputNames());
                assertRowsMatchSpec(table, spec, context(generator, params));

                ExpressionCompiler.Compilation mapped =
                        ExpressionCompiler.compile(answer.expressionSource(), false, true);
                TruthTable mappedTable = TruthTableGenerator.generate(mapped.netlist());
                assertEquals(table.rows(), mappedTable.rows(),
                        "mapping changed behavior for " + context(generator, params));
            }
        }
        assertEquals(62, combinations, "the supported template domain changed unexpectedly");
    }

    @Test
    void fourBitAdderAndFourByFourMultiplierPassPlacedGeometryVerification() {
        GeneratedCircuit adder = StandardCircuitGenerators.require("ripple_adder")
                .build(Map.of("n", "4", "carryIn", "yes"));
        GeneratedCircuit multiplier = StandardCircuitGenerators.require("array_multiplier")
                .build(Map.of("n", "4", "m", "4"));
        SchematicLayout layoutBuilder = new SchematicLayout(GateLibrary.load().all());

        for (GeneratedCircuit answer : List.of(adder, multiplier)) {
            ExpressionCompiler.Compilation compilation =
                    ExpressionCompiler.compile(answer.expressionSource(), false, true);
            SchematicLayout.LayoutResult layout = layoutBuilder.layout(compilation.netlist());
            SchematicExpressionVerifier.VerificationResult verification =
                    SchematicExpressionVerifier.verify(compilation, layout);
            assertTrue(verification.verified(),
                    answer.generator() + " geometry mismatches: " + verification.mismatches());
            assertTrue(verification.rowCount() > 0);
            assertFalse(layout.schematic().components().isEmpty());
            assertFalse(layout.schematic().wires().isEmpty());
        }
    }

    @Test
    void unsupportedEightToOneMuxIsRejectedRatherThanClamped() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> StandardCircuitGenerators.require("mux").build(Map.of("k", "3")));
        assertTrue(exception.getMessage().contains("[1, 2]"));
        assertTrue(exception.getMessage().contains("k"));
    }

    @Test
    void unknownGeneratorAndUnknownParametersHaveNamedErrors() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> StandardCircuitGenerators.require("unknown"));
        assertTrue(unknown.getMessage().contains("unknown generator"));

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> StandardCircuitGenerators.require("ripple_adder").build(Map.of("n", "4")));
        assertTrue(missing.getMessage().contains("carryIn"));
    }

    private static List<Map<String, String>> parameterCombinations(TemplateGenerator generator) {
        List<Map<String, String>> combinations = new ArrayList<>();
        combinations.add(new LinkedHashMap<>());
        for (Map.Entry<String, TemplateGenerator.Parameter> entry
                : generator.parameters().entrySet()) {
            List<Map<String, String>> expanded = new ArrayList<>();
            for (Map<String, String> previous : combinations) {
                for (String value : entry.getValue().values()) {
                    Map<String, String> next = new LinkedHashMap<>(previous);
                    next.put(entry.getKey(), value);
                    expanded.add(next);
                }
            }
            combinations = expanded;
        }
        return List.copyOf(combinations);
    }

    private static JsonObject evaluatorSpec(JsonObject answerSpec) {
        JsonObject normalized = answerSpec.deepCopy();
        for (JsonElement element : normalized.getAsJsonArray("rules")) {
            JsonObject rule = element.getAsJsonObject();
            rule.add("expr", rule.get("expression").deepCopy());
            rule.remove("expression");
        }
        return normalized;
    }

    private static void assertRowsMatchSpec(
            TruthTable table, SpecEvaluator spec, String context) {
        for (TruthTable.Row row : table.rows()) {
            Map<String, Boolean> inputValues = new LinkedHashMap<>();
            for (int index = 0; index < table.inputNames().size(); index++) {
                inputValues.put(table.inputNames().get(index), row.inputValue(index));
            }
            Map<String, Boolean> expected = spec.evaluate(inputValues).outputPins();
            for (int index = 0; index < table.outputNames().size(); index++) {
                String output = table.outputNames().get(index);
                Value actual = row.outputValues().get(index);
                assertTrue(actual == Value.ZERO || actual == Value.ONE,
                        context + " produced X at " + row.inputBits() + " for " + output);
                Value expectedValue = Boolean.TRUE.equals(expected.get(output))
                        ? Value.ONE : Value.ZERO;
                assertEquals(expectedValue, actual,
                        context + " mismatch at " + row.inputBits() + " for " + output);
            }
        }
    }

    private static String context(TemplateGenerator generator, Map<String, String> params) {
        return generator.id() + params;
    }
}
