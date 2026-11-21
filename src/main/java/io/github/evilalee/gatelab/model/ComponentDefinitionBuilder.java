package io.github.evilalee.gatelab.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Headless builder for ordered component ports selected from a schematic. */
public final class ComponentDefinitionBuilder {
    /** One source interface component, in the order chosen by the user. */
    public record PortSelection(String componentId, boolean included, String name) {
        public PortSelection {
            Objects.requireNonNull(componentId, "componentId");
            if (componentId.isBlank()) {
                throw new IllegalArgumentException("componentId must not be blank");
            }
            name = name == null ? "" : name.trim();
        }
    }

    private ComponentDefinitionBuilder() {
    }

    /**
     * Creates a reusable component with exactly the selected input/output/clock ports.
     * Every source interface component must be represented once in the ordered selection list.
     */
    public static ComponentDefinition create(
            String name,
            String description,
            String category,
            List<PortSelection> orderedSelections,
            Schematic sourceCircuit) {
        Objects.requireNonNull(sourceCircuit, "sourceCircuit");
        Objects.requireNonNull(orderedSelections, "orderedSelections");

        Map<String, PlacedComponent> sourcePorts = new HashMap<>();
        for (PlacedComponent component : sourceCircuit.components()) {
            if (isPortComponent(component)) {
                sourcePorts.put(component.id(), component);
            }
        }
        if (sourcePorts.isEmpty()) {
            throw new IllegalArgumentException(
                    "circuit must contain at least one Input, Output, or Clock component");
        }

        Schematic internalCircuit = sourceCircuit.copy();
        List<ComponentDefinition.Port> ports = new ArrayList<>();
        Set<String> selectedComponentIds = new HashSet<>();
        Set<String> portNames = new HashSet<>();
        boolean hasInput = false;
        boolean hasOutput = false;

        for (PortSelection selection : List.copyOf(orderedSelections)) {
            Objects.requireNonNull(selection, "port selection");
            if (!selectedComponentIds.add(selection.componentId())) {
                throw new IllegalArgumentException(
                        "duplicate port component selection: " + selection.componentId());
            }
            PlacedComponent source = sourcePorts.get(selection.componentId());
            if (source == null) {
                throw new IllegalArgumentException(
                        "selection does not identify an Input, Output, or Clock component: "
                                + selection.componentId());
            }

            if (!selection.included()) {
                internalCircuit.remove(source.id());
                continue;
            }

            ComponentDefinition.Direction direction = directionOf(source);
            boolean clock = "clock".equals(source.typeId());
            ComponentDefinition.Port port = new ComponentDefinition.Port(
                    selection.name(), direction, clock);
            if (!portNames.add(port.name())) {
                throw new IllegalArgumentException("duplicate port name: " + port.name());
            }
            ports.add(port);
            hasInput |= direction == ComponentDefinition.Direction.IN;
            hasOutput |= direction == ComponentDefinition.Direction.OUT;

            if (!source.label().equals(port.name())) {
                internalCircuit.setLabel(source.id(), port.name());
            }
        }

        if (!selectedComponentIds.equals(sourcePorts.keySet())) {
            Set<String> missing = new HashSet<>(sourcePorts.keySet());
            missing.removeAll(selectedComponentIds);
            throw new IllegalArgumentException(
                    "port selections are missing source components: " + missing);
        }
        if (!hasInput) {
            throw new IllegalArgumentException(
                    "component definition must include at least one input or clock port");
        }
        if (!hasOutput) {
            throw new IllegalArgumentException(
                    "component definition must include at least one output port");
        }

        String primaryOutput = ports.stream()
                .filter(port -> port.direction() == ComponentDefinition.Direction.OUT)
                .map(ComponentDefinition.Port::name)
                .findFirst()
                .orElseThrow();
        return new ComponentDefinition(
                name, description, category, ports, primaryOutput, internalCircuit);
    }

    private static boolean isPortComponent(PlacedComponent component) {
        return switch (component.typeId()) {
            case "input", "clock", "output" -> true;
            default -> false;
        };
    }

    private static ComponentDefinition.Direction directionOf(PlacedComponent component) {
        return "output".equals(component.typeId())
                ? ComponentDefinition.Direction.OUT
                : ComponentDefinition.Direction.IN;
    }
}
