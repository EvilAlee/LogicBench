package io.github.evilalee.gatelab.io;

import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.DisplayComponent;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.SchematicSnapshot;
import io.github.evilalee.gatelab.model.SchematicHistory;
import io.github.evilalee.gatelab.model.WireSegment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Headless clipboard format and transactional paste support.
 *
 * The clipboard contains an ordinary, versioned .lbc document after a fixed
 * marker. Keeping the payload in the file format lets clipboard data carry
 * exact embedded component definitions without introducing a second schema.
 */
public final class SchematicClipboard {
    public static final String MARKER = "LOGICBENCH-CLIPBOARD v1";
    private static final int GRID = PlacedComponent.GRID_SPACING;
    private static final int MAX_SPIRAL_CELLS = 20;
    private static final List<Offset> SPIRAL = buildSpiral();

    public record Payload(Schematic schematic, List<String> notices) {
        public Payload {
            schematic = Objects.requireNonNull(schematic, "schematic").copy();
            // Clipboard data never restores momentary Input or Clock source levels.
            schematic.resetSimulationSources();
            notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
        }

        /** A caller receives an isolated document, never the mutable stored payload. */
        public Schematic schematic() {
            return schematic.copy();
        }

        private Schematic source() {
            return schematic;
        }
    }

    public record PasteResult(
            List<String> componentIds,
            List<WireSegment> wires,
            int deltaX,
            int deltaY,
            List<String> notices) {
        public PasteResult {
            componentIds = List.copyOf(Objects.requireNonNull(componentIds, "componentIds"));
            wires = List.copyOf(Objects.requireNonNull(wires, "wires"));
            notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
        }
    }

    private record Offset(int x, int y) {
    }

    private record Bounds(int x, int y) {
    }

    private SchematicClipboard() {
    }

    /**
     * Encodes only selected component/instance ids and the explicitly selected
     * wire segments. Live input and clock values are deliberately left at LOW.
     */
    public static Optional<String> encode(
            Schematic source,
            Collection<String> selectedIds,
            Collection<WireSegment> selectedWires) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(selectedIds, "selectedIds");
        Objects.requireNonNull(selectedWires, "selectedWires");
        Set<String> ids = new LinkedHashSet<>(selectedIds);
        Set<WireSegment> wires = new LinkedHashSet<>(selectedWires);
        wires.retainAll(source.wires());
        if (ids.isEmpty() && wires.isEmpty()) {
            return Optional.empty();
        }

        Schematic selection = new Schematic(source.gateDefinitions());
        for (PlacedComponent component : source.components()) {
            if (ids.contains(component.id())) {
                // Adding a new input/clock leaves its external state at the LOW default.
                selection.add(component);
            }
        }
        for (ComponentInstance instance : source.componentInstances()) {
            if (ids.contains(instance.id())) {
                selection.addInstance(instance, source.componentDefinitionFor(instance));
            }
        }
        for (WireSegment wire : source.wires()) {
            if (wires.contains(wire)) {
                selection.addWire(wire);
            }
        }
        if (selection.components().isEmpty()
                && selection.componentInstances().isEmpty()
                && selection.wires().isEmpty()) {
            return Optional.empty();
        }
        selection.setUndefinedInputMode(source.undefinedInputMode());
        return Optional.of(MARKER + "\n" + SchematicJson.toJson(selection));
    }

    /** Parses only the exact LogicBench marker followed by a valid versioned document. */
    public static Optional<Payload> decode(
            String clipboardText,
            Collection<GateDef> definitions,
            ComponentLibrary library) {
        Objects.requireNonNull(definitions, "definitions");
        if (clipboardText == null) {
            return Optional.empty();
        }
        int newline = clipboardText.indexOf('\n');
        if (newline < 0) {
            return Optional.empty();
        }
        String header = clipboardText.substring(0, newline).replace("\r", "");
        if (!MARKER.equals(header)) {
            return Optional.empty();
        }
        String json = clipboardText.substring(newline + 1).trim();
        if (json.isEmpty()) {
            return Optional.empty();
        }
        try {
            SchematicJson.LoadResult loaded =
                    SchematicJson.fromJsonWithNotices(json, definitions, library);
            if (loaded.schematic().components().isEmpty()
                    && loaded.schematic().componentInstances().isEmpty()
                    && loaded.schematic().wires().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Payload(loaded.schematic(), loaded.notices()));
        } catch (RuntimeException invalidPayload) {
            return Optional.empty();
        }
    }

    /**
     * Pastes the payload as a single target mutation. A supplied pointer is
     * aligned to the copied selection's top-left bound; otherwise the caller's
     * fallback translation is used. Unsafe candidates are tried in a 20-cell
     * right-then-down square spiral before the operation is rejected.
     */
    public static Optional<PasteResult> paste(
            Schematic target,
            Payload payload,
            Integer pointerWorldX,
            Integer pointerWorldY,
            int fallbackDeltaX,
            int fallbackDeltaY) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(payload, "payload");
        if ((pointerWorldX == null) != (pointerWorldY == null)) {
            throw new IllegalArgumentException("both pointer coordinates must be supplied together");
        }

        Schematic source = payload.source();
        if (source.components().isEmpty()
                && source.componentInstances().isEmpty()
                && source.wires().isEmpty()) {
            return Optional.empty();
        }
        if (fallbackDeltaX % GRID != 0 || fallbackDeltaY % GRID != 0) {
            throw new IllegalArgumentException("paste offset must stay on the schematic grid");
        }
        requireCompatibleDefinitions(target, source);

        Bounds bounds = selectionBounds(source);
        int initialDeltaX = pointerWorldX == null
                ? fallbackDeltaX
                : snap(pointerWorldX) - bounds.x();
        int initialDeltaY = pointerWorldY == null
                ? fallbackDeltaY
                : snap(pointerWorldY) - bounds.y();

        for (Offset offset : SPIRAL) {
            int deltaX = Math.addExact(initialDeltaX, offset.x());
            int deltaY = Math.addExact(initialDeltaY, offset.y());
            if (!preservesSeparation(target, source, deltaX, deltaY)) {
                continue;
            }

            Schematic staged = target.copy();
            List<String> newIds = new ArrayList<>();
            for (PlacedComponent component : source.components()) {
                PlacedComponent pasted = staged.add(
                        component.typeId(),
                        Math.addExact(component.x(), deltaX),
                        Math.addExact(component.y(), deltaY),
                        component.facing(),
                        component.attributes());
                if (!component.label().isEmpty()) {
                    staged.setLabel(pasted.id(), component.label());
                }
                newIds.add(pasted.id());
            }
            for (ComponentInstance instance : source.componentInstances()) {
                ComponentDefinition definition =
                        source.componentDefinitionFor(instance);
                ComponentInstance pasted = staged.addInstance(
                        definition, instance.label(),
                        Math.addExact(instance.x(), deltaX),
                        Math.addExact(instance.y(), deltaY),
                        instance.rotation());
                newIds.add(pasted.id());
            }

            List<WireSegment> pastedWires = new ArrayList<>();
            for (WireSegment wire : source.wires()) {
                WireSegment pasted = translate(wire, deltaX, deltaY);
                staged.addWire(pasted);
                pastedWires.add(pasted);
            }

            List<String> notices = new ArrayList<>(payload.notices());
            List<String> duplicateNames = duplicateIoNames(target, source);
            if (!duplicateNames.isEmpty()) {
                notices.add("Pasted I/O names already used in this schematic: "
                        + String.join(", ", duplicateNames) + ".");
            }

            target.restoreSnapshot(SchematicSnapshot.capture(staged));
            return Optional.of(new PasteResult(
                    newIds, pastedWires, deltaX, deltaY, notices));
        }
        return Optional.empty();
    }

    /** Deletes a selected topology as one headless undo step after the caller has copied it. */
    public static boolean deleteSelection(
            Schematic schematic,
            SchematicHistory history,
            Collection<String> selectedIds,
            Collection<WireSegment> selectedWires) {
        Objects.requireNonNull(schematic, "schematic");
        Objects.requireNonNull(history, "history");
        Set<String> ids = new LinkedHashSet<>(Objects.requireNonNull(selectedIds, "selectedIds"));
        Set<WireSegment> wires = new LinkedHashSet<>(
                Objects.requireNonNull(selectedWires, "selectedWires"));
        wires.retainAll(schematic.wires());
        boolean hasComponent = schematic.components().stream()
                .anyMatch(component -> ids.contains(component.id()));
        boolean hasInstance = schematic.componentInstances().stream()
                .anyMatch(instance -> ids.contains(instance.id()));
        if (!hasComponent && !hasInstance && wires.isEmpty()) {
            return false;
        }
        history.begin(schematic);
        for (String id : ids) {
            schematic.remove(id);
        }
        for (WireSegment wire : wires) {
            schematic.removeWire(wire);
        }
        return history.commit(schematic);
    }

    private static void requireCompatibleDefinitions(Schematic target, Schematic source) {
        Map<String, ComponentDefinition> existing = target.componentDefinitions();
        for (Map.Entry<String, ComponentDefinition> entry
                : source.componentDefinitions().entrySet()) {
            ComponentDefinition previous = existing.get(entry.getKey());
            if (previous != null && !previous.hash().equals(entry.getValue().hash())) {
                throw new IllegalArgumentException(
                        "Cannot paste component '" + entry.getKey()
                                + "': this schematic already contains a different embedded "
                                + "definition with the same name.");
            }
        }
    }

    private static Bounds selectionBounds(Schematic source) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        for (PlacedComponent component : source.components()) {
            GateGeometry.Bounds relative = GateGeometry.bounds(
                    source.definitionFor(component), component.facing());
            minX = Math.min(minX, Math.addExact(component.x(), relative.x()));
            minY = Math.min(minY, Math.addExact(component.y(), relative.y()));
        }
        for (ComponentInstance instance : source.componentInstances()) {
            GateGeometry.Bounds bounds =
                    instance.bounds(source.componentDefinitionFor(instance));
            minX = Math.min(minX, bounds.x());
            minY = Math.min(minY, bounds.y());
        }
        for (WireSegment wire : source.wires()) {
            minX = Math.min(minX, Math.min(wire.x1(), wire.x2()));
            minY = Math.min(minY, Math.min(wire.y1(), wire.y2()));
        }
        return new Bounds(minX == Integer.MAX_VALUE ? 0 : minX,
                minY == Integer.MAX_VALUE ? 0 : minY);
    }

    private static boolean preservesSeparation(
            Schematic target, Schematic source, int deltaX, int deltaY) {
        Set<Connectivity.Point> existingNodes = new LinkedHashSet<>();
        for (Connectivity.PinPosition pin : target.pinPositions()) {
            existingNodes.add(new Connectivity.Point(pin.x(), pin.y()));
        }
        List<WireSegment> existingWires = target.wires();
        for (WireSegment wire : existingWires) {
            existingNodes.add(new Connectivity.Point(wire.x1(), wire.y1()));
            existingNodes.add(new Connectivity.Point(wire.x2(), wire.y2()));
        }

        List<Connectivity.Point> incomingNodes = new ArrayList<>();
        for (Connectivity.PinPosition pin : source.pinPositions()) {
            incomingNodes.add(new Connectivity.Point(
                    Math.addExact(pin.x(), deltaX), Math.addExact(pin.y(), deltaY)));
        }
        List<WireSegment> incomingWires = source.wires().stream()
                .map(wire -> translate(wire, deltaX, deltaY)).toList();
        for (WireSegment wire : incomingWires) {
            incomingNodes.add(new Connectivity.Point(wire.x1(), wire.y1()));
            incomingNodes.add(new Connectivity.Point(wire.x2(), wire.y2()));
        }

        for (Connectivity.Point point : incomingNodes) {
            if (existingNodes.contains(point) || liesOnAnyWire(point, existingWires)) {
                return false;
            }
        }
        for (Connectivity.Point point : existingNodes) {
            if (liesOnAnyWire(point, incomingWires)) {
                return false;
            }
        }
        return true;
    }

    private static boolean liesOnAnyWire(
            Connectivity.Point point, Collection<WireSegment> wires) {
        for (WireSegment wire : wires) {
            boolean onWire = wire.isHorizontal()
                    ? point.y() == wire.y1()
                            && point.x() >= wire.x1() && point.x() <= wire.x2()
                    : point.x() == wire.x1()
                            && point.y() >= wire.y1() && point.y() <= wire.y2();
            if (onWire) {
                return true;
            }
        }
        return false;
    }

    private static List<String> duplicateIoNames(Schematic target, Schematic source) {
        Set<String> existing = target.components().stream()
                .filter(component -> isIo(component.typeId()) && !component.label().isBlank())
                .map(PlacedComponent::label)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return source.components().stream()
                .filter(component -> isIo(component.typeId()) && !component.label().isBlank())
                .map(PlacedComponent::label)
                .filter(existing::contains)
                .distinct()
                .sorted()
                .toList();
    }

    private static boolean isIo(String typeId) {
        return typeId.equals("input") || typeId.equals("output") || typeId.equals("clock")
                || DisplayComponent.isDisplay(typeId);
    }

    private static WireSegment translate(WireSegment wire, int deltaX, int deltaY) {
        return new WireSegment(
                Math.addExact(wire.x1(), deltaX), Math.addExact(wire.y1(), deltaY),
                Math.addExact(wire.x2(), deltaX), Math.addExact(wire.y2(), deltaY));
    }

    private static int snap(int coordinate) {
        return (int) (Math.floor(coordinate / (double) GRID + 0.5) * GRID);
    }

    private static List<Offset> buildSpiral() {
        List<Offset> offsets = new ArrayList<>(
                (MAX_SPIRAL_CELLS * 2 + 1) * (MAX_SPIRAL_CELLS * 2 + 1));
        Set<Offset> seen = new LinkedHashSet<>();
        int x = 0;
        int y = 0;
        addSpiralPoint(offsets, seen, x, y);
        int direction = 0; // right, down, left, up
        int length = 1;
        while (offsets.size() < (MAX_SPIRAL_CELLS * 2 + 1)
                * (MAX_SPIRAL_CELLS * 2 + 1)) {
            for (int repeat = 0; repeat < 2; repeat++) {
                for (int step = 0; step < length; step++) {
                    switch (direction) {
                        case 0 -> x++;
                        case 1 -> y++;
                        case 2 -> x--;
                        case 3 -> y--;
                        default -> throw new AssertionError(direction);
                    }
                    addSpiralPoint(offsets, seen, x, y);
                }
                direction = (direction + 1) % 4;
            }
            length++;
        }
        return List.copyOf(offsets);
    }

    private static void addSpiralPoint(
            List<Offset> offsets, Set<Offset> seen, int x, int y) {
        if (Math.abs(x) <= MAX_SPIRAL_CELLS
                && Math.abs(y) <= MAX_SPIRAL_CELLS) {
            Offset offset = new Offset(x * GRID, y * GRID);
            if (seen.add(offset)) {
                offsets.add(offset);
            }
        }
    }
}
