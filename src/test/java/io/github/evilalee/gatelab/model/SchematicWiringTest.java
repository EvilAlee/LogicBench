package io.github.evilalee.gatelab.model;

import io.github.evilalee.gatelab.io.GateLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicWiringTest {
    @Test
    void addRemoveAndDuplicateSuppressionUseSchematicMutationMethods() {
        Schematic schematic = new Schematic(GateLibrary.load().all());
        WireSegment wire = new WireSegment(0, 0, 20, 0);

        assertFalse(schematic.addWire(10, 10, 10, 10),
                "zero-length gestures are ignored");
        assertTrue(schematic.addWire(wire));
        assertFalse(schematic.addWire(new WireSegment(20, 0, 0, 0)),
                "reversed endpoints normalize to the same exact segment");
        assertEquals(List.of(wire), schematic.wires());
        assertTrue(schematic.removeWire(wire));
        assertFalse(schematic.removeWire(wire));
        assertTrue(schematic.wires().isEmpty());
    }

    @Test
    void hitTestingUsesDistanceAndMarqueeIntersection() {
        Schematic schematic = new Schematic(GateLibrary.load().all());
        WireSegment horizontal = new WireSegment(0, 20, 40, 20);
        WireSegment vertical = new WireSegment(40, 20, 40, 50);
        schematic.addWire(horizontal);
        schematic.addWire(vertical);

        assertEquals(horizontal, schematic.wireAt(15, 23, 3).orElseThrow());
        assertTrue(schematic.wireAt(15, 24, 3).isEmpty());
        assertEquals(List.of(horizontal), schematic.wiresIntersecting(
                new GateGeometry.Bounds(10, 19, 10, 2)));
        assertEquals(List.of(horizontal, vertical), schematic.wiresIntersecting(
                new GateGeometry.Bounds(39, 19, 2, 2)));
    }

    @Test
    void movingAComponentDoesNotMoveItsAttachedWire() {
        Schematic schematic = new Schematic(GateLibrary.load().all());
        PlacedComponent component = schematic.add("input", 100, 100, Facing.EAST);
        WireSegment attached = new WireSegment(100, 100, 120, 100);
        schematic.addWire(attached);

        schematic.move(component.id(), 200, 200);

        assertEquals(attached, schematic.wires().getFirst());
    }

    @Test
    void movingSelectedWiresTranslatesOnlyThoseWiresAndKeepsGridAlignment() {
        Schematic schematic = new Schematic(GateLibrary.load().all());
        WireSegment selected = new WireSegment(0, 0, 20, 0);
        WireSegment untouched = new WireSegment(40, 0, 40, 20);
        schematic.addWire(selected);
        schematic.addWire(untouched);

        List<WireSegment> moved = schematic.moveWires(List.of(selected), 10, 20);

        assertEquals(List.of(new WireSegment(10, 20, 30, 20)), moved);
        assertEquals(List.of(untouched, new WireSegment(10, 20, 30, 20)),
                schematic.wires());
        assertTrue(schematic.wires().contains(untouched));
    }
}
