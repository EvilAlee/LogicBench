package io.github.evilalee.gatelab.synth;

import java.util.Map;
import java.util.Objects;

/** Pure-Java two-valued reference evaluator for expression ASTs. */
public final class ExpressionEvaluator {
    private ExpressionEvaluator() {
    }

    /**
     * Evaluates an expression for a complete Boolean assignment.
     *
     * @throws IllegalArgumentException when an identifier has no assignment
     */
    public static boolean evaluate(Expression expression, Map<String, Boolean> assignment) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(assignment, "assignment");
        return switch (expression) {
            case Expression.Constant constant -> constant.value();
            case Expression.Variable variable -> {
                Boolean value = assignment.get(variable.name());
                if (value == null) {
                    throw new IllegalArgumentException(
                            "missing Boolean assignment for variable: " + variable.name());
                }
                yield value;
            }
            case Expression.Unary unary ->
                    !evaluate(unary.operand(), assignment);
            case Expression.Binary binary -> evaluateBinary(binary, assignment);
            case Expression.PartCall call -> throw new IllegalArgumentException(
                    "sequential part calls cannot be evaluated as Boolean expressions: " + call.kind());
        };
    }

    private static boolean evaluateBinary(
            Expression.Binary binary, Map<String, Boolean> assignment) {
        boolean left = evaluate(binary.left(), assignment);
        boolean right = evaluate(binary.right(), assignment);
        return switch (binary.operator()) {
            case AND -> left && right;
            case NAND -> !(left && right);
            case XOR -> left ^ right;
            case XNOR -> left == right;
            case OR -> left || right;
            case NOR -> !(left || right);
        };
    }
}
