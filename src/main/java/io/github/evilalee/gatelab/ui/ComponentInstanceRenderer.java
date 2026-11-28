package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.GateGeometry;

import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.util.Objects;

/** Paints reusable black-box symbols while leaving terminal coordinates to the model. */
public final class ComponentInstanceRenderer {
    private static final Color FALLBACK_BACKGROUND = Color.WHITE;
    private static final Color FALLBACK_FOREGROUND = Color.DARK_GRAY;

    /** Keeps the original all-in-one renderer for previews and other simple callers. */
    public void render(
            Graphics2D graphics,
            ComponentInstance instance,
            ComponentDefinition definition,
            ComponentInstance.Geometry geometry,
            double scale) {
        renderSymbol(graphics, instance, definition, geometry, scale, null);
        renderLabels(graphics, instance, definition, geometry, scale);
    }

    /** Draws the box, border, pin stubs and clock markers, leaving text for a later pass. */
    public void renderSymbol(
            Graphics2D graphics,
            ComponentInstance instance,
            ComponentDefinition definition,
            ComponentInstance.Geometry geometry,
            double scale) {
        renderSymbol(graphics, instance, definition, geometry, scale, null);
    }

    /** Draws an optional warning outline without changing the symbol's geometry. */
    public void renderSymbol(
            Graphics2D graphics,
            ComponentInstance instance,
            ComponentDefinition definition,
            ComponentInstance.Geometry geometry,
            double scale,
            Color outlineOverride) {
        requireArguments(graphics, instance, definition, geometry, scale);
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);

            int x = instance.x();
            int y = instance.y();
            Color background = uiColor("Panel.background", FALLBACK_BACKGROUND);
            Color border = uiColor("Component.borderColor",
                    uiColor("Separator.foreground", FALLBACK_FOREGROUND));

            Shape body = definition.isChipStyle()
                    ? chipBodyShape(instance, definition)
                    : new java.awt.Rectangle(x, y, geometry.width(), geometry.height());
            g2.setColor(background);
            g2.fill(body);
            g2.setColor(outlineOverride == null ? border : outlineOverride);
            g2.setStroke(new BasicStroke((float) ((outlineOverride == null ? 1.4 : 2.4) / scale)));
            g2.draw(body);

            for (ComponentInstance.PinGeometry pin : geometry.pins()) {
                GateGeometry.Offset direction = outwardVector(pin.outwardFacing());
                int endX = pin.x() + direction.x() * 9;
                int endY = pin.y() + direction.y() * 9;

                g2.setStroke(new BasicStroke((float) (1.0 / scale)));
                g2.drawLine(pin.x(), pin.y(), endX, endY);
                if (pin.isClock()) {
                    g2.drawRect(pin.x() - 4, pin.y() - 4, 8, 8);
                }
            }
        } finally {
            g2.dispose();
        }
    }

    /** Draws definition, instance and port names after terminal status markers. */
    public void renderLabels(
            Graphics2D graphics,
            ComponentInstance instance,
            ComponentDefinition definition,
            ComponentInstance.Geometry geometry,
            double scale) {
        requireArguments(graphics, instance, definition, geometry, scale);
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int x = instance.x();
            int y = instance.y();
            Color foreground = uiColor("Label.foreground", FALLBACK_FOREGROUND);

            String title = definition.name();
            g2.setColor(foreground);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            FontMetrics titleMetrics = g2.getFontMetrics();
            int titleBaseline = y + (geometry.height()
                    - titleMetrics.getHeight()) / 2 + titleMetrics.getAscent();
            if (!instance.label().isEmpty()) {
                titleBaseline -= 5;
            }
            g2.drawString(title,
                    x + (geometry.width() - titleMetrics.stringWidth(title)) / 2,
                    titleBaseline);

            if (!instance.label().isEmpty()) {
                g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
                FontMetrics labelMetrics = g2.getFontMetrics();
                g2.drawString(instance.label(),
                        x + (geometry.width() - labelMetrics.stringWidth(instance.label())) / 2,
                        y + geometry.height() - 5);
            }

            g2.setColor(foreground);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, definition.isChipStyle() ? 9 : 9));
            FontMetrics pinMetrics = g2.getFontMetrics();
            for (ComponentInstance.PinGeometry pin : geometry.pins()) {
                GateGeometry.Offset direction = outwardVector(pin.outwardFacing());
                int endX = pin.x() + direction.x() * 9;
                int endY = pin.y() + direction.y() * 9;
                if (definition.isChipStyle()) {
                    drawChipPinLabels(g2, pin, direction, endX, endY, pinMetrics);
                } else {
                    int textX = endX + (direction.x() < 0
                            ? -pinMetrics.stringWidth(pin.name()) - 3
                            : direction.x() > 0 ? 3 : -pinMetrics.stringWidth(pin.name()) / 2);
                    int textY = endY + (direction.y() < 0
                            ? -2 : direction.y() > 0 ? pinMetrics.getAscent() + 2
                            : pinMetrics.getAscent() / 2);
                    g2.drawString(pin.name(), textX, textY);
                }
            }
        } finally {
            g2.dispose();
        }
    }

    private static Shape chipBodyShape(
            ComponentInstance instance, ComponentDefinition definition) {
        int packagePins = Integer.parseInt(definition.packageType().substring(3));
        int height = packagePins / 2 * ComponentInstance.PORT_SPACING
                + ComponentInstance.PORT_SPACING;
        int width = ComponentInstance.BOX_WIDTH;
        double radius = 12.0;
        double center = width / 2.0;

        Path2D.Double local = new Path2D.Double();
        local.moveTo(0, 0);
        local.lineTo(center - radius, 0);
        local.curveTo(center - radius, 16, center + radius, 16, center + radius, 0);
        local.lineTo(width, 0);
        local.lineTo(width, height);
        local.lineTo(0, height);
        local.closePath();

        AffineTransform transform = new AffineTransform();
        switch (instance.rotation()) {
            case EAST -> {
                transform.translate(instance.x(), instance.y());
            }
            case SOUTH -> {
                transform.translate(instance.x(), instance.y());
                transform.translate(height, 0);
                transform.rotate(Math.PI / 2.0);
            }
            case WEST -> {
                transform.translate(instance.x(), instance.y());
                transform.translate(width, height);
                transform.rotate(Math.PI);
            }
            case NORTH -> {
                transform.translate(instance.x(), instance.y());
                transform.translate(0, width);
                transform.rotate(-Math.PI / 2.0);
            }
        }
        return transform.createTransformedShape(local);
    }

    private static GateGeometry.Offset outwardVector(io.github.evilalee.gatelab.model.Facing side) {
        return switch (side) {
            case WEST -> new GateGeometry.Offset(-1, 0);
            case EAST -> new GateGeometry.Offset(1, 0);
            case NORTH -> new GateGeometry.Offset(0, -1);
            case SOUTH -> new GateGeometry.Offset(0, 1);
        };
    }

    private static void drawChipPinLabels(
            Graphics2D graphics,
            ComponentInstance.PinGeometry pin,
            GateGeometry.Offset outward,
            int endX,
            int endY,
            FontMetrics metrics) {
        String signal = pin.name();
        int signalWidth = metrics.stringWidth(signal);
        if (outward.x() < 0) {
            graphics.drawString(signal, pin.x() + 3, pin.y() + metrics.getAscent() / 2);
        } else if (outward.x() > 0) {
            graphics.drawString(signal, pin.x() - signalWidth - 3,
                    pin.y() + metrics.getAscent() / 2);
        } else if (outward.y() < 0) {
            graphics.drawString(signal, pin.x() - signalWidth / 2,
                    pin.y() + metrics.getAscent() + 2);
        } else {
            graphics.drawString(signal, pin.x() - signalWidth / 2, pin.y() - 3);
        }

        String number = Integer.toString(pin.pinNumber());
        int numberWidth = metrics.stringWidth(number);
        if (outward.x() < 0) {
            graphics.drawString(number, endX - numberWidth - 2,
                    endY + metrics.getAscent() / 2);
        } else if (outward.x() > 0) {
            graphics.drawString(number, endX + 2, endY + metrics.getAscent() / 2);
        } else if (outward.y() < 0) {
            graphics.drawString(number, endX - numberWidth / 2, endY - 2);
        } else {
            graphics.drawString(number, endX - numberWidth / 2,
                    endY + metrics.getAscent());
        }
    }

    private static void requireArguments(
            Graphics2D graphics,
            ComponentInstance instance,
            ComponentDefinition definition,
            ComponentInstance.Geometry geometry,
            double scale) {
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(geometry, "geometry");
        if (!Double.isFinite(scale) || scale <= 0.0) {
            throw new IllegalArgumentException("scale must be finite and positive");
        }
    }

    private static Color uiColor(String key, Color fallback) {
        Color value = UIManager.getColor(key);
        return value == null ? fallback : value;
    }
}
