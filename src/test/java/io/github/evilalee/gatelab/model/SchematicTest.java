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
    void outputReportsTappedOnlyWhenItsNetAlsoFeedsAGate() {
        Schematic schematic = new Schematic(DEFINITIONS);
        var source = schematic.add("and", 100, 100, Facing.EAST);
        var output = schematic.add("output", 300, 110, Facing.EAST);

        Connectivity.PinPosition sourcePin = schematic.pinPositions().stream()
                .filter(pin -> pin.componentId().equals(source.id())
                        && pin.pinId().equals("out"))
                .findFirst().orElseThrow();
        Connectivity.PinPosition outputPin = schematic.pinPositions().stream()
                .filter(pin -> pin.componentId().equals(output.id())
                        && pin.pinId().equals("in0"))
                .findFirst().orElseThrow();
        schematic.addWire(
                sourcePin.x(), sourcePin.y(), outputPin.x(), outputPin.y());

        assertFalse(schematic.isOutputTapped(output.id()),
                "a probe on a net with only a driving gate is not a tap");

        var consumer = schematic.add("or", 400, 110, Facing.EAST);
        Connectivity.PinPosition consumerPin = schematic.pinPositions().stream()
                .filter(pin -> pin.componentId().equals(consumer.id())
                        && pin.pinId().equals("in0"))
                .findFirst().orElseThrow();
        schematic.addWire(
                outputPin.x(), outputPin.y(), consumerPin.x(), consumerPin.y());

        assertTrue(schematic.isOutputTapped(output.id()),
                "the probe is a tap when the same net also feeds a gate input");
        assertThrows(IllegalArgumentException.class, () -> schematic.isOutputTapped(source.id()));
    }

    @Test
    void optionalLabelsSurviveMoveFacingAndAttributeReplacement() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent input = new PlacedComponent(
                "labeled-input", "input", 0, 0, Facing.EAST, java.util.Map.of(), "CLK");
        schematic.add(input);

        schematic.move(input.id(), 40, 20);
        assertEquals("CLK", schematic.components().getFirst().label());
        assertTrue(schematic.setFacing(input.id(), Facing.SOUTH));
        assertEquals("CLK", schematic.components().getFirst().label());
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
    void modificationCounterTracksEverySuccessfulMutationButNotQueries() {
        Schematic schematic = new Schematic(DEFINITIONS);
        assertEquals(0, schematic.modificationCount());

        PlacedComponent input = schematic.add("input", 0, 0, Facing.EAST);
        assertEquals(1, schematic.modificationCount());
        PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);
        assertEquals(2, schematic.modificationCount());

        schematic.move(input.id(), 10, 0);
        assertEquals(3, schematic.modificationCount());
        schematic.setFacing(gate.id(), Facing.SOUTH);
        assertEquals(4, schematic.modificationCount());

        schematic.setInputValue(input.id(), true);
        assertEquals(5, schematic.modificationCount());
        schematic.toggleInput(input.id());
        assertEquals(6, schematic.modificationCount());
        schematic.setInputValue(input.id(), true);
        assertEquals(7, schematic.modificationCount());
        assertTrue(schematic.resetInputs());
        assertEquals(8, schematic.modificationCount());

        WireSegment wire = new WireSegment(0, 0, 20, 0);
        assertTrue(schematic.addWire(wire));
        assertEquals(9, schematic.modificationCount());
        assertFalse(schematic.addWire(wire));
        assertEquals(9, schematic.modificationCount());
        List<WireSegment> moved = schematic.moveWires(List.of(wire), 10, 0);
        assertEquals(List.of(new WireSegment(10, 0, 30, 0)), moved);
        assertEquals(10, schematic.modificationCount());
        assertTrue(schematic.removeWire(moved.getFirst()));
        assertEquals(11, schematic.modificationCount());
        assertTrue(schematic.remove(gate.id()));
        assertEquals(12, schematic.modificationCount());
        assertTrue(schematic.remove(input.id()));
        assertEquals(13, schematic.modificationCount());

        schematic.components();
        schematic.wires();
        schematic.pinPositions();
        schematic.componentAt(0, 0);
        schematic.wireAt(0, 0, 1);
        assertFalse(schematic.remove("missing"));
        assertFalse(schematic.resetInputs());
        assertFalse(schematic.removeWire(moved.getFirst()));
        assertEquals(13, schematic.modificationCount());
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
    void overlappingComponentsReturnTheLastAddedComponentFirst() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent bottom = schematic.add("and", 100, 100, Facing.EAST);
        PlacedComponent top = schematic.add("or", 100, 100, Facing.EAST);

        assertEquals(top, schematic.componentAt(85, 100).orElseThrow());
        assertEquals(List.of(bottom, top), schematic.componentsIntersecting(
                new GateGeometry.Bounds(100, 100, 30, 40)));
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
