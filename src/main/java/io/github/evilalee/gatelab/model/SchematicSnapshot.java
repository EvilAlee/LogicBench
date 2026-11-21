package io.github.evilalee.gatelab.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable editable-topology snapshot; live source values and simulation state are excluded. */
public record SchematicSnapshot(
        List<PlacedComponent> components,
        List<ComponentInstance> instances,
        Map<String, ComponentDefinition> componentDefinitions,
        List<WireSegment> wires) {

    public SchematicSnapshot {
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        instances = List.copyOf(Objects.requireNonNull(instances, "instances"));
        componentDefinitions = Map.copyOf(
                Objects.requireNonNull(componentDefinitions, "componentDefinitions"));
        wires = List.copyOf(Objects.requireNonNull(wires, "wires"));
    }

    /** Backwards-compatible constructor for documents without black-box instances. */
    public SchematicSnapshot(List<PlacedComponent> components, List<WireSegment> wires) {
        this(components, List.of(), Map.of(), wires);
    }

    public static SchematicSnapshot capture(Schematic schematic) {
        Objects.requireNonNull(schematic, "schematic");
        return new SchematicSnapshot(
                schematic.components(), schematic.componentInstances(),
                schematic.componentDefinitions(), schematic.wires());
    }
}
