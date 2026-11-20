package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FeedbackCircuitExtractorTest {
    private static final List<GateDef> DEFINITIONS = GateLibrary.load().all();

    @Test
    void findsStableBackEdgeForAnInputDrivenNorLatch() {
        Schematic schematic = norLatch();
        FeedbackCircuitExtractor.Extraction first =
                FeedbackCircuitExtractor.extract(schematic).orElseThrow();
        FeedbackCircuitExtractor.Extraction second =
                FeedbackCircuitExtractor.extract(schematic).orElseThrow();

        assertEquals(first.feedbackEdges(), second.feedbackEdges());
        assertEquals(1, first.feedbackEdges().size());
        FeedbackCircuitExtractor.FeedbackEdge edge = first.feedbackEdges().getFirst();
        assertEquals("g2", edge.sourceGate());
        assertEquals("g1", edge.targetGate());
        assertEquals(1, edge.targetInputIndex());
        assertTrue(first.dagNetlist().inputs().contains(edge.placeholderSignal()));
        assertDoesNotThrow(first.dagNetlist()::topologicalGates);
        assertTrue(FeedbackCircuitExtractor.hasFeedback(schematic));
    }

    @Test
    void clockIsATraversalRootOnlyWhenItReachesFeedback() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent clock = add(schematic, "clock", 0, 0, "CLK");
        PlacedComponent latch = add(schematic, "nand", 120, 0, "");
        PlacedComponent probe = add(schematic, "output", 260, 0, "Q");
        connect(schematic, clock.id(), "out", latch.id(), "in0");
        connect(schematic, latch.id(), "out", latch.id(), "in1");
        connect(schematic, latch.id(), "out", probe.id(), "in0");

        FeedbackCircuitExtractor.Extraction extraction =
                FeedbackCircuitExtractor.extract(schematic).orElseThrow();
        assertEquals(clock.id(), extraction.clockComponentIds().get("CLK"));
        assertEquals(List.of("CLK", extraction.feedbackEdges().getFirst().placeholderSignal()),
                extraction.dagNetlist().inputs());
        assertEquals(1, extraction.feedbackEdges().size());
    }

    @Test
    void ordinaryDagDoesNotSelectTheFeedbackPath() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent input = add(schematic, "input", 0, 0, "A");
        PlacedComponent gate = add(schematic, "and", 120, 0, "");
        PlacedComponent output = add(schematic, "output", 260, 0, "F");
        connect(schematic, input.id(), "out", gate.id(), "in0");
        connect(schematic, input.id(), "out", gate.id(), "in1");
        connect(schematic, gate.id(), "out", output.id(), "in0");

        assertTrue(FeedbackCircuitExtractor.extract(schematic).isEmpty());
        assertFalse(FeedbackCircuitExtractor.hasFeedback(schematic));
    }

    private static Schematic norLatch() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent reset = add(schematic, "input", 50, -10, "R");
        PlacedComponent set = add(schematic, "input", 150, -10, "S");
        PlacedComponent qGate = add(schematic, "nor", 100, 0, "");
        PlacedComponent qBarGate = add(schematic, "nor", 200, 0, "");
        PlacedComponent q = add(schematic, "output", 120, 0, "Q");
        PlacedComponent qBar = add(schematic, "output", 220, 0, "Qbar");
        connect(schematic, reset.id(), "out", qGate.id(), "in0");
        connect(schematic, set.id(), "out", qBarGate.id(), "in0");
        path(schematic, 200, 0, 200, -40, 30, -40, 30, 10, 60, 10);
        path(schematic, 100, 0, 100, 40, 180, 40, 180, 10, 160, 10);
        connect(schematic, qGate.id(), "out", q.id(), "in0");
        connect(schematic, qBarGate.id(), "out", qBar.id(), "in0");
        return schematic;
    }

    private static PlacedComponent add(
            Schematic schematic, String type, int x, int y, String label) {
        String id = "component-" + schematic.components().size();
        PlacedComponent component = new PlacedComponent(
                id, type, x, y, Facing.EAST, Map.of(), label);
        schematic.add(component);
        return component;
    }

    private static void connect(
            Schematic schematic, String sourceId, String sourcePin,
            String targetId, String targetPin) {
        Connectivity.PinPosition source = pin(schematic, sourceId, sourcePin);
        Connectivity.PinPosition target = pin(schematic, targetId, targetPin);
        schematic.addWire(source.x(), source.y(), target.x(), source.y());
        schematic.addWire(target.x(), source.y(), target.x(), target.y());
    }

    private static void path(Schematic schematic, int... points) {
        for (int index = 0; index + 3 < points.length; index += 2) {
            schematic.addWire(points[index], points[index + 1],
                    points[index + 2], points[index + 3]);
        }
    }

    private static Connectivity.PinPosition pin(
            Schematic schematic, String componentId, String pinId) {
        return schematic.pinPositions().stream()
                .filter(value -> value.componentId().equals(componentId)
                        && value.pinId().equals(pinId))
                .findFirst().orElseThrow();
    }
}
