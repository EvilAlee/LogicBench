package io.github.evilalee.gatelab.model;

import io.github.evilalee.gatelab.io.GateLibrary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchematicHistoryOptionsTest {
    @Test
    void undefinedInputModeMarksTheDocumentButIsNotAnUndoableTopologyStep() {
        Schematic schematic = new Schematic(GateLibrary.load().all());
        SchematicHistory history = new SchematicHistory();
        long before = schematic.modificationCount();

        history.begin(schematic);
        schematic.setUndefinedInputMode(UndefinedInputMode.ERROR);

        assertEquals(before + 1, schematic.modificationCount());
        assertFalse(history.commit(schematic));
        assertFalse(history.canUndo());
        assertEquals(UndefinedInputMode.ERROR, schematic.undefinedInputMode());
    }
}
