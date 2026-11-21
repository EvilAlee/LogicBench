package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.io.ComponentLibrary;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.synth.generators.StandardCircuitGenerators;
import io.github.evilalee.gatelab.synth.layout.SchematicLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CircuitDescriberTest {
    private static final List<io.github.evilalee.gatelab.model.GateDef> DEFINITIONS =
            GateLibrary.load().all();

    @TempDir
    Path directory;

    @Test
    void sharedSubexpressionsBecomeNamedSignalsAndSingleUseGatesAreInlined() {
        Netlist netlist = new Netlist(
                List.of("A", "B"),
                List.of(new Netlist.Output("F", "g2"), new Netlist.Output("G", "g3")),
                List.of(
                        new Netlist.Gate("g1", GateOperator.AND, List.of("A", "B")),
                        new Netlist.Gate("g2", GateOperator.OR, List.of("g1", "A")),
                        new Netlist.Gate("g3", GateOperator.XOR, List.of("g1", "B"))));

        CircuitDescriber.Description description = CircuitDescriber.describe(netlist);

        assertEquals("n1 := (A & B)\nF = (n1 | A)\nG = (n1 ^ B)", description.text());
        assertEquals("g1", description.componentIds().get("n1"));
        assertFalse(description.text().contains("n2 :="));
        assertTrue(TruthTableVerifier.verify(ExpressionCompiler.compile(description.text()))
                .verified());
    }

    @Test
    void namesAreSanitizedDeduplicatedAndGeneratedForUnnamedPorts() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent secondInput = new PlacedComponent(
                "input-b", "input", 0, 0, Facing.EAST, Map.of(), "A-B");
        PlacedComponent firstInput = new PlacedComponent(
                "input-a", "input", 0, 100, Facing.EAST, Map.of(), "A B");
        PlacedComponent unnamedInput = new PlacedComponent(
                "input-unnamed", "input", 0, 200, Facing.EAST);
        PlacedComponent unnamedClock = new PlacedComponent(
                "clock-unnamed", "clock", 0, 300, Facing.EAST);
        PlacedComponent gate = new PlacedComponent(
                "gate-or", "or", 300, 100, Facing.NORTH);
        PlacedComponent output = new PlacedComponent(
                "output-not", "output", 550, 50, Facing.EAST, Map.of(), "NOT");
        for (PlacedComponent component : List.of(
                secondInput, firstInput, unnamedInput, unnamedClock, gate, output)) {
            schematic.add(component);
        }
        connect(schematic, "input-a", "out", "gate-or", "in0");
        connect(schematic, "input-b", "out", "gate-or", "in1");
        connect(schematic, "gate-or", "out", "output-not", "in0");

        CircuitDescriber.Description description = CircuitDescriber.describe(schematic);

        assertEquals("input-a", description.componentIds().get("A_B"));
        assertEquals("input-b", description.componentIds().get("A_B_2"));
        assertEquals("input-unnamed", description.componentIds().get("In1"));
        assertEquals("clock-unnamed", description.componentIds().get("Clk1"));
        assertEquals("output-not", description.componentIds().get("NOT_"));
        assertTrue(ExpressionCompiler.compile(description.text()).netlist().inputs()
                .containsAll(List.of("A_B", "A_B_2")));
        assertTrue(description.componentIds().containsKey("In1"));
        assertTrue(description.componentIds().containsKey("Clk1"));
        assertEquals(description, CircuitDescriber.describe(schematic));
    }

    @Test
    void repeatedCallsProduceIdenticalTextAndSignalMappings() {
        Schematic schematic = combinationalSchematic(
                "F = (A & B) | (A & C)\nG = (A & B) ^ C");

        CircuitDescriber.Description first = CircuitDescriber.describe(schematic);
        CircuitDescriber.Description second = CircuitDescriber.describe(schematic);

        assertEquals(first.text(), second.text());
        assertEquals(first.componentIds(), second.componentIds());
    }

    @Test
    void fullAdderMajorityAndFourBitAdderTemplatesRoundTripEveryTruthTableRow() throws Exception {
        ComponentLibrary library = componentLibrary();
        Schematic fullAdder = library.get("FA").internalCircuit();

        assertRoundTrip(fullAdder);

        String majoritySource = StandardCircuitGenerators.require("majority")
                .build(Map.of("n", "3")).expressionSource();
        assertRoundTrip(layout(ExpressionCompiler.compile(majoritySource, false)));

        String adderSource = StandardCircuitGenerators.require("ripple_adder")
                .build(Map.of("n", "4", "carryIn", "yes")).expressionSource();
        assertRoundTrip(layout(ExpressionCompiler.compile(adderSource, false)));
    }

    @Test
    void selfReferencingGateWithoutPrimaryInputsExportsAsASelfReference() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent gate = new PlacedComponent(
                "not-self", "not", 100, 100, Facing.EAST);
        PlacedComponent output = new PlacedComponent(
                "output-q", "output", 300, 100, Facing.EAST, Map.of(), "Q");
        schematic.add(gate);
        schematic.add(output);
        connect(schematic, gate.id(), "out", gate.id(), "in0");
        connect(schematic, gate.id(), "out", output.id(), "in0");

        CircuitDescriber.Description description = CircuitDescriber.describe(schematic);
        assertEquals("n1 := (NOT n1)\nQ = n1", description.text());

        ExpressionCompiler.Compilation compilation =
                ExpressionCompiler.compile(description.text(), true);
        assertTrue(compilation.hasFeedback());
        assertTrue(compilation.netlist().inputs().isEmpty());
        assertEquals("n1", compilation.netlist().outputs().getFirst().source());
    }

    @Test
    void srLatchDlatchAndGateLevelMasterSlaveDffMatchAcrossTwoHundredRandomSteps()
            throws Exception {
        Schematic srLatch = layout(ExpressionCompiler.compile("""
                Q = NOT (R | Qn)
                Qn = NOT (S | Q)
                """, true));
        Schematic dLatch = layout(ExpressionCompiler.compile("""
                Dbar := NOT D
                Sbar := D NAND EN
                Rbar := Dbar NAND EN
                Q = Sbar NAND Qbar
                Qbar = Rbar NAND Q
                """, true));
        Schematic masterSlaveDff = componentLibrary().get("DFF").internalCircuit();

        assertFeedbackRoundTrip(srLatch, "SR latch", 0x51a7L);
        assertFeedbackRoundTrip(dLatch, "D latch", 0xd1a7L);
        assertFeedbackRoundTrip(masterSlaveDff, "master-slave DFF", 0xdffL);
    }

    @Test
    void componentInstancesAndMoreThanFourHundredGatesAreRefused() throws Exception {
        Schematic withInstance = new Schematic(DEFINITIONS);
        ComponentDefinition definition = componentLibrary().get("FA");
        withInstance.addInstance(definition, 100, 100, Facing.EAST);
        IllegalArgumentException instanceFailure = assertThrows(
                IllegalArgumentException.class,
                () -> CircuitDescriber.describe(withInstance));
        assertEquals(
                "Circuit export does not support component instances yet.",
                instanceFailure.getMessage());

        Schematic oversized = new Schematic(DEFINITIONS);
        for (int index = 0; index < 401; index++) {
            oversized.add(new PlacedComponent(
                    "gate-" + index, "and", index * 10, 0, Facing.EAST));
        }
        IllegalArgumentException gateFailure = assertThrows(
                IllegalArgumentException.class,
                () -> CircuitDescriber.describe(oversized));
        assertTrue(gateFailure.getMessage().contains("maximum is 400"));
    }

    private ComponentLibrary componentLibrary() throws Exception {
        return ComponentLibrary.load(DEFINITIONS, directory);
    }

    private static Schematic layout(ExpressionCompiler.Compilation compilation) {
        if (compilation.hasFeedback()) {
            return new SchematicLayout(DEFINITIONS)
                    .layoutFeedback(compilation.feedbackExtraction().orElseThrow()).schematic();
        }
        return new SchematicLayout(DEFINITIONS).layout(compilation.netlist()).schematic();
    }

    private record SemanticRow(
            Map<String, Boolean> inputs,
            Map<String, Value> outputs,
            boolean oscillating) { }

    private static void assertRoundTrip(Schematic original) {
        Netlist originalNetlist = FeedbackCircuitExtractor.extract(original)
                .map(FeedbackCircuitExtractor.Extraction::sourceNetlist)
                .orElseGet(() -> SchematicNetlistExtractor.extract(original).netlist());
        CircuitDescriber.Description description = CircuitDescriber.describe(original);
        ExpressionCompiler.Compilation rebuilt =
                ExpressionCompiler.compile(description.text(), true);
        TruthTable expected = TruthTableGenerator.generate(originalNetlist);
        TruthTable actual = TruthTableGenerator.generate(rebuilt.netlist());

        assertEquals(java.util.Set.copyOf(expected.inputNames()),
                java.util.Set.copyOf(actual.inputNames()), description.text());
        assertEquals(java.util.Set.copyOf(expected.outputNames()),
                java.util.Set.copyOf(actual.outputNames()), description.text());
        assertEquals(rowsByNamedAssignment(expected), rowsByNamedAssignment(actual),
                description.text());
    }

    private static Map<Map<String, Boolean>, SemanticRow> rowsByNamedAssignment(
            TruthTable table) {
        Map<Map<String, Boolean>, SemanticRow> result = new LinkedHashMap<>();
        for (TruthTable.Row row : table.rows()) {
            Map<String, Boolean> inputs = new LinkedHashMap<>();
            for (int index = 0; index < table.inputNames().size(); index++) {
                inputs.put(table.inputNames().get(index), row.inputValue(index));
            }
            Map<String, Value> outputs = new LinkedHashMap<>();
            for (int index = 0; index < table.outputNames().size(); index++) {
                outputs.put(table.outputNames().get(index), row.outputValues().get(index));
            }
            result.put(Map.copyOf(inputs),
                    new SemanticRow(Map.copyOf(inputs), Map.copyOf(outputs), row.oscillating()));
        }
        return result;
    }

    private static void assertFeedbackRoundTrip(Schematic original, String circuitName, long seed) {
        CircuitDescriber.Description description = CircuitDescriber.describe(original);
        ExpressionCompiler.Compilation compilation =
                ExpressionCompiler.compile(description.text(), true);
        assertTrue(compilation.hasFeedback(), description.text());
        SchematicLayout.LayoutResult layout = new SchematicLayout(DEFINITIONS)
                .layoutFeedback(compilation.feedbackExtraction().orElseThrow());
        Schematic rebuilt = layout.schematic();

        SchematicCompiler.SimulationResult originalResult =
                SchematicCompiler.compileAndSettle(original);
        SchematicCompiler.SimulationResult rebuiltResult =
                SchematicCompiler.compileAndSettle(rebuilt);
        List<PlacedComponent> sources = original.components().stream()
                .filter(component -> component.typeId().equals("input")
                        || component.typeId().equals("clock"))
                .sorted(java.util.Comparator.comparing(PlacedComponent::id))
                .toList();
        List<PlacedComponent> outputs = original.components().stream()
                .filter(component -> component.typeId().equals("output"))
                .toList();

        assertOutputsEqual(originalResult, rebuiltResult, outputs, layout,
                circuitName + " initial");
        Random random = new Random(seed);
        for (int step = 0; step < 200; step++) {
            for (PlacedComponent source : sources) {
                String name = source.label();
                boolean high = random.nextBoolean();
                if (source.typeId().equals("clock")) {
                    original.setClockValue(source.id(), high);
                } else {
                    original.setInputValue(source.id(), high);
                }
                String rebuiltId = layout.inputComponentIds().get(name);
                assertNotNull(rebuiltId, "exported expression omitted source " + name);
                rebuilt.setInputValue(rebuiltId, high);
            }
            originalResult = SchematicCompiler.compileAndSettle(original, originalResult);
            rebuiltResult = SchematicCompiler.compileAndSettle(rebuilt, rebuiltResult);
            assertOutputsEqual(originalResult, rebuiltResult, outputs, layout,
                    circuitName + " step " + step);
        }
    }

    private static void assertOutputsEqual(
            SchematicCompiler.SimulationResult originalResult,
            SchematicCompiler.SimulationResult rebuiltResult,
            List<PlacedComponent> outputs,
            SchematicLayout.LayoutResult layout,
            String context) {
        for (PlacedComponent output : outputs) {
            String rebuiltId = layout.outputComponentIds().get(output.label());
            assertNotNull(rebuiltId, "exported expression omitted output " + output.label());
            Value originalValue = originalResult.outputValues().get(output.id());
            Value rebuiltValue = rebuiltResult.outputValues().get(rebuiltId);
            assertEquals(originalValue, rebuiltValue,
                    context + ": different output value for " + output.label());
        }
        assertEquals(originalResult.oscillating(), rebuiltResult.oscillating());
    }

    private static Schematic combinationalSchematic(String source) {
        return layout(ExpressionCompiler.compile(source, false));
    }

    private static void connect(
            Schematic schematic, String sourceId, String sourcePin,
            String targetId, String targetPin) {
        io.github.evilalee.gatelab.model.Connectivity.PinPosition source =
                pin(schematic, sourceId, sourcePin);
        io.github.evilalee.gatelab.model.Connectivity.PinPosition target =
                pin(schematic, targetId, targetPin);
        if (source.x() != target.x()) {
            schematic.addWire(source.x(), source.y(), target.x(), source.y());
        }
        if (source.y() != target.y()) {
            schematic.addWire(target.x(), source.y(), target.x(), target.y());
        }
    }

    private static io.github.evilalee.gatelab.model.Connectivity.PinPosition pin(
            Schematic schematic, String componentId, String pinId) {
        return schematic.pinPositions().stream()
                .filter(value -> value.componentId().equals(componentId)
                        && value.pinId().equals(pinId))
                .findFirst().orElseThrow();
    }
}
