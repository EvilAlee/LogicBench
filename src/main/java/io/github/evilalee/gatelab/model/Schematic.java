package io.github.evilalee.gatelab.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Editable placed-component layer. This is deliberately independent of the
 * engine Circuit: M6 will translate it into an engine graph.
 */
public final class Schematic {
    private final Map<String, GateDef> definitionsById;
    private final List<PlacedComponent> components = new ArrayList<>();
    private long nextComponentNumber = 1;

    public Schematic(Collection<GateDef> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        Map<String, GateDef> indexed = new HashMap<>();
        for (GateDef definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            if (indexed.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalArgumentException("duplicate component type: " + definition.id());
            }
        }
        if (indexed.isEmpty()) {
            throw new IllegalArgumentException("at least one component definition is required");
        }
        definitionsById = Map.copyOf(indexed);
    }

    /** Adds a new instance and allocates an id that is unique for this schematic. */
    public PlacedComponent add(String typeId, int x, int y, Facing facing) {
        requireKnownType(typeId);
        String id;
        do {
            id = "component-" + nextComponentNumber++;
        } while (containsId(id));

        PlacedComponent component = new PlacedComponent(id, typeId, x, y, facing);
        add(component);
        return component;
    }

    /** Adds a component with a caller-supplied id, rejecting duplicates. */
    public void add(PlacedComponent component) {
        Objects.requireNonNull(component, "component");
        requireKnownType(component.typeId());
        if (containsId(component.id())) {
            throw new IllegalArgumentException("duplicate component id: " + component.id());
        }
        components.add(component);
    }

    /** Removes the component with this id; returns false when it is not present. */
    public boolean remove(String id) {
        Objects.requireNonNull(id, "id");
        for (int index = 0; index < components.size(); index++) {
            if (components.get(index).id().equals(id)) {
                components.remove(index);
                return true;
            }
        }
        return false;
    }

    /** Moves a component's origin. The PlacedComponent constructor enforces grid alignment. */
    public void move(String id, int x, int y) {
        int index = indexOf(id);
        PlacedComponent current = components.get(index);
        components.set(index, new PlacedComponent(
                current.id(), current.typeId(), x, y, current.facing()));
    }

    /** Changes only the orientation; the origin remains fixed. */
    public void setFacing(String id, Facing facing) {
        int index = indexOf(id);
        PlacedComponent current = components.get(index);
        components.set(index, new PlacedComponent(
                current.id(), current.typeId(), current.x(), current.y(), facing));
    }

    /** Returns an immutable snapshot in paint order (oldest first, newest on top). */
    public List<PlacedComponent> components() {
        return List.copyOf(components);
    }

    public GateDef definitionFor(PlacedComponent component) {
        Objects.requireNonNull(component, "component");
        return requireKnownType(component.typeId());
    }

    /**
     * Returns the topmost component whose rotated world bounds contain the point.
     * Bounds edges are included, matching the canvas's visible component outline.
     */
    public Optional<PlacedComponent> componentAt(int worldX, int worldY) {
        for (int index = components.size() - 1; index >= 0; index--) {
            PlacedComponent component = components.get(index);
            GateGeometry.Bounds bounds = worldBounds(component);
            long right = (long) bounds.x() + bounds.width();
            long bottom = (long) bounds.y() + bounds.height();
            if (worldX >= bounds.x() && worldX <= right
                    && worldY >= bounds.y() && worldY <= bottom) {
                return Optional.of(component);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns components whose rotated bounds intersect the supplied world rectangle.
     * Results retain paint order. Rectangle edges are inclusive.
     */
    public List<PlacedComponent> componentsIntersecting(GateGeometry.Bounds rectangle) {
        Objects.requireNonNull(rectangle, "rectangle");
        if (rectangle.width() < 0 || rectangle.height() < 0) {
            throw new IllegalArgumentException("selection rectangle dimensions must not be negative");
        }

        long selectionRight = (long) rectangle.x() + rectangle.width();
        long selectionBottom = (long) rectangle.y() + rectangle.height();
        List<PlacedComponent> matches = new ArrayList<>();
        for (PlacedComponent component : components) {
            GateGeometry.Bounds bounds = worldBounds(component);
            long componentRight = (long) bounds.x() + bounds.width();
            long componentBottom = (long) bounds.y() + bounds.height();
            boolean intersects = bounds.x() <= selectionRight
                    && componentRight >= rectangle.x()
                    && bounds.y() <= selectionBottom
                    && componentBottom >= rectangle.y();
            if (intersects) {
                matches.add(component);
            }
        }
        return List.copyOf(matches);
    }

    private GateGeometry.Bounds worldBounds(PlacedComponent component) {
        GateGeometry.Bounds relative = GateGeometry.bounds(
                requireKnownType(component.typeId()), component.facing());
        return new GateGeometry.Bounds(
                component.x() + relative.x(),
                component.y() + relative.y(),
                relative.width(),
                relative.height());
    }

    private GateDef requireKnownType(String typeId) {
        Objects.requireNonNull(typeId, "typeId");
        GateDef definition = definitionsById.get(typeId);
        if (definition == null) {
            throw new IllegalArgumentException("unknown component type: " + typeId);
        }
        return definition;
    }

    private int indexOf(String id) {
        Objects.requireNonNull(id, "id");
        for (int index = 0; index < components.size(); index++) {
            if (components.get(index).id().equals(id)) {
                return index;
            }
        }
        throw new IllegalArgumentException("unknown component id: " + id);
    }

    private boolean containsId(String id) {
        return components.stream().anyMatch(component -> component.id().equals(id));
    }
}
