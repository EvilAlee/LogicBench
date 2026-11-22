package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.sim.PinStateHelper.State;

import javax.swing.UIManager;
import java.awt.Color;
import java.util.Objects;

/** Zoom and palette policy for schematic pin markers, kept separate for headless tests. */
public final class PinDotStyle {
    private static final double MIN_VISIBLE_ZOOM = 0.35;
    private static final int BASE_RADIUS = 3;

    public record Style(Color color, boolean filled, int radius) {
        public Style {
            Objects.requireNonNull(color, "color");
            if (radius < 2) {
                throw new IllegalArgumentException("pin-dot radius must be at least 2 pixels");
            }
        }
    }

    private PinDotStyle() {
    }

    public static Style forPin(State state, boolean gateInput, double zoom) {
        Objects.requireNonNull(state, "state");
        int radius = radiusForZoom(zoom);
        return switch (state) {
            case UNCONNECTED -> new Style(
                    gateInput ? warningColor() : unconnectedColor(), false, radius);
            case CONNECTED_ZERO -> new Style(new Color(0, 100, 0), true, radius);
            case CONNECTED_ONE -> new Style(new Color(0, 210, 0), true, radius);
            case CONNECTED_X -> new Style(new Color(0, 0, 255), true, radius);
        };
    }

    public static int radiusForZoom(double zoom) {
        requireZoom(zoom);
        return Math.max(2, (int) Math.round(BASE_RADIUS * zoom));
    }

    public static boolean visibleAtZoom(double zoom) {
        requireZoom(zoom);
        return zoom >= MIN_VISIBLE_ZOOM;
    }

    public static Color danglingEndColor() {
        return firstUiColor(
                "Component.error.focusedBorderColor",
                "Component.error.borderColor",
                "Actions.Red",
                Color.RED);
    }

    private static Color unconnectedColor() {
        return firstUiColor(
                "Component.borderColor",
                "Label.disabledForeground",
                "Separator.foreground",
                Color.GRAY);
    }

    private static Color warningColor() {
        return firstUiColor(
                "Component.warning.focusedBorderColor",
                "Component.warning.borderColor",
                "Actions.Yellow",
                Color.ORANGE);
    }

    private static Color firstUiColor(String first, String second, String third, Color fallback) {
        Color color = UIManager.getColor(first);
        if (color == null) {
            color = UIManager.getColor(second);
        }
        if (color == null) {
            color = UIManager.getColor(third);
        }
        return color == null ? fallback : color;
    }

    private static void requireZoom(double zoom) {
        if (!Double.isFinite(zoom) || zoom <= 0.0) {
            throw new IllegalArgumentException("zoom must be finite and positive");
        }
    }
}
