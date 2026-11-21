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
import java.util.Objects;

/** Paints reusable black-box symbols while leaving terminal coordinates to the model. */
public final class ComponentInstanceRenderer {
    private static final Color FALLBACK_BACKGROUND = Color.WHITE;
    private static final Color FALLBACK_FOREGROUND = Color.DARK_GRAY;

    public void render(
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
            Color foreground = uiColor("Label.foreground", FALLBACK_FOREGROUND);

            g2.setColor(background);
            g2.fillRect(x, y, geometry.width(), geometry.height());
            g2.setColor(border);
            g2.setStroke(new BasicStroke((float) (1.4 / scale)));
            g2.drawRect(x, y, geometry.width(), geometry.height());

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
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
            FontMetrics pinMetrics = g2.getFontMetrics();
            for (ComponentInstance.PinGeometry pin : geometry.pins()) {
                int outward = pin.direction() == ComponentDefinition.Direction.IN ? -9 : 9;
                GateGeometry.Offset direction = GateGeometry.rotate(
                        new GateGeometry.Offset(outward, 0), instance.rotation());
                int endX = pin.x() + direction.x();
                int endY = pin.y() + direction.y();

                // The line ends at the model-owned pin; rotating only changes its visual side.
                g2.setStroke(new BasicStroke((float) (1.0 / scale)));
                g2.drawLine(pin.x(), pin.y(), endX, endY);
                g2.fillOval(pin.x() - 2, pin.y() - 2, 4, 4);
                if (pin.isClock()) {
                    g2.drawRect(pin.x() - 4, pin.y() - 4, 8, 8);
                }

                int textX = endX + (direction.x() < 0
                        ? -pinMetrics.stringWidth(pin.name()) - 3
                        : direction.x() > 0 ? 3 : -pinMetrics.stringWidth(pin.name()) / 2);
                int textY = endY + (direction.y() < 0
                        ? -2 : direction.y() > 0 ? pinMetrics.getAscent() + 2
                        : pinMetrics.getAscent() / 2);
                g2.drawString(pin.name(), textX, textY);
            }
        } finally {
            g2.dispose();
        }
    }

    private static Color uiColor(String key, Color fallback) {
        Color value = UIManager.getColor(key);
        return value == null ? fallback : value;
    }
}
