package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.UndefinedInputMode;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.JRadioButtonMenuItem;

import static org.junit.jupiter.api.Assertions.*;

class MainSimulationOptionsTest {
    @Test
    void simulateMenuProvidesExclusiveModesAndRecomputesWithoutAddingHistory() {
        CanvasPanel canvas = new CanvasPanel();
        JMenu simulate = Main.createSimulateMenu(canvas);
        JRadioButtonMenuItem ignore = modeItem(simulate, "Ignore unconnected inputs");
        JRadioButtonMenuItem error = modeItem(simulate, "Error for undefined inputs");

        assertTrue(ignore.isSelected());
        assertFalse(error.isSelected());
        assertFalse(canvas.canUndo());

        long revision = canvas.schematic().modificationCount();
        long recomputes = canvas.simulationRecomputeCount();
        error.doClick();

        assertEquals(UndefinedInputMode.ERROR, canvas.schematic().undefinedInputMode());
        assertTrue(error.isSelected());
        assertFalse(ignore.isSelected());
        assertEquals(revision + 1, canvas.schematic().modificationCount());
        assertEquals(recomputes + 1, canvas.simulationRecomputeCount());
        assertFalse(canvas.canUndo());
    }

    private static JRadioButtonMenuItem modeItem(JMenu menu, String label) {
        for (java.awt.Component component : menu.getMenuComponents()) {
            if (component instanceof JRadioButtonMenuItem item
                    && label.equals(item.getText())) {
                return item;
            }
        }
        fail("Missing Simulate menu item: " + label);
        return null;
    }
}
