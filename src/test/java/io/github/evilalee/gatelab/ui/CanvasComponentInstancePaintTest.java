package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CanvasComponentInstancePaintTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();

    @Test
    void canvasPaintsPlaceholderForAnInstanceWithoutOpeningDialogs() {
        Schematic internal = new Schematic(GATES);
        internal.add(new PlacedComponent("input", "input", 0, 0,
                Facing.EAST, Map.of(), "D"));
        internal.add(new PlacedComponent("clock", "clock", 0, 40,
                Facing.EAST, Map.of(), "CLK"));
        internal.add(new PlacedComponent("output", "output", 200, 0,
                Facing.EAST, Map.of(), "Q"));
        ComponentDefinition definition = ComponentDefinition.fromCircuit(
                "DFF", "D flip-flop", "Sequential", "Q", internal);

        CanvasPanel panel = new CanvasPanel();
        panel.setSize(420, 320);
        panel.schematic().addInstance(definition, "U1", 100, 100, Facing.EAST);
        BufferedImage image = new BufferedImage(420, 320, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            assertDoesNotThrow(() -> panel.paint(graphics));
        } finally {
            graphics.dispose();
        }

        assertEquals(1, panel.schematic().componentInstances().size());
        assertEquals("DFF", panel.schematic().componentInstances().getFirst().definitionName());
    }
}
