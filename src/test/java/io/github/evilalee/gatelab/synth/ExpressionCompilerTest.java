package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.synth.layout.SchematicExpressionVerifier;
import io.github.evilalee.gatelab.synth.layout.SchematicLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionCompilerTest {
    @Test
    void internalAssignmentsAreInlinedWithoutCreatingOutputPins() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile(
                "S0C := A & B\nF = S0C | C");

        assertEquals(List.of("F"), compilation.netlist().outputs().stream()
                .map(Netlist.Output::name).toList());
        assertEquals(List.of("A", "B", "C"), compilation.netlist().inputs());
        assertTrue(compilation.reusedOutputNames().isEmpty());
        assertEquals(2, compilation.netlist().gates().size());
        assertEquals(List.of("A", "B"), compilation.netlist().gates().getFirst().inputs());
        assertEquals(List.of("g1", "C"), compilation.netlist().gates().get(1).inputs());
    }

    @Test
    void outputDefinitionsReferencedLaterAreReportedButRemainOutputs() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile(
                "S0C = A & B\nF = S0C | C");

        assertEquals(List.of("S0C", "F"), compilation.netlist().outputs().stream()
                .map(Netlist.Output::name).toList());
        assertEquals(List.of("A", "B", "C"), compilation.netlist().inputs());
        assertEquals(List.of("g1", "g2"), compilation.netlist().outputs().stream()
                .map(Netlist.Output::source).toList());
        assertEquals(List.of("S0C"), compilation.reusedOutputNames());
    }

    @Test
    void forwardOutputReferencesResolveWithoutCreatingExtraInputs() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile(
                "F = S0C | C\nS0C = A & B");

        assertEquals(List.of("A", "B", "C"), compilation.netlist().inputs());
        assertEquals(List.of("F", "S0C"), compilation.netlist().outputs().stream()
                .map(Netlist.Output::name).toList());
        assertEquals(List.of("g2", "g1"), compilation.netlist().outputs().stream()
                .map(Netlist.Output::source).toList());
        assertEquals(List.of("S0C"), compilation.reusedOutputNames());
        assertTrue(TruthTableVerifier.verify(compilation).verified());
    }

    @Test
    void identicalSubexpressionsAreSharedAcrossOutputs() {
        ExpressionCompiler.Compilation compilation =
                ExpressionCompiler.compile("F = A & B\nG = (A & B) | C");
        Netlist netlist = compilation.netlist();

        assertEquals(2, netlist.gates().size());
        assertEquals(1, netlist.gates().stream()
                .filter(gate -> gate.type() == GateOperator.AND).count());
        assertEquals("g1", netlist.outputs().get(0).source());
        assertEquals("g2", netlist.outputs().get(1).source());
        assertEquals(List.of("g1", "C"), netlist.gates().get(1).inputs());
    }

    @Test
    void threeOperandChainsCompileToLeftLeaningTwoInputGateTrees() {
        Netlist netlist = ExpressionCompiler.compile("F = A & B & C").netlist();
        assertEquals(2, netlist.gates().size());
        assertEquals(List.of("A", "B"), netlist.gates().get(0).inputs());
        assertEquals(List.of("g1", "C"), netlist.gates().get(1).inputs());
        assertTrue(netlist.gates().stream().allMatch(
                gate -> gate.type().inputCount() == 2));
    }

    @Test
    void multipleOutputsShareInputsAndPreserveFirstAppearanceOrder() {
        ExpressionCompiler.Compilation compilation =
                ExpressionCompiler.compile("F = B & A; G = A ^ C\nH = B | C");
        assertEquals(List.of("B", "A", "C"), compilation.netlist().inputs());
        assertEquals(List.of("F", "G", "H"),
                compilation.netlist().outputs().stream().map(Netlist.Output::name).toList());
        assertEquals(3, compilation.netlist().outputs().size());
    }

    @Test
    void constantsAreFoldedAndConstantOnlyOutputsRemainValid() {
        Netlist netlist = ExpressionCompiler.compile(
                "F = (1 & 0) | (0 ^ 1)\nG = A & 0").netlist();

        assertEquals(List.of("A"), netlist.inputs());
        assertTrue(netlist.gates().isEmpty());
        assertEquals(List.of("1", "0"),
                netlist.outputs().stream().map(Netlist.Output::source).toList());

        ExpressionCompiler.Compilation constant = ExpressionCompiler.compile("F = NOT 0");
        assertTrue(constant.netlist().inputs().isEmpty());
        assertTrue(constant.netlist().gates().isEmpty());
        assertEquals("1", constant.netlist().outputs().getFirst().source());
    }

    @Test
    void everyRequiredBooleanConstantRuleFoldsAndMatchesTheEngineTruthTable() {
        record FoldCase(String expression, List<String> inputs, String source,
                List<GateOperator> gates) { }
        List<FoldCase> cases = List.of(
                new FoldCase("A & 0", List.of("A"), "0", List.of()),
                new FoldCase("A & 1", List.of("A"), "A", List.of()),
                new FoldCase("A | 0", List.of("A"), "A", List.of()),
                new FoldCase("A | 1", List.of("A"), "1", List.of()),
                new FoldCase("A ^ 0", List.of("A"), "A", List.of()),
                new FoldCase("A ^ 1", List.of("A"), "g1", List.of(GateOperator.NOT)),
                new FoldCase("NOT 0", List.of(), "1", List.of()),
                new FoldCase("NOT 1", List.of(), "0", List.of()));

        for (FoldCase fold : cases) {
            ExpressionCompiler.Compilation compilation =
                    ExpressionCompiler.compile("F = " + fold.expression());
            assertEquals(fold.inputs(), compilation.netlist().inputs(), fold.expression());
            assertEquals(fold.source(), compilation.netlist().outputs().getFirst().source(),
                    fold.expression());
            assertEquals(fold.gates(), compilation.netlist().gates().stream()
                    .map(Netlist.Gate::type).toList(), fold.expression());
            assertTrue(TruthTableVerifier.verify(compilation).verified(), fold.expression());
        }

        ExpressionCompiler.Compilation propagated = ExpressionCompiler.compile(
                "F = NOT ((A & 1) ^ 1) | (B & 0)");
        assertEquals(List.of("A", "B"), propagated.netlist().inputs());
        assertTrue(propagated.netlist().gates().isEmpty());
        assertEquals("A", propagated.netlist().outputs().getFirst().source());
        assertTrue(TruthTableVerifier.verify(propagated).verified());
    }

    @Test
    void constantOnlyOutputsUseFixedNonPokeableSources() {
        ExpressionCompiler.Compilation compilation =
                ExpressionCompiler.compile("Zero = 0\nOne = NOT 0");
        SchematicLayout.LayoutResult layout = new SchematicLayout(GateLibrary.load().all())
                .layout(compilation.netlist());

        assertTrue(layout.inputComponentIds().isEmpty());
        assertEquals(List.of("0", "1"), List.copyOf(layout.constantComponentIds().keySet()));
        for (var entry : layout.constantComponentIds().entrySet()) {
            var component = layout.schematic().components().stream()
                    .filter(candidate -> candidate.id().equals(entry.getValue()))
                    .findFirst().orElseThrow();
            assertEquals("constant", component.typeId());
            assertEquals(entry.getKey(), component.label());
            assertThrows(IllegalArgumentException.class,
                    () -> layout.schematic().setInputValue(component.id(), true));
        }

        assertTrue(SchematicExpressionVerifier.verify(compilation, layout).verified());
        SchematicCompiler.SimulationResult result =
                SchematicCompiler.compileAndSettle(layout.schematic());
        assertEquals(Value.ZERO,
                result.outputValues().get(layout.outputComponentIds().get("Zero")));
        assertEquals(Value.ONE,
                result.outputValues().get(layout.outputComponentIds().get("One")));
    }

    @Test
    void identicalUnaryAndBinaryStructuresAreInterned() {
        Netlist netlist = ExpressionCompiler.compile(
                "F = NOT A\nG = NOT A\nH = (NOT A) & B").netlist();
        assertEquals(2, netlist.gates().size());
        assertEquals(1, netlist.gates().stream()
                .filter(gate -> gate.type() == GateOperator.NOT).count());
    }

    @Test
    void rejectsDuplicateDefaultOutputNamesAndTooManyVariables() {
        assertThrows(IllegalArgumentException.class,
                () -> ExpressionCompiler.compile("A & B\nA | B"));
        String manyInputs = "Result = A & B & C & D & E & F & G & H & I & J & K & L & M";
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ExpressionCompiler.compile(manyInputs)).getMessage().contains("maximum is 12"));
    }

    @Test
    void parseErrorPositionsAreTranslatedToTheOriginalMultiExpressionSource() {
        ParseException exception = assertThrows(
                ParseException.class, () -> ExpressionCompiler.compile("F = A&B;\nG = C &"));
        assertEquals(16, exception.position());
    }
}
