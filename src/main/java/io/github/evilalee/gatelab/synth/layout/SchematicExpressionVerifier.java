package io.github.evilalee.gatelab.synth.layout;

import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import io.github.evilalee.gatelab.synth.ExpressionEvaluator;
import io.github.evilalee.gatelab.synth.NamedExpression;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Verifies a placed Schematic through the editor's ordinary geometry-to-engine compile path. */
public final class SchematicExpressionVerifier {
    private SchematicExpressionVerifier() {
    }

    public record Mismatch(
            int rowIndex,
            String inputBits,
            String outputName,
            Value expected,
            Value actual,
            boolean oscillating) {
        public Mismatch {
            Objects.requireNonNull(inputBits, "inputBits");
            Objects.requireNonNull(outputName, "outputName");
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(actual, "actual");
        }
    }

    public record VerificationResult(int rowCount, List<Mismatch> mismatches) {
        public VerificationResult {
            if (rowCount < 1) {
                throw new IllegalArgumentException("rowCount must be positive");
            }
            mismatches = List.copyOf(Objects.requireNonNull(mismatches, "mismatches"));
        }

        public boolean verified() {
            return mismatches.isEmpty();
        }
    }

    /**
     * Exhaustively pokes every declared input combination and compiles the placed
     * geometry afresh each time. Temporary poke values are restored before return.
     */
    public static VerificationResult verify(
            ExpressionCompiler.Compilation compilation,
            SchematicLayout.LayoutResult layout) {
        Objects.requireNonNull(compilation, "compilation");
        Objects.requireNonNull(layout, "layout");
        if (!layout.inputComponentIds().keySet().equals(
                new java.util.LinkedHashSet<>(compilation.netlist().inputs()))) {
            throw new IllegalArgumentException(
                    "layout input components must match the compiled expression inputs");
        }
        if (!layout.outputComponentIds().keySet().equals(
                compilation.expressions().stream()
                        .map(NamedExpression::name)
                        .collect(java.util.stream.Collectors.toCollection(
                                java.util.LinkedHashSet::new)))) {
            throw new IllegalArgumentException(
                    "layout output components must match the compiled expression outputs");
        }

        Schematic schematic = layout.schematic();
        Map<String, Boolean> originalValues = new LinkedHashMap<>();
        layout.inputComponentIds().forEach((name, id) ->
                originalValues.put(id, schematic.inputValue(id)));
        layout.constantComponentIds().forEach((name, id) ->
                originalValues.put(id, schematic.inputValue(id)));

        int inputCount = compilation.netlist().inputs().size();
        int rowCount = 1 << inputCount;
        List<Mismatch> mismatches = new ArrayList<>();
        SchematicCompiler.SimulationResult previous = null;
        try {
            for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
                String bits = inputCount == 0 ? ""
                        : String.format(java.util.Locale.ROOT, "%1$" + inputCount + "s",
                                Integer.toBinaryString(rowIndex)).replace(' ', '0');

                Map<String, Boolean> assignment = new LinkedHashMap<>();
                for (int inputIndex = 0; inputIndex < inputCount; inputIndex++) {
                    String name = compilation.netlist().inputs().get(inputIndex);
                    boolean value = bits.charAt(inputIndex) == '1';
                    assignment.put(name, value);
                    schematic.setInputValue(layout.inputComponentIds().get(name), value);
                }

                // Constant-output source switches are separate from declared inputs.
                for (Map.Entry<String, String> constant : layout.constantComponentIds().entrySet()) {
                    schematic.setInputValue(constant.getValue(), constant.getKey().equals("1"));
                }

                previous = SchematicCompiler.compileAndSettle(schematic, previous);
                for (NamedExpression expression : compilation.expressions()) {
                    boolean expectedBit = ExpressionEvaluator.evaluate(
                            expression.expression(), assignment);
                    Value expected = expectedBit ? Value.ONE : Value.ZERO;
                    String outputId = layout.outputComponentIds().get(expression.name());
                    Value actual = previous.outputValues().getOrDefault(outputId, Value.X);
                    if (previous.oscillating() || actual != expected) {
                        mismatches.add(new Mismatch(
                                rowIndex, bits, expression.name(),
                                expected, actual, previous.oscillating()));
                    }
                }
            }
        } finally {
            for (Map.Entry<String, Boolean> entry : originalValues.entrySet()) {
                schematic.setInputValue(entry.getKey(), entry.getValue());
            }
        }
        return new VerificationResult(rowCount, mismatches);
    }
}
