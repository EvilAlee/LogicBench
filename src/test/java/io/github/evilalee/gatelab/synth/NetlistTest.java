package io.github.evilalee.gatelab.synth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NetlistTest {
    @Test
    void jsonRoundTripPreservesTheDocumentedSignalGraph() {
        Netlist original = new Netlist(
                List.of("A", "B"),
                List.of(new Netlist.Output("F", "g2")),
                List.of(
                        new Netlist.Gate("g1", GateOperator.AND, List.of("A", "B")),
                        new Netlist.Gate("g2", GateOperator.NOT, List.of("g1"))));

        String json = NetlistJson.toJson(original);
        assertTrue(json.contains("\"inputs\""));
        assertTrue(json.contains("\"outputs\""));
        assertTrue(json.contains("\"gates\""));
        assertEquals(original, NetlistJson.fromJson(json));
    }

    @Test
    void topologicalOrderAllowsForwardReferencesInJsonAndGateLists() {
        Netlist netlist = new Netlist(
                List.of("A", "B"),
                List.of(new Netlist.Output("F", "g2")),
                List.of(
                        new Netlist.Gate("g2", GateOperator.NOT, List.of("g1")),
                        new Netlist.Gate("g1", GateOperator.AND, List.of("A", "B"))));
        assertEquals(List.of("g1", "g2"),
                netlist.topologicalGates().stream().map(Netlist.Gate::id).toList());
    }

    @Test
    void rejectsDuplicateInputNames() {
        assertInvalid(
                () -> new Netlist(List.of("A", "A"),
                        List.of(new Netlist.Output("F", "A")), List.of()),
                "duplicate input name");
    }

    @Test
    void rejectsDuplicateSignalIdsAndOutputNames() {
        assertInvalid(
                () -> new Netlist(List.of("A"),
                        List.of(new Netlist.Output("F", "A")),
                        List.of(new Netlist.Gate("A", GateOperator.NOT, List.of("A")))),
                "duplicate signal name or gate id");
        assertInvalid(
                () -> new Netlist(List.of("A"),
                        List.of(new Netlist.Output("F", "A"), new Netlist.Output("F", "A")),
                        List.of()),
                "duplicate output name");
        assertInvalid(
                () -> new Netlist(List.of("A"),
                        List.of(new Netlist.Output("F", "A")),
                        List.of(new Netlist.Gate("g1", GateOperator.NOT, List.of("A")),
                                new Netlist.Gate("g1", GateOperator.NOT, List.of("A")))),
                "duplicate signal name or gate id");
    }

    @Test
    void rejectsUnknownGateReferences() {
        assertInvalid(
                () -> new Netlist(List.of("A"),
                        List.of(new Netlist.Output("F", "g1")),
                        List.of(new Netlist.Gate("g1", GateOperator.AND, List.of("A", "B")))),
                "unknown signal: B");
    }

    @Test
    void rejectsWrongGateInputCounts() {
        assertInvalid(
                () -> new Netlist(List.of("A"),
                        List.of(new Netlist.Output("F", "g1")),
                        List.of(new Netlist.Gate("g1", GateOperator.NOT, List.of("A", "A")))),
                "requires 1 inputs, got 2");
        assertInvalid(
                () -> new Netlist(List.of("A"),
                        List.of(new Netlist.Output("F", "g1")),
                        List.of(new Netlist.Gate("g1", GateOperator.XOR, List.of("A")))),
                "requires 2 inputs, got 1");
    }

    @Test
    void rejectsCombinationalCycles() {
        assertInvalid(
                () -> new Netlist(List.of("A"),
                        List.of(new Netlist.Output("F", "g1")),
                        List.of(
                                new Netlist.Gate("g1", GateOperator.NOT, List.of("g2")),
                                new Netlist.Gate("g2", GateOperator.NOT, List.of("g1")))),
                "combinational cycle detected");
    }

    @Test
    void rejectsMissingOutputSources() {
        assertInvalid(
                () -> new Netlist(List.of("A"),
                        List.of(new Netlist.Output("F", "missing")), List.of()),
                "output F references unknown source: missing");
    }

    @Test
    void rejectsMoreThanTwelveInputs() {
        List<String> inputs = java.util.stream.IntStream.range(0, 13)
                .mapToObj(index -> "A" + index).toList();
        assertInvalid(
                () -> new Netlist(inputs,
                        List.of(new Netlist.Output("F", "A0")), List.of()),
                "maximum is 12");
    }

    @Test
    void constantLiteralIsAllowedAsAnOutputSource() {
        Netlist constant = new Netlist(
                List.of(), List.of(new Netlist.Output("F", "1")), List.of());
        assertEquals("1", NetlistJson.fromJson(NetlistJson.toJson(constant))
                .outputs().getFirst().source());
    }

    @Test
    void jsonErrorsIdentifyUnknownGateTypesAndMalformedFields() {
        String unknownType = """
                {"inputs":["A"],"outputs":[{"name":"F","source":"g1"}],
                 "gates":[{"id":"g1","type":"MAJORITY","inputs":["A"]}]}
                """;
        IllegalArgumentException typeError = assertThrows(
                IllegalArgumentException.class, () -> NetlistJson.fromJson(unknownType));
        assertTrue(typeError.getMessage().contains("unknown gate type"));

        IllegalArgumentException fieldError = assertThrows(
                IllegalArgumentException.class,
                () -> NetlistJson.fromJson("{\"inputs\":[],\"outputs\":[],\"gates\":[]}"));
        assertTrue(fieldError.getMessage().contains("at least one output"));
    }

    private static void assertInvalid(Runnable action, String messagePart) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, action::run);
        assertTrue(exception.getMessage().contains(messagePart), exception.getMessage());
    }
}
