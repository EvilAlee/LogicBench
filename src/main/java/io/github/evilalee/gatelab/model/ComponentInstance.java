package io.github.evilalee.gatelab.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A black-box placement; its terminals are virtual schematic pins, not engine gates. */
public record ComponentInstance(
        String definitionName,
        String definitionHash,
        String id,
        String label,
        int x,
        int y,
        Facing rotation) {
    public static final int BOX_WIDTH = 100;
    public static final int PORT_SPACING = 20;

    public record PinGeometry(
            String name,
            ComponentDefinition.Direction direction,
            boolean isClock,
            int x,
            int y) {
        public PinGeometry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(direction, "direction");
        }
    }

    public record Geometry(int width, int height, List<PinGeometry> pins) {
        public Geometry {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("component instance bounds must be positive");
            }
            pins = List.copyOf(Objects.requireNonNull(pins, "pins"));
        }
    }

    public ComponentInstance {
        definitionName = requireText(definitionName, "definitionName");
        definitionHash = requireHash(definitionHash);
        id = requireText(id, "id");
        label = label == null || label.isBlank() ? "" : label.trim();
        if (!id.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("instance id must be a component identifier: " + id);
        }
        requireGrid(x, "x");
        requireGrid(y, "y");
        Objects.requireNonNull(rotation, "rotation");
    }

    public Geometry geometry(ComponentDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (!definition.name().equals(definitionName)
                || !definition.hash().equals(definitionHash)) {
            throw new IllegalArgumentException("component instance definition/hash mismatch: " + id);
        }
        int inputCount = definition.inputPorts().size();
        int outputCount = definition.outputPorts().size();
        int canonicalHeight = Math.max(inputCount, outputCount) * PORT_SPACING + PORT_SPACING;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int[] corner : new int[][]{
                {0, 0}, {BOX_WIDTH, 0}, {0, canonicalHeight}, {BOX_WIDTH, canonicalHeight}}) {
            GateGeometry.Offset rotated = rotateAboutCenter(
                    corner[0], corner[1], BOX_WIDTH, canonicalHeight, rotation);
            minX = Math.min(minX, rotated.x());
            minY = Math.min(minY, rotated.y());
            maxX = Math.max(maxX, rotated.x());
            maxY = Math.max(maxY, rotated.y());
        }

        List<PinGeometry> pins = new ArrayList<>(definition.ports().size());
        addPortPins(pins, definition.inputPorts(), ComponentDefinition.Direction.IN,
                0, canonicalHeight);
        addPortPins(pins, definition.outputPorts(), ComponentDefinition.Direction.OUT,
                BOX_WIDTH, canonicalHeight);
        int boundsMinX = minX;
        int boundsMinY = minY;
        List<PinGeometry> rotatedPins = new ArrayList<>(pins.size());
        for (PinGeometry pin : pins) {
            GateGeometry.Offset point = rotateAboutCenter(
                    pin.x(), pin.y(), BOX_WIDTH, canonicalHeight, rotation);
            rotatedPins.add(new PinGeometry(
                    pin.name(), pin.direction(), pin.isClock(),
                    x + point.x() - boundsMinX, y + point.y() - boundsMinY));
        }
        return new Geometry(maxX - minX, maxY - minY, rotatedPins);
    }

    public GateGeometry.Bounds bounds(ComponentDefinition definition) {
        Geometry geometry = geometry(definition);
        return new GateGeometry.Bounds(x, y, geometry.width(), geometry.height());
    }

    public List<Connectivity.PinPosition> pinPositions(ComponentDefinition definition) {
        return geometry(definition).pins().stream()
                .map(pin -> new Connectivity.PinPosition(
                        id, pin.name(), pin.x(), pin.y()))
                .toList();
    }

    private static void addPortPins(
            List<PinGeometry> target,
            List<ComponentDefinition.Port> ports,
            ComponentDefinition.Direction direction,
            int x,
            int height) {
        int firstY = (height - (ports.size() - 1) * PORT_SPACING) / 2;
        for (int index = 0; index < ports.size(); index++) {
            ComponentDefinition.Port port = ports.get(index);
            target.add(new PinGeometry(
                    port.name(), direction, port.isClock(), x, firstY + index * PORT_SPACING));
        }
    }

    private static GateGeometry.Offset rotateAboutCenter(
            int x, int y, int width, int height, Facing facing) {
        GateGeometry.Offset doubled = GateGeometry.rotate(
                new GateGeometry.Offset(x * 2 - width, y * 2 - height), facing);
        return new GateGeometry.Offset(doubled.x() / 2, doubled.y() / 2);
    }

    private static String requireHash(String value) {
        String hash = requireText(value, "definitionHash").toLowerCase(java.util.Locale.ROOT);
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("definitionHash must be a SHA-256 hex digest");
        }
        return hash;
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        String text = value.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return text;
    }

    private static void requireGrid(int value, String label) {
        if (value % PlacedComponent.GRID_SPACING != 0) {
            throw new IllegalArgumentException(
                    label + " must be a multiple of " + PlacedComponent.GRID_SPACING);
        }
    }
}
