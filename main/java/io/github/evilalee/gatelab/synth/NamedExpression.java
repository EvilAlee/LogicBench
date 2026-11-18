package io.github.evilalee.gatelab.synth;

import java.util.Objects;

/** One named expression in a multi-output synthesis request. */
public record NamedExpression(String name, Expression expression) {
    public NamedExpression {
        Objects.requireNonNull(name, "name");
        if (!name.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "output name must match [A-Za-z][A-Za-z0-9_]*: " + name);
        }
        Objects.requireNonNull(expression, "expression");
    }
}
