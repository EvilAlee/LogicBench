package io.github.evilalee.gatelab.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Mutable circuit topology. A topology revision lets simulators rebuild nets safely. */
public final class Circuit {
    private final Map<String, Component> components = new LinkedHashMap<>();
    private final Set<Wire> wires = new LinkedHashSet<>();
    private long revision;

    public void addComponent(Component component) {
        Objects.requireNonNull(component, "component");
        if (components.containsKey(component.id())) {
            throw new IllegalArgumentException("duplicate component id: " + component.id());
        }
        components.put(component.id(), component);
        revision++;
    }

    public boolean removeComponent(Component component) {
        Objects.requireNonNull(component, "component");
        if (components.get(component.id()) != component) {
            return false;
        }
        Set<Pin> ownedPins = Set.copyOf(component.pins());
        wires.removeIf(wire -> ownedPins.contains(wire.first()) || ownedPins.contains(wire.second()));
        components.remove(component.id());
        revision++;
        return true;
    }

    public Wire connect(Pin first, Pin second) {
        requireOwned(first);
        requireOwned(second);
        Wire wire = new Wire(first, second);
        if (wires.add(wire)) {
            revision++;
        }
        return wire;
    }

    public boolean disconnect(Wire wire) {
        Objects.requireNonNull(wire, "wire");
        boolean removed = wires.remove(wire);
        if (removed) {
            revision++;
        }
        return removed;
    }

    public boolean disconnect(Pin first, Pin second) {
        return disconnect(new Wire(first, second));
    }

    public boolean contains(Component component) {
        return component != null && components.get(component.id()) == component;
    }

    public boolean contains(Pin pin) {
        return pin != null && contains(pin.component());
    }

    public List<Component> components() {
        return List.copyOf(components.values());
    }

    public List<Wire> wires() {
        return List.copyOf(wires);
    }

    public List<Pin> pins() {
        List<Pin> result = new ArrayList<>();
        for (Component component : components.values()) {
            result.addAll(component.pins());
        }
        return List.copyOf(result);
    }

    public long revision() {
        return revision;
    }

    private void requireOwned(Pin pin) {
        Objects.requireNonNull(pin, "pin");
        if (!contains(pin)) {
            throw new IllegalArgumentException("pin is not part of this circuit: " + pin.id());
        }
    }
}
