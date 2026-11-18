package io.github.evilalee.gatelab.model;

import java.util.List;
import java.util.Objects;

/** Immutable editable-topology snapshot; live input values and simulation state are excluded. */
public record SchematicSnapshot(
        List<PlacedComponent> components,
        List<WireSegment> wires) {

    public SchematicSnapshot {
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        wires = List.copyOf(Objects.requireNonNull(wires, "wires"));
    }

    public static SchematicSnapshot capture(Schematic schematic) {
        Objects.requireNonNull(schematic, "schematic");
        return new SchematicSnapshot(schematic.components(), schematic.wires());
    }
}
