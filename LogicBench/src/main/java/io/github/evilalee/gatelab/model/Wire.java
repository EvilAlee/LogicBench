package io.github.evilalee.gatelab.model;

import java.util.Objects;

/** An undirected electrical connection between two pins. */
public record Wire(Pin first, Pin second) {
    public Wire {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first == second) {
            throw new IllegalArgumentException("a wire must connect two different pins");
        }
        if (first.id().compareTo(second.id()) > 0) {
            Pin swap = first;
            first = second;
            second = swap;
        }
    }
}
