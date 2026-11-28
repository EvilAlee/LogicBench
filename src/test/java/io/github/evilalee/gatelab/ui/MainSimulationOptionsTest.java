package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.UndefinedInputMode;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBoxMenuItem;
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

    @Test
    void chipsNeedPowerCheckboxStartsOnAndRecomputesWithoutChangingHistory() {
        CanvasPanel canvas = new CanvasPanel();
        assertTrue(canvas.chipsNeedPower());
        JMenu simulate = Main.createSimulateMenu(canvas);
        JCheckBoxMenuItem chipsNeedPower = null;
        for (java.awt.Component component : simulate.getMenuComponents()) {
            if (component instanceof JCheckBoxMenuItem item
                    && "Chips need power".equals(item.getText())) {
                chipsNeedPower = item;
                break;
            }
        }
        assertNotNull(chipsNeedPower);
        assertTrue(chipsNeedPower.isSelected());

        long revision = canvas.schematic().modificationCount();
        long recomputes = canvas.simulationRecomputeCount();
        chipsNeedPower.doClick();
        assertFalse(canvas.chipsNeedPower());
        assertEquals(revision, canvas.schematic().modificationCount());
        assertEquals(recomputes + 1, canvas.simulationRecomputeCount());

        chipsNeedPower.doClick();
        assertTrue(canvas.chipsNeedPower());
        assertEquals(revision, canvas.schematic().modificationCount());
        assertEquals(recomputes + 2, canvas.simulationRecomputeCount());
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
