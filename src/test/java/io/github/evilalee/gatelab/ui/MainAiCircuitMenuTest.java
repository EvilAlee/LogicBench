package io.github.evilalee.gatelab.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import java.awt.Component;

import static org.junit.jupiter.api.Assertions.*;

class MainAiCircuitMenuTest {
    @Test
    void realStartupCircuitMenuContainsAiActionAndOfflineCacheToggle() {
        CanvasPanel canvas = new CanvasPanel();
        JMenuBar menuBar = Main.createMenuBar(canvas);
        JMenu circuit = null;
        for (int index = 0; index < menuBar.getMenuCount(); index++) {
            JMenu candidate = menuBar.getMenu(index);
            if (candidate != null && "Circuit".equals(candidate.getText())) {
                circuit = candidate;
                break;
            }
        }
        assertNotNull(circuit);

        JMenuItem fromDescription = null;
        JCheckBoxMenuItem cacheOnly = null;
        for (Component component : circuit.getMenuComponents()) {
            if (component instanceof JMenuItem item
                    && "From description (AI)...".equals(item.getText())) {
                fromDescription = item;
            }
            if (component instanceof JCheckBoxMenuItem check
                    && "AI: use cache only (offline)".equals(check.getText())) {
                cacheOnly = check;
            }
        }
        assertNotNull(fromDescription);
        assertNotNull(fromDescription.getActionListeners());
        assertTrue(fromDescription.getActionListeners().length > 0);
        assertNotNull(cacheOnly);
        assertFalse(cacheOnly.isSelected());
    }
}
