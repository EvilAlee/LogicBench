package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.WireSegment;
import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import io.github.evilalee.gatelab.synth.FeedbackCircuitExtractor;
import io.github.evilalee.gatelab.synth.GateOperator;
import io.github.evilalee.gatelab.synth.Netlist;
import io.github.evilalee.gatelab.synth.layout.SchematicLayout;
import io.github.evilalee.gatelab.synth.layout.TidyCircuitService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SchematicCompilerComponentTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();

    private record Fixture(
            Schematic schematic,
            Map<String, String> inputIds,
            Map<String, String> outputIds) {
    }

    @Test
    void halfAdderInstanceMatchesItsInlineGateCircuitForEveryInputRow() {
        ComponentDefinition halfAdder = halfAdderDefinition();
        Fixture parent = topLevelFixture(halfAdder,
                Set.of("A", "B"), Set.of("S", "C"));
        Schematic inline = halfAdder.internalCircuit();
        Map<String, String> inlineInputs = namedComponents(inline, "input");
        Map<String, String> inlineOutputs = namedComponents(inline, "output");

        for (int row = 0; row < 4; row++) {
            boolean a = (row & 2) != 0;
            boolean b = (row & 1) != 0;
            parent.schematic().setInputValue(parent.inputIds().get("A"), a);
            parent.schematic().setInputValue(parent.inputIds().get("B"), b);
            inline.setInputValue(inlineInputs.get("A"), a);
            inline.setInputValue(inlineInputs.get("B"), b);

            SchematicCompiler.SimulationResult flattened =
                    SchematicCompiler.compileAndSettle(parent.schematic());
            SchematicCompiler.SimulationResult expanded =
                    SchematicCompiler.compileAndSettle(inline);

            assertFalse(flattened.oscillating());
            assertFalse(expanded.oscillating());
            assertEquals(expanded.outputValues().get(inlineOutputs.get("S")),
                    flattened.outputValues().get(parent.outputIds().get("S")), "sum row " + row);
            assertEquals(expanded.outputValues().get(inlineOutputs.get("C")),
                    flattened.outputValues().get(parent.outputIds().get("C")), "carry row " + row);
        }
    }

    @Test
    void fullAdderBuiltFromTwoHalfAddersAndOrMatchesArithmeticForEveryInputRow() {
        ComponentDefinition halfAdder = halfAdderDefinition();
        ComponentDefinition fullAdder = fullAdderFromHalfAdders(halfAdder);
        Fixture parent = topLevelFixture(fullAdder,
                Set.of("A", "B", "CIN"), Set.of("S", "COUT"));

        for (int row = 0; row < 8; row++) {
            boolean a = (row & 4) != 0;
            boolean b = (row & 2) != 0;
            boolean carryIn = (row & 1) != 0;
            parent.schematic().setInputValue(parent.inputIds().get("A"), a);
            parent.schematic().setInputValue(parent.inputIds().get("B"), b);
            parent.schematic().setInputValue(parent.inputIds().get("CIN"), carryIn);

            SchematicCompiler.SimulationResult result =
                    SchematicCompiler.compileAndSettle(parent.schematic());
            int total = (a ? 1 : 0) + (b ? 1 : 0) + (carryIn ? 1 : 0);
            assertEquals(total % 2 == 1 ? Value.ONE : Value.ZERO,
                    result.outputValues().get(parent.outputIds().get("S")), "sum row " + row);
            assertEquals(total >= 2 ? Value.ONE : Value.ZERO,
                    result.outputValues().get(parent.outputIds().get("COUT")), "carry row " + row);
            assertFalse(result.oscillating());
        }
    }

    @Test
    void passThroughPortAndTwoLevelsOfNestedDefinitionsPropagateValues() {
        ComponentDefinition pass = passThroughDefinition();
        Fixture direct = topLevelFixture(pass, Set.of("A"), Set.of("Y"));
        direct.schematic().setInputValue(direct.inputIds().get("A"), true);
        assertEquals(Value.ONE, SchematicCompiler.compileAndSettle(direct.schematic())
                .outputValues().get(direct.outputIds().get("Y")));

        ComponentDefinition levelOne = wrapDefinition("LevelOne", pass);
        ComponentDefinition levelTwo = wrapDefinition("LevelTwo", levelOne);
        Fixture nested = topLevelFixture(levelTwo, Set.of("IN"), Set.of("OUT"));
        nested.schematic().setInputValue(nested.inputIds().get("IN"), true);
        SchematicCompiler.SimulationResult result =
                SchematicCompiler.compileAndSettle(nested.schematic());
        assertEquals(Value.ONE, result.outputValues().get(nested.outputIds().get("OUT")));
        assertFalse(result.oscillating());
        assertTrue(result.gateOutputValues().isEmpty(),
                "pure pass-through black boxes add no gates");
    }

    @Test
    void unconnectedInstanceInputRemainsUndrivenAndEvaluatesToX() {
        ComponentDefinition halfAdder = halfAdderDefinition();
        Fixture parent = topLevelFixture(halfAdder, Set.of("A"), Set.of("S", "C"));
        parent.schematic().setInputValue(parent.inputIds().get("A"), true);

        SchematicCompiler.SimulationResult result =
                SchematicCompiler.compileAndSettle(parent.schematic());

        assertEquals(Value.X, result.outputValues().get(parent.outputIds().get("S")));
        assertEquals(Value.X, result.outputValues().get(parent.outputIds().get("C")));
        assertFalse(result.oscillating());
    }

    @Test
    void hierarchicalGateIdsAreStableAndFeedbackStateSurvivesUnrelatedEdit() {
        ComponentDefinition latch = srLatchDefinition();
        Fixture parent = topLevelFixture(latch,
                Set.of("S_N", "R_N"), Set.of("Q", "QN"));
        parent.schematic().setInputValue(parent.inputIds().get("S_N"), false);
        parent.schematic().setInputValue(parent.inputIds().get("R_N"), true);
        SchematicCompiler.SimulationResult set =
                SchematicCompiler.compileAndSettle(parent.schematic());
        assertEquals(Value.ONE, set.outputValues().get(parent.outputIds().get("Q")));
        assertEquals(Value.ZERO, set.outputValues().get(parent.outputIds().get("QN")));
        assertTrue(set.gateOutputValues().keySet().stream()
                .allMatch(id -> id.startsWith("U1/")));

        parent.schematic().setInputValue(parent.inputIds().get("S_N"), true);
        SchematicCompiler.SimulationResult held =
                SchematicCompiler.compileAndSettle(parent.schematic(), set);
        assertEquals(Value.ONE, held.outputValues().get(parent.outputIds().get("Q")));
        assertEquals(Value.ZERO, held.outputValues().get(parent.outputIds().get("QN")));

        parent.schematic().add(new PlacedComponent(
                "unrelated", "not", 1200, 900, Facing.EAST));
        SchematicCompiler.SimulationResult afterUnrelatedEdit =
                SchematicCompiler.compileAndSettle(parent.schematic(), held);
        assertEquals(Value.ONE,
                afterUnrelatedEdit.outputValues().get(parent.outputIds().get("Q")));
        assertEquals(Value.ZERO,
                afterUnrelatedEdit.outputValues().get(parent.outputIds().get("QN")));
    }

    @Test
    void nestingDepthAllowsEightInstancesAndRejectsTheNinthWithClearMessage() {
        ComponentDefinition current = passThroughDefinition();
        for (int level = 1; level <= 7; level++) {
            current = wrapDefinition("Level" + level, current);
        }
        Fixture depthEight = topLevelFixture(current, Set.of("IN"), Set.of("OUT"));
        assertDoesNotThrow(() -> SchematicCompiler.compileCircuit(depthEight.schematic()));

        current = wrapDefinition("Level8", current);
        Fixture depthNine = topLevelFixture(current, Set.of("IN"), Set.of("OUT"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SchematicCompiler.compileCircuit(depthNine.schematic()));
        assertTrue(failure.getMessage().contains("nesting depth exceeds 8"), failure.getMessage());
        assertTrue(failure.getMessage().contains("U1/"), failure.getMessage());
    }

    @Test
    void definitionCycleDiagnosticIncludesTheCompleteCyclePath() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SchematicCompiler.checkDefinitionEntry(
                        List.of("A", "B", "C"), "B", "U4"));
        assertTrue(failure.getMessage().contains("B -> C -> B"), failure.getMessage());
    }

    @Test
    void tidyAndExpressionExtractionRefuseBlackBoxInstances() {
        ComponentDefinition pass = passThroughDefinition();
        Fixture parent = topLevelFixture(pass, Set.of("IN"), Set.of("OUT"));

        IllegalArgumentException tidyFailure = assertThrows(IllegalArgumentException.class,
                () -> TidyCircuitService.tidy(parent.schematic(), GATES));
        assertTrue(tidyFailure.getMessage().contains("component instances"));

        IllegalArgumentException expressionFailure = assertThrows(
                IllegalArgumentException.class,
                () -> io.github.evilalee.gatelab.synth.SchematicNetlistExtractor
                        .extract(parent.schematic()));
        assertTrue(expressionFailure.getMessage().contains("component instances"));
    }

    private static ComponentDefinition halfAdderDefinition() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile(
                "S = A ^ B; C = A & B", false, true);
        SchematicLayout.LayoutResult layout =
                new SchematicLayout(GATES).layout(compilation.netlist());
        return ComponentDefinition.fromCircuit(
                "HA", "Half adder", "Arithmetic", null, layout.schematic());
    }

    private static ComponentDefinition fullAdderFromHalfAdders(
            ComponentDefinition halfAdder) {
        Schematic schematic = new Schematic(GATES);
        ComponentInstance first = schematic.addInstance(
                halfAdder, "first half adder", 200, 200, Facing.EAST);
        ComponentInstance second = schematic.addInstance(
                halfAdder, "second half adder", 500, 200, Facing.EAST);
        PlacedComponent or = new PlacedComponent(
                "OR", "or", 800, 220, Facing.EAST);
        schematic.add(or);

        ComponentInstance.PinGeometry firstA = port(first, halfAdder, "A");
        ComponentInstance.PinGeometry firstB = port(first, halfAdder, "B");
        ComponentInstance.PinGeometry firstSum = port(first, halfAdder, "S");
        ComponentInstance.PinGeometry firstCarry = port(first, halfAdder, "C");
        ComponentInstance.PinGeometry secondA = port(second, halfAdder, "A");
        ComponentInstance.PinGeometry secondB = port(second, halfAdder, "B");
        ComponentInstance.PinGeometry secondSum = port(second, halfAdder, "S");
        ComponentInstance.PinGeometry secondCarry = port(second, halfAdder, "C");

        addInputAt(schematic, "A", "A", firstA.x(), firstA.y());
        addInputAt(schematic, "B", "B", firstB.x(), firstB.y());
        addInputAt(schematic, "CIN", "CIN", secondB.x(), secondB.y());
        addOutputAt(schematic, "S", "S", secondSum.x(), secondSum.y());

        Connectivity.PinPosition orIn0 = pin(schematic, "OR", "in0");
        Connectivity.PinPosition orIn1 = pin(schematic, "OR", "in1");
        Connectivity.PinPosition orOut = pin(schematic, "OR", "out");
        addOutputAt(schematic, "COUT", "COUT", orOut.x(), orOut.y());

        connectStraight(schematic,
                new Connectivity.Point(firstSum.x(), firstSum.y()),
                new Connectivity.Point(secondA.x(), secondA.y()));

        int laneY = Math.min(Math.min(firstCarry.y(), orIn0.y()), orIn1.y()) - 60;
        int farRightX = Math.max(firstCarry.x(), orIn0.x()) + 50;
        addPath(schematic,
                new Connectivity.Point(firstCarry.x(), firstCarry.y()),
                new Connectivity.Point(firstCarry.x() - 20, firstCarry.y()),
                new Connectivity.Point(firstCarry.x() - 20, laneY),
                new Connectivity.Point(farRightX, laneY),
                new Connectivity.Point(farRightX, orIn0.y()),
                new Connectivity.Point(orIn0.x(), orIn0.y()));

        int secondCarryLaneX = Math.min(secondCarry.x(), orIn1.x()) + 80;
        addPath(schematic,
                new Connectivity.Point(secondCarry.x(), secondCarry.y()),
                new Connectivity.Point(secondCarryLaneX, secondCarry.y()),
                new Connectivity.Point(secondCarryLaneX, orIn1.y()),
                new Connectivity.Point(orIn1.x(), orIn1.y()));

        return ComponentDefinition.fromCircuit(
                "FA_Test", "Full adder from two half adders and OR",
                "Arithmetic", null, schematic);
    }

    private static ComponentDefinition passThroughDefinition() {
        Schematic internal = new Schematic(GATES);
        PlacedComponent input = new PlacedComponent(
                "input-A", "input", 100, 100, Facing.EAST,
                Map.of(), "A");
        internal.add(input);
        Connectivity.PinPosition inputPin = pin(internal, "input-A", "out");

        GateDef outputDefinition = gateDefinition("output");
        GateGeometry.Offset outputOffset =
                GateGeometry.inputOffsets(outputDefinition, Facing.EAST).getFirst();
        PlacedComponent output = new PlacedComponent(
                "output-Y", "output",
                300 - outputOffset.x(), inputPin.y() - outputOffset.y(),
                Facing.EAST, Map.of(), "Y");
        internal.add(output);
        Connectivity.PinPosition outputPin = pin(internal, "output-Y", "in0");
        connectStraight(internal,
                new Connectivity.Point(inputPin.x(), inputPin.y()),
                new Connectivity.Point(outputPin.x(), outputPin.y()));

        return ComponentDefinition.fromCircuit(
                "Pass", "A direct input-to-output connection", "Test", null, internal);
    }

    private static ComponentDefinition wrapDefinition(
            String name, ComponentDefinition childDefinition) {
        Schematic internal = new Schematic(GATES);
        ComponentInstance child = internal.addInstance(
                childDefinition, "nested", 200, 100, Facing.EAST);
        ComponentDefinition.Port childInput = childDefinition.inputPorts().getFirst();
        ComponentDefinition.Port childOutput = childDefinition.outputPorts().getFirst();
        ComponentInstance.PinGeometry in = port(child, childDefinition, childInput.name());
        ComponentInstance.PinGeometry out = port(child, childDefinition, childOutput.name());

        addInputAt(internal, "boundary-in", "IN", in.x(), in.y());
        addOutputAt(internal, "boundary-out", "OUT", out.x(), out.y());
        return ComponentDefinition.fromCircuit(
                name, "Wrapper around " + childDefinition.name(), "Test", null, internal);
    }

    private static ComponentDefinition srLatchDefinition() {
        Netlist latch = new Netlist(
                List.of("S_N", "R_N"),
                List.of(new Netlist.Output("Q", "Q"),
                        new Netlist.Output("QN", "QN")),
                List.of(
                        new Netlist.Gate("Q", GateOperator.NAND, List.of("S_N", "QN")),
                        new Netlist.Gate("QN", GateOperator.NAND, List.of("R_N", "Q"))),
                true);
        FeedbackCircuitExtractor.Extraction feedback =
                FeedbackCircuitExtractor.extract(latch).orElseThrow();
        SchematicLayout.LayoutResult layout =
                new SchematicLayout(GATES).layoutFeedback(feedback);
        return ComponentDefinition.fromCircuit(
                "SRLatch", "Cross-coupled NAND latch", "Sequential", "Q",
                layout.schematic());
    }

    private static Fixture topLevelFixture(
            ComponentDefinition definition,
            Set<String> connectedInputs,
            Set<String> connectedOutputs) {
        Schematic parent = new Schematic(GATES);
        ComponentInstance instance = parent.addInstance(
                definition, "U1", 300, 200, Facing.EAST);
        Map<String, String> inputIds = new LinkedHashMap<>();
        Map<String, String> outputIds = new LinkedHashMap<>();
        for (ComponentDefinition.Port port : definition.inputPorts()) {
            if (!connectedInputs.contains(port.name())) {
                continue;
            }
            ComponentInstance.PinGeometry terminal = port(instance, definition, port.name());
            String id = "IN_" + port.name();
            addInputAt(parent, id, port.name(), terminal.x(), terminal.y());
            inputIds.put(port.name(), id);
        }
        for (ComponentDefinition.Port port : definition.outputPorts()) {
            if (!connectedOutputs.contains(port.name())) {
                continue;
            }
            ComponentInstance.PinGeometry terminal = port(instance, definition, port.name());
            String id = "OUT_" + port.name();
            addOutputAt(parent, id, port.name(), terminal.x(), terminal.y());
            outputIds.put(port.name(), id);
        }
        return new Fixture(parent, Map.copyOf(inputIds), Map.copyOf(outputIds));
    }

    private static void addInputAt(
            Schematic schematic, String id, String name, int pinX, int pinY) {
        GateDef definition = gateDefinition("input");
        GateGeometry.Offset offset = GateGeometry.outputOffset(definition, Facing.EAST);
        schematic.add(new PlacedComponent(
                id, "input", pinX - offset.x(), pinY - offset.y(),
                Facing.EAST, Map.of(), name));
    }

    private static void addOutputAt(
            Schematic schematic, String id, String name, int pinX, int pinY) {
        GateDef definition = gateDefinition("output");
        GateGeometry.Offset offset =
                GateGeometry.inputOffsets(definition, Facing.EAST).getFirst();
        schematic.add(new PlacedComponent(
                id, "output", pinX - offset.x(), pinY - offset.y(),
                Facing.EAST, Map.of(), name));
    }

    private static ComponentInstance.PinGeometry port(
            ComponentInstance instance,
            ComponentDefinition definition,
            String name) {
        return instance.geometry(definition).pins().stream()
                .filter(pin -> pin.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static GateDef gateDefinition(String family) {
        return GATES.stream()
                .filter(definition -> definition.family().equals(family)
                        && definition.size().equals("narrow"))
                .findFirst().orElseThrow();
    }

    private static Connectivity.PinPosition pin(
            Schematic schematic, String componentId, String pinId) {
        return schematic.pinPositions().stream()
                .filter(position -> position.componentId().equals(componentId)
                        && position.pinId().equals(pinId))
                .findFirst().orElseThrow();
    }

    private static Map<String, String> namedComponents(Schematic schematic, String type) {
        Map<String, String> result = new LinkedHashMap<>();
        for (PlacedComponent component : schematic.components()) {
            if (component.typeId().equals(type)) {
                result.put(component.label(), component.id());
            }
        }
        return result;
    }

    private static void connectStraight(
            Schematic schematic, Connectivity.Point first, Connectivity.Point second) {
        if (first.equals(second)) {
            return;
        }
        if (first.x() != second.x() && first.y() != second.y()) {
            throw new IllegalArgumentException("test helper expects aligned pins");
        }
        schematic.addWire(new WireSegment(
                first.x(), first.y(), second.x(), second.y()));
    }

    private static void addPath(Schematic schematic, Connectivity.Point... points) {
        for (int index = 0; index + 1 < points.length; index++) {
            connectStraight(schematic, points[index], points[index + 1]);
        }
    }
}
