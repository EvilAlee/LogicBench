package io.github.evilalee.gatelab.model;

import io.github.evilalee.gatelab.io.GateLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicTest {
    private static final List<GateDef> DEFINITIONS = GateLibrary.load().all();

    @Test
    void addRemoveMoveAndSetFacingMutateOnlyThroughSchematic() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent added = schematic.add("and", 100, 200, Facing.EAST);

        assertEquals(1, schematic.components().size());
        assertEquals(added, schematic.components().getFirst());

        schematic.move(added.id(), -30, 40);
        assertEquals(new PlacedComponent(
                added.id(), "and", -30, 40, Facing.EAST), schematic.components().getFirst());

        schematic.setFacing(added.id(), Facing.NORTH);
        assertEquals(Facing.NORTH, schematic.components().getFirst().facing());
        assertEquals(-30, schematic.components().getFirst().x());
        assertEquals(40, schematic.components().getFirst().y());

        assertTrue(schematic.remove(added.id()));
        assertTrue(schematic.components().isEmpty());
        assertFalse(schematic.remove(added.id()));
    }

    @Test
    void generatedAndExplicitIdsMustBeUnique() {
        Schematic schematic = new Schematic(DEFINITIONS);
        schematic.add(new PlacedComponent("component-1", "and", 0, 0, Facing.EAST));
        PlacedComponent generated = schematic.add("or", 100, 0, Facing.EAST);
        PlacedComponent another = schematic.add("not", 200, 0, Facing.EAST);

        assertNotEquals("component-1", generated.id());
        assertNotEquals(generated.id(), another.id());
        assertEquals(3, schematic.components().stream()
                .map(PlacedComponent::id).distinct().count());

        assertThrows(IllegalArgumentException.class, () ->
                schematic.add(new PlacedComponent(
                        generated.id(), "nand", 300, 0, Facing.EAST)));
    }

    @Test
    void pointHitTestingUsesRotatedBoundsForEveryFacing() {
        for (GateDef definition : DEFINITIONS) {
            for (Facing facing : Facing.values()) {
                Schematic schematic = new Schematic(DEFINITIONS);
                PlacedComponent placed = schematic.add(definition.id(), 100, 200, facing);
                GateGeometry.Bounds bounds = GateGeometry.bounds(definition, facing);
                int hitX = placed.x() + bounds.x() + bounds.width() / 2;
                int hitY = placed.y() + bounds.y() + bounds.height() / 2;

                assertEquals(placed, schematic.componentAt(hitX, hitY).orElseThrow(),
                        definition.id() + " at " + facing);
                assertTrue(schematic.componentAt(
                        placed.x() + bounds.x() - 2,
                        placed.y() + bounds.y() - 2).isEmpty(),
                        definition.id() + " outside " + facing);
            }
        }
    }

    @Test
    void rectangleSelectionUsesRotatedBoundsForEveryFacing() {
        for (GateDef definition : DEFINITIONS) {
            for (Facing facing : Facing.values()) {
                Schematic schematic = new Schematic(DEFINITIONS);
                PlacedComponent placed = schematic.add(definition.id(), 300, -100, facing);
                GateGeometry.Bounds relative = GateGeometry.bounds(definition, facing);
                GateGeometry.Bounds selection = new GateGeometry.Bounds(
                        placed.x() + relative.x(),
                        placed.y() + relative.y(),
                        relative.width(),
                        relative.height());

                assertEquals(List.of(placed), schematic.componentsIntersecting(selection),
                        definition.id() + " at " + facing);
                assertTrue(schematic.componentsIntersecting(new GateGeometry.Bounds(
                        selection.x() + selection.width() + 20,
                        selection.y() + selection.height() + 20,
                        10, 10)).isEmpty(),
                        definition.id() + " outside " + facing);
            }
        }
    }

    @Test
    void componentAndMoveCoordinatesMustStayOnTheTenUnitGrid() {
        Schematic schematic = new Schematic(DEFINITIONS);
        assertThrows(IllegalArgumentException.class, () ->
                new PlacedComponent("bad", "and", 11, 20, Facing.EAST));

        PlacedComponent placed = schematic.add("and", 10, 20, Facing.EAST);
        assertThrows(IllegalArgumentException.class, () -> schematic.move(placed.id(), 10, 21));

        schematic.move(placed.id(), -40, 70);
        assertEquals(-40, schematic.components().getFirst().x());
        assertEquals(70, schematic.components().getFirst().y());
        assertEquals(0, schematic.components().getFirst().x() % PlacedComponent.GRID_SPACING);
        assertEquals(0, schematic.components().getFirst().y() % PlacedComponent.GRID_SPACING);
    }
}
