package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.SchematicSnapshot;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import java.awt.Component;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MainTidyMenuTest {
    @Test
    void realCircuitMenuItemTracksGatePresenceAndRunsOneUndoableTidy() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 600);
        JMenuBar menuBar = Main.createMenuBar(canvas);
        JMenu circuit = menu(menuBar, "Circuit");
        JMenuItem tidy = item(circuit, "Tidy circuit");

        assertNotNull(tidy);
        assertFalse(tidy.isEnabled(), "tidy is disabled on an empty document");

        addCrossingCircuit(canvas.schematic());
        assertTrue(tidy.isEnabled(), "adding a gate enables tidy");

        SchematicSnapshot before = SchematicSnapshot.capture(canvas.schematic());
        tidy.doClick();

        SchematicSnapshot after = SchematicSnapshot.capture(canvas.schematic());
        assertNotEquals(before, after, "the menu action must replace the messy topology");
        assertTrue(canvas.canUndo(), "tidy must create one undo step");
        assertTrue(canvas.undo());
        assertFalse(canvas.canUndo(), "there must not be a second undo step");
        assertEquals(before, SchematicSnapshot.capture(canvas.schematic()));
    }

    private static void addCrossingCircuit(Schematic schematic) {
        PlacedComponent inputA = new PlacedComponent(
                "input-a", "input", 0, 0, Facing.EAST, Map.of(), "A");
        PlacedComponent inputB = new PlacedComponent(
                "input-b", "input", 0, 200, Facing.EAST, Map.of(), "B");
        PlacedComponent gate = new PlacedComponent(
                "gate-and", "and", 300, 100, Facing.NORTH);
        PlacedComponent output = new PlacedComponent(
                "output-f", "output", 500, 100, Facing.EAST, Map.of(), "F");
        schematic.add(inputA);
        schematic.add(inputB);
        schematic.add(gate);
        schematic.add(output);

        Connectivity.PinPosition a = pin(schematic, inputA.id(), "out");
        Connectivity.PinPosition b = pin(schematic, inputB.id(), "out");
        Connectivity.PinPosition firstInput = pin(schematic, gate.id(), "in0");
        Connectivity.PinPosition secondInput = pin(schematic, gate.id(), "in1");
        Connectivity.PinPosition gateOutput = pin(schematic, gate.id(), "out");
        Connectivity.PinPosition outputInput = pin(schematic, output.id(), "in0");

        connect(schematic, a, firstInput);
        connect(schematic, b, secondInput);
        connect(schematic, gateOutput, outputInput);
        schematic.setInputValue(inputB.id(), true);
    }

    private static void connect(
            Schematic schematic,
            Connectivity.PinPosition source,
            Connectivity.PinPosition target) {
        schematic.addWire(source.x(), source.y(), target.x(), source.y());
        schematic.addWire(target.x(), source.y(), target.x(), target.y());
    }

    private static Connectivity.PinPosition pin(
            Schematic schematic, String componentId, String pinId) {
        return schematic.pinPositions().stream()
                .filter(value -> value.componentId().equals(componentId)
                        && value.pinId().equals(pinId))
                .findFirst().orElseThrow();
    }

    private static JMenu menu(JMenuBar bar, String name) {
        for (int index = 0; index < bar.getMenuCount(); index++) {
            JMenu menu = bar.getMenu(index);
            if (menu != null && menu.getText().equals(name)) {
                return menu;
            }
        }
        fail("Missing menu: " + name);
        return null;
    }

    private static JMenuItem item(JMenu menu, String name) {
        for (Component component : menu.getMenuComponents()) {
            if (component instanceof JMenuItem item && item.getText().equals(name)) {
                return item;
            }
        }
        return null;
    }
}
