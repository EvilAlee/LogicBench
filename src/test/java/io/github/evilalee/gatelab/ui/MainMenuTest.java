package io.github.evilalee.gatelab.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import java.awt.Component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainMenuTest {
    @Test
    void simulateMenuEnablesTickOnceAndExposesTicksEnabledCheckbox() {
        CanvasPanel canvas = new CanvasPanel();
        JMenu simulate = Main.createSimulateMenu(canvas);
        JMenuItem tickOnce = null;
        JCheckBoxMenuItem ticksEnabled = null;
        JMenuItem reset = null;
        for (Component item : simulate.getMenuComponents()) {
            if (item instanceof JCheckBoxMenuItem checkbox
                    && "Ticks Enabled".equals(checkbox.getText())) {
                ticksEnabled = checkbox;
            } else if (item instanceof JMenuItem menuItem
                    && "Tick Once".equals(menuItem.getText())) {
                tickOnce = menuItem;
            } else if (item instanceof JMenuItem menuItem
                    && "Reset".equals(menuItem.getText())) {
                reset = menuItem;
            }
        }

        assertNotNull(tickOnce);
        assertTrue(tickOnce.isEnabled());
        assertNotNull(ticksEnabled);
        assertNotNull(reset);

        tickOnce.doClick();
        assertTrue(canvas.simulationStatusText().contains("Step 1"));
        assertTrue(canvas.simulationStatusText().contains("cycle 0.5"));
        reset.doClick();
        assertEquals("Step 0, cycle 0", canvas.simulationStatusText());
    }

    @Test
    void startupMenuBarContainsShowGateNamesUnderView() {
        // createMenuBar is the same builder used by Main.createFrame during startup.
        JMenuBar menuBar = Main.createMenuBar(new CanvasPanel());
        JMenu view = null;
        for (int index = 0; index < menuBar.getMenuCount(); index++) {
            JMenu candidate = menuBar.getMenu(index);
            if (candidate != null && "View".equals(candidate.getText())) {
                view = candidate;
                break;
            }
        }

        assertNotNull(view, "the application menu bar must contain View");
        boolean found = false;
        for (Component item : view.getMenuComponents()) {
            if (item instanceof JCheckBoxMenuItem checkbox
                    && "Show gate names".equals(checkbox.getText())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "View must contain the Show gate names checkbox");
    }
}
