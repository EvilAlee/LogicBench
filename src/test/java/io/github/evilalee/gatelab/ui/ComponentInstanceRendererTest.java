package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import org.junit.jupiter.api.Test;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComponentInstanceRendererTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();

    private static ComponentDefinition sampleDefinition() {
        Schematic internal = new Schematic(GATES);
        internal.add(new PlacedComponent("data", "input", 0, 0,
                Facing.EAST, Map.of(), "D"));
        internal.add(new PlacedComponent("clock", "clock", 0, 40,
                Facing.EAST, Map.of(), "CLK"));
        internal.add(new PlacedComponent("result", "output", 200, 0,
                Facing.EAST, Map.of(), "Q"));
        return ComponentDefinition.fromCircuit(
                "Sample", "Renderer test", "Test", "Q", internal);
    }

    @Test
    void drawsFlatBoxWithFlatLafColorsAndKeepsExistingPinCoordinates() {
        ComponentDefinition definition = sampleDefinition();
        ComponentInstance instance = new ComponentInstance(
                "Sample", definition.hash(), "U1", "storage", 100, 100, Facing.EAST);
        ComponentInstance.Geometry geometry = instance.geometry(definition);
        Color background = new Color(246, 247, 248);
        Color border = new Color(42, 52, 62);
        Color foreground = new Color(30, 40, 50);
        Object oldBackground = UIManager.get("Panel.background");
        Object oldBorder = UIManager.get("Component.borderColor");
        Object oldForeground = UIManager.get("Label.foreground");
        UIManager.put("Panel.background", background);
        UIManager.put("Component.borderColor", border);
        UIManager.put("Label.foreground", foreground);

        BufferedImage image = new BufferedImage(260, 220, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            new ComponentInstanceRenderer().render(
                    graphics, instance, definition, geometry, 1.0);
        } finally {
            graphics.dispose();
            restore("Panel.background", oldBackground);
            restore("Component.borderColor", oldBorder);
            restore("Label.foreground", oldForeground);
        }

        assertEquals(background.getRGB(), image.getRGB(150, 110));
        assertEquals(border.getRGB(), image.getRGB(100, 100));
        assertEquals(List.of(100, 100, 200),
                geometry.pins().stream().map(ComponentInstance.PinGeometry::x).toList());
        assertEquals(List.of(120, 140, 130),
                geometry.pins().stream().map(ComponentInstance.PinGeometry::y).toList());
    }

    @Test
    void rotatedBoxRendersWithoutChangingPinGeometryContract() {
        ComponentDefinition definition = sampleDefinition();
        ComponentInstance instance = new ComponentInstance(
                "Sample", definition.hash(), "U1", "", 100, 100, Facing.SOUTH);
        ComponentInstance.Geometry geometry = instance.geometry(definition);
        BufferedImage image = new BufferedImage(240, 240, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            assertDoesNotThrow(() -> new ComponentInstanceRenderer().render(
                    graphics, instance, definition, geometry, 1.0));
        } finally {
            graphics.dispose();
        }

        assertEquals(60, geometry.width());
        assertEquals(100, geometry.height());
        assertEquals(List.of(120, 140, 130),
                geometry.pins().stream().map(ComponentInstance.PinGeometry::x).toList());
    }

    private static void restore(String key, Object value) {
        if (value == null) {
            UIManager.getDefaults().remove(key);
        } else {
            UIManager.put(key, value);
        }
    }
}
