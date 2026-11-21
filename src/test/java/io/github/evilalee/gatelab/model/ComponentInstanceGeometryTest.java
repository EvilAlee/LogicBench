package io.github.evilalee.gatelab.model;

import io.github.evilalee.gatelab.io.GateLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComponentInstanceGeometryTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();

    private static ComponentDefinition sampleDefinition() {
        Schematic internal = new Schematic(GATES);
        internal.add(new PlacedComponent("a", "input", 0, 0,
                Facing.EAST, Map.of(), "A"));
        internal.add(new PlacedComponent("clk", "clock", 0, 40,
                Facing.EAST, Map.of(), "CLK"));
        internal.add(new PlacedComponent("b", "input", 0, 80,
                Facing.EAST, Map.of(), "B"));
        internal.add(new PlacedComponent("q", "output", 200, 0,
                Facing.EAST, Map.of(), "Q"));
        internal.add(new PlacedComponent("qn", "output", 200, 40,
                Facing.EAST, Map.of(), "QN"));
        return ComponentDefinition.fromCircuit(
                "Sample", "Sample component", "Test", "Q", internal);
    }

    @Test
    void pinsFollowPortOrderWithTwentyUnitSpacingAndSpecifiedBodyHeight() {
        ComponentDefinition definition = sampleDefinition();
        ComponentInstance instance = new ComponentInstance(
                "Sample", definition.hash(), "U1", "", 100, 200, Facing.EAST);

        ComponentInstance.Geometry geometry = instance.geometry(definition);

        assertEquals(100, geometry.width());
        assertEquals(80, geometry.height());
        assertEquals(List.of("A", "CLK", "B", "Q", "QN"),
                geometry.pins().stream().map(ComponentInstance.PinGeometry::name).toList());
        assertEquals(List.of(0, 0, 0, 100, 100),
                geometry.pins().stream().map(pin -> pin.x() - instance.x()).toList());
        assertEquals(List.of(20, 40, 60, 30, 50),
                geometry.pins().stream().map(pin -> pin.y() - instance.y()).toList());
        assertTrue(geometry.pins().get(1).isClock());
        assertFalse(geometry.pins().get(0).isClock());
    }

    @Test
    void rotationRotatesBodyAndItsPinCoordinatesAsOnePureGeometry() {
        ComponentDefinition definition = sampleDefinition();
        ComponentInstance east = new ComponentInstance(
                "Sample", definition.hash(), "U1", "", 100, 200, Facing.EAST);
        ComponentInstance south = new ComponentInstance(
                "Sample", definition.hash(), "U1", "", 100, 200, Facing.SOUTH);

        ComponentInstance.Geometry eastGeometry = east.geometry(definition);
        ComponentInstance.Geometry southGeometry = south.geometry(definition);

        assertEquals(100, eastGeometry.width());
        assertEquals(80, eastGeometry.height());
        assertEquals(80, southGeometry.width());
        assertEquals(100, southGeometry.height());
        ComponentInstance.PinGeometry eastClock = eastGeometry.pins().get(1);
        ComponentInstance.PinGeometry southClock = southGeometry.pins().get(1);
        assertEquals(east.x() + 40, eastClock.x());
        assertEquals(east.y() + 40, eastClock.y());
        assertEquals(south.x() + 40, southClock.x());
        assertEquals(south.y(), southClock.y());
        assertTrue(southClock.isClock());
    }

    @Test
    void instanceRejectsDefinitionHashOrIdentityMismatch() {
        ComponentDefinition definition = sampleDefinition();
        ComponentInstance instance = new ComponentInstance(
                "Sample", definition.hash(), "U1", "", 0, 0, Facing.EAST);
        assertThrows(IllegalArgumentException.class,
                () -> instance.geometry(ComponentDefinition.fromCircuit(
                        "Other", "Different", "Test", null, simpleCircuit())));
    }

    private static Schematic simpleCircuit() {
        Schematic schematic = new Schematic(GATES);
        schematic.add(new PlacedComponent("input", "input", 0, 0,
                Facing.EAST, Map.of(), "A"));
        return schematic;
    }
}
