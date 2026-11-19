package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.PlacedComponent;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import java.awt.Component;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MainExamplesMenuTest {
    @Test
    void examplesMenuListsAllBundledCircuitsAndLoadsSelectedExampleAsUntitledDocument() {
        CanvasPanel canvas = new CanvasPanel();
        JMenuBar menuBar = Main.createMenuBar(canvas);
        JMenu examples = null;
        for (int index = 0; index < menuBar.getMenuCount(); index++) {
            JMenu candidate = menuBar.getMenu(index);
            if (candidate != null && "Examples".equals(candidate.getText())) {
                examples = candidate;
                break;
            }
        }
        assertNotNull(examples, "the startup menu bar must contain Examples");

        assertEquals(List.of("D latch", "D flip-flop", "Divide by 2", "2-bit counter"),
                menuItemLabels(examples));

        findItem(examples, "D latch").doClick();

        assertEquals(List.of("D", "EN"), inputLabels(canvas));
        assertEquals(List.of("Q", "Qbar"), outputLabels(canvas));
    }

    @Test
    void aMissingExampleResourceIsReportedAsAnIoFailureInsteadOfDereferenced() {
        IOException exception = assertThrows(IOException.class,
                () -> Main.loadExampleSchematic("examples/not-present.lbc"));
        assertTrue(exception.getMessage().contains("not-present.lbc"));
    }

    private static List<String> menuItemLabels(JMenu menu) {
        return java.util.Arrays.stream(menu.getMenuComponents())
                .filter(JMenuItem.class::isInstance)
                .map(JMenuItem.class::cast)
                .map(JMenuItem::getText)
                .toList();
    }

    private static JMenuItem findItem(JMenu menu, String label) {
        for (Component component : menu.getMenuComponents()) {
            if (component instanceof JMenuItem item && label.equals(item.getText())) {
                return item;
            }
        }
        throw new AssertionError("Missing Examples item: " + label);
    }

    private static List<String> inputLabels(CanvasPanel canvas) {
        return canvas.schematic().components().stream()
                .filter(component -> component.typeId().equals("input")
                        || component.typeId().equals("clock"))
                .map(PlacedComponent::label)
                .toList();
    }

    private static List<String> outputLabels(CanvasPanel canvas) {
        return canvas.schematic().components().stream()
                .filter(component -> component.typeId().equals("output"))
                .map(PlacedComponent::label)
                .toList();
    }
}
