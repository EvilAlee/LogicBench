package io.github.evilalee.gatelab.ai;

/** Single source of truth for AI circuit response and user-check limits. */
public final class AiCircuitLimits {
    public static final int MAX_INPUTS = 16;
    public static final int MAX_OUTPUTS = 16;
    public static final int MAX_GATES = 400;
    public static final int MAX_SIGNALS = 160;
    public static final int MAX_EXPRESSION_LENGTH = 300;
    public static final int MAX_EXPRESSION_TEXT = 3_000;
    public static final int MAX_SPEC_RULES = 16;
    public static final int MAX_EXPECTED_ROWS = 64;
    public static final int MAX_ATTEMPTS = 3;
    public static final int MAX_INSTANCES = 32;

    private AiCircuitLimits() {
    }
}
