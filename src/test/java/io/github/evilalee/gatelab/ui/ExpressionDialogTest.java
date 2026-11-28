package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.synth.ExpressionCompiler;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionDialogTest {
    @Test
    void detectsLibraryCallsButNotBooleanOperatorParentheses() {
        assertFalse(ExpressionDialog.containsLibraryComponentCall(
                "F = NOT (A & B) | (C NAND D)"));
        assertTrue(ExpressionDialog.containsLibraryComponentCall(
                "Q = DFF(D, CLK)"));
        assertTrue(ExpressionDialog.containsLibraryComponentCall(
                "U0 := FA(A, B, CIN)\\nS = U0.S"));
    }

    @Test
    void truthTablePreviewStopsAtTheExistingTenInputLimit() {
        assertTrue(ExpressionDialog.shouldBuildTruthTable(0));
        assertTrue(ExpressionDialog.shouldBuildTruthTable(10));
        assertFalse(ExpressionDialog.shouldBuildTruthTable(11));
    }

    @Test
    void reusedOutputAssignmentShowsTheExactNonBlockingHint() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile(
                "S0C = A & B\nF = S0C | C");

        assertEquals(
                "S0C is both an output and used by another line. "
                        + "Use := for an internal signal with no pin.",
                ExpressionDialog.outputReuseHint(compilation));
        assertTrue(compilation.netlist().outputs().stream()
                .anyMatch(output -> output.name().equals("S0C")),
                "the hint must not remove the output pin");
    }

    @Test
    void outputDefinedLaterStillShowsTheReuseHintWithoutBecomingAnInput() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile(
                "F = S0C | C\nS0C = A & B");

        assertEquals(List.of("A", "B", "C"), compilation.netlist().inputs());
        assertEquals(
                "S0C is both an output and used by another line. "
                        + "Use := for an internal signal with no pin.",
                ExpressionDialog.outputReuseHint(compilation));
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
