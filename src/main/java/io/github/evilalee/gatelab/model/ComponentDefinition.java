package io.github.evilalee.gatelab.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable black-box component metadata and its editable internal schematic.
 * Input, Clock, and Output component labels define the public port contract.
 */
public final class ComponentDefinition {
    public enum Direction {
        IN,
        OUT
    }

    public record Port(String name, Direction direction, boolean isClock) {
        public Port {
            name = requireIdentifier(name, "port name");
            Objects.requireNonNull(direction, "direction");
            if (isClock && direction != Direction.IN) {
                throw new IllegalArgumentException("clock port must have direction IN: " + name);
            }
        }
    }

    private final String name;
    private final String description;
    private final String category;
    private final List<Port> ports;
    private final String primaryOutput;
    private final Schematic internalCircuit;
    private final String hash;

    public ComponentDefinition(
            String name,
            String description,
            String category,
            List<Port> ports,
            String primaryOutput,
            Schematic internalCircuit) {
        this.name = requireIdentifier(name, "definition name");
        this.description = requireText(description, "description");
        this.category = requireText(category, "category");
        this.ports = List.copyOf(Objects.requireNonNull(ports, "ports"));
        if (this.ports.isEmpty()) {
            throw new IllegalArgumentException("component definition must have at least one port");
        }
        Set<String> names = new HashSet<>();
        for (Port port : this.ports) {
            Objects.requireNonNull(port, "port");
            if (!names.add(port.name())) {
                throw new IllegalArgumentException("duplicate port name: " + port.name());
            }
        }
        String normalizedPrimary = primaryOutput == null || primaryOutput.isBlank()
                ? null : requireIdentifier(primaryOutput.trim(), "primaryOutput");
        if (normalizedPrimary != null && this.ports.stream().noneMatch(port ->
                port.name().equals(normalizedPrimary) && port.direction() == Direction.OUT)) {
            throw new IllegalArgumentException(
                    "primaryOutput must name an OUT port: " + normalizedPrimary);
        }
        this.primaryOutput = normalizedPrimary;
        this.internalCircuit = Objects.requireNonNull(internalCircuit, "internalCircuit").copy();
        validatePorts(this.ports, this.internalCircuit);
        this.hash = calculateHash();
    }

    public static ComponentDefinition fromCircuit(
            String name, String description, String category,
            String primaryOutput, Schematic internalCircuit) {
        Schematic snapshot = Objects.requireNonNull(internalCircuit, "internalCircuit").copy();
        List<Port> ports = portsFromCircuit(snapshot);
        return new ComponentDefinition(
                name, description, category, ports, primaryOutput, snapshot);
    }

    public static List<Port> portsFromCircuit(Schematic circuit) {
        Objects.requireNonNull(circuit, "circuit");
        List<Port> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (PlacedComponent component : circuit.components()) {
            Direction direction;
            boolean clock;
            switch (component.typeId()) {
                case "input" -> {
                    direction = Direction.IN;
                    clock = false;
                }
                case "clock" -> {
                    direction = Direction.IN;
                    clock = true;
                }
                case "output" -> {
                    direction = Direction.OUT;
                    clock = false;
                }
                default -> {
                    continue;
                }
            }
            if (component.label().isBlank()) {
                throw new IllegalArgumentException(
                        "definition port component must have a Name: " + component.id());
            }
            String portName = requireIdentifier(component.label(), "port name");
            if (!names.add(portName)) {
                throw new IllegalArgumentException("duplicate port name: " + portName);
            }
            result.add(new Port(portName, direction, clock));
        }
        return List.copyOf(result);
    }

    private static void validatePorts(List<Port> ports, Schematic circuit) {
        Map<String, Port> expected = new HashMap<>();
        ports.forEach(port -> expected.put(port.name(), port));
        Set<String> found = new HashSet<>();
        for (PlacedComponent component : circuit.components()) {
            Direction direction;
            boolean clock;
            switch (component.typeId()) {
                case "input" -> {
                    direction = Direction.IN;
                    clock = false;
                }
                case "clock" -> {
                    direction = Direction.IN;
                    clock = true;
                }
                case "output" -> {
                    direction = Direction.OUT;
                    clock = false;
                }
                default -> {
                    continue;
                }
            }
            if (component.label().isBlank()) {
                throw new IllegalArgumentException(
                        "definition port component must have a Name: " + component.id());
            }
            String portName = requireIdentifier(component.label(), "port name");
            Port declared = expected.get(portName);
            if (declared == null) {
                throw new IllegalArgumentException(
                        "internal port component has no matching definition port: " + portName);
            }
            if (declared.direction() != direction || declared.isClock() != clock) {
                throw new IllegalArgumentException(
                        "port direction/type does not match internal component: " + portName);
            }
            if (!found.add(portName)) {
                throw new IllegalArgumentException("duplicate port name: " + portName);
            }
        }
        if (!found.equals(expected.keySet())) {
            Set<String> missing = new java.util.TreeSet<>(expected.keySet());
            missing.removeAll(found);
            throw new IllegalArgumentException(
                    "definition ports must match named internal Input/Output/Clock components; "
                            + "missing " + missing);
        }
    }

    private String calculateHash() {
        StringBuilder canonical = new StringBuilder();
        append(canonical, name);
        append(canonical, description);
        append(canonical, category);
        append(canonical, primaryOutput == null ? "" : primaryOutput);
        for (Port port : ports) {
            append(canonical, port.name());
            append(canonical, port.direction().name());
            append(canonical, Boolean.toString(port.isClock()));
        }
        append(canonical, internalCircuit.undefinedInputMode().jsonValue());
        internalCircuit.components().stream()
                .sorted(Comparator.comparing(PlacedComponent::id))
                .forEach(component -> {
                    append(canonical, component.id());
                    append(canonical, component.typeId());
                    append(canonical, Integer.toString(component.x()));
                    append(canonical, Integer.toString(component.y()));
                    append(canonical, component.facing().name());
                    append(canonical, component.label());
                    component.attributes().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(entry -> {
                                append(canonical, entry.getKey());
                                append(canonical, entry.getValue());
                            });
                    // Source levels are runtime state, not part of a reusable definition's identity.
                });
        internalCircuit.componentInstances().stream()
                .sorted(Comparator.comparing(ComponentInstance::id))
                .forEach(instance -> {
                    append(canonical, instance.id());
                    append(canonical, instance.definitionName());
                    append(canonical, instance.definitionHash());
                    append(canonical, Integer.toString(instance.x()));
                    append(canonical, Integer.toString(instance.y()));
                    append(canonical, instance.rotation().name());
                    append(canonical, instance.label());
                });
        internalCircuit.wires().stream()
                .sorted(Comparator.comparingInt(WireSegment::x1)
                        .thenComparingInt(WireSegment::y1)
                        .thenComparingInt(WireSegment::x2)
                        .thenComparingInt(WireSegment::y2))
                .forEach(wire -> append(canonical, wire.toString()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }

    private static String requireIdentifier(String value, String label) {
        String text = requireText(value, label);
        if (!text.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    label + " must contain only letters, digits and underscores: " + value);
        }
        return text;
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        String text = value.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return text;
    }

    public String name() { return name; }
    public String description() { return description; }
    public String category() { return category; }
    public List<Port> ports() { return ports; }
    public String primaryOutput() { return primaryOutput; }
    public String hash() { return hash; }

    /** Returns an isolated copy so callers cannot mutate the definition. */
    public Schematic internalCircuit() {
        return internalCircuit.copy();
    }

    public List<Port> inputPorts() {
        return ports.stream().filter(port -> port.direction() == Direction.IN).toList();
    }

    public List<Port> outputPorts() {
        return ports.stream().filter(port -> port.direction() == Direction.OUT).toList();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ComponentDefinition definition
                && hash.equals(definition.hash);
    }

    @Override
    public int hashCode() {
        return hash.hashCode();
    }

    @Override
    public String toString() {
        return name + "@" + hash.substring(0, 12);
    }
}
