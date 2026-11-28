package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.DisplayComponent;
import io.github.evilalee.gatelab.model.DisplayLogic;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.util.List;
import java.util.Objects;

/** Java2D painting for the two built-in seven-segment display components. */
public final class DisplayRenderer {
    private record Segment(double x1, double y1, double x2, double y2) { }

    private static final Segment A = new Segment(43, 19, 71, 19);
    private static final Segment B = new Segment(77, 24, 77, 49);
    private static final Segment C = new Segment(77, 57, 77, 82);
    private static final Segment D = new Segment(43, 88, 71, 88);
    private static final Segment E = new Segment(37, 57, 37, 82);
    private static final Segment F = new Segment(37, 24, 37, 49);
    private static final Segment G = new Segment(43, 53, 71, 53);
    private static final List<Segment> GLYPH = List.of(A, B, C, D, E, F, G);
    private static final Color DIM = new Color(205, 205, 205);

    public void render(
            Graphics2D graphics,
            GateDef definition,
            Facing facing,
            double worldX,
            double worldY,
            String typeId,
            List<String> inputNames,
            DisplayLogic.State state,
            String onColour) {
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(facing, "facing");
        Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(inputNames, "inputNames");
        Objects.requireNonNull(state, "state");

        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(worldX, worldY);
            g.rotate(facing.rotationRadians());
            g.translate(-definition.origin().x(), -definition.origin().y());

            g.setColor(Color.WHITE);
            g.fillRect(0, 0, definition.width(), definition.height());
            g.setColor(new Color(65, 70, 76));
            g.setStroke(new BasicStroke(1.5f));
            g.drawRect(1, 1, definition.width() - 2, definition.height() - 2);

            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 8));
            for (int index = 0; index < inputNames.size(); index++) {
                int pinY = definition.inputs().get(index).y();
                g.setColor(new Color(75, 80, 86));
                g.drawString(inputNames.get(index), 5, pinY + 3);
            }

            Color lit = onColor(onColour);
            for (int index = 0; index < GLYPH.size(); index++) {
                String name = List.of("a", "b", "c", "d", "e", "f", "g").get(index);
                paintSegment(g, GLYPH.get(index), state.segments().get(name), lit);
            }
            paintDot(g, 88, 92, state.segments().get("dp"), lit);

            if (state.unknownHex()) {
                // A dim centre dash plus an X communicates an unknown nibble without implying a value.
                g.setColor(DIM);
                g.setStroke(new BasicStroke(7.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                g.draw(new Line2D.Double(48, 53, 66, 53));
                g.setColor(new Color(lit.getRed(), lit.getGreen(), lit.getBlue(), 190));
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
                g.drawString("X", 50, 68);
            }
        } finally {
            g.dispose();
        }
    }

    public static Color onColor(String name) {
        return switch (name == null ? "red" : name.toLowerCase(java.util.Locale.ROOT)) {
            case "green" -> new Color(0, 155, 55);
            case "yellow" -> new Color(220, 175, 0);
            case "blue" -> new Color(35, 105, 215);
            default -> new Color(205, 35, 35);
        };
    }

    private static void paintSegment(
            Graphics2D graphics, Segment segment,
            DisplayLogic.SegmentState state, Color lit) {
        if (state == null || state == DisplayLogic.SegmentState.DIM) {
            graphics.setColor(DIM);
            graphics.setStroke(new BasicStroke(7.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        } else if (state == DisplayLogic.SegmentState.UNKNOWN) {
            graphics.setColor(lit.darker());
            graphics.setStroke(new BasicStroke(7.0f, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER, 10.0f, new float[]{2.0f, 3.0f}, 0.0f));
        } else {
            graphics.setColor(lit);
            graphics.setStroke(new BasicStroke(7.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        }
        graphics.draw(new Line2D.Double(segment.x1(), segment.y1(), segment.x2(), segment.y2()));
    }

    private static void paintDot(
            Graphics2D graphics, int x, int y,
            DisplayLogic.SegmentState state, Color lit) {
        int diameter = 8;
        if (state == DisplayLogic.SegmentState.LIT) {
            graphics.setColor(lit);
            graphics.fillOval(x - diameter / 2, y - diameter / 2, diameter, diameter);
        } else if (state == DisplayLogic.SegmentState.UNKNOWN) {
            graphics.setColor(lit.darker());
            graphics.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER, 10.0f, new float[]{2.0f, 2.0f}, 0.0f));
            graphics.drawOval(x - diameter / 2, y - diameter / 2, diameter, diameter);
        } else {
            graphics.setColor(DIM);
            graphics.fillOval(x - diameter / 2, y - diameter / 2, diameter, diameter);
        }
    }
}
