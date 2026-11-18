package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionDialogTest {
    @Test
    void reusedOutputAssignmentShowsTheExactNonBlockingHint() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile(
                "S0C = A & B\nF = S0C | C");

        assertEquals(
                "S0C is both an output and used by later lines. "
                        + "Use := for an internal signal with no pin.",
                ExpressionDialog.outputReuseHint(compilation));
        assertTrue(compilation.netlist().outputs().stream()
                .anyMatch(output -> output.name().equals("S0C")),
                "the hint must not remove the output pin");
    }

    @Test
    void internalAssignmentsAndPlainOutputsDoNotShowTheHint() {
        ExpressionCompiler.Compilation internal = ExpressionCompiler.compile(
                "S0C := A & B\nF = S0C | C");
        ExpressionCompiler.Compilation plainOutput = ExpressionCompiler.compile(
                "S0C = A & B");

        assertNull(ExpressionDialog.outputReuseHint(internal));
        assertNull(ExpressionDialog.outputReuseHint(plainOutput));
        assertEquals(1, internal.netlist().outputs().size());
        assertEquals("F", internal.netlist().outputs().getFirst().name());
    }
}
