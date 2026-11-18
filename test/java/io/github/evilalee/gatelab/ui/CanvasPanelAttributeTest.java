package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.PlacedComponent;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CanvasPanelAttributeTest {
    @Test
    void addModeToolAttributesUpdatePreviewPlacementAndAreRememberedPerFamily() {
        CanvasPanel canvas = new CanvasPanel();
        List<String> statuses = new ArrayList<>();
        canvas.setStatusMessageListener(statuses::add);

        canvas.enterAddMode("and");
        assertEquals("AND", canvas.attributePanelState().title());
        assertEquals(List.of("Facing", "Size", "Number of Inputs"),
                canvas.attributePanelState().rows().stream()
                        .map(CanvasPanel.AttributeRow::label).toList());

        assertTrue(canvas.setSelectedAttribute("facing", "WEST"));
        assertEquals("West", canvas.attributePanelState().rows().getFirst().selected().label());
        assertFalse(canvas.setSelectedAttribute("size", "wide"));
        assertEquals("Not available", statuses.getLast());

        canvas.placeFromDrop("and", new Point(100, 100));
        PlacedComponent first = canvas.schematic().components().getFirst();
        assertEquals(Facing.WEST, first.facing());
        assertTrue(first.attributes().isEmpty());

        canvas.enterAddMode("and");
        assertEquals("West", canvas.attributePanelState().rows().getFirst().selected().label());
        assertTrue(canvas.setSelectedAttribute("facing", "SOUTH"));
        canvas.placeFromDrop("and", new Point(250, 100));

        PlacedComponent second = canvas.schematic().components().getLast();
        assertEquals(Facing.SOUTH, second.facing());
        assertEquals(Facing.WEST, first.facing(),
                "tool settings for a new placement must not mutate an existing component");
    }

    @Test
    void selectedComponentAttributeEditCreatesAnUndoableMutation() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.placeFromDrop("and", new Point(100, 100));
        PlacedComponent placed = canvas.schematic().components().getFirst();

        CanvasPanel.AttributePanelState selected = canvas.attributePanelState();
        assertEquals("AND", selected.title());
        assertEquals(3, selected.rows().size());
        assertTrue(canvas.setSelectedAttribute("facing", "NORTH"));
        assertEquals(Facing.NORTH, canvas.schematic().components().getFirst().facing());
        assertTrue(canvas.canUndo());

        assertTrue(canvas.undo());
        assertEquals(Facing.EAST, canvas.schematic().components().getFirst().facing());
        assertEquals(placed.id(), canvas.schematic().components().getFirst().id());
    }
}
