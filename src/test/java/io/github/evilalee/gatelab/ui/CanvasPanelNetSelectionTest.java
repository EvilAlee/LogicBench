package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.WireSegment;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanvasPanelNetSelectionTest {
    @Test
    void collectorIncludesBothBranchesAttachedAtJunctions() {
        Schematic schematic = new Schematic(GateLibrary.load().all());
        WireSegment trunk = new WireSegment(0, 20, 40, 20);
        WireSegment firstBranch = new WireSegment(10, 0, 10, 20);
        WireSegment secondBranch = new WireSegment(30, 20, 30, 40);
        schematic.addWire(trunk);
        schematic.addWire(firstBranch);
        schematic.addWire(secondBranch);

        assertEquals(Set.of(trunk, firstBranch, secondBranch),
                CanvasPanel.collectNetSegments(schematic, trunk));
    }

    @Test
    void collectorKeepsSeparateNetsSeparate() {
        Schematic schematic = new Schematic(GateLibrary.load().all());
        WireSegment firstNet = new WireSegment(0, 0, 10, 0);
        WireSegment secondNet = new WireSegment(30, 0, 40, 0);
        schematic.addWire(firstNet);
        schematic.addWire(secondNet);

        assertEquals(Set.of(firstNet), CanvasPanel.collectNetSegments(schematic, firstNet));
        assertEquals(Set.of(secondNet), CanvasPanel.collectNetSegments(schematic, secondNet));
    }
}
