package io.github.evilalee.gatelab.model;

import io.github.evilalee.gatelab.io.GateLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectivityTest {
    @Test
    void wireSegmentsAreAxisAlignedGridBoundedNormalizedAndNonzero() {
        assertEquals(new WireSegment(0, 0, 20, 0), new WireSegment(20, 0, 0, 0));
        assertEquals(new WireSegment(10, 0, 10, 30), new WireSegment(10, 30, 10, 0));
        assertTrue(new WireSegment(0, 0, 10, 0).isHorizontal());
        assertFalse(new WireSegment(0, 0, 0, 10).isHorizontal());

        assertThrows(IllegalArgumentException.class, () -> new WireSegment(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WireSegment(0, 0, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new WireSegment(0, 0, 11, 0));
    }

    @Test
    void twoPinsAreConnectedByAnLShapedWire() {
        Connectivity.PinPosition first = pin("input", "out", 0, 0);
        Connectivity.PinPosition second = pin("output", "in0", 10, 10);
        List<WireSegment> wires = List.of(
                new WireSegment(0, 0, 10, 0),
                new WireSegment(10, 0, 10, 10));

        Connectivity.Result result = Connectivity.analyze(List.of(first, second), wires);

        assertEquals(1, result.nets().size());
        assertEquals(Set.of(first, second), result.nets().getFirst().pins());
        assertEquals(Set.copyOf(wires), result.nets().getFirst().segments());
        assertTrue(result.junctions().isEmpty(), "a two-segment corner is not a junction");
    }

    @Test
    void crossingSegmentsWithoutANodeRemainSeparate() {
        WireSegment horizontal = new WireSegment(-10, 0, 10, 0);
        WireSegment vertical = new WireSegment(0, -10, 0, 10);

        Connectivity.Result result = Connectivity.analyze(List.of(), List.of(horizontal, vertical));

        assertEquals(2, result.nets().size());
        assertTrue(result.junctions().isEmpty());
        assertTrue(result.nets().stream().allMatch(net -> net.segments().size() == 1));
    }

    @Test
    void endpointOnAnotherSegmentCreatesATJunction() {
        WireSegment horizontal = new WireSegment(0, 10, 20, 10);
        WireSegment branch = new WireSegment(10, 0, 10, 10);

        Connectivity.Result result = Connectivity.analyze(List.of(), List.of(horizontal, branch));

        assertEquals(1, result.nets().size());
        assertEquals(Set.of(horizontal, branch), result.nets().getFirst().segments());
        assertEquals(Set.of(new Connectivity.Point(10, 10)), result.junctions());
    }

    @Test
    void pinOnWireInteriorJoinsTheWireWithoutAddingDegree() {
        Connectivity.PinPosition pin = pin("gate", "in0", 10, 10);
        WireSegment wire = new WireSegment(0, 10, 20, 10);

        Connectivity.Result result = Connectivity.analyze(List.of(pin), List.of(wire));

        assertEquals(1, result.nets().size());
        assertEquals(Set.of(pin), result.nets().getFirst().pins());
        assertTrue(result.junctions().isEmpty(), "a pin does not add segment degree");
    }

    @Test
    void collinearOverlappingSegmentsJoinAndMarkTheirInteriorEndpoints() {
        WireSegment first = new WireSegment(0, 10, 20, 10);
        WireSegment second = new WireSegment(10, 10, 30, 10);

        Connectivity.Result result = Connectivity.analyze(List.of(), List.of(first, second));

        assertEquals(1, result.nets().size());
        assertEquals(Set.of(first, second), result.nets().getFirst().segments());
        assertEquals(Set.of(
                new Connectivity.Point(10, 10),
                new Connectivity.Point(20, 10)), result.junctions());
    }

    @Test
    void junctionDegreeCountsAnEndpointAsOneAndAnInteriorAsTwo() {
        WireSegment horizontal = new WireSegment(0, 10, 20, 10);
        WireSegment upper = new WireSegment(10, 0, 10, 10);
        WireSegment lower = new WireSegment(10, 10, 10, 20);
        Connectivity.Result result = Connectivity.analyze(
                List.of(), List.of(horizontal, upper, lower));

        assertEquals(Set.of(new Connectivity.Point(10, 10)), result.junctions());
    }

    @Test
    void placedPinWorldPositionsUseTheM3RotatedPinGeometry() {
        Schematic schematic = new Schematic(GateLibrary.load().all());
        PlacedComponent placed = schematic.add("and", 100, 200, Facing.WEST);
        GateDef definition = GateLibrary.load().get("and");

        List<Connectivity.PinPosition> pins = schematic.pinPositions();
        for (int index = 0; index < definition.inputs().size(); index++) {
            int inputIndex = index;
            GateGeometry.Offset offset = GateGeometry.pinOffset(
                    definition, definition.inputs().get(inputIndex), Facing.WEST);
            Connectivity.PinPosition pin = pins.stream()
                    .filter(candidate -> candidate.componentId().equals(placed.id())
                            && candidate.pinId().equals("in" + inputIndex))
                    .findFirst().orElseThrow();
            assertEquals(placed.x() + offset.x(), pin.x());
            assertEquals(placed.y() + offset.y(), pin.y());
        }

        GateGeometry.Offset output = GateGeometry.outputOffset(definition, Facing.WEST);
        Connectivity.PinPosition outputPin = pins.stream()
                .filter(candidate -> candidate.pinId().equals("out"))
                .findFirst().orElseThrow();
        assertEquals(placed.x() + output.x(), outputPin.x());
        assertEquals(placed.y() + output.y(), outputPin.y());
    }

    private static Connectivity.PinPosition pin(
            String component, String pin, int x, int y) {
        return new Connectivity.PinPosition(component, pin, x, y);
    }
}
