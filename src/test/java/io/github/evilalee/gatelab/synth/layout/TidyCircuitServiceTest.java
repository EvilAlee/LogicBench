package io.github.evilalee.gatelab.synth.layout;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.SchematicSnapshot;
import io.github.evilalee.gatelab.model.WireSegment;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.sim.SchematicCompiler.SimulationResult;
import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import io.github.evilalee.gatelab.synth.Netlist;
import io.github.evilalee.gatelab.synth.SchematicNetlistExtractor;
import io.github.evilalee.gatelab.synth.FeedbackCircuitExtractor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class TidyCircuitServiceTest {
    private static final List<GateDef> DEFINITIONS = GateLibrary.load().all();

    @Test
    void clockWithoutFeedbackKeepsTheOriginalUnsupportedComponentFailure() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent clock = add(schematic, "clock-clk", "clock", 0, 0, "CLK");
        PlacedComponent output = add(schematic, "output-q", "output", 100, 0, "Q");
        connect(schematic, clock.id(), "out", output.id(), "in0");
        SchematicSnapshot before = SchematicSnapshot.capture(schematic);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TidyCircuitService.tidy(schematic, DEFINITIONS));

        assertEquals("Unsupported component: clock", failure.getMessage());
        assertEquals(before, SchematicSnapshot.capture(schematic));
    }

    @Test
    void tidyPreservesTheExistingDagLayoutAndRunsItsUsualEquivalenceCheck() {
        Schematic original = simpleCombinationalCircuit();
        Netlist netlist = SchematicNetlistExtractor.extract(original).netlist();
        Schematic expected = new SchematicLayout(DEFINITIONS).layout(netlist).schematic();

        TidyCircuitService.TidyResult result =
                TidyCircuitService.tidy(original, DEFINITIONS);

        assertFalse(result.feedbackCircuit());
        assertEquals(4, result.verificationRows());
        assertFalse(result.equivalenceSkipped());
        assertEquals(
                SchematicSnapshot.capture(expected),
                SchematicSnapshot.capture(result.schematic()));
    }

    @Test
    void norSrLatchTidiesAndStillSetsHoldsAndResets() {
        Schematic original = norSrLatch();
        SchematicSnapshot before = SchematicSnapshot.capture(original);
        TidyCircuitService.TidyResult result =
                TidyCircuitService.tidy(original, DEFINITIONS);

        assertTrue(result.feedbackCircuit());
        assertEquals(0, result.verificationRows());
        assertFalse(result.equivalenceSkipped());
        assertEquals(
                "Tidied. Feedback circuit: connections verified, behaviour not simulated. "
                        + "Gate output state restarted at X.",
                result.verificationMessage());
        assertEquals(2, result.schematic().components().stream()
                .filter(component -> component.typeId().equals("nor")).count());
        PlacedComponent reset = componentByLabel(result.schematic(), "R");
        PlacedComponent set = componentByLabel(result.schematic(), "S");
        PlacedComponent q = componentByLabel(result.schematic(), "Q");
        PlacedComponent qBar = componentByLabel(result.schematic(), "Qbar");
        assertEquals("", result.schematic().components().stream()
                .filter(component -> component.typeId().equals("nor"))
                .findFirst().orElseThrow().label());

        SimulationResult simulation = SchematicCompiler.compileAndSettle(result.schematic());
        assertEquals(Value.X, simulation.outputValues().get(q.id()));
        assertEquals(Value.X, simulation.outputValues().get(qBar.id()));
        assertFalse(simulation.oscillating());

        result.schematic().setInputValue(set.id(), true);
        simulation = SchematicCompiler.compileAndSettle(result.schematic(), simulation);
        assertEquals(Value.ONE, simulation.outputValues().get(q.id()));
        assertEquals(Value.ZERO, simulation.outputValues().get(qBar.id()));

        result.schematic().setInputValue(set.id(), false);
        simulation = SchematicCompiler.compileAndSettle(result.schematic(), simulation);
        assertEquals(Value.ONE, simulation.outputValues().get(q.id()), "released set must hold");
        assertEquals(Value.ZERO, simulation.outputValues().get(qBar.id()));

        result.schematic().setInputValue(reset.id(), true);
        simulation = SchematicCompiler.compileAndSettle(result.schematic(), simulation);
        assertEquals(Value.ZERO, simulation.outputValues().get(q.id()));
        assertEquals(Value.ONE, simulation.outputValues().get(qBar.id()));
        assertFalse(simulation.oscillating());
        assertEquals(before.components().size(), original.components().size());
        assertEquals(before, SchematicSnapshot.capture(original));
    }

    @Test
    void masterSlaveNandFlipFlopTidiesAndPreservesInputAndClockLevels() {
        Schematic original = masterSlaveNandFlipFlop();
        original.setInputValue(componentByLabel(original, "D").id(), true);
        original.setClockValue(componentByLabel(original, "CLK").id(), true);

        assertTrue(FeedbackCircuitExtractor.hasFeedback(original),
                "the master-slave NAND schematic must have reachable feedback");
        FeedbackCircuitExtractor.Extraction extraction =
                FeedbackCircuitExtractor.extract(original).orElseThrow();
        SchematicLayout.LayoutResult layout =
                new SchematicLayout(DEFINITIONS).layoutFeedback(extraction);
        Map<String, String> newToOldIds = new java.util.LinkedHashMap<>();
        extraction.inputComponentIds().forEach((signal, oldId) ->
                newToOldIds.put(layout.inputComponentIds().get(signal), oldId));
        extraction.clockComponentIds().forEach((signal, oldId) ->
                newToOldIds.put(layout.clockComponentIds().get(signal), oldId));
        extraction.outputComponentIds().forEach((signal, oldId) ->
                newToOldIds.put(layout.outputComponentIds().get(signal), oldId));
        extraction.gateComponentIds().forEach((signal, oldId) ->
                newToOldIds.put(layout.gateComponentIds().get(signal), oldId));
        assertDoesNotThrow(() ->
                FeedbackConnectivityVerifier.verify(original, layout.schematic(), newToOldIds));

        TidyCircuitService.TidyResult result =
                TidyCircuitService.tidy(original, DEFINITIONS);

        assertTrue(result.feedbackCircuit());
        assertEquals(10, result.schematic().components().stream()
                .filter(component -> component.typeId().equals("nand")).count());
        assertTrue(result.schematic().inputValue(
                componentByLabel(result.schematic(), "D").id()));
        assertTrue(result.schematic().clockValue(
                componentByLabel(result.schematic(), "CLK").id()));
        assertEquals("Q", componentByLabel(result.schematic(), "Q").label());
        assertEquals("Qbar", componentByLabel(result.schematic(), "Qbar").label());
        assertEquals(
                "Tidied. Feedback circuit: connections verified, behaviour not simulated. "
                        + "Gate output state restarted at X.",
                result.verificationMessage());
    }

    @Test
    void corruptedFeedbackLayoutFailsConnectivityVerificationWithoutChangingOriginal() {
        Schematic original = norSrLatch();
        FeedbackCircuitExtractor.Extraction extraction =
                FeedbackCircuitExtractor.extract(original).orElseThrow();
        Schematic tidied = TidyCircuitService.tidy(original, DEFINITIONS).schematic();
        Map<String, String> newToOld = feedbackIdentityMap(extraction);
        Connectivity.Net netToCorrupt = Connectivity.analyze(
                        tidied.pinPositions(), tidied.wires()).nets().stream()
                .filter(net -> net.pins().size() > 1 && !net.segments().isEmpty())
                .min(java.util.Comparator.comparingInt(net -> net.segments().size()))
                .orElseThrow();
        List<WireSegment> corruptedWires = new ArrayList<>(tidied.wires());
        corruptedWires.removeAll(netToCorrupt.segments());
        Schematic corrupted = new Schematic(DEFINITIONS);
        corrupted.restoreSnapshot(new SchematicSnapshot(
                tidied.components(), corruptedWires));
        SchematicSnapshot before = SchematicSnapshot.capture(original);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> FeedbackConnectivityVerifier.verify(original, corrupted, newToOld));

        assertTrue(failure.getMessage().contains("Feedback connectivity verification failed"));
        assertEquals(before, SchematicSnapshot.capture(original));
    }

    @Test
    void tidiesScrambledExpressionSchematicAndPreservesLabelsAndInputValues() {
        ExpressionCompiler.Compilation compilation =
                ExpressionCompiler.compile("F = A & B");
        SchematicLayout.LayoutResult generated =
                new SchematicLayout(DEFINITIONS).layout(compilation.netlist());
        Schematic schematic = generated.schematic();
        String inputA = generated.inputComponentIds().get("A");
        schematic.setInputValue(inputA, true);
        PlacedComponent gate = schematic.components().stream()
                .filter(component -> component.typeId().equals("and"))
                .findFirst().orElseThrow();
        schematic.move(gate.id(), gate.x() + 100, gate.y() + 80);

        List<PlacedComponent> before = schematic.components();
        TidyCircuitService.TidyResult first =
                TidyCircuitService.tidy(schematic, DEFINITIONS);
        TidyCircuitService.TidyResult second =
                TidyCircuitService.tidy(schematic, DEFINITIONS);
        assertFalse(first.equivalenceSkipped());
        assertEquals(4, first.verificationRows());
        assertEquals(first.schematic().components(), second.schematic().components());
        assertEquals(first.schematic().wires(), second.schematic().wires());
        assertNotEquals(before.stream().map(c -> List.of(c.x(), c.y())).toList(),
                first.schematic().components().stream().map(c -> List.of(c.x(), c.y())).toList());

        Map<String, String> newInputIds = first.schematic().components().stream()
                .filter(component -> component.typeId().equals("input"))
                .collect(Collectors.toMap(PlacedComponent::label, PlacedComponent::id));
        assertEquals(Set.of("A", "B"), newInputIds.keySet());
        assertTrue(first.schematic().inputValue(newInputIds.get("A")));
        assertEquals("F", first.schematic().components().stream()
                .filter(component -> component.typeId().equals("output"))
                .map(PlacedComponent::label).findFirst().orElseThrow());
        assertTrue(first.schematic().components().stream()
                .filter(component -> !component.typeId().equals("input")
                        && !component.typeId().equals("output"))
                .allMatch(component -> component.facing() == Facing.EAST));

        TidyCircuitService.TidyResult repeated =
                TidyCircuitService.tidy(first.schematic(), DEFINITIONS);
        assertEquals(first.schematic().components(), repeated.schematic().components());
        assertEquals(first.schematic().wires(), repeated.schematic().wires());
    }

    @Test
    void tidiesExpressionGeneratedLiteralSourceAndPreservesItsFixedValue() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile("""
                F = A & B
                Constant = 1
                """);
        SchematicLayout.LayoutResult generated =
                new SchematicLayout(DEFINITIONS).layout(compilation.netlist());
        PlacedComponent literal = generated.schematic().components().stream()
                .filter(component -> component.typeId().equals("constant")
                        && component.label().equals("1"))
                .findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class,
                () -> generated.schematic().inputValue(literal.id()));

        TidyCircuitService.TidyResult result =
                TidyCircuitService.tidy(generated.schematic(), DEFINITIONS);
        assertFalse(result.equivalenceSkipped());
        assertEquals(4, result.verificationRows());
        PlacedComponent retainedLiteral = result.schematic().components().stream()
                .filter(component -> component.typeId().equals("constant")
                        && component.label().equals("1"))
                .findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class,
                () -> result.schematic().inputValue(retainedLiteral.id()));
    }

    @Test
    void tidyIsIdempotentForAComplexMultiOutputCircuit() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile("""
                F = (A & B) | (C ^ D)
                G = (A NAND C) ^ (B NOR D)
                """);
        SchematicLayout.LayoutResult generated =
                new SchematicLayout(DEFINITIONS).layout(compilation.netlist());
        TidyCircuitService.TidyResult first =
                TidyCircuitService.tidy(generated.schematic(), DEFINITIONS);
        TidyCircuitService.TidyResult second =
                TidyCircuitService.tidy(first.schematic(), DEFINITIONS);

        assertEquals(first.schematic().components(), second.schematic().components());
        assertEquals(first.schematic().wires(), second.schematic().wires());
    }

    @Test
    void handBuiltMessyCircuitWithCrossingWiresRemainsEquivalentAfterTidy() {
        Schematic schematic = crossingCircuit();
        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        Connectivity.Net inputNet = topology.nets().stream()
                .filter(net -> net.pins().stream().anyMatch(pin ->
                        pin.componentId().equals("input-a") && pin.pinId().equals("out")))
                .findFirst().orElseThrow();
        assertTrue(inputNet.pins().stream().anyMatch(pin ->
                pin.componentId().equals("gate-and") && pin.pinId().equals("in0")));
        assertFalse(inputNet.pins().stream().anyMatch(pin ->
                pin.componentId().equals("gate-and") && pin.pinId().equals("out")));

        Connectivity.Point crossing = new Connectivity.Point(400, 100);
        assertTrue(schematic.wires().stream().filter(wire -> !wire.isHorizontal())
                .anyMatch(wire -> crossing.x() == wire.x1()
                        && crossing.y() > wire.y1() && crossing.y() < wire.y2()));
        assertTrue(schematic.wires().stream().filter(WireSegment::isHorizontal)
                .anyMatch(wire -> crossing.y() == wire.y1()
                        && crossing.x() > wire.x1() && crossing.x() < wire.x2()));

        TidyCircuitService.TidyResult result =
                TidyCircuitService.tidy(schematic, DEFINITIONS);
        assertFalse(result.equivalenceSkipped());
        assertEquals(4, result.verificationRows());
        assertTrue(result.schematic().components().stream()
                .filter(component -> component.typeId().equals("and"))
                .allMatch(component -> component.facing() == Facing.EAST));
    }

    @Test
    void skipsExhaustiveEquivalenceForMoreThanTwelveInputsButStillTidies() {
        Schematic schematic = new Schematic(DEFINITIONS);
        List<PlacedComponent> inputs = new ArrayList<>();
        for (int index = 0; index < 13; index++) {
            int y = index == 0 ? 0 : index == 1 ? 200 : 400 + (index - 2) * 40;
            PlacedComponent input = new PlacedComponent(
                    "input-" + index, "input", 0, y,
                    Facing.EAST, Map.of(), "A" + index);
            schematic.add(input);
            inputs.add(input);
        }
        PlacedComponent gate = new PlacedComponent(
                "gate-and", "and", 300, 100, Facing.EAST);
        PlacedComponent output = new PlacedComponent(
                "output-f", "output", 500, 100, Facing.EAST, Map.of(), "F");
        schematic.add(gate);
        schematic.add(output);
        connect(schematic, inputs.get(0).id(), "out", gate.id(), "in0");
        connect(schematic, inputs.get(1).id(), "out", gate.id(), "in1");
        connect(schematic, gate.id(), "out", output.id(), "in0");

        TidyCircuitService.TidyResult result = TidyCircuitService.tidy(schematic, DEFINITIONS);
        assertTrue(result.equivalenceSkipped());
        assertEquals(13, result.inputCount());
        assertEquals(0, result.verificationRows());
        assertTrue(result.verificationMessage().contains("skipped"));
    }

    @Test
    void everyRefusalLeavesTheOriginalSchematicUntouched() {
        Schematic bare = new Schematic(DEFINITIONS);
        PlacedComponent bareInput = add(bare, "input-a", "input", 0, 0, "A");
        PlacedComponent bareGate = add(bare, "gate-and", "and", 300, 100, "");
        PlacedComponent bareOutput = add(bare, "output-f", "output", 500, 100, "F");
        connect(bare, bareInput.id(), "out", bareGate.id(), "in0");
        connect(bare, bareGate.id(), "out", bareOutput.id(), "in0");
        assertRefusal(bare, DEFINITIONS, "bare (unconnected)");

        Schematic undriven = new Schematic(DEFINITIONS);
        PlacedComponent source = add(undriven, "input-a", "input", 0, 0, "A");
        PlacedComponent firstGate = add(undriven, "gate-and", "and", 300, 100, "");
        PlacedComponent secondGate = add(undriven, "gate-or", "or", 500, 200, "");
        PlacedComponent drivenOutput = add(undriven, "output-f", "output", 700, 100, "F");
        connect(undriven, source.id(), "out", firstGate.id(), "in0");
        connect(undriven, firstGate.id(), "in1", secondGate.id(), "in0");
        connect(undriven, firstGate.id(), "out", drivenOutput.id(), "in0");
        assertRefusal(undriven, DEFINITIONS, "net with no driver");

        Schematic multipleDrivers = new Schematic(DEFINITIONS);
        PlacedComponent driverA = add(multipleDrivers, "input-a", "input", 0, 0, "A");
        add(multipleDrivers, "input-b", "input", 0, 0, "B");
        PlacedComponent andGate = add(multipleDrivers, "gate-and", "and", 300, 100, "");
        PlacedComponent andOutput = add(multipleDrivers, "output-f", "output", 500, 100, "F");
        connect(multipleDrivers, driverA.id(), "out", andGate.id(), "in0");
        connect(multipleDrivers, andGate.id(), "out", andOutput.id(), "in0");
        assertRefusal(multipleDrivers, DEFINITIONS, "more than one driver");

        Schematic noOutputDriver = new Schematic(DEFINITIONS);
        PlacedComponent input1 = add(noOutputDriver, "input-a", "input", 0, 0, "A");
        PlacedComponent input2 = add(noOutputDriver, "input-b", "input", 0, 200, "B");
        PlacedComponent noOutputGate = add(noOutputDriver, "gate-and", "and", 300, 100, "");
        PlacedComponent undrivenOutput = add(
                noOutputDriver, "output-f", "output", 500, 500, "F");
        PlacedComponent secondUndrivenOutput = add(
                noOutputDriver, "output-g", "output", 700, 500, "G");
        connect(noOutputDriver, input1.id(), "out", noOutputGate.id(), "in0");
        connect(noOutputDriver, input2.id(), "out", noOutputGate.id(), "in1");
        connect(noOutputDriver, undrivenOutput.id(), "in0",
                secondUndrivenOutput.id(), "in0");
        assertRefusal(noOutputDriver, DEFINITIONS, "net with no driver");

        Schematic feedback = feedbackCircuit();
        assertRefusal(feedback, DEFINITIONS, "combinational cycle");

        List<GateDef> unsupportedDefinitions = new ArrayList<>(DEFINITIONS);
        unsupportedDefinitions.add(new GateDef(
                "custom", "svg/custom.svg", 20, 20,
                List.of(new GateGeometry.Offset(0, 10)),
                new GateGeometry.Offset(20, 10)));
        Schematic unsupported = new Schematic(unsupportedDefinitions);
        unsupported.add("custom", 100, 100, Facing.EAST);
        assertRefusal(unsupported, unsupportedDefinitions, "Unsupported component");

        List<GateDef> threeInputDefinitions = new ArrayList<>(DEFINITIONS);
        threeInputDefinitions.add(new GateDef(
                "and-normal-3", "svg/and.svg", 30, 60,
                List.of(new GateGeometry.Offset(0, 10),
                        new GateGeometry.Offset(0, 30),
                        new GateGeometry.Offset(0, 50)),
                new GateGeometry.Offset(30, 30), "and", "normal", 3));
        Schematic unsupportedArity = new Schematic(threeInputDefinitions);
        unsupportedArity.add(
                "and", 100, 100, Facing.EAST,
                Map.of("size", "normal", "inputs", "3"));
        assertRefusal(unsupportedArity, threeInputDefinitions, "Unsupported input count");
    }

    private static Schematic simpleCombinationalCircuit() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent inputA = add(schematic, "input-a", "input", 0, 0, "A");
        PlacedComponent inputB = add(schematic, "input-b", "input", 0, 200, "B");
        PlacedComponent gate = add(schematic, "gate-and", "and", 300, 100, "");
        PlacedComponent output = add(schematic, "output-f", "output", 500, 100, "F");
        connect(schematic, inputA.id(), "out", gate.id(), "in0");
        connect(schematic, inputB.id(), "out", gate.id(), "in1");
        connect(schematic, gate.id(), "out", output.id(), "in0");
        return schematic;
    }

    private static Schematic norSrLatch() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent reset = add(schematic, "input-reset", "input", 50, -10, "R");
        PlacedComponent set = add(schematic, "input-set", "input", 150, -10, "S");
        PlacedComponent qGate = add(schematic, "gate-q", "nor", 100, 0, "");
        PlacedComponent qBarGate = add(schematic, "gate-qbar", "nor", 200, 0, "");
        PlacedComponent q = add(schematic, "output-q", "output", 120, 0, "Q");
        PlacedComponent qBar = add(schematic, "output-qbar", "output", 220, 0, "Qbar");
        connect(schematic, reset.id(), "out", qGate.id(), "in0");
        connect(schematic, set.id(), "out", qBarGate.id(), "in0");
        path(schematic, 200, 0, 200, -40, 30, -40, 30, 10, 60, 10);
        path(schematic, 100, 0, 100, 40, 180, 40, 180, 10, 160, 10);
        connect(schematic, qGate.id(), "out", q.id(), "in0");
        connect(schematic, qBarGate.id(), "out", qBar.id(), "in0");
        return schematic;
    }

    private static Schematic masterSlaveNandFlipFlop() {
        Schematic schematic = new Schematic(DEFINITIONS);
        DffWiring wiring = new DffWiring(schematic);
        PlacedComponent d = add(schematic, "input-d", "input", 0, 0, "D");
        PlacedComponent clock = add(schematic, "clock-clk", "clock", 0, 100, "CLK");
        PlacedComponent dNot = add(schematic, "gate-d-not", "nand", 1000, 200, "");
        PlacedComponent clockNot = add(schematic, "gate-clock-not", "nand", 1000, 300, "");
        PlacedComponent masterSet = add(schematic, "gate-master-set", "nand", 2000, 400, "");
        PlacedComponent masterReset = add(schematic, "gate-master-reset", "nand", 2000, 500, "");
        PlacedComponent masterQ = add(schematic, "gate-master-q", "nand", 3000, 600, "");
        PlacedComponent masterQBar = add(schematic, "gate-master-qbar", "nand", 3000, 700, "");
        PlacedComponent slaveSet = add(schematic, "gate-slave-set", "nand", 4000, 800, "");
        PlacedComponent slaveReset = add(schematic, "gate-slave-reset", "nand", 4000, 900, "");
        PlacedComponent slaveQ = add(schematic, "gate-slave-q", "nand", 5000, 1000, "");
        PlacedComponent slaveQBar = add(schematic, "gate-slave-qbar", "nand", 5000, 1100, "");
        PlacedComponent q = add(schematic, "output-q", "output", 6000, 1000, "Q");
        PlacedComponent qBar = add(schematic, "output-qbar", "output", 6000, 1100, "Qbar");

        wiring.connect( d.id(), "out", dNot.id(), "in0");
        wiring.connect( d.id(), "out", dNot.id(), "in1");
        wiring.connect( d.id(), "out", masterSet.id(), "in0");
        wiring.connect( clock.id(), "out", clockNot.id(), "in0");
        wiring.connect( clock.id(), "out", clockNot.id(), "in1");
        wiring.connect( clock.id(), "out", masterSet.id(), "in1");
        wiring.connect( dNot.id(), "out", masterReset.id(), "in0");
        wiring.connect( clock.id(), "out", masterReset.id(), "in1");
        wiring.connect( masterSet.id(), "out", masterQ.id(), "in0");
        wiring.connect( masterQBar.id(), "out", masterQ.id(), "in1");
        wiring.connect( masterReset.id(), "out", masterQBar.id(), "in0");
        wiring.connect( masterQ.id(), "out", masterQBar.id(), "in1");
        wiring.connect( masterQ.id(), "out", slaveSet.id(), "in0");
        wiring.connect( clockNot.id(), "out", slaveSet.id(), "in1");
        wiring.connect( masterQBar.id(), "out", slaveReset.id(), "in0");
        wiring.connect( clockNot.id(), "out", slaveReset.id(), "in1");
        wiring.connect( slaveSet.id(), "out", slaveQ.id(), "in0");
        wiring.connect( slaveQBar.id(), "out", slaveQ.id(), "in1");
        wiring.connect( slaveReset.id(), "out", slaveQBar.id(), "in0");
        wiring.connect( slaveQ.id(), "out", slaveQBar.id(), "in1");
        wiring.connect( slaveQ.id(), "out", q.id(), "in0");
        wiring.connect( slaveQBar.id(), "out", qBar.id(), "in0");
        return schematic;
    }

    private static final class DffWiring {
        private final Schematic schematic;
        private int nextLane;

        private DffWiring(Schematic schematic) {
            this.schematic = schematic;
        }

        private void connect(
                String sourceId, String sourcePin, String targetId, String targetPin) {
            Connectivity.PinPosition source = pin(schematic, sourceId, sourcePin);
            Connectivity.PinPosition target = pin(schematic, targetId, targetPin);
            int lane = nextLane++;
            int sourceTrackX = source.x()
                    + PlacedComponent.GRID_SPACING * (1 + lane);
            int targetTrackX = target.x()
                    - PlacedComponent.GRID_SPACING * (1 + lane);
            int laneY = -100 - lane * PlacedComponent.GRID_SPACING * 2;
            addSegment(source.x(), source.y(), sourceTrackX, source.y());
            addSegment(sourceTrackX, source.y(), sourceTrackX, laneY);
            addSegment(targetTrackX, laneY, sourceTrackX, laneY);
            addSegment(targetTrackX, laneY, targetTrackX, target.y());
            addSegment(targetTrackX, target.y(), target.x(), target.y());
        }

        private void addSegment(int x1, int y1, int x2, int y2) {
            if (x1 != x2 || y1 != y2) {
                schematic.addWire(x1, y1, x2, y2);
            }
        }
    }

    private static PlacedComponent componentByLabel(Schematic schematic, String label) {
        return schematic.components().stream()
                .filter(component -> component.label().equals(label))
                .findFirst().orElseThrow();
    }

    private static Map<String, String> feedbackIdentityMap(
            FeedbackCircuitExtractor.Extraction extraction) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        extraction.inputComponentIds().forEach((signal, oldId) ->
                result.put("expr-input-" + signal, oldId));
        extraction.clockComponentIds().forEach((signal, oldId) ->
                result.put("expr-clock-" + signal, oldId));
        extraction.outputComponentIds().forEach((signal, oldId) ->
                result.put("expr-output-" + signal, oldId));
        extraction.gateComponentIds().forEach((signal, oldId) ->
                result.put("expr-gate-" + signal, oldId));
        return result;
    }

    private static void path(Schematic schematic, int... points) {
        for (int index = 0; index + 3 < points.length; index += 2) {
            schematic.addWire(
                    points[index], points[index + 1],
                    points[index + 2], points[index + 3]);
        }
    }

    private static Schematic crossingCircuit() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent inputA = add(schematic, "input-a", "input", 0, 0, "A");
        PlacedComponent inputB = add(schematic, "input-b", "input", 0, 200, "B");
        PlacedComponent gate = add(schematic, "gate-and", "and", 300, 100, "");
        PlacedComponent output = add(schematic, "output-f", "output", 500, 100, "F");

        Connectivity.PinPosition gateOutput = pin(schematic, gate.id(), "out");
        Connectivity.PinPosition outputInput = pin(schematic, output.id(), "in0");

        schematic.addWire(0, 0, 100, 0);
        schematic.addWire(100, 0, 100, 50);
        schematic.addWire(100, 50, 400, 50);
        schematic.addWire(400, 50, 400, 150);
        schematic.addWire(260, 150, 400, 150);
        schematic.addWire(260, 90, 260, 150);
        schematic.addWire(260, 90, 270, 90);
        schematic.addWire(0, 200, 270, 200);
        schematic.addWire(270, 110, 270, 200);
        schematic.addWire(gateOutput.x(), gateOutput.y(),
                outputInput.x(), outputInput.y());
        schematic.setInputValue(inputB.id(), true);
        return schematic;
    }

    private static Schematic simpleCircuit(Facing facing) {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent inputA = add(schematic, "input-a", "input", 0, 0, "A");
        PlacedComponent inputB = add(schematic, "input-b", "input", 0, 200, "B");
        PlacedComponent gate = new PlacedComponent(
                "gate-and", "and", 300, 100, facing);
        schematic.add(gate);
        PlacedComponent output = add(schematic, "output-f", "output", 500, 100, "F");
        connect(schematic, inputA.id(), "out", gate.id(), "in0");
        connect(schematic, inputB.id(), "out", gate.id(), "in1");
        connect(schematic, gate.id(), "out", output.id(), "in0");
        schematic.setInputValue(inputB.id(), true);
        return schematic;
    }

    private static Schematic feedbackCircuit() {
        Schematic schematic = new Schematic(DEFINITIONS);
        add(schematic, "not-a", "not", 100, 100, "");
        add(schematic, "not-b", "not", 400, 200, "");
        add(schematic, "output-f", "output", 700, 200, "F");
        schematic.addWire(100, 100, 350, 100);
        schematic.addWire(350, 100, 350, 200);
        schematic.addWire(350, 200, 370, 200);
        schematic.addWire(400, 200, 600, 200);
        schematic.addWire(600, 200, 600, 0);
        schematic.addWire(600, 0, 70, 0);
        schematic.addWire(70, 0, 70, 100);
        schematic.addWire(400, 200, 700, 200);
        return schematic;
    }

    private static PlacedComponent add(
            Schematic schematic, String id, String type, int x, int y, String label) {
        PlacedComponent component = new PlacedComponent(
                id, type, x, y, Facing.EAST, Map.of(), label);
        schematic.add(component);
        return component;
    }

    private static void connect(
            Schematic schematic,
            String sourceId,
            String sourcePin,
            String targetId,
            String targetPin) {
        Connectivity.PinPosition source = pin(schematic, sourceId, sourcePin);
        Connectivity.PinPosition target = pin(schematic, targetId, targetPin);
        int inputIndex = Integer.parseInt(targetPin.substring(2));
        int entryTrackX = target.x() - (inputIndex + 1) * PlacedComponent.GRID_SPACING;
        if (source.x() != entryTrackX) {
            schematic.addWire(source.x(), source.y(), entryTrackX, source.y());
        }
        if (source.y() != target.y()) {
            schematic.addWire(entryTrackX, source.y(), entryTrackX, target.y());
        }
        if (entryTrackX != target.x()) {
            schematic.addWire(entryTrackX, target.y(), target.x(), target.y());
        }
    }

    private static Connectivity.PinPosition pin(
            Schematic schematic, String componentId, String pinId) {
        return schematic.pinPositions().stream()
                .filter(pin -> pin.componentId().equals(componentId)
                        && pin.pinId().equals(pinId))
                .findFirst().orElseThrow();
    }

    private static void assertRefusal(
            Schematic schematic, List<GateDef> definitions, String message) {
        SchematicSnapshot before = SchematicSnapshot.capture(schematic);
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> TidyCircuitService.tidy(schematic, definitions));
        assertTrue(exception.getMessage().contains(message), exception.getMessage());
        assertEquals(before, SchematicSnapshot.capture(schematic));
    }
}
