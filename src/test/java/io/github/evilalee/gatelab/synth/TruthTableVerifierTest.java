package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.model.Value;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TruthTableVerifierTest {
    @Test
    void engineTruthTablesMatchReferenceEvaluatorForHandWrittenExpressions() {
        List<String> examples = List.of(
                "F = A & B",
                "F = A | B",
                "F = A ^ B",
                "F = A XNOR B",
                "F = A NAND B",
                "F = A NOR B",
                "F = !(A & B)",
                "F = ~(A | (B & C))",
                "F = A' AND B",
                "F = A * B + C",
                "F = NOT (A XOR B) OR C",
                "F = (A & B) | (C & D)",
                "F = 0",
                "F = 1");
        for (String source : examples) {
            TruthTableVerifier.VerificationResult result = TruthTableVerifier.verify(source);
            assertTrue(result.verified(), source + ": " + result.mismatches());
            assertEquals(1 << result.truthTable().inputNames().size(),
                    result.truthTable().rows().size(), source);
            assertTrue(result.truthTable().rows().stream().noneMatch(
                    TruthTable.Row::oscillating), source);
        }
    }

    @Test
    void fullAdderSumAndCarryVerifyAgainstTheEngineForAllEightRows() {
        String source = """
                Sum = A ^ B ^ Cin
                Carry = (A & B) | (Cin & (A ^ B))
                """;
        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile(source);
        TruthTableVerifier.VerificationResult result = TruthTableVerifier.verify(compilation);

        assertTrue(result.verified(), result.mismatches().toString());
        assertEquals(List.of("A", "B", "Cin"), result.truthTable().inputNames());
        assertEquals(8, result.truthTable().rows().size());
        assertEquals(1, compilation.netlist().gates().stream()
                .filter(gate -> gate.type() == GateOperator.XOR
                        && gate.inputs().contains("A") && gate.inputs().contains("B"))
                .count());
    }

    @Test
    void truthTableUsesTheFirstInputAsTheMostSignificantBit() {
        TruthTable table = TruthTableGenerator.generate(
                ExpressionCompiler.compile("F = A & B").netlist());
        assertEquals(List.of("00", "01", "10", "11"),
                table.rows().stream().map(TruthTable.Row::inputBits).toList());
        assertEquals(List.of(Value.ZERO, Value.ZERO, Value.ZERO, Value.ONE),
                table.rows().stream().map(row -> row.outputValues().getFirst()).toList());
    }

    @Test
    void directInputsAndConstantOutputsAreIncludedInEngineTruthTables() {
        ExpressionCompiler.Compilation compilation =
                ExpressionCompiler.compile("Aout = A\nZero = 0\nOne = 1");
        TruthTableVerifier.VerificationResult result = TruthTableVerifier.verify(compilation);
        assertTrue(result.verified(), result.mismatches().toString());
        assertEquals(2, result.truthTable().rows().size());
        assertEquals(List.of(Value.ZERO, Value.ZERO, Value.ONE),
                result.truthTable().rows().getFirst().outputValues());
        assertEquals(List.of(Value.ONE, Value.ZERO, Value.ONE),
                result.truthTable().rows().getLast().outputValues());
    }

    @Test
    void twoHundredFixedSeedRandomExpressionsVerifyAcrossUpToFiveVariables() {
        Random random = new Random(0x5EEDC0DEL);
        for (int index = 0; index < 200; index++) {
            String expression = randomExpression(random, 4);
            String source = "F = " + expression;
            TruthTableVerifier.VerificationResult result = TruthTableVerifier.verify(source);
            assertTrue(result.verified(),
                    "random expression " + index + ": " + source
                            + " mismatches=" + result.mismatches());
            assertTrue(result.truthTable().inputNames().size() <= 5);
        }
    }

    private static String randomExpression(Random random, int depth) {
        if (depth == 0 || random.nextInt(5) == 0) {
            int leaf = random.nextInt(7);
            if (leaf == 5) {
                return "0";
            }
            if (leaf == 6) {
                return "1";
            }
            return Character.toString((char) ('A' + leaf));
        }

        return switch (random.nextInt(8)) {
            case 0 -> "!(" + randomExpression(random, depth - 1) + ")";
            case 1 -> "~(" + randomExpression(random, depth - 1) + ")";
            case 2 -> "(" + randomExpression(random, depth - 1) + ")'";
            default -> {
                String[] operators = {" & ", " | ", " ^ ", " NAND ",
                        " NOR ", " XNOR ", " AND ", " OR "};
                String left = randomExpression(random, depth - 1);
                String right = randomExpression(random, depth - 1);
                String operator = operators[random.nextInt(operators.length)];
                yield "(" + left + operator + right + ")";
            }
        };
    }
}
