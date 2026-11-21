package io.github.evilalee.gatelab.synth.layout;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.WireSegment;
import io.github.evilalee.gatelab.synth.FeedbackCircuitExtractor;
import io.github.evilalee.gatelab.synth.GateOperator;
import io.github.evilalee.gatelab.synth.Netlist;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicLayoutFeedbackTest {
    @Test
    void primaryInputMovesBesideFirstConsumerAndSharesOneTrunkForLaterFanout() {
        Netlist netlist = new Netlist(
                List.of("A", "B", "C", "D"),
                List.of(new Netlist.Output("Q", "g4"), new Netlist.Output("Later", "g6")),
                List.of(
                        new Netlist.Gate("g1", GateOperator.NAND, List.of("B", "C")),
                        new Netlist.Gate("g2", GateOperator.AND, List.of("g1", "D")),
                        new Netlist.Gate("g3", GateOperator.OR, List.of("A", "g2")),
                        new Netlist.Gate("g4", GateOperator.NOR, List.of("g3", "g5")),
                        new Netlist.Gate("g5", GateOperator.NOT, List.of("g4")),
                        new Netlist.Gate("g6", GateOperator.XOR, List.of("A", "g3"))),
                true);
        FeedbackCircuitExtractor.Extraction extraction =
                FeedbackCircuitExtractor.extract(netlist).orElseThrow();
        SchematicLayout.LayoutResult result =
                new SchematicLayout(GateLibrary.load().all()).layoutFeedback(extraction);
        Schematic schematic = result.schematic();

        PlacedComponent inputA = component(schematic, result.inputComponentIds().get("A"));
        PlacedComponent firstConsumerColumn = component(
                schematic, result.gateComponentIds().get("g2"));
        assertEquals(columnOrigin(schematic, inputA), columnOrigin(schematic, firstConsumerColumn),
                "A should be moved to the column immediately left of its first consumer");

        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        Connectivity.Net inputNet = topology.nets().stream()
                .filter(net -> net.pins().stream().anyMatch(pin ->
                        pin.componentId().equals(inputA.id()) && pin.pinId().equals("out")))
                .findFirst().orElseThrow();
        assertTrue(inputNet.pins().stream().anyMatch(pin ->
                pin.componentId().equals(result.gateComponentIds().get("g3"))
                        && pin.pinId().equals("in0")));
        assertTrue(inputNet.pins().stream().anyMatch(pin ->
                pin.componentId().equals(result.gateComponentIds().get("g6"))
                        && pin.pinId().equals("in0")));

        PlacedComponent g3 = component(schematic, result.gateComponentIds().get("g3"));
        int gapLeft = bounds(schematic, firstConsumerColumn).x()
                + bounds(schematic, firstConsumerColumn).width();
        int gapRight = bounds(schematic, g3).x();
        long trunksInFirstConsumerGap = inputNet.segments().stream()
                .filter(wire -> !wire.isHorizontal())
                .filter(wire -> wire.x1() > gapLeft && wire.x1() < gapRight)
                .count();
        assertEquals(1, trunksInFirstConsumerGap,
                "first gap should contain one junction trunk for A's fan-out");
    }

    private static PlacedComponent component(Schematic schematic, String id) {
        return schematic.components().stream()
                .filter(component -> component.id().equals(id))
                .findFirst().orElseThrow();
    }

    private static GateGeometry.Bounds bounds(Schematic schematic, PlacedComponent component) {
        GateGeometry.Bounds local = GateGeometry.bounds(
                schematic.definitionFor(component), Facing.EAST);
        return new GateGeometry.Bounds(
                component.x() + local.x(), component.y() + local.y(),
                local.width(), local.height());
    }

    private static int columnOrigin(Schematic schematic, PlacedComponent component) {
        return component.x() - schematic.definitionFor(component).origin().x();
    }

    @Test
    void disjointFeedbackIntervalsReuseLanesWithoutOverlappingSpans() {
        List<FeedbackCircuitExtractor.FeedbackEdge> edges = List.of(
                new FeedbackCircuitExtractor.FeedbackEdge("G5", "G1", 0, "Feedback1"),
                new FeedbackCircuitExtractor.FeedbackEdge("G4", "G2", 1, "Feedback2"),
                new FeedbackCircuitExtractor.FeedbackEdge("G7", "G6", 0, "Feedback3"));
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int column = 1; column <= 7; column++) {
            columns.put("G" + column, column);
        }

        Map<FeedbackCircuitExtractor.FeedbackEdge, Integer> lanes =
                SchematicLayout.assignFeedbackLanes(edges, columns);

        assertEquals(0, lanes.get(edges.get(0)));
        assertEquals(1, lanes.get(edges.get(1)));
        assertEquals(0, lanes.get(edges.get(2)));
        for (int first = 0; first < edges.size(); first++) {
            for (int second = first + 1; second < edges.size(); second++) {
                if (!lanes.get(edges.get(first)).equals(lanes.get(edges.get(second)))) {
                    continue;
                }
                int firstStart = columns.get(edges.get(first).targetGate());
                int firstEnd = columns.get(edges.get(first).sourceGate());
                int secondStart = columns.get(edges.get(second).targetGate());
                int secondEnd = columns.get(edges.get(second).sourceGate());
                assertTrue(firstEnd < secondStart || secondEnd < firstStart,
                        "feedback intervals assigned to one lane must be disjoint");
            }
        }
    }
}
