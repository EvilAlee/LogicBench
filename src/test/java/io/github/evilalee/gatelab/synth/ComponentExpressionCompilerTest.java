package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.io.ComponentLibrary;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.ComponentInstance;
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

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComponentExpressionCompilerTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();

    @Test
    void halfAndFullAdderCallsCreateBoxesAndMatchEveryTruthTableRow(@TempDir Path directory)
            throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, directory);
        ComponentExpressionCompiler.Compilation half =
                compile(library, """
                        U0 := HA(A, B)
                        S = U0.S
                        C = U0.C
                        """);
        assertEquals(1, half.schematic().componentInstances().size());
        assertEquals("HA", half.schematic().componentInstances().getFirst().definitionName());
        assertTruthTable(half, List.of("A", "B"), List.of("S", "C"),
                row -> List.of(
                        ((row >> 0) & 1) ^ ((row >> 1) & 1),
                        ((row >> 0) & 1) & ((row >> 1) & 1)));

        ComponentExpressionCompiler.Compilation full =
                compile(library, """
                        U0 := FA(A, B, CIN)
                        S = U0.S
                        COUT = U0.COUT
                        """);
        assertEquals(1, full.schematic().componentInstances().size());
        assertTruthTable(full, List.of("A", "B", "CIN"), List.of("S", "COUT"),
                row -> List.of(
                        ((row & 1) ^ ((row >> 1) & 1) ^ ((row >> 2) & 1)),
                        ((Integer.bitCount(row) >= 2) ? 1 : 0)));
    }

    @Test
    void fourBitRippleAdderFromFullAdderBoxesMatchesAll512Rows(@TempDir Path directory)
            throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, directory);
        String source = """
                U0 := FA(A0, B0, CIN)
                U1 := FA(A1, B1, U0.COUT)
                U2 := FA(A2, B2, U1.COUT)
                U3 := FA(A3, B3, U2.COUT)
                S0 = U0.S
                S1 = U1.S
                S2 = U2.S
                S3 = U3.S
                COUT = U3.COUT
                """;
        ComponentExpressionCompiler.Compilation compilation = compile(library, source);
        assertEquals(4, compilation.schematic().componentInstances().size());
        assertTruthTable(compilation,
                List.of("A0", "A1", "A2", "A3", "B0", "B1", "B2", "B3", "CIN"),
                List.of("S0", "S1", "S2", "S3", "COUT"),
                row -> {
                    int a = ((row >> 0) & 1)
                            | (((row >> 1) & 1) << 1)
                            | (((row >> 2) & 1) << 2)
                            | (((row >> 3) & 1) << 3);
                    int b = ((row >> 4) & 1)
                            | (((row >> 5) & 1) << 1)
                            | (((row >> 6) & 1) << 2)
                            | (((row >> 7) & 1) << 3);
                    int sum = a + b + ((row >> 8) & 1);
                    return List.of(sum & 1, (sum >> 1) & 1, (sum >> 2) & 1,
                            (sum >> 3) & 1, (sum >> 4) & 1);
                });
    }

    @Test
    void dffBoxesCountThroughSequenceRunnerFromAResetState(@TempDir Path directory)
            throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, directory);
        ComponentExpressionCompiler.Compilation compilation = compile(library, """
                U0 := DFF((NOT RESET) AND NOT U0.Q, CLK)
                U1 := DFF((NOT RESET) AND (U0.Q XOR U1.Q), CLK)
                Q0 = U0.Q
                Q1 = U1.Q
                """);
        assertEquals(2, compilation.schematic().componentInstances().size());
        assertTrue(compilation.hasSequentialInstances());
        assertTrue(compilation.hasFeedback());

        SchematicCompiler.CompiledCircuit flattened =
                SchematicCompiler.compileCircuit(compilation.schematic());
        Map<String, InputPin> inputs = namedInputs(compilation.schematic(), flattened);
        Map<String, OutputProbe> outputs = namedOutputs(compilation.schematic(), flattened);
        SequenceRunner runner = new SequenceRunner(
                flattened.circuit(), inputs, "CLK", outputs);
        SequenceRunner.SequenceResult result = runner.run(List.of(
                new SequenceRunner.Step("reset", Map.of("RESET", Value.ONE), 1,
                        Map.of("Q0", Value.ZERO, "Q1", Value.ZERO)),
                new SequenceRunner.Step("00 to 01", Map.of("RESET", Value.ZERO), 1,
                        Map.of("Q0", Value.ONE, "Q1", Value.ZERO)),
                new SequenceRunner.Step("01 to 10", Map.of("RESET", Value.ZERO), 1,
                        Map.of("Q0", Value.ZERO, "Q1", Value.ONE)),
                new SequenceRunner.Step("10 to 11", Map.of("RESET", Value.ZERO), 1,
                        Map.of("Q0", Value.ONE, "Q1", Value.ONE)),
                new SequenceRunner.Step("11 to 00", Map.of("RESET", Value.ZERO), 1,
                        Map.of("Q0", Value.ZERO, "Q1", Value.ZERO))));
        assertTrue(result.passed(), () -> String.valueOf(result.firstMismatch()));
    }

    @Test
    void reportsUnknownDefinitionsArityAndOutputPinErrors(@TempDir Path directory)
            throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, directory);
        IllegalArgumentException unknown = assertThrows(
                IllegalArgumentException.class,
                () -> compile(library, "F = MISSING(A)"));
        assertTrue(unknown.getMessage().contains("unknown component"));
        assertTrue(unknown.getMessage().contains("FA"));

        IllegalArgumentException arity = assertThrows(
                IllegalArgumentException.class,
                () -> compile(library, "U0 := FA(A, B)\nS = U0.S"));
        assertTrue(arity.getMessage().contains("requires 3 arguments"));
        assertTrue(arity.getMessage().contains("A, B, CIN"));

        IllegalArgumentException output = assertThrows(
                IllegalArgumentException.class,
                () -> compile(library, "U0 := FA(A, B, CIN)\nS = U0.BAD"));
        assertTrue(output.getMessage().contains("unknown output pin 'BAD'"));
        assertTrue(output.getMessage().contains("S, COUT"));

        IllegalArgumentException direct = assertThrows(
                IllegalArgumentException.class,
                () -> compile(library, "S = FA(A, B, CIN)"));
        assertTrue(direct.getMessage().contains("multiple outputs"));
        assertTrue(direct.getMessage().contains("S, COUT"));
    }

    @Test
    void clockArgumentsAreValidatedAgainstClockPorts(@TempDir Path directory)
            throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, directory);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> compile(library, "Q = DFF(D, NOT CLK)"));
        assertTrue(error.getMessage().contains("clock input must be a plain identifier"));
    }

    @Test
    void legacyOptionKeepsSequentialPartsGateExpanded(@TempDir Path directory)
            throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, directory);
        ComponentExpressionCompiler.Compilation compilation =
                ComponentExpressionCompiler.compile(
                        "Q = DFF(D, CLK)", GATES, library.all(),
                        ComponentExpressionCompiler.CompileOption.LEGACY_SEQUENTIAL_GATE_EXPANSION);
        assertTrue(compilation.legacyCompilation().orElseThrow().hasSequentialParts());
        assertTrue(compilation.schematic().componentInstances().isEmpty());
        assertTrue(compilation.schematic().components().stream()
                .anyMatch(component -> !component.typeId().equals("input")
                        && !component.typeId().equals("clock")
                        && !component.typeId().equals("output")));
    }

    private static ComponentExpressionCompiler.Compilation compile(
            ComponentLibrary library, String source) {
        return ComponentExpressionCompiler.compile(source, GATES, library.all());
    }

    @FunctionalInterface
    private interface ExpectedRow {
        List<Integer> values(int row);
    }

    private static void assertTruthTable(
            ComponentExpressionCompiler.Compilation compilation,
            List<String> inputNames,
            List<String> outputNames,
            ExpectedRow expected) {
        assertEquals(inputNames.size(), compilation.inputComponentIds().size());
        assertEquals(outputNames.size(), compilation.outputComponentIds().size());
        Schematic schematic = compilation.schematic();
        for (int row = 0; row < (1 << inputNames.size()); row++) {
            for (int index = 0; index < inputNames.size(); index++) {
                schematic.setInputValue(
                        compilation.inputComponentIds().get(inputNames.get(index)),
                        ((row >> index) & 1) == 1);
            }
            SchematicCompiler.SimulationResult actual =
                    SchematicCompiler.compileAndSettle(schematic);
            List<Integer> expectedValues = expected.values(row);
            for (int index = 0; index < outputNames.size(); index++) {
                Value value = actual.outputValues().get(
                        compilation.outputComponentIds().get(outputNames.get(index)));
                assertNotNull(value, "missing output " + outputNames.get(index));
                assertEquals(expectedValues.get(index) == 1 ? Value.ONE : Value.ZERO,
                        value, "row " + row + ", output " + outputNames.get(index));
            }
            assertFalse(actual.oscillating(), "oscillation on combinational row " + row);
        }
    }

    private static Map<String, InputPin> namedInputs(
            Schematic schematic, SchematicCompiler.CompiledCircuit compiled) {
        Map<String, InputPin> result = new LinkedHashMap<>();
        for (PlacedComponent component : schematic.components()) {
            if (component.typeId().equals("input") || component.typeId().equals("clock")) {
                result.put(component.label(), compiled.inputs().get(component.id()));
            }
        }
        return result;
    }

    private static Map<String, OutputProbe> namedOutputs(
            Schematic schematic, SchematicCompiler.CompiledCircuit compiled) {
        Map<String, OutputProbe> result = new LinkedHashMap<>();
        for (PlacedComponent component : schematic.components()) {
            if (component.typeId().equals("output")) {
                result.put(component.label(), compiled.outputs().get(component.id()));
            }
        }
        return result;
    }
}
