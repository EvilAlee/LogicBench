package io.github.evilalee.gatelab.synth;

/** Gate operations supported by expression netlists. Binary operations have exactly two inputs. */
public enum GateOperator {
    AND(2),
    OR(2),
    NAND(2),
    NOR(2),
    XOR(2),
    XNOR(2),
    NOT(1),
    BUFFER(1);

    private final int inputCount;

    GateOperator(int inputCount) {
        this.inputCount = inputCount;
    }

    public int inputCount() {
        return inputCount;
    }
}
