package io.github.evilalee.gatelab.synth;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Immutable Boolean expression abstract syntax tree. */
public sealed interface Expression
        permits Expression.Constant, Expression.Variable, Expression.Unary, Expression.Binary {

    /** Distinct variable names in left-to-right source order. */
    default List<String> variables() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        collectVariables(this, names);
        return List.copyOf(names);
    }

    private static void collectVariables(Expression expression, LinkedHashSet<String> names) {
        switch (expression) {
            case Constant ignored -> {
            }
            case Variable variable -> names.add(variable.name());
            case Unary unary -> collectVariables(unary.operand(), names);
            case Binary binary -> {
                collectVariables(binary.left(), names);
                collectVariables(binary.right(), names);
            }
        }
    }

    record Constant(boolean value) implements Expression {
    }

    record Variable(String name) implements Expression {
        public Variable {
            name = requireIdentifier(name, "variable name");
        }
    }

    enum UnaryOperator {
        NOT
    }

    record Unary(UnaryOperator operator, Expression operand) implements Expression {
        public Unary {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(operand, "operand");
        }
    }

    enum BinaryOperator {
        AND,
        NAND,
        XOR,
        XNOR,
        OR,
        NOR
    }

    record Binary(BinaryOperator operator, Expression left, Expression right)
            implements Expression {
        public Binary {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    private static String requireIdentifier(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!value.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(label + " must match [A-Za-z][A-Za-z0-9_]*: " + value);
        }
        return value;
    }
}
