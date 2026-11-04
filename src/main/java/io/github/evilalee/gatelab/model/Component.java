package io.github.evilalee.gatelab.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Base type for a circuit element and the pins it owns. */
public abstract class Component {
    private final String id;
    private final List<Pin> pins = new ArrayList<>();

    protected Component(String id) {
        this.id = Objects.requireNonNull(id, "id").trim();
        if (this.id.isEmpty()) {
            throw new IllegalArgumentException("component id must not be blank");
        }
    }

    protected final Pin createPin(String name, Pin.Direction direction) {
        Pin pin = new Pin(id + "." + name, name, direction, this);
        pins.add(pin);
        return pin;
    }

    public final String id() {
        return id;
    }

    public final List<Pin> pins() {
        return List.copyOf(pins);
    }
}
