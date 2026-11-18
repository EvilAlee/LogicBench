package io.github.evilalee.gatelab.model;

import java.util.Objects;

/** A named electrical terminal belonging to one component. */
public final class Pin {
    public enum Direction {
        INPUT,
        OUTPUT
    }

    private final String id;
    private final String name;
    private final Direction direction;
    private final Component component;

    Pin(String id, String name, Direction direction, Component component) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.direction = Objects.requireNonNull(direction, "direction");
        this.component = Objects.requireNonNull(component, "component");
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Direction direction() {
        return direction;
    }

    public Component component() {
        return component;
    }

    @Override
    public String toString() {
        return id;
    }
}
