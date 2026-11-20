package io.github.evilalee.gatelab.synth.layout;

import io.github.evilalee.gatelab.synth.FeedbackCircuitExtractor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicLayoutFeedbackTest {
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
