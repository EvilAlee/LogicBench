package io.github.evilalee.gatelab.synth;

/** Gate operations supported by expression netlists. */
public enum GateOperator {
    AND(2, 5),
    OR(2, 5),
    NAND(2, 5),
    NOR(2, 5),
    XOR(2, 5),
    XNOR(2, 5),
    NOT(1, 1),
    BUFFER(1, 1);

    private final int minimumInputCount;
    private final int maximumInputCount;

    GateOperator(int minimumInputCount, int maximumInputCount) {
        this.minimumInputCount = minimumInputCount;
        this.maximumInputCount = maximumInputCount;
    }

    /** Minimum input count; retained for existing binary-expression callers. */
    public int inputCount() {
        return minimumInputCount;
    }

    public int minimumInputCount() {
        return minimumInputCount;
    }

    public int maximumInputCount() {
        return maximumInputCount;
    }

    public boolean acceptsInputCount(int count) {
        return count >= minimumInputCount && count <= maximumInputCount;
    }
}
