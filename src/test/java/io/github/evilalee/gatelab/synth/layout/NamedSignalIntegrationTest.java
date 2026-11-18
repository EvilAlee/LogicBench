package io.github.evilalee.gatelab.synth.layout;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import io.github.evilalee.gatelab.synth.GateOperator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NamedSignalIntegrationTest {
    private final SchematicLayout layout = new SchematicLayout(GateLibrary.load().all());

    @Test
    void reusedOutputNameRemainsAnInputWhileInternalSignalHasNoOutputPin() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile(
                "S0 = A^B; C := A&B; S1 = S0 ^ C");

        // Observed main-branch behavior: a later reference to an '=' output name
        // is treated as a primary input, not as a reference to that output's AST.
        assertEquals(List.of("A", "B", "S0"), compilation.netlist().inputs());
        assertEquals(List.of("S0", "S1"), compilation.netlist().outputs().stream()
                .map(output -> output.name()).toList());
        assertFalse(compilation.netlist().outputs().stream()
                .anyMatch(output -> output.name().equals("C")));

        SchematicLayout.LayoutResult placed = layout.layout(compilation.netlist());
        SchematicExpressionVerifier.VerificationResult result =
                SchematicExpressionVerifier.verify(compilation, placed);
        assertTrue(result.verified(), result.mismatches().toString());
        assertEquals(1 << compilation.netlist().inputs().size(), result.rowCount());
    }

    @Test
    void internalCarryCreatesNoPinAndSharedSubexpressionCreatesOneGate() {
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile(
                "S0 = A^B^Cin; C0 := A&B + Cin&(A^B); S1 = A1^B1^C0");

        assertEquals(List.of("A", "B", "Cin", "A1", "B1"), compilation.netlist().inputs());
        assertEquals(List.of("S0", "S1"), compilation.netlist().outputs().stream()
                .map(output -> output.name()).toList());
        assertFalse(compilation.netlist().outputs().stream()
                .anyMatch(output -> output.name().equals("C0")));
        assertEquals(1, compilation.netlist().gates().stream()
                .filter(gate -> gate.type() == GateOperator.XOR
                        && gate.inputs().equals(List.of("A", "B")))
                .count());

        SchematicLayout.LayoutResult placed = layout.layout(compilation.netlist());
        SchematicExpressionVerifier.VerificationResult result =
                SchematicExpressionVerifier.verify(compilation, placed);
        assertTrue(result.verified(), result.mismatches().toString());
        assertEquals(1 << compilation.netlist().inputs().size(), result.rowCount());
    }
}
