package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.sim.PinStateHelper.State;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.*;

class PinDotStyleTest {
    @Test
    void connectedStatesUseTheExistingWirePaletteAndAreFilled() {
        assertStyle(State.CONNECTED_ONE, new Color(0, 210, 0));
        assertStyle(State.CONNECTED_ZERO, new Color(0, 100, 0));
        assertStyle(State.CONNECTED_X, new Color(0, 0, 255));
    }

    @Test
    void unconnectedPinsAreHollowAndGateInputsUseWarningColour() {
        PinDotStyle.Style ordinary = PinDotStyle.forPin(State.UNCONNECTED, false, 1.0);
        PinDotStyle.Style gateInput = PinDotStyle.forPin(State.UNCONNECTED, true, 1.0);

        assertFalse(ordinary.filled());
        assertFalse(gateInput.filled());
        assertNotNull(ordinary.color());
        assertNotNull(gateInput.color());
        assertEquals(ordinary.radius(), gateInput.radius());
    }

    @Test
    void radiusTracksZoomAndDotsDisappearWhenZoomedFarOut() {
        assertEquals(2, PinDotStyle.radiusForZoom(0.25));
        assertEquals(3, PinDotStyle.radiusForZoom(1.0));
        assertEquals(6, PinDotStyle.radiusForZoom(2.0));

        assertFalse(PinDotStyle.visibleAtZoom(0.25));
        assertTrue(PinDotStyle.visibleAtZoom(0.5));
    }

    @Test
    void danglingEndsHaveAnErrorColour() {
        assertNotNull(PinDotStyle.danglingEndColor());
    }

    private static void assertStyle(State state, Color expected) {
        PinDotStyle.Style style = PinDotStyle.forPin(state, false, 1.0);
        assertEquals(expected, style.color());
        assertTrue(style.filled());
        assertEquals(3, style.radius());
    }
}
