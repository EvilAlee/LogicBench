package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SequenceRunner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionSequentialTest {
    @Test
    void expressionBuiltTwoBitCounterCountsAfterResetWithSequenceRunner() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile("""
                Q0 = DFF(RESET_N & NOT(Q0), CLK)
                Q1 = DFF(RESET_N & (Q1 ^ Q0), CLK)
                """, false);

        assertTrue(compilation.hasSequentialParts());
        assertTrue(compilation.hasFeedback());
        assertEquals("CLK", compilation.clockName().orElseThrow());
        assertEquals(List.of("RESET_N", "CLK"), compilation.netlist().inputs());
        assertEquals(List.of("Q0", "Q1"), compilation.netlist().outputs().stream()
                .map(Netlist.Output::name).toList());

        ExpressionCompiler.SequentialCircuit sequential =
                compilation.sequentialCircuit().orElseThrow();
        SequenceRunner runner = new SequenceRunner(
                sequential.circuit(),
                sequential.inputs(),
                compilation.clockName().orElseThrow(),
                sequential.outputs(),
                sequential.fixedInputs());

        SequenceRunner.SequenceResult result = runner.run(List.of(
                step("reset", Value.ZERO, Value.ZERO, Value.ZERO),
                step("01", Value.ONE, Value.ONE, Value.ZERO),
                step("10", Value.ONE, Value.ZERO, Value.ONE),
                step("11", Value.ONE, Value.ONE, Value.ONE),
                step("00", Value.ONE, Value.ZERO, Value.ZERO)));

        assertTrue(result.passed(), result.toString());
        assertEquals(
                List.of("00", "01", "10", "11", "00"),
                result.steps().stream()
                        .map(observation -> bit(observation.observedOutputs().get("Q1"))
                                + bit(observation.observedOutputs().get("Q0")))
                        .toList());
    }

    @Test
    void rejectsRawFeedbackUnlessExplicitlyEnabled() {
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> ExpressionCompiler.compile("Q = NOT Q", false));
        assertTrue(rejected.getMessage().contains("raw feedback is disabled"));
        assertTrue(rejected.getMessage().contains("Q -> Q"));

        ExpressionCompiler.Compilation accepted =
                ExpressionCompiler.compile("Q = NOT Q", true);
        assertTrue(accepted.hasFeedback());
        assertFalse(accepted.hasSequentialParts());
    }

    @Test
    void rawCycleOutsideASequentialPartIsRejectedButPartFeedbackIsAllowed() {
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> ExpressionCompiler.compile("Q = DFF(A, CLK) & Q", false));
        assertTrue(rejected.getMessage().contains("raw feedback is disabled"));

        ExpressionCompiler.Compilation accepted = ExpressionCompiler.compile(
                "Q = DFF(RESET_N & NOT(Q), CLK)", false);
        assertTrue(accepted.hasSequentialParts());
        assertTrue(accepted.hasFeedback());
    }

    @Test
    void rejectsMultipleClockNamesAndClockIdentifiersDefinedByAssignments() {
        IllegalArgumentException multiple = assertThrows(
                IllegalArgumentException.class,
                () -> ExpressionCompiler.compile("""
                        Q0 = DFF(D0, CLK_A)
                        Q1 = JKFF(J, K, CLK_B)
                        """, false));
        assertTrue(multiple.getMessage().contains("one clock"));
        assertTrue(multiple.getMessage().contains("CLK_A"));
        assertTrue(multiple.getMessage().contains("CLK_B"));

        IllegalArgumentException defined = assertThrows(
                IllegalArgumentException.class,
                () -> ExpressionCompiler.compile("""
                        CLK = A
                        Q = DFF(D, CLK)
                        """, false));
        assertTrue(defined.getMessage().contains("clock identifier 'CLK'"));
        assertTrue(defined.getMessage().contains("cannot be defined"));
    }

    @Test
    void parserLeavesLibraryClockPortValidationToComponentResolution() {
        Expression.ComponentCall call = assertInstanceOf(
                Expression.ComponentCall.class,
                ExpressionParser.parse("DFF(D, NOT CLK)"));
        assertEquals("DFF", call.componentName());
        assertEquals(2, call.arguments().size());
        assertInstanceOf(Expression.Unary.class, call.arguments().getLast());
    }

    @Test
    void sequentialPartArgumentsFoldConstantsButLeaveNoConstantCircuitsUnchanged() {
        ExpressionCompiler.Compilation folded =
                ExpressionCompiler.compile("Q = DFF(A & 0, CLK)", false);
        ExpressionCompiler.SequentialCircuit foldedCircuit =
                folded.sequentialCircuit().orElseThrow();
        assertTrue(foldedCircuit.fixedInputs().values().contains(Value.ZERO));
        assertFalse(foldedCircuit.inputs().containsKey("A"));
        assertFalse(folded.netlist().gates().stream()
                .anyMatch(gate -> gate.type() == GateOperator.AND));

        ExpressionCompiler.Compilation unchanged =
                ExpressionCompiler.compile("Q = DFF(A & A, CLK)", false);
        assertEquals(1, unchanged.netlist().gates().stream()
                .filter(gate -> gate.type() == GateOperator.AND).count());
    }

    @Test
    void ordinaryExpressionCompilationRemainsOnTheOriginalDagPath() {
        ExpressionCompiler.Compilation compilation =
                ExpressionCompiler.compile("F = A & B", false);
        assertFalse(compilation.hasSequentialParts());
        assertFalse(compilation.hasFeedback());
        assertEquals(List.of("A", "B"), compilation.netlist().inputs());
        assertEquals(List.of(new Netlist.Gate(
                "g1", GateOperator.AND, List.of("A", "B"))),
                compilation.netlist().gates());
        assertTrue(TruthTableVerifier.verify(compilation).verified());
    }

    private static SequenceRunner.Step step(
            String name, Value reset, Value q0, Value q1) {
        return new SequenceRunner.Step(
                name,
                Map.of("RESET_N", reset),
                1,
                Map.of("Q0", q0, "Q1", q1));
    }

    private static String bit(Value value) {
        return switch (value) {
            case ZERO -> "0";
            case ONE -> "1";
            case X -> "X";
        };
    }
}
