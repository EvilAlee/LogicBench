package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.model.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compares engine-produced rows with the independent pure-Java AST evaluator. */
public final class TruthTableVerifier {
    private TruthTableVerifier() {
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

    public record VerificationResult(
            TruthTable truthTable,
            List<Mismatch> mismatches) {
        public VerificationResult {
            Objects.requireNonNull(truthTable, "truthTable");
            mismatches = List.copyOf(Objects.requireNonNull(mismatches, "mismatches"));
        }

        public boolean verified() {
            return mismatches.isEmpty();
        }

        /** Distinct engine rows containing at least one mismatch or an oscillation. */
        public List<TruthTable.Row> mismatchingRows() {
            List<TruthTable.Row> result = new ArrayList<>();
            for (TruthTable.Row row : truthTable.rows()) {
                if (row.oscillating() || mismatches.stream()
                        .anyMatch(mismatch -> mismatch.rowIndex() == row.index())) {
                    result.add(row);
                }
            }
            return List.copyOf(result);
        }
    }

    public static VerificationResult verify(ExpressionCompiler.Compilation compilation) {
        Objects.requireNonNull(compilation, "compilation");
        Netlist netlist = compilation.netlist();
        List<NamedExpression> expressions = compilation.expressions();
        List<String> expectedInputs = new ArrayList<>();
        for (NamedExpression named : expressions) {
            for (String variable : named.expression().variables()) {
                if (!expectedInputs.contains(variable)) {
                    expectedInputs.add(variable);
                }
            }
        }
        if (!expectedInputs.equals(netlist.inputs())) {
            throw new IllegalArgumentException(
                    "netlist input order must match expression variable first-appearance order");
        }

        TruthTable table = TruthTableGenerator.generate(netlist);
        List<Mismatch> mismatches = new ArrayList<>();
        for (TruthTable.Row row : table.rows()) {
            Map<String, Boolean> assignment = new LinkedHashMap<>();
            for (int index = 0; index < table.inputNames().size(); index++) {
                assignment.put(table.inputNames().get(index), row.inputValue(index));
            }

            for (int outputIndex = 0; outputIndex < expressions.size(); outputIndex++) {
                NamedExpression expression = expressions.get(outputIndex);
                boolean expectedBoolean = ExpressionEvaluator.evaluate(
                        expression.expression(), assignment);
                Value expected = expectedBoolean ? Value.ONE : Value.ZERO;
                Value actual = row.outputValues().get(outputIndex);
                if (row.oscillating() || actual != expected) {
                    mismatches.add(new Mismatch(
                            row.index(), row.inputBits(), expression.name(),
                            expected, actual, row.oscillating()));
                }
            }
        }
        return new VerificationResult(table, mismatches);
    }

    public static VerificationResult verify(String source) {
        return verify(ExpressionCompiler.compile(source));
    }
}
