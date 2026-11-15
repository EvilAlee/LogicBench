package io.github.evilalee.gatelab.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Editable placed-component layer. This is deliberately independent of the
 * engine Circuit: M6 will translate it into an engine graph.
 */
public final class Schematic {
    private final Map<String, GateDef> definitionsById;
    private final List<PlacedComponent> components = new ArrayList<>();
    private final List<WireSegment> wires = new ArrayList<>();
    private final Map<String, Boolean> inputValues = new HashMap<>();
    private final List<Runnable> changeListeners = new ArrayList<>();
    private long modificationCount;
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
        notifyChanged();
    }

    /** Removes the component with this id; returns false when it is not present. */
    public boolean remove(String id) {
        Objects.requireNonNull(id, "id");
        for (int index = 0; index < components.size(); index++) {
            if (components.get(index).id().equals(id)) {
                components.remove(index);
                inputValues.remove(id);
                notifyChanged();
                return true;
            }
        }
        return false;
    }

    /** Moves a component's origin and preserves any stationary pin attachments with links. */
    public void move(String id, int x, int y) {
        PlacedComponent current = components.get(indexOf(id));
        moveSelection(List.of(id), List.of(), x - current.x(), y - current.y());
    }

    /**
     * Moves a complete component/wire selection as one model mutation. Existing stationary
     * wires are left untouched; links are added only where they touched a moved pin before
     * the move. Wires included in the selection move without generating links.
     *
     * @return the translated selected wires, in their selection order
     */
    public List<WireSegment> moveSelection(
            Collection<String> selectedComponentIds,
            Collection<WireSegment> selectedWireSegments,
            int deltaX,
            int deltaY) {
        Objects.requireNonNull(selectedComponentIds, "selectedComponentIds");
        Objects.requireNonNull(selectedWireSegments, "selectedWireSegments");
        requireGridDelta(deltaX, deltaY);

        Set<String> movingComponentIds = new LinkedHashSet<>(selectedComponentIds);
        for (String id : movingComponentIds) {
            indexOf(Objects.requireNonNull(id, "component id"));
        }
        Set<WireSegment> movingWires = new LinkedHashSet<>(selectedWireSegments);
        movingWires.retainAll(wires);

        if (deltaX == 0 && deltaY == 0) {
            return List.copyOf(movingWires);
        }

        Map<String, PlacedComponent> replacements = new LinkedHashMap<>();
        if (!movingComponentIds.isEmpty()) {
            for (PlacedComponent current : components) {
                if (movingComponentIds.contains(current.id())) {
                    replacements.put(current.id(), new PlacedComponent(
                            current.id(), current.typeId(),
                            current.x() + deltaX, current.y() + deltaY,
                            current.facing()));
                }
            }
        }
        if (replacements.isEmpty() && movingWires.isEmpty()) {
            return List.of();
        }

        // Determine attachment links against the original geometry, before moving any wires.
        List<PinMove> linkedPins = pinsWithStationaryAttachments(
                replacements, movingComponentIds, movingWires);

        List<WireSegment> movedWires = new ArrayList<>(movingWires.size());
        if (!movingWires.isEmpty()) {
            wires.removeAll(movingWires);
            for (WireSegment wire : movingWires) {
                WireSegment translated = translateWire(wire, deltaX, deltaY);
                if (!wires.contains(translated)) {
                    wires.add(translated);
                }
                movedWires.add(translated);
            }
        }

        replaceComponents(replacements);
        for (PinMove pinMove : linkedPins) {
            addConnectingPath(pinMove.oldPin(), pinMove.newPin());
        }
        notifyChanged();
        return List.copyOf(new LinkedHashSet<>(movedWires));
    }

    /** Changes one component's orientation and adds links for pins that were attached. */
    public void setFacing(String id, Facing facing) {
        setFacing(List.of(id), facing);
    }

    /**
     * Applies a facing change to the complete selection. Pins on other selected components
     * are not treated as stationary attachments, so a selection rotates as one gesture.
     */
    public void setFacing(Collection<String> selectedComponentIds, Facing facing) {
        Objects.requireNonNull(selectedComponentIds, "selectedComponentIds");
        Objects.requireNonNull(facing, "facing");
        Set<String> movingComponentIds = new LinkedHashSet<>(selectedComponentIds);
        for (String id : movingComponentIds) {
            indexOf(Objects.requireNonNull(id, "component id"));
        }

        Map<String, PlacedComponent> replacements = new LinkedHashMap<>();
        for (PlacedComponent current : components) {
            if (movingComponentIds.contains(current.id()) && current.facing() != facing) {
                replacements.put(current.id(), new PlacedComponent(
                        current.id(), current.typeId(), current.x(), current.y(), facing));
            }
        }
        if (replacements.isEmpty()) {
            return;
        }

        List<PinMove> linkedPins = pinsWithStationaryAttachments(
                replacements, movingComponentIds, Set.of());
        replaceComponents(replacements);
        for (PinMove pinMove : linkedPins) {
            addConnectingPath(pinMove.oldPin(), pinMove.newPin());
        }
        notifyChanged();
    }

    /**
     * Replaces editable topology with a prior snapshot. Input values are external state:
     * values survive only for input ids that exist both before and after restoration.
     */
    public void restoreSnapshot(SchematicSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<PlacedComponent> replacementComponents = snapshot.components();
        List<WireSegment> replacementWires = snapshot.wires();
        Set<String> replacementIds = new LinkedHashSet<>();
        for (PlacedComponent component : replacementComponents) {
            requireKnownType(component.typeId());
            if (!replacementIds.add(component.id())) {
                throw new IllegalArgumentException("duplicate component id: " + component.id());
            }
        }
        if (components.equals(replacementComponents) && wires.equals(replacementWires)) {
            return;
        }

        Map<String, Boolean> survivingInputValues = new HashMap<>();
        for (PlacedComponent current : components) {
            if ("input".equals(current.typeId())
                    && Boolean.TRUE.equals(inputValues.get(current.id()))) {
                survivingInputValues.put(current.id(), true);
            }
        }

        components.clear();
        components.addAll(replacementComponents);
        wires.clear();
        wires.addAll(replacementWires);
        inputValues.clear();
        for (PlacedComponent replacement : replacementComponents) {
            if ("input".equals(replacement.typeId())
                    && Boolean.TRUE.equals(survivingInputValues.get(replacement.id()))) {
                inputValues.put(replacement.id(), true);
            }
        }
        notifyChanged();
    }

    /** Returns an immutable snapshot in paint order (oldest first, newest on top). */
    public List<PlacedComponent> components() {
        return List.copyOf(components);
    }

    /** Number of successful schematic mutations; queries never change this value. */
    public long modificationCount() {
        return modificationCount;
    }

    public void addChangeListener(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        if (!changeListeners.contains(listener)) {
            changeListeners.add(listener);
        }
    }

    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(Objects.requireNonNull(listener, "listener"));
    }

    /** Input switches are LOW by default and keep their state through rebuilds. */
    public boolean inputValue(String id) {
        requireInput(id);
        return Boolean.TRUE.equals(inputValues.get(id));
    }

    public void setInputValue(String id, boolean value) {
        requireInput(id);
        if (inputValue(id) == value) {
            return;
        }
        if (value) {
            inputValues.put(id, true);
        } else {
            inputValues.remove(id);
        }
        notifyChanged();
    }

    public boolean toggleInput(String id) {
        boolean next = !inputValue(id);
        setInputValue(id, next);
        return next;
    }

    public boolean resetInputs() {
        if (inputValues.isEmpty()) {
            return false;
        }
        inputValues.clear();
        notifyChanged();
        return true;
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


    /** Adds a geometric wire, ignoring exact duplicates. */
    public boolean addWire(WireSegment wire) {
        Objects.requireNonNull(wire, "wire");
        if (wires.contains(wire)) {
            return false;
        }
        wires.add(wire);
        notifyChanged();
        return true;
    }

    /** Adds a wire from coordinates; a zero-length gesture is a no-op. */
    public boolean addWire(int x1, int y1, int x2, int y2) {
        if (x1 == x2 && y1 == y2) {
            return false;
        }
        return addWire(new WireSegment(x1, y1, x2, y2));
    }

    /** Removes a geometric wire; returns false when it is not present. */
    public boolean removeWire(WireSegment wire) {
        Objects.requireNonNull(wire, "wire");
        boolean removed = wires.remove(wire);
        if (removed) {
            notifyChanged();
        }
        return removed;
    }

    /** Returns an immutable snapshot in insertion order. */
    public List<WireSegment> wires() {
        return List.copyOf(wires);
    }

    /**
     * Moves only the supplied wires. Wires are independent of component movement;
     * duplicate results are collapsed through the same schematic mutation boundary.
     */
    public List<WireSegment> moveWires(Collection<WireSegment> selected, int deltaX, int deltaY) {
        Objects.requireNonNull(selected, "selected");
        if (deltaX % PlacedComponent.GRID_SPACING != 0
                || deltaY % PlacedComponent.GRID_SPACING != 0) {
            throw new IllegalArgumentException("wire movement must stay on the schematic grid");
        }

        Set<WireSegment> moving = new LinkedHashSet<>(selected);
        moving.retainAll(wires);
        if ((deltaX == 0 && deltaY == 0) || moving.isEmpty()) {
            return List.copyOf(moving);
        }

        wires.removeAll(moving);
        List<WireSegment> moved = new ArrayList<>(moving.size());
        for (WireSegment wire : moving) {
            WireSegment translated = new WireSegment(
                    wire.x1() + deltaX, wire.y1() + deltaY,
                    wire.x2() + deltaX, wire.y2() + deltaY);
            if (!wires.contains(translated)) {
                wires.add(translated);
            }
            moved.add(translated);
        }
        notifyChanged();
        return List.copyOf(new LinkedHashSet<>(moved));
    }

    /** Finds the topmost wire no farther than tolerance from a world-space point. */
    public Optional<WireSegment> wireAt(double worldX, double worldY, double tolerance) {
        if (!Double.isFinite(worldX) || !Double.isFinite(worldY)
                || !Double.isFinite(tolerance) || tolerance < 0.0) {
            throw new IllegalArgumentException("wire hit-test coordinates and tolerance must be finite");
        }
        for (int index = wires.size() - 1; index >= 0; index--) {
            WireSegment wire = wires.get(index);
            double nearestX = wire.isHorizontal()
                    ? Math.max(wire.x1(), Math.min(wire.x2(), worldX)) : wire.x1();
            double nearestY = wire.isHorizontal()
                    ? wire.y1() : Math.max(wire.y1(), Math.min(wire.y2(), worldY));
            if (Math.hypot(worldX - nearestX, worldY - nearestY) <= tolerance) {
                return Optional.of(wire);
            }
        }
        return Optional.empty();
    }

    /** Returns wires touched by an inclusive world-space marquee rectangle. */
    public List<WireSegment> wiresIntersecting(GateGeometry.Bounds rectangle) {
        Objects.requireNonNull(rectangle, "rectangle");
        if (rectangle.width() < 0 || rectangle.height() < 0) {
            throw new IllegalArgumentException("selection rectangle dimensions must not be negative");
        }
        long right = (long) rectangle.x() + rectangle.width();
        long bottom = (long) rectangle.y() + rectangle.height();
        List<WireSegment> matches = new ArrayList<>();
        for (WireSegment wire : wires) {
            boolean intersects = wire.isHorizontal()
                    ? wire.y1() >= rectangle.y() && wire.y1() <= bottom
                            && wire.x2() >= rectangle.x() && wire.x1() <= right
                    : wire.x1() >= rectangle.x() && wire.x1() <= right
                            && wire.y2() >= rectangle.y() && wire.y1() <= bottom;
            if (intersects) {
                matches.add(wire);
            }
        }
        return List.copyOf(matches);
    }

    /** Returns each placed component pin at its M3-rotated world coordinate. */
    public List<Connectivity.PinPosition> pinPositions() {
        List<Connectivity.PinPosition> pins = new ArrayList<>();
        for (PlacedComponent component : components) {
            pins.addAll(pinPositionsFor(component));
        }
        return List.copyOf(pins);
    }

    private List<Connectivity.PinPosition> pinPositionsFor(PlacedComponent component) {
        GateDef definition = requireKnownType(component.typeId());
        List<Connectivity.PinPosition> pins = new ArrayList<>();
        for (int index = 0; index < definition.inputs().size(); index++) {
            GateGeometry.Offset offset = GateGeometry.pinOffset(
                    definition, definition.inputs().get(index), component.facing());
            pins.add(new Connectivity.PinPosition(
                    component.id(), "in" + index,
                    component.x() + offset.x(), component.y() + offset.y()));
        }
        if (definition.output() != null) {
            GateGeometry.Offset offset = GateGeometry.outputOffset(definition, component.facing());
            pins.add(new Connectivity.PinPosition(
                    component.id(), "out",
                    component.x() + offset.x(), component.y() + offset.y()));
        }
        return List.copyOf(pins);
    }

    private List<PinMove> pinsWithStationaryAttachments(
            Map<String, PlacedComponent> replacements,
            Set<String> movingComponentIds,
            Set<WireSegment> movingWires) {
        Set<Connectivity.Point> stationaryPinPoints = new LinkedHashSet<>();
        for (Connectivity.PinPosition pin : pinPositions()) {
            if (!movingComponentIds.contains(pin.componentId())) {
                stationaryPinPoints.add(new Connectivity.Point(pin.x(), pin.y()));
            }
        }

        List<PinMove> linked = new ArrayList<>();
        for (PlacedComponent oldComponent : components) {
            PlacedComponent newComponent = replacements.get(oldComponent.id());
            if (newComponent == null) {
                continue;
            }
            Map<String, Connectivity.PinPosition> newPinsById = new HashMap<>();
            for (Connectivity.PinPosition pin : pinPositionsFor(newComponent)) {
                newPinsById.put(pin.pinId(), pin);
            }
            for (Connectivity.PinPosition oldPin : pinPositionsFor(oldComponent)) {
                Connectivity.PinPosition newPin = newPinsById.get(oldPin.pinId());
                if (newPin == null || (oldPin.x() == newPin.x() && oldPin.y() == newPin.y())) {
                    continue;
                }
                Connectivity.Point oldPoint = new Connectivity.Point(oldPin.x(), oldPin.y());
                boolean touchedByWire = wires.stream().anyMatch(wire ->
                        !movingWires.contains(wire) && contains(wire, oldPoint));
                if (stationaryPinPoints.contains(oldPoint) || touchedByWire) {
                    linked.add(new PinMove(oldPin, newPin));
                }
            }
        }
        return List.copyOf(linked);
    }

    private void replaceComponents(Map<String, PlacedComponent> replacements) {
        for (int index = 0; index < components.size(); index++) {
            PlacedComponent current = components.get(index);
            PlacedComponent replacement = replacements.get(current.id());
            if (replacement != null) {
                components.set(index, replacement);
            }
        }
    }

    private static boolean contains(WireSegment wire, Connectivity.Point point) {
        if (wire.isHorizontal()) {
            return point.y() == wire.y1()
                    && point.x() >= wire.x1() && point.x() <= wire.x2();
        }
        return point.x() == wire.x1()
                && point.y() >= wire.y1() && point.y() <= wire.y2();
    }

    private void addConnectingPath(
            Connectivity.PinPosition oldPin, Connectivity.PinPosition newPin) {
        int oldX = oldPin.x();
        int oldY = oldPin.y();
        int newX = newPin.x();
        int newY = newPin.y();
        long deltaX = Math.abs((long) newX - oldX);
        long deltaY = Math.abs((long) newY - oldY);

        if (deltaX == 0 || deltaY == 0) {
            addWireIfAbsent(oldX, oldY, newX, newY);
        } else if (deltaX >= deltaY) {
            addWireIfAbsent(oldX, oldY, newX, oldY);
            addWireIfAbsent(newX, oldY, newX, newY);
        } else {
            addWireIfAbsent(oldX, oldY, oldX, newY);
            addWireIfAbsent(oldX, newY, newX, newY);
        }
    }

    private boolean addWireIfAbsent(int x1, int y1, int x2, int y2) {
        if (x1 == x2 && y1 == y2) {
            return false;
        }
        WireSegment wire = new WireSegment(x1, y1, x2, y2);
        if (wires.contains(wire)) {
            return false;
        }
        wires.add(wire);
        return true;
    }

    private static WireSegment translateWire(WireSegment wire, int deltaX, int deltaY) {
        return new WireSegment(
                wire.x1() + deltaX, wire.y1() + deltaY,
                wire.x2() + deltaX, wire.y2() + deltaY);
    }

    private static void requireGridDelta(int deltaX, int deltaY) {
        if (deltaX % PlacedComponent.GRID_SPACING != 0
                || deltaY % PlacedComponent.GRID_SPACING != 0) {
            throw new IllegalArgumentException("component and wire movement must stay on the schematic grid");
        }
    }

    private record PinMove(
            Connectivity.PinPosition oldPin,
            Connectivity.PinPosition newPin) {
    }

    private PlacedComponent requireInput(String id) {
        Objects.requireNonNull(id, "id");
        PlacedComponent component = components.get(indexOf(id));
        if (!"input".equals(component.typeId())) {
            throw new IllegalArgumentException("component is not an input: " + id);
        }
        return component;
    }

    private void notifyChanged() {
        modificationCount++;
        for (Runnable listener : List.copyOf(changeListeners)) {
            listener.run();
        }
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
