package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.synth.layout.SchematicLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionFeedbackTest {
    private static final List<io.github.evilalee.gatelab.model.GateDef> DEFINITIONS =
            GateLibrary.load().all();

    @Test
    void forwardReferencesBuildAndSimulateAnSrLatch() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile("""
                Q = NOT (R | Qn)
                Qn = NOT (S | Q)
                """, true);

        assertEquals(List.of("R", "S"), compilation.netlist().inputs());
        assertEquals(List.of("Q", "Qn"), compilation.netlist().outputs().stream()
                .map(Netlist.Output::name).toList());
        assertTrue(compilation.hasFeedback());
        assertThrows(IllegalArgumentException.class,
                compilation.netlist()::topologicalGates);
        assertDoesNotThrow(compilation.feedbackExtraction().orElseThrow()
                .dagNetlist()::topologicalGates);

        SchematicLayout.LayoutResult layout = new SchematicLayout(DEFINITIONS)
                .layoutFeedback(compilation.feedbackExtraction().orElseThrow());
        assertEquals(List.of("R", "S"),
                List.copyOf(layout.inputComponentIds().keySet()));
        assertEquals(List.of("Q", "Qn"),
                List.copyOf(layout.outputComponentIds().keySet()));

        Schematic schematic = layout.schematic();
        SchematicCompiler.SimulationResult result =
                SchematicCompiler.compileAndSettle(schematic);
        setInput(schematic, layout, "R", false);
        setInput(schematic, layout, "S", true);
        result = SchematicCompiler.compileAndSettle(schematic, result);
        assertEquals(Value.ONE, output(layout, result, "Q"), "set");
        assertEquals(Value.ZERO, output(layout, result, "Qn"), "set");

        setInput(schematic, layout, "S", false);
        result = SchematicCompiler.compileAndSettle(schematic, result);
        assertEquals(Value.ONE, output(layout, result, "Q"), "hold");
        assertEquals(Value.ZERO, output(layout, result, "Qn"), "hold");

        setInput(schematic, layout, "R", true);
        result = SchematicCompiler.compileAndSettle(schematic, result);
        assertEquals(Value.ZERO, output(layout, result, "Q"), "reset");
        assertEquals(Value.ONE, output(layout, result, "Qn"), "reset");
    }

    @Test
    void expressionLinesBuildAndSimulateFourNandOneNotDLatch() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile("""
                Dbar := NOT D
                Sbar := D NAND EN
                Rbar := Dbar NAND EN
                Q = Sbar NAND Qbar
                Qbar = Rbar NAND Q
                """);

        assertTrue(compilation.hasFeedback());
        assertEquals(List.of("D", "EN"), compilation.netlist().inputs());
        assertEquals(5, compilation.netlist().gates().size());
        assertEquals(1, compilation.netlist().gates().stream()
                .filter(gate -> gate.type() == GateOperator.NOT).count());
        assertEquals(4, compilation.netlist().gates().stream()
                .filter(gate -> gate.type() == GateOperator.NAND).count());

        SchematicLayout.LayoutResult layout = new SchematicLayout(DEFINITIONS)
                .layoutFeedback(compilation.feedbackExtraction().orElseThrow());
        Schematic schematic = layout.schematic();
        SchematicCompiler.SimulationResult result =
                SchematicCompiler.compileAndSettle(schematic);

        setInput(schematic, layout, "EN", true);
        setInput(schematic, layout, "D", true);
        result = SchematicCompiler.compileAndSettle(schematic, result);
        assertEquals(Value.ONE, output(layout, result, "Q"), "transparent high");

        setInput(schematic, layout, "EN", false);
        setInput(schematic, layout, "D", false);
        result = SchematicCompiler.compileAndSettle(schematic, result);
        assertEquals(Value.ONE, output(layout, result, "Q"), "disabled latch holds");

        setInput(schematic, layout, "EN", true);
        result = SchematicCompiler.compileAndSettle(schematic, result);
        assertEquals(Value.ZERO, output(layout, result, "Q"), "transparent low");
    }

    @Test
    void namedConstantDefinitionsDoNotBecomeInputsInsideFeedbackCircuits() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile("""
                One := 1
                Q = One NAND Qn
                Qn = S NAND Q
                """);

        assertTrue(compilation.hasFeedback());
        assertEquals(List.of("S"), compilation.netlist().inputs());
        assertTrue(compilation.netlist().gates().stream()
                .anyMatch(gate -> gate.inputs().contains("1")));
        SchematicLayout.LayoutResult layout = new SchematicLayout(DEFINITIONS)
                .layoutFeedback(compilation.feedbackExtraction().orElseThrow());
        assertTrue(layout.constantComponentIds().containsKey("1"));
        assertEquals(List.of("S"), List.copyOf(layout.inputComponentIds().keySet()));
    }

    @Test
    void selfReferencesAreFeedbackNotPrimaryInputsAndUseBoundedSettling() {
        ExpressionCompiler.Compilation compilation =
                ExpressionCompiler.compile("Q = NOT Q", true);

        assertTrue(compilation.hasFeedback());
        assertTrue(compilation.netlist().inputs().isEmpty());
        assertEquals("Q", compilation.netlist().outputs().getFirst().source());
        assertThrows(IllegalArgumentException.class, () -> new Netlist(
                List.of(), List.of(new Netlist.Output("Q", "Q")),
                List.of(new Netlist.Gate("Q", GateOperator.NOT, List.of("Q")))));

        SchematicLayout.LayoutResult layout = new SchematicLayout(DEFINITIONS)
                .layoutFeedback(compilation.feedbackExtraction().orElseThrow());
        SchematicCompiler.SimulationResult result =
                SchematicCompiler.compileAndSettle(layout.schematic());
        assertEquals(Value.X, result.outputValues().get(
                layout.outputComponentIds().get("Q")));
        assertFalse(result.oscillating(),
                "an unknown-state self-loop settles to X under three-valued semantics");
    }

    @Test
    void ordinaryCombinationalExpressionsStillUseTheDagPath() {
        ExpressionCompiler.Compilation compilation =
                ExpressionCompiler.compile("F = A & B");

        assertFalse(compilation.hasFeedback());
        assertEquals(List.of("A", "B"), compilation.netlist().inputs());
        assertEquals(List.of(new Netlist.Gate(
                "g1", GateOperator.AND, List.of("A", "B"))),
                compilation.netlist().gates());
        assertEquals("g1", compilation.netlist().outputs().getFirst().source());
        assertTrue(TruthTableVerifier.verify(compilation).verified());
    }

    private static void setInput(
            Schematic schematic, SchematicLayout.LayoutResult layout,
            String name, boolean value) {
        schematic.setInputValue(layout.inputComponentIds().get(name), value);
    }

    private static Value output(
            SchematicLayout.LayoutResult layout,
            SchematicCompiler.SimulationResult result,
            String name) {
        return result.outputValues().get(layout.outputComponentIds().get(name));
    }
}
