package io.github.evilalee.gatelab.model;

/** Three-valued digital logic. X represents an unknown or conflicting signal. */
public enum Value {
    ZERO,
    ONE,
    X;

    public Value inverted() {
        return switch (this) {
            case ZERO -> ONE;
            case ONE -> ZERO;
            case X -> X;
        };
    }
}
