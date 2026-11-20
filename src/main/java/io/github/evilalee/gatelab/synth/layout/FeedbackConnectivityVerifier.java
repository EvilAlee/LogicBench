package io.github.evilalee.gatelab.synth.layout;

import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Verifies that a rebuilt feedback schematic retains the original component
 * types and exact pin-to-net partition, independent of generated component ids.
 */
public final class FeedbackConnectivityVerifier {
    private record PinKey(String componentId, String pinId) {
    }

    private FeedbackConnectivityVerifier() {
    }

    /**
     * @param newToOriginalComponentIds maps every rebuilt component id to the
     *                                  corresponding component id in original
     * @throws IllegalStateException when topology or component identities differ
     */
    public static void verify(
            Schematic original,
            Schematic rebuilt,
            Map<String, String> newToOriginalComponentIds) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(rebuilt, "rebuilt");
        Objects.requireNonNull(newToOriginalComponentIds, "newToOriginalComponentIds");

        Map<String, PlacedComponent> oldById = index(original);
        Map<String, PlacedComponent> newById = index(rebuilt);
        if (!componentCounts(original).equals(componentCounts(rebuilt))) {
            throw new IllegalStateException(
                    "Feedback connectivity verification failed: component counts by type differ.");
        }
        if (newToOriginalComponentIds.size() != newById.size()) {
            throw new IllegalStateException(
                    "Feedback connectivity verification failed: component identity map is incomplete.");
        }

        Set<String> mappedOldIds = new HashSet<>();
        for (Map.Entry<String, String> mapping : newToOriginalComponentIds.entrySet()) {
            PlacedComponent newComponent = newById.get(mapping.getKey());
            PlacedComponent oldComponent = oldById.get(mapping.getValue());
            if (newComponent == null || oldComponent == null
                    || !newComponent.typeId().equals(oldComponent.typeId())
                    || !mappedOldIds.add(mapping.getValue())) {
                throw new IllegalStateException(
                        "Feedback connectivity verification failed: component identity map "
                                + "does not match the original schematic.");
            }
        }
        if (!mappedOldIds.equals(oldById.keySet())) {
            throw new IllegalStateException(
                    "Feedback connectivity verification failed: original components are missing.");
        }

        Set<Set<String>> originalNets = pinNetPartition(original, Map.of(), false);
        Set<Set<String>> rebuiltNets = pinNetPartition(
                rebuilt, newToOriginalComponentIds, true);
        if (!originalNets.equals(rebuiltNets)) {
            throw new IllegalStateException(
                    "Feedback connectivity verification failed: pin-to-net grouping changed.");
        }
    }

    private static Map<String, PlacedComponent> index(Schematic schematic) {
        Map<String, PlacedComponent> result = new LinkedHashMap<>();
        for (PlacedComponent component : schematic.components()) {
            if (result.put(component.id(), component) != null) {
                throw new IllegalStateException(
                        "Feedback connectivity verification failed: duplicate component id.");
            }
        }
        return result;
    }

    private static Map<String, Long> componentCounts(Schematic schematic) {
        return schematic.components().stream().collect(Collectors.groupingBy(
                PlacedComponent::typeId, java.util.TreeMap::new, Collectors.counting()));
    }

    private static Set<Set<String>> pinNetPartition(
            Schematic schematic,
            Map<String, String> componentIdMapping,
            boolean rejectOrphanWires) {
        List<Connectivity.PinPosition> pins = schematic.pinPositions();
        Set<PinKey> expectedPins = pins.stream()
                .map(pin -> new PinKey(pin.componentId(), pin.pinId()))
                .collect(Collectors.toCollection(HashSet::new));
        Set<PinKey> seenPins = new HashSet<>();
        Set<Set<String>> partition = new HashSet<>();
        Connectivity.Result topology = Connectivity.analyze(pins, schematic.wires());

        for (Connectivity.Net net : topology.nets()) {
            if (net.pins().isEmpty()) {
                if (rejectOrphanWires) {
                    throw new IllegalStateException(
                            "Feedback connectivity verification failed: layout contains an orphan wire net.");
                }
                continue;
            }
            Set<String> members = new TreeSet<>();
            for (Connectivity.PinPosition pin : net.pins()) {
                PinKey key = new PinKey(pin.componentId(), pin.pinId());
                if (!expectedPins.contains(key) || !seenPins.add(key)) {
                    throw new IllegalStateException(
                            "Feedback connectivity verification failed: unexpected or duplicate pin "
                                    + pin.componentId() + "." + pin.pinId() + ".");
                }
                String originalId = componentIdMapping.getOrDefault(
                        pin.componentId(), pin.componentId());
                members.add(originalId + "." + pin.pinId());
            }
            partition.add(Set.copyOf(members));
        }

        if (!seenPins.equals(expectedPins)) {
            Set<PinKey> missing = new HashSet<>(expectedPins);
            missing.removeAll(seenPins);
            throw new IllegalStateException(
                    "Feedback connectivity verification failed: one or more pins are missing "
                            + "from topology analysis: " + missing + ".");
        }
        return Set.copyOf(partition);
    }
}
