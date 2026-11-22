package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.WireSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only pin diagnostics derived from the exact root topology and values
 * produced by SchematicCompiler. This class deliberately does not rebuild nets.
 */
public final class PinStateHelper {
    public enum State {
        UNCONNECTED,
        CONNECTED_ZERO,
        CONNECTED_ONE,
        CONNECTED_X
    }

    public record PinKey(String componentId, String pinId) {
        public PinKey {
            componentId = requireText(componentId, "componentId");
            pinId = requireText(pinId, "pinId");
        }
    }

    public record Pin(Connectivity.PinPosition position, State state, Value value) {
        public Pin {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(value, "value");
        }
    }

    public record Result(
            Map<PinKey, Pin> pins,
            Set<Connectivity.Point> danglingWireEnds) {
        public Result {
            pins = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(pins, "pins")));
            danglingWireEnds = Set.copyOf(
                    Objects.requireNonNull(danglingWireEnds, "danglingWireEnds"));
        }

        public Pin requirePin(String componentId, String pinId) {
            Pin pin = pins.get(new PinKey(componentId, pinId));
            if (pin == null) {
                throw new IllegalArgumentException(
                        "unknown schematic pin " + componentId + "." + pinId);
            }
            return pin;
        }
    }

    private PinStateHelper() {
    }

    /**
     * Associates each visible schematic pin with its existing compiler net.
     * The SimulationResult must belong to the current schematic snapshot.
     */
    public static Result analyze(
            Schematic schematic, SchematicCompiler.SimulationResult simulation) {
        Objects.requireNonNull(schematic, "schematic");
        Objects.requireNonNull(simulation, "simulation");

        Map<PinKey, Connectivity.Net> netByPin = new LinkedHashMap<>();
        for (Connectivity.Net net : simulation.topology().nets()) {
            for (Connectivity.PinPosition position : net.pins()) {
                PinKey key = new PinKey(position.componentId(), position.pinId());
                if (netByPin.putIfAbsent(key, net) != null) {
                    throw new IllegalArgumentException(
                            "simulation topology contains duplicate pin " + key);
                }
            }
        }

        Map<PinKey, Pin> pins = new LinkedHashMap<>();
        List<Connectivity.PinPosition> schematicPins = schematic.pinPositions();
        for (Connectivity.PinPosition position : schematicPins) {
            PinKey key = new PinKey(position.componentId(), position.pinId());
            Connectivity.Net net = netByPin.get(key);
            if (net == null || !net.pins().contains(position)) {
                throw new IllegalArgumentException(
                        "simulation result does not match schematic pin "
                                + position.componentId() + "." + position.pinId());
            }

            Value value = simulation.netValues().getOrDefault(net, Value.X);
            boolean connected = !net.segments().isEmpty() || net.pins().size() > 1;
            State state = !connected ? State.UNCONNECTED : switch (value) {
                case ZERO -> State.CONNECTED_ZERO;
                case ONE -> State.CONNECTED_ONE;
                case X -> State.CONNECTED_X;
            };
            pins.put(key, new Pin(position, state, value));
        }

        if (pins.size() != netByPin.size()) {
            throw new IllegalArgumentException(
                    "simulation result contains pins that are not in the current schematic");
        }

        return new Result(pins, findDanglingWireEnds(schematic, simulation));
    }

    private static Set<Connectivity.Point> findDanglingWireEnds(
            Schematic schematic, SchematicCompiler.SimulationResult simulation) {
        Set<Connectivity.Point> pinPoints = new HashSet<>();
        for (Connectivity.PinPosition pin : schematic.pinPositions()) {
            pinPoints.add(new Connectivity.Point(pin.x(), pin.y()));
        }
        Set<Connectivity.Point> junctions = simulation.topology().junctions();
        List<WireSegment> wires = schematic.wires();
        Set<Connectivity.Point> dangling = new LinkedHashSet<>();

        for (int index = 0; index < wires.size(); index++) {
            WireSegment wire = wires.get(index);
            addDanglingEndpoint(
                    new Connectivity.Point(wire.x1(), wire.y1()),
                    index, wires, pinPoints, junctions, dangling);
            addDanglingEndpoint(
                    new Connectivity.Point(wire.x2(), wire.y2()),
                    index, wires, pinPoints, junctions, dangling);
        }
        return Set.copyOf(dangling);
    }

    private static void addDanglingEndpoint(
            Connectivity.Point endpoint,
            int wireIndex,
            List<WireSegment> wires,
            Set<Connectivity.Point> pinPoints,
            Set<Connectivity.Point> junctions,
            Set<Connectivity.Point> dangling) {
        if (pinPoints.contains(endpoint) || junctions.contains(endpoint)) {
            return;
        }
        for (int index = 0; index < wires.size(); index++) {
            if (index != wireIndex && contains(wires.get(index), endpoint)) {
                return;
            }
        }
        dangling.add(endpoint);
    }

    private static boolean contains(WireSegment wire, Connectivity.Point point) {
        if (wire.isHorizontal()) {
            return point.y() == wire.y1()
                    && point.x() >= wire.x1() && point.x() <= wire.x2();
        }
        return point.x() == wire.x1()
                && point.y() >= wire.y1() && point.y() <= wire.y2();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }
}
