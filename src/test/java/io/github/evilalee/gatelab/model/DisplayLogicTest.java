package io.github.evilalee.gatelab.model;

import io.github.evilalee.gatelab.ui.DisplayRenderer;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DisplayLogicTest {
    @Test
    void seg7UsesActiveHighAndShowsDigitTooltip() {
        List<DisplayLogic.Input> inputs = List.of(
                input(Value.ZERO), input(Value.ONE), input(Value.ONE), input(Value.ZERO),
                input(Value.ZERO), input(Value.ZERO), input(Value.ZERO), input(Value.ZERO));

        DisplayLogic.State state = DisplayLogic.seg7(inputs, DisplayLogic.ActiveLevel.HIGH);
        assertEquals(DisplayLogic.SegmentState.LIT, state.segments().get("b"));
        assertEquals(DisplayLogic.SegmentState.LIT, state.segments().get("c"));
        assertEquals(DisplayLogic.SegmentState.DIM, state.segments().get("a"));
        assertEquals(DisplayLogic.SegmentState.DIM, state.segments().get("dp"));
        assertEquals("Digit 1", state.tooltip(false));
    }

    @Test
    void seg7ActiveLowReversesLitAndDimPolarity() {
        List<DisplayLogic.Input> inputs = List.of(
                input(Value.ONE), input(Value.ZERO), input(Value.ZERO), input(Value.ONE),
                input(Value.ONE), input(Value.ONE), input(Value.ONE), input(Value.ZERO));

        DisplayLogic.State state = DisplayLogic.seg7(inputs, DisplayLogic.ActiveLevel.LOW);
        assertEquals(DisplayLogic.SegmentState.LIT, state.segments().get("b"));
        assertEquals(DisplayLogic.SegmentState.LIT, state.segments().get("c"));
        assertEquals(DisplayLogic.SegmentState.DIM, state.segments().get("a"));
        assertEquals(DisplayLogic.SegmentState.LIT, state.segments().get("dp"));
        assertEquals("Digit 1", state.tooltip(false));
    }

    @Test
    void seg7DistinguishesConnectedUnknownFromAnUnconnectedDimInput() {
        List<DisplayLogic.Input> inputs = new ArrayList<>();
        inputs.add(input(Value.X));
        for (int index = 1; index < 7; index++) inputs.add(input(Value.ZERO));
        inputs.add(unconnected());
        DisplayLogic.State state = DisplayLogic.seg7(inputs, DisplayLogic.ActiveLevel.HIGH);

        assertEquals(DisplayLogic.SegmentState.UNKNOWN, state.segments().get("a"));
        assertEquals(DisplayLogic.SegmentState.DIM, state.segments().get("b"));
        assertEquals(DisplayLogic.SegmentState.DIM, state.segments().get("dp"));
        assertTrue(state.tooltip(false).contains("unknown: a"));
    }

    @Test
    void hexDigitGlyphTableCoversEveryNibbleFromZeroThroughF() {
        String[] characters = {
                "0", "1", "2", "3", "4", "5", "6", "7",
                "8", "9", "A", "b", "C", "d", "E", "F"
        };
        for (int digit = 0; digit < 16; digit++) {
            DisplayLogic.State state = DisplayLogic.hexDigit(
                    nibble(digit, DisplayLogic.ActiveLevel.HIGH),
                    DisplayLogic.ActiveLevel.HIGH);
            Set<String> lit = state.segments().entrySet().stream()
                    .filter(entry -> entry.getValue() == DisplayLogic.SegmentState.LIT)
                    .map(java.util.Map.Entry::getKey)
                    .filter(name -> !name.equals("dp"))
                    .collect(Collectors.toSet());
            assertEquals(DisplayLogic.glyphSegments(digit), lit, "nibble " + digit);
            assertEquals(characters[digit], DisplayLogic.hexCharacter(digit));
            assertEquals(digit, state.hexDigit());
            assertFalse(state.unknownHex());
            assertEquals("Hex digit: " + characters[digit], state.tooltip(true));
        }
    }

    @Test
    void hexDigitUsesD0AsLeastSignificantBitIndependentOfSegmentActiveLevel() {
        List<DisplayLogic.Input> activeLowData = List.of(
                input(Value.ZERO), input(Value.ONE), input(Value.ZERO), input(Value.ONE),
                input(Value.ZERO));
        DisplayLogic.State state = DisplayLogic.hexDigit(
                activeLowData, DisplayLogic.ActiveLevel.LOW);
        assertEquals(0xA, state.hexDigit());
        assertEquals("A", DisplayLogic.hexCharacter(state.hexDigit()));
        assertEquals(DisplayLogic.SegmentState.LIT, state.segments().get("dp"),
                "active-low dp is lit by ZERO");
    }

    @Test
    void anyUnknownOrUnconnectedHexDataBitShowsUnknownGlyphButDpIsIndependent() {
        List<DisplayLogic.Input> inputs = new ArrayList<>(nibble(5, DisplayLogic.ActiveLevel.HIGH));
        inputs.set(2, input(Value.X));
        inputs.set(4, input(Value.ONE));
        DisplayLogic.State unknown = DisplayLogic.hexDigit(inputs, DisplayLogic.ActiveLevel.HIGH);
        assertTrue(unknown.unknownHex());
        assertNull(unknown.hexDigit());
        assertTrue(unknown.segments().entrySet().stream()
                .filter(entry -> !entry.getKey().equals("dp"))
                .allMatch(entry -> entry.getValue() == DisplayLogic.SegmentState.DIM));
        assertEquals(DisplayLogic.SegmentState.LIT, unknown.segments().get("dp"));
        assertEquals("Hex digit: ?", unknown.tooltip(true));

        inputs.set(2, unconnected());
        assertTrue(DisplayLogic.hexDigit(inputs, DisplayLogic.ActiveLevel.HIGH).unknownHex());
    }

    @Test
    void onColourHelperProvidesAllFourPaletteChoices() {
        assertEquals(new Color(205, 35, 35), DisplayRenderer.onColor("red"));
        assertEquals(new Color(0, 155, 55), DisplayRenderer.onColor("green"));
        assertEquals(new Color(220, 175, 0), DisplayRenderer.onColor("yellow"));
        assertEquals(new Color(35, 105, 215), DisplayRenderer.onColor("blue"));
    }

    private static List<DisplayLogic.Input> nibble(
            int value, DisplayLogic.ActiveLevel activeLevel) {
        List<DisplayLogic.Input> inputs = new ArrayList<>();
        for (int bit = 0; bit < 4; bit++) {
            boolean on = (value & (1 << bit)) != 0;
            Value pinValue = activeLevel == DisplayLogic.ActiveLevel.HIGH
                    ? (on ? Value.ONE : Value.ZERO)
                    : (on ? Value.ZERO : Value.ONE);
            inputs.add(input(pinValue));
        }
        inputs.add(input(activeLevel == DisplayLogic.ActiveLevel.HIGH
                ? Value.ZERO : Value.ONE));
        return inputs;
    }

    private static DisplayLogic.Input input(Value value) {
        return new DisplayLogic.Input(value, true);
    }

    private static DisplayLogic.Input unconnected() {
        return new DisplayLogic.Input(Value.X, false);
    }
}
