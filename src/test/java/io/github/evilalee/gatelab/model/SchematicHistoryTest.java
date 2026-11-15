package io.github.evilalee.gatelab.model;

import io.github.evilalee.gatelab.io.GateLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicHistoryTest {
    private static final List<GateDef> DEFINITIONS = GateLibrary.load().all();

    @Test
    void undoRedoCoversAddRemoveMoveFacingAndWireOperations() {
        Schematic schematic = new Schematic(DEFINITIONS);
        SchematicHistory history = new SchematicHistory();

        history.begin(schematic);
        PlacedComponent input = schematic.add("input", 0, 0, Facing.EAST);
        assertTrue(history.commit(schematic));
        assertEquals(List.of(input), schematic.components());
        assertTrue(history.undo(schematic));
        assertTrue(schematic.components().isEmpty());
        assertTrue(history.redo(schematic));
        assertEquals(List.of(input), schematic.components());

        history.begin(schematic);
        assertTrue(schematic.remove(input.id()));
        assertTrue(history.commit(schematic));
        assertTrue(history.undo(schematic));
        assertEquals(input, schematic.components().getFirst());
        assertTrue(history.redo(schematic));
        assertTrue(schematic.components().isEmpty());

        history.undo(schematic);
        history.begin(schematic);
        schematic.move(input.id(), 40, 20);
        assertTrue(history.commit(schematic));
        assertEquals(40, schematic.components().getFirst().x());
        history.undo(schematic);
        assertEquals(0, schematic.components().getFirst().x());
        history.redo(schematic);
        assertEquals(40, schematic.components().getFirst().x());

        history.begin(schematic);
        schematic.setFacing(input.id(), Facing.NORTH);
        assertTrue(history.commit(schematic));
        history.undo(schematic);
        assertEquals(Facing.EAST, schematic.components().getFirst().facing());
        history.redo(schematic);
        assertEquals(Facing.NORTH, schematic.components().getFirst().facing());

        WireSegment wire = new WireSegment(0, 0, 40, 0);
        history.begin(schematic);
        assertTrue(schematic.addWire(wire));
        assertTrue(history.commit(schematic));
        history.undo(schematic);
        assertTrue(schematic.wires().isEmpty());
        history.redo(schematic);
        assertEquals(List.of(wire), schematic.wires());

        history.begin(schematic);
        assertTrue(schematic.removeWire(wire));
        assertTrue(history.commit(schematic));
        history.undo(schematic);
        assertEquals(List.of(wire), schematic.wires());
        history.redo(schematic);
        assertTrue(schematic.wires().isEmpty());
    }

    @Test
    void multiSegmentWireAndMultiComponentMoveEachUseOneStep() {
        Schematic schematic = new Schematic(DEFINITIONS);
        SchematicHistory history = new SchematicHistory();
        PlacedComponent first = schematic.add("and", 0, 0, Facing.EAST);
        PlacedComponent second = schematic.add("or", 100, 100, Facing.EAST);

        history.begin(schematic);
        schematic.addWire(new WireSegment(0, 0, 50, 0));
        schematic.addWire(new WireSegment(50, 0, 50, 40));
        assertTrue(history.commit(schematic));
        assertEquals(1, history.undoDepth());
        history.undo(schematic);
        assertTrue(schematic.wires().isEmpty());

        history.begin(schematic);
        schematic.move(first.id(), 20, 30);
        schematic.move(second.id(), 120, 130);
        assertTrue(history.commit(schematic));
        assertEquals(2, history.undoDepth());
        history.undo(schematic);
        assertEquals(0, schematic.components().getFirst().x());
        assertEquals(100, schematic.components().get(1).x());
    }

    @Test
    void aNewEditAfterUndoDiscardsRedo() {
        Schematic schematic = new Schematic(DEFINITIONS);
        SchematicHistory history = new SchematicHistory();

        history.begin(schematic);
        schematic.add("and", 0, 0, Facing.EAST);
        history.commit(schematic);
        assertTrue(history.undo(schematic));
        assertTrue(history.canRedo());

        history.begin(schematic);
        schematic.add("or", 100, 0, Facing.EAST);
        history.commit(schematic);
        assertFalse(history.canRedo());
    }

    @Test
    void inputValuesAreNotRestoredFromHistoryAndSurvivingInputsKeepTheirValues() {
        Schematic schematic = new Schematic(DEFINITIONS);
        SchematicHistory history = new SchematicHistory();
        PlacedComponent input = schematic.add("input", 0, 0, Facing.EAST);
        schematic.setInputValue(input.id(), true);

        history.begin(schematic);
        schematic.add("and", 100, 0, Facing.EAST);
        history.commit(schematic);
        schematic.setInputValue(input.id(), false);
        history.undo(schematic);
        assertFalse(schematic.inputValue(input.id()));
        history.redo(schematic);
        assertFalse(schematic.inputValue(input.id()));
    }

    @Test
    void undoingDeleteRestoresIdsAndGeneratedIdsRemainCollisionFree() {
        Schematic schematic = new Schematic(DEFINITIONS);
        SchematicHistory history = new SchematicHistory();
        PlacedComponent original = schematic.add("input", 0, 0, Facing.EAST);
        String originalId = original.id();

        history.begin(schematic);
        schematic.remove(originalId);
        history.commit(schematic);
        history.undo(schematic);
        assertEquals(originalId, schematic.components().getFirst().id());

        PlacedComponent generated = schematic.add("output", 100, 0, Facing.EAST);
        assertNotEquals(originalId, generated.id());
        assertEquals(2, schematic.components().stream()
                .map(PlacedComponent::id).distinct().count());
    }

    @Test
    void historyIsBoundedToTheLatestOneHundredSteps() {
        Schematic schematic = new Schematic(DEFINITIONS);
        SchematicHistory history = new SchematicHistory();

        for (int step = 0; step < 200; step++) {
            history.begin(schematic);
            schematic.add("and", step * 10, 0, Facing.EAST);
            history.commit(schematic);
        }

        assertEquals(SchematicHistory.MAX_HISTORY_STEPS, history.undoDepth());
        for (int step = 0; step < 100; step++) {
            assertTrue(history.undo(schematic));
        }
        assertEquals(100, schematic.components().size());
        assertFalse(history.canUndo());
        for (int step = 0; step < 100; step++) {
            assertTrue(history.redo(schematic));
        }
        assertEquals(200, schematic.components().size());
    }
}
