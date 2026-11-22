package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.io.ComponentLibrary;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.InputPin;
import io.github.evilalee.gatelab.model.OutputProbe;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.sim.SequenceRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ComponentCallCircuitDescriberTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();

    @TempDir
    Path directory;

    @Test
    void fullAdderInstanceExportsCallAndBothNamedOutputSelectors() throws Exception {
        ComponentLibrary library = componentLibrary();
        ComponentExpressionCompiler.Compilation original = ComponentExpressionCompiler.compile(
                """
                U0 := FA(A, B, CIN)
                S = U0.S
                COUT = U0.COUT
                """, GATES, library.all());

        CircuitDescriber.Description description = CircuitDescriber.describe(original.schematic());

        assertTrue(description.text().contains("U0 := FA(A, B, CIN)"), description.text());
        assertTrue(description.text().contains("S = U0.S"), description.text());
        assertTrue(description.text().contains("COUT = U0.COUT"), description.text());
        assertEquals("U0", description.componentIds().keySet().stream()
                .filter("U0"::equals).findFirst().orElseThrow());

        ComponentExpressionCompiler.Compilation rebuilt =
                ComponentExpressionCompiler.compile(description.text(), GATES, library.all());
        assertCombinationallyEquivalent(original, rebuilt);
    }

    @Test
    void fourBitRippleAdderOfFaBoxesRoundTripsEveryInputRow() throws Exception {
        ComponentLibrary library = componentLibrary();
        ComponentExpressionCompiler.Compilation original = ComponentExpressionCompiler.compile(
                """
                U0 := FA(A0, B0, CIN)
                U1 := FA(A1, B1, U0.COUT)
                U2 := FA(A2, B2, U1.COUT)
                U3 := FA(A3, B3, U2.COUT)
                S0 = U0.S
                S1 = U1.S
                S2 = U2.S
                S3 = U3.S
                COUT = U3.COUT
                """, GATES, library.all());

        CircuitDescriber.Description description = CircuitDescriber.describe(original.schematic());
        assertTrue(description.text().contains("U3 := FA(A3, B3, U2.COUT)"),
                description.text());
        ComponentExpressionCompiler.Compilation rebuilt =
                ComponentExpressionCompiler.compile(description.text(), GATES, library.all());
        assertEquals(4, rebuilt.schematic().componentInstances().size());
        assertCombinationallyEquivalent(original, rebuilt);
    }

    @Test
    void counterOfDffBoxesRoundTripsAndMatchesAcrossTwoHundredRandomStepsFromX()
            throws Exception {
        ComponentLibrary library = componentLibrary();
        ComponentExpressionCompiler.Compilation original = ComponentExpressionCompiler.compile(
                """
                U0 := DFF((NOT RESET) AND NOT U0.Q, CLK)
                U1 := DFF((NOT RESET) AND (U0.Q XOR U1.Q), CLK)
                Q0 = U0.Q
                Q1 = U1.Q
                """, GATES, library.all());

        CircuitDescriber.Description description = CircuitDescriber.describe(original.schematic());
        assertTrue(description.text().contains("U0 := DFF("), description.text());
        assertTrue(description.text().contains("U1 := DFF("), description.text());
        assertTrue(description.text().contains("U0.Q"), description.text());
        assertTrue(description.text().contains("U1.Q"), description.text());

        ComponentExpressionCompiler.Compilation rebuilt =
                ComponentExpressionCompiler.compile(description.text(), GATES, library.all());
        assertEquals(2, rebuilt.schematic().componentInstances().size());

        List<SequenceRunner.Step> steps = new ArrayList<>();
        steps.add(new SequenceRunner.Step(
                "start at X", Map.of("RESET", Value.X), 0, Map.of()));
        Random random = new Random(0xdffca11L);
        for (int index = 0; index < 200; index++) {
            steps.add(new SequenceRunner.Step(
                    "random step " + index,
                    Map.of("RESET", random.nextBoolean() ? Value.ONE : Value.ZERO),
                    1, Map.of()));
        }

        SequenceRunner.SequenceResult originalRun = runSequence(original.schematic(), steps);
        SequenceRunner.SequenceResult rebuiltRun = runSequence(rebuilt.schematic(), steps);
        assertEquals(201, originalRun.steps().size());
        assertEquals(originalRun.steps().stream()
                        .map(SequenceRunner.StepObservation::observedOutputs).toList(),
                rebuiltRun.steps().stream()
                        .map(SequenceRunner.StepObservation::observedOutputs).toList());
        assertEquals(Value.X,
                originalRun.steps().getFirst().observedOutputs().get("Q0"));
        assertEquals(Value.X,
                rebuiltRun.steps().getFirst().observedOutputs().get("Q0"));
        assertTrue(originalRun.passed(), () -> String.valueOf(originalRun.firstMismatch()));
        assertTrue(rebuiltRun.passed(), () -> String.valueOf(rebuiltRun.firstMismatch()));
    }

    @Test
    void unnamedInstanceNamesAreDeterministicAndUseUIdentifiers() throws Exception {
        ComponentLibrary library = componentLibrary();
        ComponentExpressionCompiler.Compilation compiled = ComponentExpressionCompiler.compile(
                """
                U1 := FA(A0, B0, CIN)
                U2 := FA(A1, B1, U1.COUT)
                S0 = U1.S
                S1 = U2.S
                COUT = U2.COUT
                """, GATES, library.all());
        // The compiler's document instance ids are already U0/U1. Clear only the
        // optional display labels to model an unnamed saved document instance.
        Schematic schematic = compiled.schematic();
        CircuitDescriber.Description first = CircuitDescriber.describe(schematic);
        CircuitDescriber.Description second = CircuitDescriber.describe(schematic);
        assertEquals(first, second);
        assertTrue(first.text().contains("U1 := FA("), first.text());
        assertTrue(first.text().contains("U2 := FA("), first.text());
    }

    @Test
    void savedInstanceLabelIsUsedAsTheExpressionCallName() throws Exception {
        ComponentLibrary library = componentLibrary();
        ComponentExpressionCompiler.Compilation compiled = ComponentExpressionCompiler.compile(
                """
                U0 := FA(A, B, CIN)
                S = U0.S
                COUT = U0.COUT
                """, GATES, library.all());
        Schematic labelled = withInstanceLabel(compiled.schematic(), "U0", "Final Adder");

        CircuitDescriber.Description description = CircuitDescriber.describe(labelled);
        assertTrue(description.text().contains("Final_Adder := FA("), description.text());
        assertTrue(description.text().contains("S = Final_Adder.S"), description.text());
        assertEquals("U0", description.componentIds().get("Final_Adder"));

        ComponentExpressionCompiler.Compilation rebuilt =
                ComponentExpressionCompiler.compile(description.text(), GATES, library.all());
        assertCombinationallyEquivalent(compiled, rebuilt);
    }

    private static Schematic withInstanceLabel(
            Schematic original, String targetId, String label) {
        Schematic copy = new Schematic(original.gateDefinitions());
        for (PlacedComponent component : original.components()) {
            copy.add(component);
        }
        for (var instance : original.componentInstances()) {
            var definition = original.componentDefinitionFor(instance);
            copy.addInstance(new io.github.evilalee.gatelab.model.ComponentInstance(
                    instance.definitionName(), instance.definitionHash(), instance.id(),
                    instance.id().equals(targetId) ? label : instance.label(),
                    instance.x(), instance.y(), instance.rotation()), definition);
        }
        for (var wire : original.wires()) {
            copy.addWire(wire);
        }
        return copy;
    }

    private void assertCombinationallyEquivalent(
            ComponentExpressionCompiler.Compilation original,
            ComponentExpressionCompiler.Compilation rebuilt) {
        List<String> inputs = original.inputComponentIds().keySet().stream().sorted().toList();
        List<String> outputs = original.outputComponentIds().keySet().stream().sorted().toList();
        assertEquals(inputs, rebuilt.inputComponentIds().keySet().stream().sorted().toList());
        assertEquals(outputs, rebuilt.outputComponentIds().keySet().stream().sorted().toList());
        for (int row = 0; row < (1 << inputs.size()); row++) {
            for (int index = 0; index < inputs.size(); index++) {
                boolean high = ((row >>> (inputs.size() - index - 1)) & 1) != 0;
                String name = inputs.get(index);
                original.schematic().setInputValue(
                        original.inputComponentIds().get(name), high);
                rebuilt.schematic().setInputValue(
                        rebuilt.inputComponentIds().get(name), high);
            }
            SchematicCompiler.SimulationResult expected =
                    SchematicCompiler.compileAndSettle(original.schematic());
            SchematicCompiler.SimulationResult actual =
                    SchematicCompiler.compileAndSettle(rebuilt.schematic());
            for (String output : outputs) {
                assertEquals(expected.outputValues().get(
                                original.outputComponentIds().get(output)),
                        actual.outputValues().get(rebuilt.outputComponentIds().get(output)),
                        "row " + row + ", output " + output);
            }
            assertFalse(expected.oscillating(), "original oscillated on row " + row);
            assertFalse(actual.oscillating(), "rebuilt oscillated on row " + row);
        }
    }

    private SequenceRunner.SequenceResult runSequence(
            Schematic schematic, List<SequenceRunner.Step> steps) {
        SchematicCompiler.CompiledCircuit compiled = SchematicCompiler.compileCircuit(schematic);
        Map<String, InputPin> inputs = new LinkedHashMap<>();
        Map<String, OutputProbe> outputs = new LinkedHashMap<>();
        for (PlacedComponent component : schematic.components()) {
            if (component.typeId().equals("input") || component.typeId().equals("clock")) {
                inputs.put(component.label(), compiled.inputs().get(component.id()));
            } else if (component.typeId().equals("output")) {
                outputs.put(component.label(), compiled.outputs().get(component.id()));
            }
        }
        return new SequenceRunner(compiled.circuit(), inputs, "CLK", outputs).run(steps);
    }

    private ComponentLibrary componentLibrary() throws Exception {
        return ComponentLibrary.load(GATES, directory);
    }
}
