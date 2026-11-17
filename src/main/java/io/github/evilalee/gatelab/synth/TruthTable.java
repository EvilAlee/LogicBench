package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.model.Value;

import java.util.List;
import java.util.Objects;

/** Immutable engine-produced truth table. Input bit zero is the leftmost (MSB) variable. */
public record TruthTable(
        List<String> inputNames,
        List<String> outputNames,
        List<Row> rows) {
    public TruthTable {
        inputNames = List.copyOf(Objects.requireNonNull(inputNames, "inputNames"));
        outputNames = List.copyOf(Objects.requireNonNull(outputNames, "outputNames"));
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        for (Row row : rows) {
            if (row.inputBits().length() != inputNames.size()) {
                throw new IllegalArgumentException(
                        "truth-table row input width does not match its input names");
            }
            if (row.outputValues().size() != outputNames.size()) {
                throw new IllegalArgumentException(
                        "truth-table row output width does not match its output names");
            }
        }
    }

    public record Row(
            int index,
            String inputBits,
            List<Value> outputValues,
            boolean oscillating) {
        public Row {
            if (index < 0) {
                throw new IllegalArgumentException("row index must not be negative");
            }
            Objects.requireNonNull(inputBits, "inputBits");
            outputValues = List.copyOf(Objects.requireNonNull(outputValues, "outputValues"));
            for (int bitIndex = 0; bitIndex < inputBits.length(); bitIndex++) {
                char bit = inputBits.charAt(bitIndex);
                if (bit != '0' && bit != '1') {
                    throw new IllegalArgumentException("inputBits must contain only 0 and 1");
                }
            }
        }

        public boolean inputValue(int index) {
            return inputBits.charAt(index) == '1';
        }
    }
}
