package io.github.evilalee.gatelab.model;

import java.util.List;
import java.util.Objects;

/** Supported combinational Boolean operators with three-valued semantics. */
public enum GateType {
    AND(2, 8),
    OR(2, 8),
    NAND(2, 8),
    NOR(2, 8),
    XOR(2, 8),
    XNOR(2, 8),
    NOT(1, 1),
    BUFFER(1, 1);

    private final int minimumInputs;
    private final int maximumInputs;

    GateType(int minimumInputs, int maximumInputs) {
        this.minimumInputs = minimumInputs;
        this.maximumInputs = maximumInputs;
    }

    public int minimumInputs() {
        return minimumInputs;
    }

    public int maximumInputs() {
        return maximumInputs;
    }

    public boolean acceptsInputCount(int count) {
        return count >= minimumInputs && count <= maximumInputs;
    }

    public Value evaluate(Value... inputs) {
        return evaluate(List.of(inputs));
    }

    public Value evaluate(List<Value> inputs) {
        Objects.requireNonNull(inputs, "inputs");
        if (!acceptsInputCount(inputs.size())) {
            throw new IllegalArgumentException(
                    name() + " requires " + minimumInputs + ".." + maximumInputs
                            + " inputs, got " + inputs.size());
        }
        inputs.forEach(value -> Objects.requireNonNull(value, "input value"));

        return switch (this) {
            case AND -> and(inputs);
            case OR -> or(inputs);
            case NAND -> and(inputs).inverted();
            case NOR -> or(inputs).inverted();
            case XOR -> xor(inputs);
            case XNOR -> xor(inputs).inverted();
            case NOT -> inputs.getFirst().inverted();
            case BUFFER -> inputs.getFirst();
        };
    }

    private static Value and(List<Value> inputs) {
        if (inputs.contains(Value.ZERO)) {
            return Value.ZERO;
        }
        return inputs.contains(Value.X) ? Value.X : Value.ONE;
    }

    private static Value or(List<Value> inputs) {
        if (inputs.contains(Value.ONE)) {
            return Value.ONE;
        }
        return inputs.contains(Value.X) ? Value.X : Value.ZERO;
    }

    private static Value xor(List<Value> inputs) {
        if (inputs.contains(Value.X)) {
            return Value.X;
        }
        boolean parity = false;
        for (Value input : inputs) {
            parity ^= input == Value.ONE;
        }
        return parity ? Value.ONE : Value.ZERO;
    }
}
