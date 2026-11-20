package io.github.evilalee.gatelab.synth;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Immutable Boolean expression abstract syntax tree. */
public sealed interface Expression
        permits Expression.Constant, Expression.Variable, Expression.Unary, Expression.Binary, Expression.PartCall {

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
            case PartCall call -> call.arguments().forEach(argument ->
                    collectVariables(argument, names));
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

    enum PartKind {
        DLATCH(2, false),
        DFF(2, true),
        TFF(2, true),
        JKFF(3, true);

        private final int argumentCount;
        private final boolean clocked;

        PartKind(int argumentCount, boolean clocked) {
            this.argumentCount = argumentCount;
            this.clocked = clocked;
        }

        public int argumentCount() {
            return argumentCount;
        }

        public boolean clocked() {
            return clocked;
        }
    }

    record PartCall(PartKind kind, List<Expression> arguments) implements Expression {
        public PartCall {
            Objects.requireNonNull(kind, "kind");
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
            if (arguments.size() != kind.argumentCount()) {
                throw new IllegalArgumentException(
                        kind + " requires " + kind.argumentCount()
                                + " arguments, got " + arguments.size());
            }
            if (kind.clocked()
                    && !(arguments.get(arguments.size() - 1) instanceof Variable)) {
                throw new IllegalArgumentException(
                        "CLK argument to " + kind + " must be a plain identifier");
            }
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
