package io.github.evilalee.gatelab.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Set;
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
    /** Result of one atomic component/wire move gesture. */
    public record MoveResult(List<WireSegment> movedWires, boolean linksFollowed) {
        public MoveResult {
            movedWires = List.copyOf(Objects.requireNonNull(movedWires, "movedWires"));
        }
    }

    private record PinKey(String componentId, String pinId) {
    }

    private record PinMove(
            Connectivity.PinPosition oldPin,
            Connectivity.PinPosition newPin) {
    }

    private record GestureCommit(boolean linksFollowed, boolean committed) {
    }

    private final Map<String, List<GateDef>> definitionsByFamily;
    private UndefinedInputMode undefinedInputMode = UndefinedInputMode.IGNORE;
    private final List<PlacedComponent> components = new ArrayList<>();
    private final List<ComponentInstance> componentInstances = new ArrayList<>();
    private final Map<String, ComponentDefinition> componentDefinitions = new LinkedHashMap<>();
    private final List<WireSegment> wires = new ArrayList<>();
    private final Map<String, Boolean> inputValues = new HashMap<>();
    private final Map<String, Boolean> clockValues = new HashMap<>();
    private final List<Runnable> changeListeners = new ArrayList<>();
    private long modificationCount;
    private long nextComponentNumber = 1;
    private long nextInstanceNumber = 1;

    public Schematic(Collection<GateDef> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        Map<String, List<GateDef>> grouped = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        Set<String> variants = new HashSet<>();
        for (GateDef definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            if (!ids.add(definition.id())) {
                throw new IllegalArgumentException("duplicate component definition: " + definition.id());
            }
            String variantKey = definition.family() + "|" + definition.size()
                    + "|" + definition.inputCount();
            if (!variants.add(variantKey)) {
                throw new IllegalArgumentException("duplicate component variant: " + variantKey);
            }
            grouped.computeIfAbsent(definition.family(), ignored -> new ArrayList<>())
                    .add(definition);
        }
        if (grouped.isEmpty()) {
            throw new IllegalArgumentException("at least one component definition is required");
        }
        Map<String, List<GateDef>> immutable = new LinkedHashMap<>();
        for (Map.Entry<String, List<GateDef>> entry : grouped.entrySet()) {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        definitionsByFamily = Map.copyOf(immutable);
        for (String family : definitionsByFamily.keySet()) {
            defaultDefinition(family);
        }
    }

    /** Adds a new instance with default variant attributes. */
    public PlacedComponent add(String typeId, int x, int y, Facing facing) {
        return add(typeId, x, y, facing, Map.of());
    }

    /** Adds a new instance with validated non-default variant attributes. */
    public PlacedComponent add(
            String typeId, int x, int y, Facing facing, Map<String, String> attributes) {
        requireKnownType(typeId);
        String id;
        do {
            id = "component-" + nextComponentNumber++;
        } while (containsId(id));

        PlacedComponent component = new PlacedComponent(
                id, typeId, x, y, facing, normalizeStoredAttributes(typeId, attributes));
        add(component);
        return components.getLast();
    }

    /** Adds a component with a caller-supplied id, rejecting duplicates. */
    public void add(PlacedComponent component) {
        Objects.requireNonNull(component, "component");
        requireKnownType(component.typeId());
        if (containsId(component.id())) {
            throw new IllegalArgumentException("duplicate component id: " + component.id());
        }
        Map<String, String> suppliedAttributes =
                new LinkedHashMap<>(component.attributes());
        String label = component.label();
        // Legacy fixed sources stored their value in label. Migrate that metadata
        // into the Value attribute before treating label as the optional Name.
        if (component.typeId().equals("constant")
                && !suppliedAttributes.containsKey("value")) {
            if (label.equals("0") || label.equals("1")) {
                suppliedAttributes.put("value", label);
                label = "";
            } else {
                // Persist even the default so a later Name of "0" or "1"
                // cannot be confused with the legacy value-in-label encoding.
                suppliedAttributes.put("value", "0");
            }
        }
        Map<String, String> normalized = normalizeStoredAttributes(
                component.typeId(), suppliedAttributes);
        PlacedComponent validated = new PlacedComponent(
                component.id(), component.typeId(), component.x(), component.y(),
                component.facing(), normalized, label);
        definitionFor(validated);
        components.add(validated);
        notifyChanged();
    }

    /** Registers an immutable definition used by one or more black-box instances. */
    public void registerComponentDefinition(ComponentDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        ComponentDefinition existing = componentDefinitions.get(definition.name());
        if (existing != null && !existing.hash().equals(definition.hash())) {
            throw new IllegalArgumentException(
                    "conflicting embedded component definitions named " + definition.name());
        }
        if (existing == null) {
            componentDefinitions.put(definition.name(), definition);
        }
    }

    /** Adds a black-box placement and embeds the exact definition used by the document. */
    public void addInstance(ComponentInstance instance, ComponentDefinition definition) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(definition, "definition");
        if (!instance.definitionName().equals(definition.name())
                || !instance.definitionHash().equals(definition.hash())) {
            throw new IllegalArgumentException(
                    "component instance definition/hash mismatch: " + instance.id());
        }
        if (containsId(instance.id())) {
            throw new IllegalArgumentException("duplicate component id: " + instance.id());
        }
        instance.geometry(definition);
        registerComponentDefinition(definition);
        componentInstances.add(instance);
        notifyChanged();
    }

    /** Allocates a stable U1, U2, ... instance id for the supplied definition. */
    public ComponentInstance addInstance(
            ComponentDefinition definition, int x, int y, Facing rotation) {
        return addInstance(definition, "", x, y, rotation);
    }

    public ComponentInstance addInstance(
            ComponentDefinition definition, String label, int x, int y, Facing rotation) {
        Objects.requireNonNull(definition, "definition");
        String id;
        do {
            id = "U" + nextInstanceNumber++;
        } while (containsId(id));
        ComponentInstance instance = new ComponentInstance(
                definition.name(), definition.hash(), id, label, x, y, rotation);
        addInstance(instance, definition);
        return instance;
    }

    public List<ComponentInstance> componentInstances() {
        return List.copyOf(componentInstances);
    }

    /** Returns the topmost black-box instance whose body contains a world point. */
    public Optional<ComponentInstance> componentInstanceAt(int worldX, int worldY) {
        for (int index = componentInstances.size() - 1; index >= 0; index--) {
            ComponentInstance instance = componentInstances.get(index);
            GateGeometry.Bounds bounds = instance.bounds(componentDefinitionFor(instance));
            long right = (long) bounds.x() + bounds.width();
            long bottom = (long) bounds.y() + bounds.height();
            if (worldX >= bounds.x() && worldX <= right
                    && worldY >= bounds.y() && worldY <= bottom) {
                return Optional.of(instance);
            }
        }
        return Optional.empty();
    }

    /** Returns black-box instances whose body bounds intersect a marquee rectangle. */
    public List<ComponentInstance> componentInstancesIntersecting(
            GateGeometry.Bounds rectangle) {
        Objects.requireNonNull(rectangle, "rectangle");
        if (rectangle.width() < 0 || rectangle.height() < 0) {
            throw new IllegalArgumentException(
                    "selection rectangle dimensions must not be negative");
        }
        long selectionRight = (long) rectangle.x() + rectangle.width();
        long selectionBottom = (long) rectangle.y() + rectangle.height();
        List<ComponentInstance> matches = new ArrayList<>();
        for (ComponentInstance instance : componentInstances) {
            GateGeometry.Bounds bounds = instance.bounds(componentDefinitionFor(instance));
            long instanceRight = (long) bounds.x() + bounds.width();
            long instanceBottom = (long) bounds.y() + bounds.height();
            if (bounds.x() <= selectionRight && instanceRight >= rectangle.x()
                    && bounds.y() <= selectionBottom && instanceBottom >= rectangle.y()) {
                matches.add(instance);
            }
        }
        return List.copyOf(matches);
    }

    public Map<String, ComponentDefinition> componentDefinitions() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(componentDefinitions));
    }

    public ComponentDefinition componentDefinitionFor(ComponentInstance instance) {
        Objects.requireNonNull(instance, "instance");
        ComponentDefinition definition = componentDefinitions.get(instance.definitionName());
        if (definition == null) {
            throw new IllegalArgumentException(
                    "missing component definition: " + instance.definitionName());
        }
        if (!definition.hash().equals(instance.definitionHash())) {
            throw new IllegalArgumentException(
                    "component definition hash mismatch for " + instance.id()
                            + ": expected " + instance.definitionHash()
                            + ", found " + definition.hash());
        }
        return definition;
    }

    /** Returns an isolated document copy, including its embedded definitions and source state. */
    public Schematic copy() {
        Schematic copy = new Schematic(gateDefinitions());
        copy.restoreSnapshot(SchematicSnapshot.capture(this));
        copy.setUndefinedInputMode(undefinedInputMode);
        for (PlacedComponent component : components) {
            if (component.typeId().equals("input") && inputValue(component.id())) {
                copy.setInputValue(component.id(), true);
            } else if (component.typeId().equals("clock") && clockValue(component.id())) {
                copy.setClockValue(component.id(), true);
            }
        }
        return copy;
    }

    public List<GateDef> gateDefinitions() {
        return definitionsByFamily.values().stream().flatMap(List::stream).toList();
    }

    /** Returns the current effective value for an editable component attribute. */
    public String attributeValue(String id, String key) {
        PlacedComponent component = components.get(indexOf(id));
        ComponentAttributeRegistry.AttributeSpec spec =
                ComponentAttributeRegistry.require(component.typeId(), key);
        return switch (key) {
            case "facing" -> component.facing().name();
            default -> component.attributes().getOrDefault(key, spec.defaultValue());
        };
    }

    /** Variant options are always derived from the definitions supplied to this schematic. */
    public List<String> attributeOptions(String family, String key) {
        ComponentAttributeRegistry.require(family, key);
        if (key.equals("facing")) {
            return List.of("EAST", "NORTH", "WEST", "SOUTH");
        }
        if (key.equals("value")) {
            return List.of("0", "1");
        }
        if (key.equals("activeLevel")) {
            return List.of("high", "low");
        }
        if (key.equals("onColour")) {
            return List.of("red", "green", "yellow", "blue");
        }
        List<GateDef> variants = variantsFor(family);
        if (key.equals("size")) {
            return variants.stream().map(GateDef::size).distinct()
                    .sorted(java.util.Comparator.comparingInt(Schematic::sizeOrder)).toList();
        }
        if (key.equals("inputs")) {
            return variants.stream().map(GateDef::inputCount).distinct().sorted()
                    .map(String::valueOf).toList();
        }
        throw new IllegalArgumentException("unsupported attribute: " + key);
    }

    /** Removes a placed component or black-box instance with this id. */
    public boolean remove(String id) {
        Objects.requireNonNull(id, "id");
        for (int index = 0; index < components.size(); index++) {
            if (components.get(index).id().equals(id)) {
                components.remove(index);
                inputValues.remove(id);
                clockValues.remove(id);
                notifyChanged();
                return true;
            }
        }
        for (int index = 0; index < componentInstances.size(); index++) {
            ComponentInstance instance = componentInstances.get(index);
            if (instance.id().equals(id)) {
                removeWiresAttachedToInstance(id);
                componentInstances.remove(index);
                notifyChanged();
                return true;
            }
        }
        return false;
    }

    /** Moves one component and safely follows its stationary pin connections. */
    public void move(String id, int x, int y) {
        PlacedComponent current = components.get(indexOf(id));
        moveSelection(List.of(id), List.of(), x - current.x(), y - current.y());
    }

    /**
     * Moves selected components and wires as one validated gesture. Generated links
     * are committed only if every candidate preserves the pre-gesture net partition.
     * On failure the components still move, selected wires still move with them, and
     * all automatically generated links are discarded.
     */
    public MoveResult moveSelection(
            Collection<String> selectedComponentIds,
            Collection<WireSegment> selectedWireSegments,
            int deltaX,
            int deltaY) {
        Objects.requireNonNull(selectedComponentIds, "selectedComponentIds");
        Objects.requireNonNull(selectedWireSegments, "selectedWireSegments");
        requireGridDelta(deltaX, deltaY);

        Set<String> movingComponentIds = new LinkedHashSet<>(selectedComponentIds);
        for (String id : movingComponentIds) {
            if (!containsId(Objects.requireNonNull(id, "component id"))) {
                throw new IllegalArgumentException("unknown component id: " + id);
            }
        }
        Set<WireSegment> movingWires = new LinkedHashSet<>(selectedWireSegments);
        movingWires.retainAll(wires);

        if (deltaX == 0 && deltaY == 0) {
            return new MoveResult(List.copyOf(movingWires), true);
        }

        Map<String, PlacedComponent> replacements = new HashMap<>();
        for (PlacedComponent current : components) {
            if (movingComponentIds.contains(current.id())) {
                replacements.put(current.id(), new PlacedComponent(
                        current.id(), current.typeId(),
                        current.x() + deltaX, current.y() + deltaY,
                        current.facing(), current.attributes(), current.label()));
            }
        }
        Map<String, ComponentInstance> instanceReplacements = new HashMap<>();
        for (ComponentInstance current : componentInstances) {
            if (movingComponentIds.contains(current.id())) {
                instanceReplacements.put(current.id(), new ComponentInstance(
                        current.definitionName(), current.definitionHash(), current.id(),
                        current.label(), current.x() + deltaX, current.y() + deltaY,
                        current.rotation()));
            }
        }

        List<WireSegment> baseWires = new ArrayList<>();
        for (WireSegment wire : wires) {
            if (!movingWires.contains(wire)) {
                baseWires.add(wire);
            }
        }
        List<WireSegment> movingWireOrder = new ArrayList<>(movingWires);
        List<WireSegment> movedWires = new ArrayList<>();
        for (WireSegment wire : movingWireOrder) {
            WireSegment translated = translateWire(wire, deltaX, deltaY);
            if (!baseWires.contains(translated)) {
                baseWires.add(translated);
            }
            movedWires.add(translated);
        }

        if (replacements.isEmpty() && instanceReplacements.isEmpty() && movingWires.isEmpty()) {
            return new MoveResult(List.of(), true);
        }

        GestureCommit commit = commitValidatedGesture(
                replacements, instanceReplacements, movingWires, baseWires);
        List<WireSegment> selectedAfterCommit = commit.committed()
                ? movedWires
                : movingWireOrder;
        return new MoveResult(
                List.copyOf(new LinkedHashSet<>(selectedAfterCommit)), commit.linksFollowed());
    }

    /** Changes one component's facing and safely follows its stationary connections. */
    public boolean setFacing(String id, Facing facing) {
        return setFacing(List.of(id), Objects.requireNonNull(facing, "facing"));
    }

    /** Changes the facing of a complete component selection in one validated gesture. */
    public boolean setFacing(Collection<String> selectedComponentIds, Facing facing) {
        Objects.requireNonNull(selectedComponentIds, "selectedComponentIds");
        Objects.requireNonNull(facing, "facing");
        Set<String> selected = new LinkedHashSet<>(selectedComponentIds);
        for (String id : selected) {
            if (!containsId(Objects.requireNonNull(id, "component id"))) {
                throw new IllegalArgumentException("unknown component id: " + id);
            }
        }

        Map<String, PlacedComponent> replacements = new HashMap<>();
        for (PlacedComponent current : components) {
            if (selected.contains(current.id()) && current.facing() != facing) {
                replacements.put(current.id(), new PlacedComponent(
                        current.id(), current.typeId(), current.x(), current.y(),
                        facing, current.attributes(), current.label()));
            }
        }
        Map<String, ComponentInstance> instanceReplacements = new HashMap<>();
        for (ComponentInstance current : componentInstances) {
            if (selected.contains(current.id()) && current.rotation() != facing) {
                instanceReplacements.put(current.id(), new ComponentInstance(
                        current.definitionName(), current.definitionHash(), current.id(),
                        current.label(), current.x(), current.y(), facing));
            }
        }
        if (replacements.isEmpty() && instanceReplacements.isEmpty()) {
            return true;
        }
        return commitValidatedGesture(
                replacements, instanceReplacements, Set.of(), List.copyOf(wires)).linksFollowed();
    }

    /**
     * Updates a component's visible label without changing its geometry or pins.
     *
     * @return true when the component exists and the request was accepted
     */
    public boolean setLabel(String id, String label) {
        Objects.requireNonNull(label, "label");
        int index = indexOf(id);
        PlacedComponent current = components.get(index);
        String normalized = label.isBlank() ? "" : label.trim();
        if (current.label().equals(normalized)) {
            return true;
        }
        components.set(index, new PlacedComponent(
                current.id(), current.typeId(), current.x(), current.y(),
                current.facing(), current.attributes(), normalized));
        notifyChanged();
        return true;
    }

    /**
     * Changes one registered attribute through the same atomic mutation path as
     * facing changes. Size/input variants therefore use M8b-v2's validated links.
     */
    public boolean setAttribute(String id, String key, String value) {
        PlacedComponent current = components.get(indexOf(id));
        String normalized = ComponentAttributeRegistry.normalizeValue(
                current.typeId(), key, value);
        if (key.equals("facing")) {
            return setFacing(id, Facing.valueOf(normalized));
        }

        Map<String, String> attributes = new LinkedHashMap<>(current.attributes());
        ComponentAttributeRegistry.AttributeSpec spec =
                ComponentAttributeRegistry.require(current.typeId(), key);
        if (normalized.equals(spec.defaultValue())) {
            attributes.remove(key);
        } else {
            attributes.put(key, normalized);
        }
        PlacedComponent replacement = new PlacedComponent(
                current.id(), current.typeId(), current.x(), current.y(),
                current.facing(), normalizeStoredAttributes(current.typeId(), attributes),
                current.label());
        try {
            definitionFor(replacement);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Not available: " + exception.getMessage(), exception);
        }
        if (current.equals(replacement)) {
            return true;
        }

        GateDef previousDefinition = definitionFor(current);
        GateDef nextDefinition = definitionFor(replacement);
        if (key.equals("inputs") && previousDefinition.output() != null
                && nextDefinition.output() != null) {
            // Catalogue variants keep the existing input-row coordinates stable.
            // Move the output origin by the SVG centre delta so in0..inN retain
            // their world positions while the body grows below its original top edge.
            int outputShift = nextDefinition.output().y() - previousDefinition.output().y();
            if (outputShift != 0) {
                GateGeometry.Offset worldShift = GateGeometry.rotate(
                        new GateGeometry.Offset(0, outputShift), current.facing());
                replacement = new PlacedComponent(
                        replacement.id(), replacement.typeId(),
                        Math.addExact(replacement.x(), worldShift.x()),
                        Math.addExact(replacement.y(), worldShift.y()),
                        replacement.facing(), replacement.attributes(), replacement.label());
            }
        }
        Set<PinKey> deletedPins = new HashSet<>();
        for (int index = nextDefinition.inputs().size();
                index < previousDefinition.inputs().size(); index++) {
            deletedPins.add(new PinKey(id, "in" + index));
        }

        Set<PinKey> disconnectedPins = new HashSet<>(deletedPins);
        Set<WireSegment> removedWires = new HashSet<>();
        if (!deletedPins.isEmpty()) {
            List<Connectivity.PinPosition> originalPins = pinPositions();
            List<Connectivity.PinPosition> afterPins = originalPins.stream()
                    .filter(pin -> !deletedPins.contains(key(pin)))
                    .toList();
            for (Connectivity.Net net : Connectivity.analyze(originalPins, wires).nets()) {
                List<Connectivity.PinPosition> survivingPins = net.pins().stream()
                        .filter(pin -> !deletedPins.contains(key(pin)))
                        .toList();
                if (survivingPins.size() <= 1) {
                    // A source connected only to removed inputs should not be left
                    // with a dangling wire. Its former net is no longer a required link.
                    removedWires.addAll(net.segments());
                    net.pins().forEach(pin -> disconnectedPins.add(key(pin)));
                    continue;
                }
                for (Connectivity.PinPosition deletedPin : net.pins()) {
                    if (!deletedPins.contains(key(deletedPin))) {
                        continue;
                    }
                    Connectivity.Point point =
                            new Connectivity.Point(deletedPin.x(), deletedPin.y());
                    for (WireSegment segment : net.segments()) {
                        boolean endpoint = (segment.x1() == point.x() && segment.y1() == point.y())
                                || (segment.x2() == point.x() && segment.y2() == point.y());
                        if (!endpoint || removedWires.contains(segment)) {
                            continue;
                        }
                        List<WireSegment> candidateWires = wires.stream()
                                .filter(wire -> !removedWires.contains(wire) && !wire.equals(segment))
                                .toList();
                        boolean preservesOtherPins = Connectivity.analyze(
                                        afterPins, candidateWires).nets().stream()
                                .anyMatch(candidate -> candidate.pins().containsAll(survivingPins));
                        if (preservesOtherPins) {
                            removedWires.add(segment);
                        }
                    }
                }
            }
        }
        List<WireSegment> remainingWires = wires.stream()
                .filter(wire -> !removedWires.contains(wire))
                .toList();
        return commitValidatedGesture(
                Map.of(id, replacement), Map.of(), Set.of(), remainingWires,
                disconnectedPins).linksFollowed();
    }

    /**
     * Computes the before-net partition before changing geometry, tries every
     * deterministic orthogonal route, and publishes exactly one schematic change.
     */
    private GestureCommit commitValidatedGesture(
            Map<String, PlacedComponent> replacements,
            Set<WireSegment> movingWires,
            List<WireSegment> baseWires) {
        return commitValidatedGesture(
                replacements, Map.of(), movingWires, baseWires, Set.of());
    }

    private GestureCommit commitValidatedGesture(
            Map<String, PlacedComponent> replacements,
            Map<String, ComponentInstance> instanceReplacements,
            Set<WireSegment> movingWires,
            List<WireSegment> baseWires) {
        return commitValidatedGesture(
                replacements, instanceReplacements, movingWires, baseWires, Set.of());
    }

    private GestureCommit commitValidatedGesture(
            Map<String, PlacedComponent> replacements,
            Map<String, ComponentInstance> instanceReplacements,
            Set<WireSegment> movingWires,
            List<WireSegment> baseWires,
            Set<PinKey> intentionallyDisconnectedPins) {
        List<Connectivity.PinPosition> beforePins = pinPositions();
        List<WireSegment> originalWires = List.copyOf(wires);
        Map<String, PlacedComponent> originalComponents = new HashMap<>();
        for (PlacedComponent component : components) {
            if (replacements.containsKey(component.id())) {
                originalComponents.put(component.id(), component);
            }
        }
        Map<String, ComponentInstance> originalInstances = new HashMap<>();
        for (ComponentInstance instance : componentInstances) {
            if (instanceReplacements.containsKey(instance.id())) {
                originalInstances.put(instance.id(), instance);
            }
        }
        Map<PinKey, Integer> beforeNetIds = netIds(beforePins, originalWires);
        // A user-selected wire is an intentional move, not an auto-follow target.
        // Preserve connections that existed through stationary geometry, while the
        // full before partition still guards against shorts introduced by the edit.
        List<WireSegment> stationaryBeforeWires = originalWires.stream()
                .filter(wire -> !movingWires.contains(wire))
                .toList();
        Map<PinKey, Integer> requiredConnectionNetIds = new HashMap<>(
                netIds(beforePins, stationaryBeforeWires));
        requiredConnectionNetIds.keySet().removeAll(intentionallyDisconnectedPins);
        List<PinMove> pinsToLink = attachedPinsToFollow(
                replacements, instanceReplacements, movingWires, beforePins, originalWires);

        replaceComponents(replacements);
        replaceInstances(instanceReplacements);
        wires.clear();
        wires.addAll(baseWires);
        List<Connectivity.PinPosition> afterPins = pinPositions();

        if (!hasNoNewShorts(afterPins, wires, beforeNetIds)) {
            // If the moved pins themselves land on unrelated old nets, even the M4
            // no-link fallback would be electrically unsafe; reject that placement.
            replaceComponents(originalComponents);
            replaceInstances(originalInstances);
            wires.clear();
            wires.addAll(originalWires);
            return new GestureCommit(false, false);
        }

        boolean linksFollowed = true;
        for (PinMove pinMove : pinsToLink) {
            boolean accepted = false;
            for (List<WireSegment> candidate : candidateRoutes(pinMove)) {
                List<WireSegment> attempted = new ArrayList<>(wires);
                for (WireSegment segment : candidate) {
                    if (!attempted.contains(segment)) {
                        attempted.add(segment);
                    }
                }
                if (hasNoNewShorts(afterPins, attempted, beforeNetIds)) {
                    wires.clear();
                    wires.addAll(attempted);
                    accepted = true;
                    break;
                }
            }
            if (!accepted) {
                linksFollowed = false;
                break;
            }
        }

        if (linksFollowed
                && !preservesBeforeConnections(afterPins, wires, requiredConnectionNetIds)) {
            linksFollowed = false;
        }
        if (!linksFollowed) {
            // A failed link invalidates the entire gesture's generated routing.
            wires.clear();
            wires.addAll(baseWires);
        }

        notifyChanged();
        return new GestureCommit(linksFollowed, true);
    }

    private List<PinMove> attachedPinsToFollow(
            Map<String, PlacedComponent> replacements,
            Map<String, ComponentInstance> instanceReplacements,
            Set<WireSegment> movingWires,
            List<Connectivity.PinPosition> beforePins,
            List<WireSegment> beforeWires) {
        Set<String> movingIds = new LinkedHashSet<>(replacements.keySet());
        movingIds.addAll(instanceReplacements.keySet());
        Set<Connectivity.Point> stationaryPinPoints = new HashSet<>();
        for (Connectivity.PinPosition pin : beforePins) {
            if (!movingIds.contains(pin.componentId())) {
                stationaryPinPoints.add(new Connectivity.Point(pin.x(), pin.y()));
            }
        }
        List<WireSegment> stationaryWires = beforeWires.stream()
                .filter(wire -> !movingWires.contains(wire))
                .toList();

        Map<PinKey, Connectivity.PinPosition> newPins = new HashMap<>();
        for (PlacedComponent component : components) {
            PlacedComponent replacement = replacements.get(component.id());
            newPinsFor(replacement == null ? component : replacement, newPins);
        }
        for (ComponentInstance instance : componentInstances) {
            ComponentInstance replacement = instanceReplacements.get(instance.id());
            newPinsFor(replacement == null ? instance : replacement, newPins);
        }

        List<PinMove> result = new ArrayList<>();
        for (Connectivity.PinPosition oldPin : beforePins) {
            if (!movingIds.contains(oldPin.componentId())) {
                continue;
            }
            Connectivity.PinPosition newPin = newPins.get(key(oldPin));
            if (newPin == null || (oldPin.x() == newPin.x() && oldPin.y() == newPin.y())) {
                continue;
            }
            Connectivity.Point oldPoint = new Connectivity.Point(oldPin.x(), oldPin.y());
            boolean attachedToWire = stationaryWires.stream()
                    .anyMatch(wire -> contains(wire, oldPoint));
            boolean attachedToStationaryPin = stationaryPinPoints.contains(oldPoint);
            if (attachedToWire || attachedToStationaryPin) {
                result.add(new PinMove(oldPin, newPin));
            }
        }
        return List.copyOf(result);
    }

    private void newPinsFor(
            ComponentInstance instance,
            Map<PinKey, Connectivity.PinPosition> target) {
        for (Connectivity.PinPosition pin :
                instance.pinPositions(componentDefinitionFor(instance))) {
            target.put(key(pin), pin);
        }
    }

    private void newPinsFor(
            PlacedComponent component,
            Map<PinKey, Connectivity.PinPosition> target) {
        GateDef definition = definitionFor(component);
        for (int index = 0; index < definition.inputs().size(); index++) {
            GateGeometry.Offset offset = GateGeometry.pinOffset(
                    definition, definition.inputs().get(index), component.facing());
            Connectivity.PinPosition pin = new Connectivity.PinPosition(
                    component.id(), "in" + index,
                    component.x() + offset.x(), component.y() + offset.y());
            target.put(key(pin), pin);
        }
        if (definition.output() != null) {
            GateGeometry.Offset offset = GateGeometry.outputOffset(definition, component.facing());
            Connectivity.PinPosition pin = new Connectivity.PinPosition(
                    component.id(), "out",
                    component.x() + offset.x(), component.y() + offset.y());
            target.put(key(pin), pin);
        }
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

    private void replaceInstances(Map<String, ComponentInstance> replacements) {
        if (replacements.isEmpty()) {
            return;
        }
        for (int index = 0; index < componentInstances.size(); index++) {
            ComponentInstance current = componentInstances.get(index);
            ComponentInstance replacement = replacements.get(current.id());
            if (replacement != null) {
                componentInstances.set(index, replacement);
            }
        }
    }

    private static PinKey key(Connectivity.PinPosition pin) {
        return new PinKey(pin.componentId(), pin.pinId());
    }

    private static Map<PinKey, Integer> netIds(
            Collection<Connectivity.PinPosition> pins,
            Collection<WireSegment> geometry) {
        Connectivity.Result topology = Connectivity.analyze(pins, geometry);
        Map<PinKey, Integer> result = new HashMap<>();
        int netId = 0;
        for (Connectivity.Net net : topology.nets()) {
            for (Connectivity.PinPosition pin : net.pins()) {
                result.put(key(pin), netId);
            }
            netId++;
        }
        return result;
    }

    private static boolean hasNoNewShorts(
            Collection<Connectivity.PinPosition> currentPins,
            Collection<WireSegment> geometry,
            Map<PinKey, Integer> beforeNetIds) {
        Connectivity.Result topology = Connectivity.analyze(currentPins, geometry);
        for (Connectivity.Net net : topology.nets()) {
            Integer originalNet = null;
            for (Connectivity.PinPosition pin : net.pins()) {
                Integer pinNet = beforeNetIds.get(key(pin));
                if (pinNet == null) {
                    continue;
                }
                if (originalNet == null) {
                    originalNet = pinNet;
                } else if (!originalNet.equals(pinNet)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean preservesBeforeConnections(
            Collection<Connectivity.PinPosition> currentPins,
            Collection<WireSegment> geometry,
            Map<PinKey, Integer> beforeNetIds) {
        Map<PinKey, Integer> afterNetIds = netIds(currentPins, geometry);
        Map<Integer, Integer> afterNetForOriginalNet = new HashMap<>();
        for (Map.Entry<PinKey, Integer> entry : beforeNetIds.entrySet()) {
            Integer afterNet = afterNetIds.get(entry.getKey());
            if (afterNet == null) {
                continue;
            }
            Integer previousNet = afterNetForOriginalNet.putIfAbsent(entry.getValue(), afterNet);
            if (previousNet != null && !previousNet.equals(afterNet)) {
                return false;
            }
        }
        return true;
    }

    private static List<List<WireSegment>> candidateRoutes(PinMove pinMove) {
        Connectivity.Point oldPoint = new Connectivity.Point(
                pinMove.oldPin().x(), pinMove.oldPin().y());
        Connectivity.Point newPoint = new Connectivity.Point(
                pinMove.newPin().x(), pinMove.newPin().y());
        long deltaX = Math.abs((long) newPoint.x() - oldPoint.x());
        long deltaY = Math.abs((long) newPoint.y() - oldPoint.y());
        boolean horizontalFirst = deltaX >= deltaY;

        LinkedHashSet<List<WireSegment>> candidates = new LinkedHashSet<>();
        addCandidate(candidates, oldPoint, new Connectivity.Point(newPoint.x(), oldPoint.y()), newPoint);
        addCandidate(candidates, oldPoint, new Connectivity.Point(oldPoint.x(), newPoint.y()), newPoint);

        if (horizontalFirst) {
            addZCandidates(candidates, oldPoint, newPoint, true);
            addZCandidates(candidates, oldPoint, newPoint, false);
        } else {
            addZCandidates(candidates, oldPoint, newPoint, false);
            addZCandidates(candidates, oldPoint, newPoint, true);
        }
        return List.copyOf(candidates);
    }

    private static void addZCandidates(
            Set<List<WireSegment>> candidates,
            Connectivity.Point start,
            Connectivity.Point end,
            boolean horizontalFirst) {
        int midpoint = horizontalFirst
                ? midpointOnGrid(start.x(), end.x())
                : midpointOnGrid(start.y(), end.y());
        int spacing = PlacedComponent.GRID_SPACING;
        // Eight candidates per orientation: midpoint, then +10/-10 ... +30/-30, +40.
        int[] offsets = {0, spacing, -spacing, spacing * 2, -spacing * 2,
                spacing * 3, -spacing * 3, spacing * 4};
        for (int offset : offsets) {
            long middle = (long) midpoint + offset;
            if (middle < Integer.MIN_VALUE || middle > Integer.MAX_VALUE) {
                continue;
            }
            if (horizontalFirst) {
                int x = (int) middle;
                addCandidate(candidates, start,
                        new Connectivity.Point(x, start.y()),
                        new Connectivity.Point(x, end.y()),
                        end);
            } else {
                int y = (int) middle;
                addCandidate(candidates, start,
                        new Connectivity.Point(start.x(), y),
                        new Connectivity.Point(end.x(), y),
                        end);
            }
        }
    }

    private static int midpointOnGrid(int first, int second) {
        double midpoint = ((long) first + second) / 2.0;
        return (int) (Math.round(midpoint / PlacedComponent.GRID_SPACING)
                * PlacedComponent.GRID_SPACING);
    }

    private static void addCandidate(
            Set<List<WireSegment>> candidates,
            Connectivity.Point... points) {
        LinkedHashSet<WireSegment> segments = new LinkedHashSet<>();
        for (int index = 0; index + 1 < points.length; index++) {
            Connectivity.Point first = points[index];
            Connectivity.Point second = points[index + 1];
            if (first.equals(second)) {
                continue;
            }
            segments.add(new WireSegment(first.x(), first.y(), second.x(), second.y()));
        }
        if (!segments.isEmpty()) {
            candidates.add(List.copyOf(segments));
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

    private static WireSegment translateWire(WireSegment wire, int deltaX, int deltaY) {
        return new WireSegment(
                wire.x1() + deltaX, wire.y1() + deltaY,
                wire.x2() + deltaX, wire.y2() + deltaY);
    }

    private static void requireGridDelta(int deltaX, int deltaY) {
        if (deltaX % PlacedComponent.GRID_SPACING != 0
                || deltaY % PlacedComponent.GRID_SPACING != 0) {
            throw new IllegalArgumentException(
                    "component and wire movement must stay on the schematic grid");
        }
    }

    /**
     * Replaces editable topology with a prior snapshot. Input values are external state:
     * values survive only for input ids that exist both before and after restoration.
     */
    public void restoreSnapshot(SchematicSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<PlacedComponent> replacementComponents = snapshot.components();
        List<ComponentInstance> replacementInstances = snapshot.instances();
        Map<String, ComponentDefinition> replacementDefinitions = snapshot.componentDefinitions();
        List<WireSegment> replacementWires = snapshot.wires();
        Set<String> replacementIds = new LinkedHashSet<>();
        for (PlacedComponent component : replacementComponents) {
            requireKnownType(component.typeId());
            normalizeStoredAttributes(component.typeId(), component.attributes());
            definitionFor(component);
            if (!replacementIds.add(component.id())) {
                throw new IllegalArgumentException("duplicate component id: " + component.id());
            }
        }
        for (ComponentDefinition definition : replacementDefinitions.values()) {
            Objects.requireNonNull(definition, "component definition");
        }
        for (ComponentInstance instance : replacementInstances) {
            if (!replacementIds.add(instance.id())) {
                throw new IllegalArgumentException("duplicate component id: " + instance.id());
            }
            ComponentDefinition definition = replacementDefinitions.get(instance.definitionName());
            if (definition == null || !definition.hash().equals(instance.definitionHash())) {
                throw new IllegalArgumentException(
                        "missing or mismatched component definition for " + instance.id());
            }
            instance.geometry(definition);
        }
        if (components.equals(replacementComponents)
                && componentInstances.equals(replacementInstances)
                && componentDefinitions.equals(replacementDefinitions)
                && wires.equals(replacementWires)) {
            return;
        }

        Map<String, Boolean> survivingInputValues = new HashMap<>();
        Map<String, Boolean> survivingClockValues = new HashMap<>();
        for (PlacedComponent current : components) {
            if ("input".equals(current.typeId())
                    && Boolean.TRUE.equals(inputValues.get(current.id()))) {
                survivingInputValues.put(current.id(), true);
            } else if ("clock".equals(current.typeId())
                    && Boolean.TRUE.equals(clockValues.get(current.id()))) {
                survivingClockValues.put(current.id(), true);
            }
        }

        components.clear();
        components.addAll(replacementComponents);
        componentInstances.clear();
        componentInstances.addAll(replacementInstances);
        componentDefinitions.clear();
        componentDefinitions.putAll(replacementDefinitions);
        wires.clear();
        wires.addAll(replacementWires);
        inputValues.clear();
        clockValues.clear();
        for (PlacedComponent replacement : replacementComponents) {
            if ("input".equals(replacement.typeId())
                    && Boolean.TRUE.equals(survivingInputValues.get(replacement.id()))) {
                inputValues.put(replacement.id(), true);
            } else if ("clock".equals(replacement.typeId())
                    && Boolean.TRUE.equals(survivingClockValues.get(replacement.id()))) {
                clockValues.put(replacement.id(), true);
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

    /** Current document option for handling electrically bare gate inputs. */
    public UndefinedInputMode undefinedInputMode() {
        return undefinedInputMode;
    }

    /** Changes a persisted document option without adding an undoable topology step. */
    public void setUndefinedInputMode(UndefinedInputMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (undefinedInputMode == mode) {
            return;
        }
        undefinedInputMode = mode;
        notifyChanged();
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

    /** Current app-controlled level of a Clock component; new clocks start LOW. */
    public boolean clockValue(String id) {
        requireClock(id);
        return Boolean.TRUE.equals(clockValues.get(id));
    }

    public void setClockValue(String id, boolean value) {
        requireClock(id);
        if (clockValue(id) == value) {
            return;
        }
        if (value) {
            clockValues.put(id, true);
        } else {
            clockValues.remove(id);
        }
        notifyChanged();
    }

    /** Flips every Clock together and publishes one schematic change. */
    public boolean toggleClocks() {
        boolean found = false;
        for (PlacedComponent component : components) {
            if (!"clock".equals(component.typeId())) {
                continue;
            }
            found = true;
            if (clockValue(component.id())) {
                clockValues.remove(component.id());
            } else {
                clockValues.put(component.id(), true);
            }
        }
        if (found) {
            notifyChanged();
        }
        return found;
    }

    /** Clears all user input values and Clock levels as one mutation. */
    public boolean resetSimulationSources() {
        if (inputValues.isEmpty() && clockValues.isEmpty()) {
            return false;
        }
        inputValues.clear();
        clockValues.clear();
        notifyChanged();
        return true;
    }

    public GateDef definitionFor(PlacedComponent component) {
        Objects.requireNonNull(component, "component");
        List<GateDef> variants = variantsFor(component.typeId());
        String size = component.attributes().getOrDefault("size", "narrow");
        int inputCount = ComponentAttributeRegistry.supports(component.typeId(), "inputs")
                ? Integer.parseInt(component.attributes().getOrDefault("inputs", "2"))
                : defaultDefinition(component.typeId()).inputCount();
        return variants.stream()
                .filter(definition -> definition.size().equals(size)
                        && definition.inputCount() == inputCount)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Not available: " + component.typeId() + " " + size
                                + " with " + inputCount + " inputs"));
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
                definitionFor(component), component.facing());
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
            GateDef definition = definitionFor(component);
            for (int index = 0; index < definition.inputs().size(); index++) {
                GateGeometry.Offset offset = GateGeometry.pinOffset(
                        definition, definition.inputs().get(index), component.facing());
                pins.add(new Connectivity.PinPosition(
                        component.id(), "in" + index,
                        component.x() + offset.x(), component.y() + offset.y()));
            }
            if (definition.output() != null) {
                GateGeometry.Offset offset = GateGeometry.outputOffset(
                        definition, component.facing());
                pins.add(new Connectivity.PinPosition(
                        component.id(), "out",
                        component.x() + offset.x(), component.y() + offset.y()));
            }
        }
        for (ComponentInstance instance : componentInstances) {
            pins.addAll(instance.pinPositions(componentDefinitionFor(instance)));
        }
        return List.copyOf(pins);
    }

    /**
     * Reports whether an output probe observes a net that also sinks into a gate.
     * This is derived from current geometry and never stored in the document.
     */
    public boolean isOutputTapped(String outputId) {
        PlacedComponent output = components.get(indexOf(outputId));
        if (!output.typeId().equals("output")) {
            throw new IllegalArgumentException("component is not an output: " + outputId);
        }

        List<Connectivity.PinPosition> pins = pinPositions();
        Connectivity.PinPosition probeInput = pins.stream()
                .filter(pin -> pin.componentId().equals(outputId)
                        && pin.pinId().equals("in0"))
                .findFirst().orElse(null);
        if (probeInput == null) {
            return false;
        }

        Connectivity.Net outputNet = Connectivity.analyze(pins, wires).nets().stream()
                .filter(net -> net.pins().contains(probeInput))
                .findFirst().orElse(null);
        if (outputNet == null) {
            return false;
        }

        Set<String> gateIds = components.stream()
                .filter(component -> !component.typeId().equals("input")
                        && !component.typeId().equals("output")
                        && !component.typeId().equals("constant"))
                .map(PlacedComponent::id)
                .collect(java.util.stream.Collectors.toSet());
        return outputNet.pins().stream().anyMatch(pin ->
                gateIds.contains(pin.componentId()) && pin.pinId().startsWith("in"));
    }

    private PlacedComponent requireInput(String id) {
        Objects.requireNonNull(id, "id");
        PlacedComponent component = components.get(indexOf(id));
        if (!"input".equals(component.typeId())) {
            throw new IllegalArgumentException("component is not an input: " + id);
        }
        return component;
    }

    private PlacedComponent requireClock(String id) {
        Objects.requireNonNull(id, "id");
        PlacedComponent component = components.get(indexOf(id));
        if (!"clock".equals(component.typeId())) {
            throw new IllegalArgumentException("component is not a Clock: " + id);
        }
        return component;
    }

    private void removeWiresAttachedToInstance(String instanceId) {
        Set<WireSegment> attached = Connectivity.analyze(pinPositions(), wires).nets().stream()
                .filter(net -> net.pins().stream()
                        .anyMatch(pin -> pin.componentId().equals(instanceId)))
                .flatMap(net -> net.segments().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        wires.removeAll(attached);
    }

    private void notifyChanged() {
        modificationCount++;
        for (Runnable listener : List.copyOf(changeListeners)) {
            listener.run();
        }
    }

    private GateDef requireKnownType(String family) {
        return defaultDefinition(Objects.requireNonNull(family, "family"));
    }

    private List<GateDef> variantsFor(String family) {
        List<GateDef> variants = definitionsByFamily.get(Objects.requireNonNull(family, "family"));
        if (variants == null) {
            throw new IllegalArgumentException("unknown component type: " + family);
        }
        return variants;
    }

    private GateDef defaultDefinition(String family) {
        List<GateDef> variants = variantsFor(family);
        int inputCount = ComponentAttributeRegistry.supports(family, "inputs")
                ? 2
                : variants.stream()
                        .filter(definition -> definition.size().equals("narrow"))
                        .mapToInt(GateDef::inputCount)
                        .min().orElse(0);
        return variants.stream()
                .filter(definition -> definition.size().equals("narrow")
                        && definition.inputCount() == inputCount)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "component family " + family + " has no narrow default"));
    }

    private Map<String, String> normalizeStoredAttributes(
            String family, Map<String, String> attributes) {
        Objects.requireNonNull(attributes, "attributes");
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "attribute key");
            if (key.equals("facing")) {
                throw new IllegalArgumentException(
                        "attribute 'facing' is stored in the top-level facing field");
            }
            ComponentAttributeRegistry.AttributeSpec spec =
                    ComponentAttributeRegistry.require(family, key);
            String value = ComponentAttributeRegistry.normalizeValue(
                    family, key, Objects.requireNonNull(entry.getValue(), "attribute value"));
            if (!value.equals(spec.defaultValue())
                    || (family.equals("constant") && key.equals("value"))) {
                normalized.put(key, value);
            }
        }
        return Map.copyOf(normalized);
    }

    private static int sizeOrder(String size) {
        return switch (size) {
            case "narrow" -> 0;
            case "normal" -> 1;
            case "wide" -> 2;
            default -> 3;
        };
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
        return components.stream().anyMatch(component -> component.id().equals(id))
                || componentInstances.stream().anyMatch(instance -> instance.id().equals(id));
    }
}
